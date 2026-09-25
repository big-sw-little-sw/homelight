"""Real JLine setup journeys using only disposable fixture roots."""
import importlib.util
import fcntl
from pathlib import Path
import struct
import sys
import tempfile
import termios

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('pty_harness', Path(__file__).parents[1] / 'session-d2-step3/pty-check.py')
harness = importlib.util.module_from_spec(spec)
spec.loader.exec_module(harness)
class Terminal(harness.Terminal):
    def key(self, key, seconds=.15):
        # A standalone Escape must outlast JLine's sequence-disambiguation window.
        super().key(key, max(seconds, .35) if key == '\x1b' else seconds)

    def __init__(self, args, width, height, fixture=False):
        # Reuse the accepted PTY/parser/restoration harness; replace only its main
        # class when deterministic bundled input or a non-interruptible reader is needed.
        original = harness.subprocess.run
        def launch(command, *args, **kwargs):
            command = list(command)
            if fixture:
                index = command.index('io.github.bigswlittlesw.homelight.cli.HomeLightCommand')
                command[index] = 'io.github.bigswlittlesw.homelight.tui.CandidateSetupPty'
            return original(command, *args, **kwargs)
        harness.subprocess.run = launch
        try:
            super().__init__(args, width, height)
        finally:
            harness.subprocess.run = original


def resize(t, width, height):
    if (width, height) == (t.width, t.height):
        return
    t.width, t.height = width, height
    t.cells = [[' '] * width for _ in range(height)]
    fcntl.ioctl(t.slave, termios.TIOCSWINSZ, struct.pack('HHHH', height, width, 0, 0))
    t.pump(.5)
    assert 'HOMELIGHT' in t.text(), t.text()


def locations(t, root, shared=None):
    t.key('\x15' + str(root / 'home'))
    t.key('\x1b[B')
    t.key('\x15' + str(root / 'local'))
    if shared:
        t.key('\x1b[B')
        t.key('\x15' + str(shared))
    t.key('\r')
    t.wait_for('Relocations')


def representative(width=80, height=24):
    with tempfile.TemporaryDirectory(prefix='homelight-b32b-') as directory:
        root = Path(directory).resolve()
        (root / 'home/.m2').mkdir(parents=True)
        (root / 'home/.m2/payload').write_text('unchanged')
        t = Terminal(['init', '--config', str(root / 'config.yaml')], width, height)
        t.wait_for('Storage locations')
        locations(t, root)
        t.snap('Relocations: manual entry and candidate browse')
        t.key('b', .6)
        t.wait_for('Browse candidates')
        t.snap('Bundled candidates grouped by app')
        # Production bundled order starts Maven / .m2.
        t.key('j')
        t.wait_for('Space/a: Add')
        t.snap('Compact checklist with direct Add on the focused row')
        t.key(' ')
        t.wait_for('[x] .m2')
        assert 'Browse candidates' in t.text() and 'Candidate details' not in t.text()
        t.snap('Space adds in place; focus remains on the checked row')
        t.key('e')
        t.wait_for('Edit relocation 1')
        t.key('\x1b[B')
        t.key('\x15custom-maven')
        t.snap('Existing Row details edits the candidate target')
        t.key('\x1b[B')
        t.key('  ')
        assert 'Esc: Table' in t.text(), t.text()
        t.snap('Policy field consequence and available shortcuts')
        t.key('\x1b')
        t.wait_for('custom-maven')
        t.key('s')
        t.wait_for('[1: Workspace]')
        assert (root / 'home/.m2/payload').read_text() == 'unchanged'
        assert not (root / 'local/custom-maven').exists()
        t.snap('Save returns to Workspace without execution')
        t.key('q')
        t.finish()


def choose(t, path):
    t.key('\x1b[H', .05)
    for _ in range(90):
        if any(harness.re.search(r'❯   (\[.\]| − ) ' + harness.re.escape(path) + r'(?: +|$)', line) for line in t.text().splitlines()):
            return
        t.key('j', .035)
    raise AssertionError((path, t.text()))


