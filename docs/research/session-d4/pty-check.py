"""Manual first-run table journeys at supported sizes; uses disposable explicit configs.

Run after Maven tests, because the shared PTY harness reads Surefire's Java classpath.
"""
import fcntl
import importlib.util
import os
from pathlib import Path
import struct
import sys
import tempfile
import termios

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('exit_pty', Path(__file__).parents[1] / 'session-d2-step3/pty-check.py')
harness = importlib.util.module_from_spec(spec)
spec.loader.exec_module(harness)
Terminal = harness.Terminal


def resize(t, width, height):
    t.width, t.height = width, height
    t.cells = [row[:width] + [' '] * max(0, width - len(row)) for row in t.cells[:height]]
    t.cells.extend([[' '] * width for _ in range(height - len(t.cells))])
    fcntl.ioctl(t.slave, termios.TIOCSWINSZ, struct.pack('HHHH', height, width, 0, 0))
    t.pump(.8)
    t.key('[', .2)  # Request a render after JLine has delivered the resize event.
    assert 'HOMELIGHT' in t.text(), (t.text(), t.raw[-2000:])


def resize_both_ways(t, width, height):
    other_width, other_height = (120, 30) if (width, height) == (80, 24) else (80, 24)
    resize(t, other_width, other_height)
    resize(t, width, height)


def enter_locations(t, root):
    t.key('\x7f' * len(str(Path.home())))
    t.key(str(root / 'home'))
    t.key('\x1b[B')
    t.key(str(root / 'local'))
    t.key('\r')
    t.wait_for('Relocations')


def table_journey(width, height):
    with tempfile.TemporaryDirectory(prefix='homelight-first-run-table-pty-') as directory:
        root = Path(directory).resolve()
        config, source = root / 'nested/config.yaml', root / 'home-edited/cache'
        t = Terminal(['init', '--config', str(config)], width, height)
        t.wait_for('Create configuration')
        assert not config.exists()
        enter_locations(t, root)

        t.key('a')
        t.wait_for('Edit relocation 1')
        t.key('cache')
        assert 'Source path: cache' in t.text(), t.text()
        assert 'Target path: cache' in t.text(), t.text()
        resize_both_ways(t, width, height)
        t.key('\x1b')
        t.wait_for('Source (relative)')

        t.key('v')
        t.wait_for('Validation: valid')
        t.key('e')
        t.key('\x7f' * len(str(root / 'home')))
        t.key(str(root / 'home-edited'))
        t.key('\r')
        t.wait_for('cache')
        assert 'Validation: not run' in t.text(), t.text()
        t.key('v')
        t.wait_for('Validation: valid')

        t.key('a')
        t.wait_for('Edit relocation 2')
        t.key('remove-me')
        t.key('\x1b')
        t.key('d')
        t.wait_for('Relocation removed')
        assert 'remove-me' not in t.text(), t.text()
        t.key('v')
        t.wait_for('Validation: valid')

        t.key('q')
        t.wait_for('Discard setup draft?')
        t.key('\x1b')
        t.wait_for('Relocations')
        assert not config.exists(), 'discard cancellation must keep the in-memory draft only'
        t.key('s')
        t.wait_for('[1: Workspace]')
        assert config.exists() and not source.exists(), 'save creates configuration only; it must not relocate'
        assert 'cache' in t.text(), t.text()
        t.key('q')
        t.finish()


def explicit_missing_discard(width, height):
    with tempfile.TemporaryDirectory(prefix='homelight-first-run-explicit-pty-') as directory:
        config = Path(directory) / 'missing.yaml'
        t = Terminal(['status', '--config', str(config)], width, height)
        t.wait_for('Configuration file does not exist')
        t.key('i')
        t.wait_for('Create configuration')
        resize_both_ways(t, width, height)
        t.key('\r')
        t.wait_for('Relocations')
        t.key('q')
        t.wait_for('Discard setup draft?')
        t.key('\r')
        t.wait_for('Configuration file does not exist')
        assert not config.exists(), 'discarding an explicit missing setup must not create a file'
        t.key('q')
        t.finish()


def discard_reopen_and_correct(width, height):
    with tempfile.TemporaryDirectory(prefix='homelight-first-run-correct-pty-') as directory:
        root = Path(directory).resolve()
        config, source = root / 'config.yaml', root / 'home/nested/cache'
        t = Terminal(['init', '--config', str(config)], width, height)
        t.wait_for('Create configuration')
        enter_locations(t, root)
        t.key('a')
        t.key('discard-me')
        t.key('\x1b')
        t.key('q')
        t.wait_for('Discard setup draft?')
        t.key('\r')
        t.wait_for('Configuration file does not exist')

        t.key('i')
        t.wait_for('Create configuration')
        assert 'discard-me' not in t.text(), t.text()
        assert 'Validation: not run' in t.text(), t.text()
        enter_locations(t, root)
        t.key('a')
        t.key('\x1b')
        t.key('v')
        t.wait_for('Relocation paths cannot be blank')
        t.key('s')
        t.wait_for('Save failed: Relocation paths cannot be blank')
        assert not config.exists() and not source.exists(), 'invalid save must not create or relocate'
        resize_both_ways(t, width, height)
        t.key('\r')
        t.wait_for('Edit relocation 1')
        t.key('nested/cache')
        t.key('\x1b')
        t.key('v')
        t.wait_for('Validation: valid')
        t.key('s')
        t.wait_for('[1: Workspace]')
        assert config.exists() and not source.exists(), 'corrected save must not relocate'
        t.key('q')
        t.finish()


def default_missing_cancel(width, height):
    with tempfile.TemporaryDirectory(prefix='homelight-first-run-default-pty-') as directory:
        previous = os.environ.get('JAVA_TOOL_OPTIONS')
        os.environ['JAVA_TOOL_OPTIONS'] = '-Duser.home=' + directory
        try:
            t = Terminal([], width, height)
        finally:
            if previous is None:
                del os.environ['JAVA_TOOL_OPTIONS']
            else:
                os.environ['JAVA_TOOL_OPTIONS'] = previous
        t.wait_for('i: Manual setup')
        t.key('i')
        t.wait_for('Create configuration')
        t.key('\r')
        t.wait_for('Relocations')
        t.key('q')
        t.wait_for('Discard setup draft?')
        t.key('\x1b')
        t.key('\x1b')
        t.key('\x1b')
        assert not (Path(directory) / '.homelight.yaml').exists(), 'default-path cancellation must not create a file'
        t.key('q')
        t.finish()


if __name__ == '__main__':
    print('JAVA', harness.JAVA, flush=True)
    sizes = ((80, 24), (120, 30)) if len(sys.argv) == 1 else ((int(sys.argv[1]), int(sys.argv[2])),)
    for width, height in sizes:
        table_journey(width, height)
        explicit_missing_discard(width, height)
        discard_reopen_and_correct(width, height)
        default_missing_cancel(width, height)
    print('PASS: missing default and explicit configs, init, Locations → Relocations → Row details, resize both ways, discard → fresh reopen, invalid-input correction → save, root edits, row add/remove, validation reset, discard confirmation/cancellation, and save → workspace without relocation at 80x24 and 120x30', flush=True)
