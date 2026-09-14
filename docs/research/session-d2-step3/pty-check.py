"""Run after Maven tests: real JLine PTYs, temporary fixtures, no external packages."""
import codecs
import fcntl
import os
from pathlib import Path
import re
import select
import struct
import subprocess
import tempfile
import termios
import time
import unicodedata
import xml.etree.ElementTree as ET

REPO = Path(__file__).resolve().parents[3]
REPORT = REPO / 'target/surefire-reports/TEST-io.github.bigswlittlesw.homelight.tui.HomeLightExitTest.xml'
PROPERTIES = {p.attrib['name']: p.attrib['value'] for p in ET.parse(REPORT).findall('./properties/property')}
JAVA = str(Path(PROPERTIES['java.home']) / 'bin/java')
CLASSPATH = PROPERTIES['java.class.path']


class Terminal:
    def __init__(self, args, width, height):
        self.width, self.height = width, height
        self.cells = [[' '] * width for _ in range(height)]
        self.x = self.y = 0
        self.raw = self.pending = ''
        self.status = None
        self.decoder = codecs.getincrementaldecoder('utf-8')('replace')
        self.master, self.slave = os.openpty()
        fcntl.ioctl(self.slave, termios.TIOCSWINSZ, struct.pack('HHHH', height, width, 0, 0))
        self.before = termios.tcgetattr(self.slave)
        release_read, self.release_write = os.pipe()
        self.pid = os.fork()
        if self.pid == 0:
            os.close(self.release_write)
            os.close(self.master)
            os.setsid()
            fcntl.ioctl(self.slave, termios.TIOCSCTTY, 0)
            for fd in (0, 1, 2):
                os.dup2(self.slave, fd)
            if self.slave > 2:
                os.close(self.slave)
            os.environ['TERM'] = 'xterm-256color'
            result = subprocess.run([JAVA, '--enable-native-access=ALL-UNNAMED', '-cp', CLASSPATH,
                                     'io.github.bigswlittlesw.homelight.cli.HomeLightCommand', *args])
            # Keep the controlling process alive: macOS revokes the slave when it exits.
            os.write(1, f'\nPTY_JAVA_EXIT={result.returncode}\n'.encode())
            os.read(release_read, 1)
            os._exit(result.returncode)
        os.close(release_read)

    def alive(self):
        if 'PTY_JAVA_EXIT=' in self.raw:
            return False
        if self.status is None:
            pid, status = os.waitpid(self.pid, os.WNOHANG)
            if pid:
                self.status = status
        return self.status is None

    def pump(self, seconds=.15):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            if not select.select([self.master], [], [], max(0, end - time.monotonic()))[0]:
                break
            data = os.read(self.master, 65536)
            decoded = self.decoder.decode(data)
            self.raw += decoded
            self.pending += decoded
            self.parse()

    def parse(self):
        while self.pending:
            if self.pending == '\x1b':
                return
            if self.pending.startswith('\x1b['):
                match = re.match(r'\x1b\[([0-?]*)([ -/]*)([@-~])', self.pending)
                if not match:
                    return
                params, _, command = match.groups()
                self.pending = self.pending[match.end():]
                if command in 'Hf':
                    nums = [int(n or '1') for n in params.split(';')]
                    self.y, self.x = nums[0] - 1, (nums[1] if len(nums) > 1 else 1) - 1
                elif command in 'ABCDEFGd' and not params.startswith('?'):
                    amount = int(params or '1')
                    if command in 'ABEF':
                        self.y = max(0, min(self.height - 1, self.y + (amount if command in 'BE' else -amount)))
                    if command in 'CD':
                        self.x = max(0, min(self.width - 1, self.x + (amount if command == 'C' else -amount)))
                    if command in 'EF':
                        self.x = 0
                    if command == 'G':
                        self.x = amount - 1
                    if command == 'd':
                        self.y = amount - 1
                elif command == 'X' and 0 <= self.y < self.height:
                    for x in range(max(0, self.x), min(self.width, self.x + int(params or '1'))):
                        self.cells[self.y][x] = ' '
                elif command == 'J' and params in ('2', '3'):
                    self.cells = [[' '] * self.width for _ in range(self.height)]
                elif command == 'K' and params in ('', '0'):
                    if 0 <= self.y < self.height:
                        for x in range(max(0, self.x), self.width):
                            self.cells[self.y][x] = ' '
                continue
            if self.pending.startswith('\x1b'):
                self.pending = self.pending[2:]
                continue
            char, self.pending = self.pending[0], self.pending[1:]
            if char == '\r':
                self.x = 0
            elif char == '\n':
                self.y = min(self.height - 1, self.y + 1)
            elif char >= ' ':
                cells = 2 if unicodedata.east_asian_width(char) in ('W', 'F') else 1
                if 0 <= self.y < self.height and 0 <= self.x < self.width:
                    self.cells[self.y][self.x] = char
                    if cells == 2 and self.x + 1 < self.width:
                        self.cells[self.y][self.x + 1] = ''
                self.x += cells

    def text(self):
        return '\n'.join(''.join(row).rstrip() for row in self.cells)

    def wait_for(self, text, timeout=10):
        end = time.monotonic() + timeout
        while text not in self.text() and time.monotonic() < end and self.alive():
            self.pump()
        assert text in self.text(), (text, self.text(), self.raw[-1000:])

    def key(self, key, seconds=.15):
        os.write(self.master, key.encode())
        self.pump(seconds)

    def snap(self, label):
        print(f'\n{self.width}x{self.height}: {label}\n{self.text()}', flush=True)

    def finish(self):
        end = time.monotonic() + 15
        while self.alive() and time.monotonic() < end:
            self.pump()
        assert not self.alive(), 'application did not exit after settlement'
        self.pump()
        assert 'PTY_JAVA_EXIT=0' in self.raw, self.raw[-1500:]
        after = termios.tcgetattr(self.slave)
        assert after[:3] == self.before[:3], ('terminal mode flags were not restored', self.before, after)
        # PENDIN is a kernel indication to reprocess queued input after leaving raw mode.
        pendin = getattr(termios, 'PENDIN', 0)
        assert after[3] & ~pendin == self.before[3] & ~pendin, ('local modes not restored', self.before, after)
        controls = ('VEOF', 'VEOL', 'VEOL2', 'VERASE', 'VINTR', 'VKILL', 'VMIN', 'VQUIT',
                    'VREPRINT', 'VSTART', 'VSTATUS', 'VSTOP', 'VSUSP', 'VTIME', 'VWERASE',
                    'VLNEXT', 'VDISCARD', 'VDSUSP')
        for name in controls:
            if hasattr(termios, name):
                index = getattr(termios, name)
                assert after[6][index] == self.before[6][index], (name, self.before, after)
        assert self.raw.count('\x1b[?1049l') == 1, 'alternate screen must be left once'
        assert '\x1b[?25h' in self.raw, 'cursor must be restored'
        print('PASS: exit 0; termios modes (excluding transient PENDIN) and defined control characters restored; '
              'alternate screen left once; cursor shown', flush=True)
        print('PTY speed fields before/after:', self.before[4:6], after[4:6],
              '(JLine normalizes these and unused control slots on macOS)', flush=True)
        os.write(self.release_write, b'1')
        os.waitpid(self.pid, 0)
        os.close(self.release_write)
        os.close(self.master)
        os.close(self.slave)