def inspect_all(t):
    text = ''
    t.key('\x1b[H', .03)
    for _ in range(70):
        text += t.text() + '\n' + ''.join(line[1:-1].replace('█', '').replace('│', '').rstrip()
                                          for line in t.text().splitlines() if line.startswith('│'))
        t.key(']', .025)
    t.key('\x1b[H', .03)
    return text


def fixtures(root):
    for relative in ('.m2', '.cache/uv', '.cache/example', '.local/share/uv/tools',
                     'team-cache', 'datasets', 'quiet-cache'):
        (root / 'home' / relative).mkdir(parents=True, exist_ok=True)
    (root / 'home/team-cache/payload').write_text('unchanged')
    (root / 'home/link-cache').symlink_to(root / 'outside')
    (root / 'home/linked-parent').symlink_to(root / 'outside')
    (root / 'home/not-a-directory').write_text('regular file')
    shared = root / 'shared.yaml'
    base = (harness.REPO / 'docs/research/session-b-fixtures/nested/shared.yaml').read_text()
    long_path = 'long-path-' + 'distinguishing-segment-' * 7 + 'suffix'
    (root / 'home' / long_path).mkdir()
    shared.write_text(base + '\n  - path: quiet-cache\n    advice: usually-unnecessary\n'
                      '    reason: Fixture-only advice, never a safety assessment.\n'
                      f'  - path: {long_path}\n'
                      '    reason: This long explanation must remain accessible after wrapping and scrolling. '
                      + 'Literal descriptive text. ' * 18 + 'REASON-END\n')
    return shared, long_path


def shared_journey(width, height):
    with tempfile.TemporaryDirectory(prefix='homelight-b32b-', dir='/tmp') as directory:
        root = Path(directory).resolve()
        shared, long_path = fixtures(root)
        config = root / 'config.yaml'
        t = Terminal([str(config)], width, height, fixture=True)
        t.wait_for('Storage locations')
        locations(t, root, shared)
        t.key('b', .6)
        t.wait_for('13 candidates')
        assert '2 usually-unnecessary directories hidden' in t.text(), t.text()
        t.snap('Bundled/shared, mixed advice and counted reveal')
        for size in ((120, 30), (80, 24), (width, height)):
            resize(t, *size)
        choose(t, '.m2')
        t.key('\r')
        evidence = inspect_all(t)
        assert 'Build tools' in evidence and 'Maven' in evidence
        assert 'Usually unnecessary' in evidence and 'Consider' in evidence
        t.snap('Full app associations and attributed advice')
        t.key('a')
        t.key('e')
        t.key('\x1b[B\x15custom-maven')
        t.key('\x1b[B  ')
        t.key('\x1b')
        t.key('b')
        t.key('r', .5)
        assert 'Candidate details' in t.text(), t.text()
        assert 'custom-maven' in inspect_all(t)
        t.key('\x1b')
        t.key('u')
        choose(t, '.cache/example')
        t.key(' ')
        t.key('u')
        choose(t, '.cache/example')
        assert '[x] .cache/example' in t.text(), t.text()
        t.snap('Selected usually-unnecessary directory remains visible after collapse')
        choose(t, '.local/share/uv')
        t.key('\r')
        t.key('a')
        t.key('\x1b')
        choose(t, '.local/share/uv/tools')
        t.key('\r')
        t.key('a')
        t.snap('Overlap rejection before scrolling')
        assert 'Priorchoicesareunchanged' in harness.re.sub(r'\s+', '', inspect_all(t))
        t.key(']', .03)
        t.snap('Overlap rejection retains prior selections')
        t.key('\x1b')
        # Long row names wrap; focus by their distinguishing prefix.
        t.key('\x1b[F')
        t.key('\r')
        evidence = inspect_all(t)
        assert str(root / 'home' / long_path) in evidence, evidence
        assert 'REASON-END' in evidence, evidence
        t.key(']', .03)
        t.snap('Long full path and reason reader with scrollbar')
        t.key('\x1b')
        choose(t, 'team-cache')
        t.key('\r')
        t.key('a')
        t.key('e')
        t.key('\x1b[B\x15custom-team')
        t.key('\x1b')
        t.key('b')
        shared.write_text((harness.REPO / 'docs/research/session-b-fixtures/nested/shared-refreshed.yaml').read_text())
        t.key('r', .5)
        evidence = inspect_all(t)
        assert 'Historical attribution' in evidence and 'custom-team' in evidence
        t.snap('Removed selected candidate retains target and historical attribution')
        t.key('\x1b')
        choose(t, 'new-cache')
        assert '[x] new-cache' not in t.text()
        shared.write_text('apps: [')
        t.key('r', .5)
        t.key('i')
        assert 'stale' in inspect_all(t)
        t.snap('Malformed source diagnostics remain inspectable')
        t.key('\x1b')
        t.key('\x1b')
        # Failed create must preserve all choices, then a retry saves without apply.
        config.write_text('concurrent winner')
        t.key('s')
        t.wait_for('Save failed')
        assert config.read_text() == 'concurrent winner'
        t.snap('Failed save preserves editable draft')
        config.unlink()
        t.key('s')
        t.wait_for('[1: Workspace]')
        saved = config.read_text()
        assert 'custom-maven' in saved and 'custom-team' in saved
        assert 'advice:' not in saved and 'apps:' not in saved
        assert (root / 'home/team-cache/payload').read_text() == 'unchanged'
        assert not (root / 'local/custom-team').exists()
        t.snap('Only explicit selections saved; no execution')
        t.key('q')
        t.finish()


