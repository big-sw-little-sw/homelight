D3 polish is implemented locally over the existing uncommitted work. This is a
verification handoff, not UX acceptance. Keep #30, #26 and #27 open for the user's
walkthrough; stop before first-run/discovery work.

Implemented the order in #30 and `docs/tui-design.md`:

- Consistent 1: Workspace / 2: Review or Results; unnumbered execution labels;
  discoverable a: Review & apply from both workspace panes. Stage navigation
  remains guarded during execution; lowercase y remains the only confirmation.
- Contextual help, stable pane titles and visible choice focus. Current facts,
  relevant saved policy, unsaved draft, expected consequences, alternatives and
  a full Paths section replace repeated low-level details. Config appears once
  at screen level. Empty/unresolved/in-sync/unchanged/blocked copy is distinct.
- Semantic glyphs/colors, complete relocation counts, plain independent Of these
  counts and explicit review action units. Review has planned-change totals,
  not premature execution progress. Readers use visible overflow track/thumb
  scrollbars that update on resize and disappear when content fits. No numeric
  line counters; prose word-wraps while unbreakable paths remain fully readable.

Evidence (local artifacts, no commit/push):

- `docs/research/session-d3/polish-focused-tests.txt`: 85 tests passed.
- `docs/research/session-d3/polish-full-suite.txt`: Java 25.0.3 clean suite,
  153 tests passed; zero failures/errors/skips.
- `docs/research/session-d3/polish-pty-check.txt`: nine real-JLine sessions covering
  success, preflight rejection and partial failure at 80×24, 120×30 and 200×50.
  Captures include all choices, review list/details, cancel, retained results
  and no-change replan, with growing/shrinking through all three dimensions.
- `docs/research/session-d3/polish-exit-pty-check.txt`: twelve exit sessions at
  the same dimensions, including Escape/Ctrl-C, Keep running, deferred exit on
  success/failure, dialog selection through completion and terminal restoration.
- `D3PolishTest` plus updated render/controller tests cover overflow thumb
  movement/disappearance, full paths and consequences, focused choices through
  resize, numbered cancellation without lost context, and review discoverability.
  `git diff --check` passes.

Captures are ANSI-decoded terminal text, not screenshots or human acceptance.
The decoder now clears wide-glyph continuation cells and handles cursor movement.
Existing macOS/JLine termios exclusions remain documented in the handoff.
Empty configured projection coverage is synthetic because the existing YAML
loader rejects `relocations: []`; no validation change was included.

Updated `docs/research/session-d3-implementation-handoff.md`, `docs/tui-design.md`
and `docs/next-sessions.md`. Executor algorithms, JSON behavior/schema, first-run,
discovery and deferred first-mutating-action selection remain unchanged.
Next: the user's conflict → choose → review → cancel → confirm → results →
revisit/replan UX walkthrough. Do not close these issues based on passing tests.
