"""Production workspace journeys; imports the existing real-JLine PTY harness."""
import importlib.util
import fcntl
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
    if (width, height) == (t.width, t.height):
        return
    t.width, t.height = width, height
    t.cells = [[' '] * width for _ in range(height)]
    fcntl.ioctl(t.slave, termios.TIOCSWINSZ, struct.pack('HHHH', height, width, 0, 0))
    t.pump(.5)
    assert 'HOMELIGHT' in t.text(), t.text()


def fixture(root):
    body = f'homelight:\n  target-root: {root}/local\n  relocations:\n'
    for name in ('conflict', 'migrate', 'adopt', 'discard', 'synced', 'unchanged'):
        source, target = root / 'home' / name, root / 'local' / name
        source.parent.mkdir(exist_ok=True)
        target.parent.mkdir(exist_ok=True)
        if name != 'synced':
            source.mkdir()
            (source / 'payload').write_text('source content')
        if name != 'migrate':
            target.mkdir()
            (target / 'payload').write_text('target content')
        if name == 'synced':
            source.symlink_to(target)
        body += f'    - source-path: {source}\n      target-path: {target}\n'
        if name == 'adopt':
            body += '      when-source-and-target-directories-exist: adopt\n      when-adopting-target: discard-source\n'
        if name == 'discard':
            body += '      when-source-and-target-directories-exist: discard\n'
        if name == 'unchanged':
            body += '      when-source-and-target-directories-exist: leave-unchanged\n'
        if name == 'conflict':
            body += f'      source-archive-root: {root}/archive-destination-distinguishing-suffix\n'
    config = root / 'config.yaml'
    config.write_text(body)
    return config


