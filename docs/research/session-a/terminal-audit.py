import codecs
import fcntl
import os
import pty
import re
import select
import signal
import struct
import subprocess
import sys
import termios
import time
from pathlib import Path

# Bounded audit driver: runs the real TUI on a PTY; no external dependencies.
class Terminal:
    def __init__(self, args, width=120, height=30, isolated_home=None):
        self.width, self.height = width, height
        self.cells = [[' '] * width for _ in range(height)]
        self.x = self.y = 0
        self.pending = ''
        self.decoder = codecs.getincrementaldecoder('utf-8')('replace')
        self.raw = ''
        self.pid, self.fd = pty.fork()
        if self.pid == 0:
            os.environ['TERM'] = 'xterm-256color'
            os.environ['JAVA_HOME'] = '/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home'
            if isolated_home:
                os.environ['MAVEN_OPTS'] = '-Duser.home=' + isolated_home + ' -Dmaven.repo.local=/Users/jsiva/.m2/repository'
            os.execv('./homelight', ['./homelight'] + args)
        self.resize(width, height)
        self.pump(4)

    def resize(self, width, height):
        self.width, self.height = width, height
        self.cells = [[' '] * width for _ in range(height)]
        fcntl.ioctl(self.fd, termios.TIOCSWINSZ, struct.pack('HHHH', height, width, 0, 0))
        os.kill(self.pid, signal.SIGWINCH)

    def pump(self, seconds=.3):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            if not select.select([self.fd], [], [], max(0, end-time.monotonic()))[0]:
                break
            try:
                data = os.read(self.fd, 65536)
            except OSError:
                break
            if not data:
                break
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
                    self.y, self.x = nums[0]-1, (nums[1] if len(nums)>1 else 1)-1
                elif command == 'J' and params in ('2', '3'):
                    self.cells = [[' '] * self.width for _ in range(self.height)]
                continue
            char, self.pending = self.pending[0], self.pending[1:]
            if char == '\r':
                self.x = 0
            elif char == '\n':
                self.y = min(self.height-1, self.y+1)
            elif char >= ' ':
                if 0 <= self.y < self.height and 0 <= self.x < self.width:
                    self.cells[self.y][self.x] = char
                self.x += 1

    def key(self, keys, delay=.3):
        os.write(self.fd, keys.encode())
        self.pump(delay)

    def snap(self, label):
        print('\n=== ' + label + f' [{self.width}x{self.height}] ===', flush=True)
        print('\n'.join(''.join(row).rstrip() for row in self.cells), flush=True)

    def quit(self):
        self.key('q', .5)
        print('TERMINAL_RESTORED', '\x1b[?1049l' in self.raw and '\x1b[?25h' in self.raw, flush=True)
        os.waitpid(self.pid, 0)
        os.close(self.fd)

def fixture():
    output = subprocess.check_output(['bash', 'scripts/setup-smoke-fixture.sh'], text=True)
    return re.search(r'Configuration: (.+)', output)[1]

if '--extra' in sys.argv:
    config = fixture()
    root = Path(config).parent
    t = Terminal([], 120, 30, str(root))
    t.snap('isolated missing default status'); t.key('2'); t.snap('isolated missing default plan'); t.quit()
    t = Terminal(['status', '--config', config], 120, 30)
    t.key('j'); t.snap('status warning at 120x30')
    t.resize(120, 45); t.pump(.5); t.snap('status diagnostic wording expanded for inspection')
    t.resize(80, 24); t.pump(.5); t.snap('status warning at 80x24')
    t.key('2l '); t.snap('resolved plan before switching status')
    t.key('1'); t.snap('status still reflects saved prompt policy')
    t.key('2'); t.snap('plan retains draft resolution')
    t.key('3j'); t.snap('long migration details at 80x24')
    t.key('j'); t.snap('long destructive replacement details at 80x24')
    t.key('n'); t.quit()
    t = Terminal(['plan', '--config', config], 80, 24)
    t.key('\x03', .5)
    print('CTRL_C_RESTORED', '\x1b[?1049l' in t.raw and '\x1b[?25h' in t.raw, flush=True)
    os.waitpid(t.pid, 0); os.close(t.fd)
    sys.exit(0)

config = fixture()
print('FIXTURE', config, flush=True)
t = Terminal(['--config', config, '--debug-step-delay-ms', '1500'])
t.snap('status fresh')
t.key('2l'); t.snap('plan conflict first option')
t.key('j'); t.snap('plan conflict second option')
t.resize(80, 24); t.pump(.5); t.snap('resize while second option focused')
t.resize(120, 30); t.pump(.5); t.snap('resize back preserves focus')
t.key('k '); t.snap('choose adopt source discard')
t.key('3'); t.snap('confirmation from detail')
t.key('\r'); t.snap('enter does not confirm')
t.key('n'); t.snap('cancel returns to plan')
t.key('3y', .3); t.snap('running first mutation')
t.key('k'); t.snap('manual inspection while running')
t.key('qr123y', .3); t.snap('running consumes navigation and quit')
t.pump(2); t.snap('follows next action')
t.pump(18); t.snap('retained result')
t.key('1'); t.snap('refreshed status')
t.key('3'); t.snap('revisit retained result')
t.key('r3'); t.snap('no change replan')
t.key('y'); t.snap('no change y ignored')
t.quit()

config = fixture()
t = Terminal(['plan', '--config', config], 80, 24)
t.key('l 3'); t.snap('before stale change')
subprocess.run(['mkdir', config.removesuffix('/config.yaml') + '/local/stage-cache'], check=True)
t.key('y'); t.snap('stale result')
t.key('r'); t.snap('explicit replan after stale')
t.quit()

config = fixture()
root = Path(config).parent
# Add an absent relocation before the migration, so the failure follows real mutation.
partial_config = root / 'partial.yaml'
partial_config.write_text('homelight:\n  target-root: ' + str(root / 'local')
    + '\n  staging-root: ' + str(root / 'local/.homelight-staging')
    + '\n  relocations:\n' + ''.join(
        '    - source-path: ' + str(root / ('home/' + name))
        + '\n      target-path: ' + str(root / ('local/' + name)) + '\n'
        for name in ('first', 'stage-cache', 'third')))
t = Terminal(['apply', '--config', str(partial_config)], 120, 30)
t.snap('apply command opens plan')
t.key('3')
subprocess.run(['touch', config.removesuffix('/config.yaml') + '/local/.homelight-staging'], check=True)
t.key('y', 1); t.snap('partial failure retained')
assert (root / 'home/first').is_symlink()
assert not (root / 'home/third').exists()
print('PARTIAL_FILESYSTEM: first is symlink; third absent', flush=True)
t.key('y3'); t.snap('failure cannot be reapplied')
t.quit()

missing = config.removesuffix('/config.yaml') + '/missing.yaml'
t = Terminal(['--config', missing], 80, 24)
t.snap('missing explicit config status'); t.key('2'); t.snap('missing explicit config plan'); t.quit()
if not Path('/Users/jsiva/.homelight.yaml').exists():
    t = Terminal([], 120, 30)
    t.snap('missing default config status'); t.key('2'); t.snap('missing default config plan'); t.quit()