def discard_policy_journey(width, height):
    with tempfile.TemporaryDirectory(prefix='homelight-b32b-discard-', dir='/tmp') as directory:
        root = Path(directory).resolve()
        for name in ('home', 'local'):
            (root / name / '.m2').mkdir(parents=True)
            (root / name / '.m2/payload').write_text(name + ' unchanged')
        config = root / ('long-configuration-location-' * 5) / 'config.yaml'
        t = Terminal(['init', '--config', str(config)], width, height)
        t.wait_for('Storage locations')
        locations(t, root)
        t.key('a')
        t.key('.m2\x1b[B\x1b[B    ')
        consequence = ('Permanently delete both source and target directory trees. '
                       'Create an empty target directory and link the source to it.')
        for size in ((width, height), (120, 30), (80, 24), (width, height)):
            resize(t, *size)
            screen = t.text()
            assert '❯ Both directories: Discard both' in screen, screen
            assert harness.re.sub(r'\s+', '', consequence) in harness.re.sub(r'[│█\s]', '', screen), screen
            assert 'Discard target' not in screen and 'relocate source' not in screen, screen
            assert 'Space: Policy' in screen and 'Esc: Table' in screen, screen
            t.snap('Discard both: focused label and complete destructive consequence after resize')
        evidence = inspect_all(t)
        assert str(config) in evidence, evidence
        t.snap('Scrollable details retain full configuration path and policy context')
        t.key('\x1b')
        t.wait_for('Relocations')
        assert 'Discard both' in t.text(), t.text()
        t.snap('Relocations table explicitly summarizes Discard both')
        t.key('s')
        t.wait_for('[1: Workspace]')
        t.wait_for('a: Review & apply')
        assert "when-source-and-target-directories-exist: 'discard'" in config.read_text()
        for name in ('home', 'local'):
            assert (root / name / '.m2/payload').read_text() == name + ' unchanged'
        t.snap('Save preserves both payloads; no Apply was requested')
        t.key('q')
        t.finish()