def detail_text(t):
    result = ''
    for row in t.text().splitlines():
        start = row.find('│', t.width * 45 // 100 - 1)
        if start >= 0 and start + 1 < len(row) and row[start + 1] == '│':
            start += 1
        end = row.rfind('│')
        if end > start >= 0:
            result += row[start + 1:end].replace('█', '').replace('│', '').rstrip()
    return result


def scroll_all(t, count=100):
    evidence = ''
    for _ in range(count):
        evidence += detail_text(t)
        t.key(']', .025)
    return evidence


def capture_sizes(t, label):
    for width, height in ((80, 24), (120, 30), (200, 50), (120, 30), (80, 24)):
        resize(t, width, height)
        assert '3: ' not in t.text() and 'never quit' not in t.text(), t.text()
        assert 'Planned actions' not in t.text() and 'Overlapping risks' not in t.text(), t.text()
        assert not harness.re.search(r'\d+–\d+/\d+', t.text()), t.text()
        t.snap(label)


def journey(width, height):
    with tempfile.TemporaryDirectory(prefix='homelight-workspace-pty-') as directory:
        root = Path(directory).resolve()
        config = fixture(root)
        t = Terminal(['apply', '--config', str(config), '--debug-step-delay-ms', '100'], width, height)
        t.wait_for('[1: Workspace]')
        assert '3 actionable' in t.text() and '1 unchanged' in t.text(), t.text()
        assert '1 with warnings' in t.text(), t.text()
        assert 'synced' not in t.text(), t.text()
        t.snap('returning-user baseline, complete counts and 45 percent pane')
        t.key('c')
        assert 'synced' in t.text()
        t.key('c')
        t.key('l')
        for choice in range(4):
            for w, h in ((width, height), (120, 30), (200, 50), (80, 24)):
                resize(t, w, h)
                assert 'Details' in t.text() and '❯ (○)' in t.text(), t.text()
                assert 'Space/Enter: Select' in t.text() and 'q: Quit' in t.text(), t.text()
                t.snap(f'choice {choice + 1}, focused after resize')
            if choice < 3:
                t.key('j')
        # Inspect the complete archive destination, then select the focused fourth (discard) choice.
        archive_evidence = scroll_all(t)
        assert 'archive-destination-distinguishing-suffix' in archive_evidence, archive_evidence
        t.key(' ')
        assert 'a: Review & apply' in t.text(), t.text()
        t.key('\x1b', .3)
        assert 'a: Review & apply' in t.text(), t.text()
        t.snap('ready workspace list advertises review and apply')
        t.key('l')
        t.key('2')
        t.wait_for('Confirm reviewed plan')
        capture_sizes(t, 'review list, planned change units and contextual confirmation help')
        t.key('l')
        capture_sizes(t, 'review details, exact affected paths and destinations')
        t.key('\r')
        assert not (root / 'home/conflict').is_symlink()
        t.snap('review requires y, Enter does not execute')
        t.key('1')
        assert 'Details' in t.text() and '(●)' in t.text(), t.text()
        capture_sizes(t, '1 cancels review, retaining source, fourth choice and detail focus')
        t.key('a')
        t.key('y')
        t.wait_for('Applying reviewed plan')
        t.key('j')
        resize(t, 120, 30)
        resize(t, 80, 24)
        t.wait_for('Application complete', timeout=15)
        capture_sizes(t, 'retained successful result')
        t.key('\r')
        assert '5 in sync' in t.text() and '1 unchanged' in t.text(), t.text()
        assert 'Results retained' in t.text()
        t.key('2')
        t.wait_for('[2: Results]')
        t.key('r')
        t.wait_for('[1: Workspace]')
        t.key('2')
        t.wait_for('No changes to apply')
        capture_sizes(t, 'explicit no-change replan')
        t.key('\x1b', .3)
        assert t.alive()
        t.key('q')
        t.finish()
        assert (root / 'home/conflict').is_symlink()
        assert (root / 'home/unchanged/payload').read_text() == 'source content'


def rejection(width, height):
    with tempfile.TemporaryDirectory(prefix='homelight-stale-pty-') as directory:
        root = Path(directory).resolve()
        config = harness.fixture(root)
        t = Terminal(['status', '--config', config], width, height)
        t.wait_for('[1: Workspace]')
        t.key('2')
        t.wait_for('Confirm reviewed plan')
        (root / 'home/cache').write_text('changed after review')
        t.key('y')
        t.wait_for('preflight rejected')
        t.snap('preflight rejected before mutation')
        evidence = scroll_all(t)
        assert 'cache' in evidence
        assert not (root / 'local/cache').exists()
        assert (root / 'home/cache').read_text() == 'changed after review'
        t.key('q')
        t.finish()


def partial_failure(width, height):
    with tempfile.TemporaryDirectory(prefix='homelight-partial-workspace-pty-') as directory:
        root = Path(directory).resolve()
        config = Path(harness.fixture(root, ('first', 'second', 'third'), partial=True))
        stage = root / 'local' / ('long-staging-segment-' * 5) / 'failure-path-distinguishing-suffix'
        config.write_text(config.read_text().replace(str(root / 'local/.stage'), str(stage)))
        stage.parent.mkdir()
        t = Terminal(['plan', '--config', str(config)], width, height)
        t.wait_for('[1: Workspace]')
        t.key('2')
        t.wait_for('Confirm reviewed plan')
        stage.write_text('force staging failure')
        t.key('y')
        t.wait_for('[2: Results]')
        t.snap('partial failure retains completed, failed and not-run mutations')
        assert 'not run' in t.text() and 'failed' in t.text(), t.text()
        t.key('l')
        for w, h in ((120, 30), (200, 50), (80, 24)):
            resize(t, w, h)
        evidence = scroll_all(t)
        assert 'failure-path-distinguishing-suffix' in evidence, evidence
        t.snap('complete failure path is accessible through scrolling')
        assert (root / 'home/first').is_symlink()
        assert (root / 'home/second/payload').read_text() == 'preserve this payload'
        assert not (root / 'home/third').exists()
        t.key('\r')
        t.wait_for('[1: Workspace]')
        t.key('2')
        t.wait_for('[2: Results]')
        t.key('q')
        t.finish()


if __name__ == '__main__':
    print('JAVA', harness.JAVA, flush=True)
    for width, height in ((80, 24), (120, 30), (200, 50)):
        journey(width, height)
        rejection(width, height)
        partial_failure(width, height)
    print('PASS: nine production workspace sessions, all choices, resize both directions, review/cancel, results/replan, preflight and retained partial failure', flush=True)