def fixture(root, names=('cache',), conflict=False, partial=False):
    (root / 'home').mkdir()
    (root / 'local').mkdir()
    if conflict:
        (root / 'local/cache').mkdir()
    if partial:
        (root / 'home/second').mkdir()
        (root / 'home/second/payload').write_text('preserve this payload')
    config = root / 'config.yaml'
    config.write_text('homelight:\n  target-root: ' + str(root / 'local')
                      + '\n  staging-root: ' + str(root / 'local/.stage')
                      + '\n  relocations:\n' + ''.join(
                          f'    - source-path: {root}/home/{name}\n      target-path: {root}/local/{name}\n'
                          for name in names))
    return str(config)


def run(width, height):
    with tempfile.TemporaryDirectory(prefix='homelight-exit-pty-') as directory:
        root = Path(directory).resolve()
        config = fixture(root, conflict=True)
        t = Terminal(['plan', '--config', config, '--debug-step-delay-ms', '2500'], width, height)
        t.wait_for('HOMELIGHT')
        t.key('\x1b', .3)
        assert t.alive()
        t.key('l')
        t.snap('detail pane before Escape')
        t.key('\x1b', .3)
        t.snap('Escape returns to master')
        # Enter detail again and resolve; Escape from apply cancels without mutation.
        t.key('l ')
        t.key('2')
        t.wait_for('Confirm reviewed plan')
        t.key('\x1b', .3)
        assert not (root / 'home/cache').exists()
        t.key('2')
        t.wait_for('Confirm reviewed plan')
        t.key('y')
        t.wait_for('Applying reviewed plan')
        t.key('\x1b', .3)
        assert t.alive()
        t.key('\x03')
        t.wait_for('Quit HomeLight?')
        assert '❯ Keep running' in t.text()
        t.key('j')
        t.key('\x1b', .3)
        assert 'Quit HomeLight?' not in t.text()
        t.key('q')
        t.wait_for('Quit HomeLight?')
        assert '❯ Keep running' in t.text()
        t.snap('Ctrl-C and q open safe default; Escape cancels')
        t.wait_for('Application complete', timeout=8)
        assert t.alive() and '❯ Keep running' in t.text()
        t.snap('completion leaves the dialog and selection intact')
        t.key('\r')
        assert t.alive() and 'Quit HomeLight?' not in t.text()
        t.key('\x1b', .3)
        assert t.alive()
        t.snap('Keep running retains results; result Escape does nothing')
        assert (root / 'home/cache').is_symlink()
        t.key('q')
        t.finish()

    for partial in (False, True):
        with tempfile.TemporaryDirectory(prefix='homelight-exit-pty-') as directory:
            root = Path(directory).resolve()
            config = fixture(root, ('first', 'second', 'third') if partial else ('cache',), partial=partial)
            t = Terminal(['plan', '--config', config, '--debug-step-delay-ms', '1000'], width, height)
            t.wait_for('HOMELIGHT')
            t.key('a')
            t.wait_for('Confirm reviewed plan')
            if partial:
                (root / 'local/.stage').write_text('force staging failure after first relocation')
            t.key('y')
            t.wait_for('Applying reviewed plan')
            t.key('q')
            t.wait_for('Quit HomeLight?')
            assert '❯ Keep running' in t.text()
            assert "won't remain available after exit" in t.text()
            t.key('j\r')
            t.wait_for('Will exit after execution settles')
            t.key('\x1b', .3)
            assert t.alive()
            t.snap('deferred exit during ' + ('partial failure fixture' if partial else 'success fixture'))
            t.finish()
            if partial:
                assert (root / 'home/first').is_symlink()
                assert (root / 'home/second/payload').read_text() == 'preserve this payload'
                assert not (root / 'local/second').exists()
                assert not (root / 'home/third').exists()
                print('PASS: completed first relocation; failed migration preserved payload; third not run', flush=True)
            else:
                assert (root / 'home/cache').is_symlink()
                assert (root / 'local/cache').is_dir()
                print('PASS: all operations finished without interruption', flush=True)

    with tempfile.TemporaryDirectory(prefix='homelight-exit-pty-') as directory:
        t = Terminal(['status', '--config', str(Path(directory) / 'missing.yaml')], width, height)
        t.wait_for('HOMELIGHT')
        t.key('\x1b', .3)
        assert t.alive()
        t.key('2')
        t.key('\x1b', .3)
        assert t.alive()
        t.snap('missing configuration: Escape does nothing in Status and Plan')
        t.key('q')
        t.finish()


if __name__ == '__main__':
    print('JAVA', JAVA, flush=True)
    for width, height in ((80, 24), (120, 30), (200, 50)):
        run(width, height)
    print('\nPASS: all twelve real PTY sessions', flush=True)
