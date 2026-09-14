"""Agent-operated PTY walkthrough, not a usability test or production test suite."""
import ast
from pathlib import Path

# Reuse only the existing audit's terminal decoder, never its fixture mutations.
source = ast.parse(Path('docs/research/session-a/terminal-audit.py').read_text())
source.body = [node for node in source.body if isinstance(node, (ast.Import, ast.ImportFrom, ast.ClassDef))]
exec(compile(source, '<session-a terminal decoder>', 'exec'))

class PrototypeTerminal(Terminal):
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
                nums = [int(n or '0') for n in params.split(';')] if not params.startswith(('?', '>')) else [0]
                n = nums[0] or 1
                if command in 'Hf':
                    self.y, self.x = n - 1, (nums[1] or 1) - 1 if len(nums) > 1 else 0
                elif command == 'A': self.y = max(0, self.y - n)
                elif command == 'B': self.y = min(self.height - 1, self.y + n)
                elif command == 'C': self.x = min(self.width - 1, self.x + n)
                elif command == 'D': self.x = max(0, self.x - n)
                elif command == 'G': self.x = n - 1
                elif command == 'd': self.y = n - 1
                elif command == 'J' and nums[0] in (2, 3): self.cells = [[' '] * self.width for _ in range(self.height)]
                elif command == 'K' and 0 <= self.y < self.height:
                    start, end = (0, self.width) if nums[0] == 2 else (0, self.x + 1) if nums[0] == 1 else (self.x, self.width)
                    self.cells[self.y][start:end] = [' '] * (end - start)
                elif command == 'X' and 0 <= self.y < self.height:
                    end = min(self.width, self.x + n)
                    self.cells[self.y][self.x:end] = [' '] * (end - self.x)
                continue
            char, self.pending = self.pending[0], self.pending[1:]
            if char == '\r': self.x = 0
            elif char == '\n': self.y = min(self.height - 1, self.y + 1)
            elif char >= ' ':
                if 0 <= self.y < self.height and 0 <= self.x < self.width:
                    self.cells[self.y][self.x] = char
                self.x += 1

    def __init__(self, width, height):
        self.width, self.height = width, height
        self.cells = [[' '] * width for _ in range(height)]
        self.x = self.y = 0
        self.pending = self.raw = ''
        self.decoder = codecs.getincrementaldecoder('utf-8')('replace')
        self.pid, self.fd = pty.fork()
        if self.pid == 0:
            os.environ['TERM'] = 'xterm-256color'
            os.execv('/bin/bash', ['bash', 'docs/research/session-c-prototype/launch.sh'])
        self.resize(width, height)
        self.pump(6)

for width, height in [(80, 24), (120, 30)]:
    t = PrototypeTerminal(width, height)
    t.snap('workspace initial')
    t.key('l '); t.snap('first choice')
    t.key('j'); t.snap('archive choice with full paths')
    t.resize(120 if width == 80 else 80, 30 if height == 24 else 24)
    t.pump(.4); t.snap('choice survives resize')
    t.resize(width, height); t.pump(.4)
    t.key('j'); t.snap('third choice')
    t.key('k 3'); t.snap('review archive')
    t.key(']'); t.snap('review paths page 2')
    t.key(']'); t.snap('review paths page 3')
    t.key('n'); t.snap('workspace cancel retains detail focus')
    t.key('j3n'); t.snap('workspace cancel retains non-first relocation')
    t.key('v2l j 3n'); t.snap('tabs cancel resets focus')
    t.key('1'); t.key('l'); t.snap('tabs saved-policy status versus draft')
    t.key('2j3n'); t.snap('tabs cancel loses non-first relocation')
    t.key('vx'); t.key('l  3y'); t.snap('preflight zero mutations')
    t.key(']'); t.snap('preflight full reason')
    t.key('1l'); t.snap('preflight observations')
    t.key('3'); t.snap('retained rejection')
    t.key('r'); t.snap('explicit replan')
    t.key('x'); t.key('l  3y'); t.pump(2)
    t.key('k', .1); t.snap('manual inspection while running')
    t.pump(2); t.snap('follow next action transition')
    t.pump(4); t.snap('partial execution result')
    t.key(']'); t.snap('full failure path and recovery')
    t.key('x'); t.snap('missing default config')
    t.key('mu'); t.snap('unavailable optional shared input')
    t.key(' '); t.key('f'); t.snap('refresh preserves draft')
    t.key('n'); t.snap('cancel no save')
    t.key('m sy'); t.snap('deliberate simulated save')
    t.key('fu'); t.snap('shared changes preserve configured entries')
    t.key('x'); t.snap('missing explicit config')
    t.key('m'); t.key('o'); t.snap('manual root edit'); t.key('\x1b')
    t.key('n'); t.key('x'); t.key('l  3\r'); t.snap('Enter does not execute')
    t.key('y'); t.pump(19); t.snap('success retained')
    t.key('13'); t.snap('result revisited')
    t.key('r3y'); t.snap('explicit no-change replan')
    t.quit()