def missing_journey(width, height):
    with tempfile.TemporaryDirectory(prefix='homelight-b32b-missing-', dir='/tmp') as directory:
        root = Path(directory).resolve()
        shared, _ = fixtures(root)
        config = root / 'config.yaml'
        t = Terminal([str(config)], width, height, fixture=True)
        t.wait_for('Storage locations')
        locations(t, root, shared)
        t.key('b', .6)
        choose(t, 'absent-cache')
        assert '[ ] absent-cache' in t.text() and 'Space/a: Add' in t.text(), t.text()
        assert 'Missing' not in t.text() and 'Not created yet' not in t.text(), t.text()
        assert 'Blocked by parent link' in t.text() and 'Regular file' in t.text(), t.text()
        t.snap('Missing path is eligible before the app creates it')
        t.key('\r')
        t.snap('Missing path details explain future creation and save-only setup')
        evidence = inspect_all(t)
        assert 'Metadata: Not created yet' in evidence
        assert 'source and target are both missing' in evidence
        assert 'If only the target exists' in evidence
        assert 'Save writes configuration only' in evidence
        t.key('\x1b')
        t.key(' ')
        t.wait_for('[x] absent-cache')
        for size in ((120, 30), (80, 24), (width, height)):
            resize(t, *size)
        t.key('e')
        t.key('\x1b[B\x15future-cache')
        t.key('\x1b[B\x1b[B  ')
        t.snap('Missing-source policy remains an explicit row edit')
        t.key('\x1b')
        t.key('b')
        t.key('r', .6)
        assert '❯   [x] absent-cache' in t.text(), t.text()
        t.snap('Refresh retains missing-path membership and focus')
        t.key('\x1b')
        t.key('s')
        t.wait_for('[1: Workspace]')
        saved = config.read_text()
        assert 'future-cache' in saved and "when-only-target-exists: 'adopt-target'" in saved
        assert not (root / 'home/absent-cache').exists() and not (root / 'local').exists()
        t.snap('Configuration saved; missing source and target still absent')
        t.key('q')
        t.finish()


def stalled_journey(width, height):
    with tempfile.TemporaryDirectory(prefix='homelight-b32b-stall-', dir='/tmp') as directory:
        root = Path(directory).resolve()
        shared, _ = fixtures(root)
        release = root / 'release'
        config = root / 'config.yaml'
        t = Terminal([str(config), str(release)], width, height, fixture=True)
        t.wait_for('Storage locations')
        locations(t, root, shared)
        t.key('b', .4)
        t.wait_for('Shared: pending')
        t.snap('Stalled shared reader leaves bundled discovery usable')
        t.key('\x1b')
        t.key('a')
        t.key('manual')
        t.key('\x1b')
        t.key('q')
        t.key('\x1b')
        assert 'manual' in t.text(), t.text()
        t.key('e')
        t.key('\x15' + str(root / 'other-home'))
        t.key('\x1b[B\x1b[B\x15')
        t.key('\r')
        t.key('b', .4)
        assert 'Shared:' not in t.text()
        t.snap('Root/location changes retain rows and detach the old request')
        t.key('q')
        t.key('\r')
        t.wait_for('Configuration file does not exist')
        release.touch()
        t.pump(.3)
        t.key('i')
        t.wait_for('Storage locations')
        assert 'other-home' not in t.text() and 'shared.yaml' not in t.text()
        locations(t, root, shared)
        t.key('b', .4)
        t.wait_for('13 candidates')
        t.snap('Discard/reopen starts a fresh discovery session after late completion')
        t.key('\x1b')
        t.key('a')
        t.key('manual')
        t.key('\x1b')
        t.key('s')
        t.wait_for('[1: Workspace]')
        t.key('q')
        t.finish()


def save_while_stalled(width, height):
    with tempfile.TemporaryDirectory(prefix='homelight-b32b-save-', dir='/tmp') as directory:
        root = Path(directory).resolve()
        shared, _ = fixtures(root)
        config = root / 'config.yaml'
        t = Terminal([str(config), str(root / 'never-release')], width, height, fixture=True)
        t.wait_for('Storage locations')
        locations(t, root, shared)
        t.key('b', .5)
        t.wait_for('Shared: pending')
        t.key('\x1b')
        t.key('a')
        t.key('manual')
        t.key('\x1b')
        t.key('s')
        t.wait_for('[1: Workspace]')
        assert config.exists() and not (root / 'home/manual').exists()
        t.snap('Manual save succeeds while the optional reader is still blocked')
        t.key('q')
        t.finish()


if __name__ == '__main__':
    if '--representative' in sys.argv:
        representative()
    elif '--missing' in sys.argv:
        for dimensions in ((80, 24), (120, 30)):
            missing_journey(*dimensions)
    elif '--discard-policy' in sys.argv:
        for dimensions in ((80, 24), (120, 30)):
            discard_policy_journey(*dimensions)
    else:
        for dimensions in ((80, 24), (120, 30)):
            representative(*dimensions)
            shared_journey(*dimensions)
            missing_journey(*dimensions)
            discard_policy_journey(*dimensions)
            stalled_journey(*dimensions)
            save_while_stalled(*dimensions)
