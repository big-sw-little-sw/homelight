# Issue #29, step 2: JSON reviewed execution

Implemented locally on 2026-09-14 over the existing uncommitted #28 and #29
step 1 work. Baseline HEAD remains
`99bb49d2a6d6201c6b5e100f07c0f33e0b2559dc`. No commit or push.

Read #29's accepted scope, the application design, the step 1 handoff, and #25.
#25 remains open with no findings/comments. No executor-internal changes.

## Changes and JSON compatibility

- `ApplyCommand` captures the evaluated plan in `ReviewedExecution`, starts it
  synchronously with `Runnable::run`, waits for completion, and renders its result.
  A package-private Executor constructor enables deterministic tests at the
  planning/start boundary. No screen-bearing session or TUI is constructed.
- Whole-plan preflight now applies to JSON before any action. Per-action guards
  remain in the existing executor. Captured plans are not reloaded from config.
- `ApplyRenderer` delegates actual execution results to the existing JSON path:
  success, partial failure, no-op, and leave-unchanged shapes remain unchanged.
  Results without an execution retain the existing relocation/action fields,
  force `succeeded:false`, and add `stale` plus string-array `diagnostics`.
  Preflight rejection reports pending actions, unresolved outcomes, all drift
  diagnostics, and `stale:true`. Scheduling failure reports `stale:false`.
  Known step evidence is retained; missing execution alone does not mean zero
  mutation. No versioned envelope or #19 schema redesign was introduced.
- Exit behavior remains 0 for success/no-change, 1 for execution failure or
  unresolved/blocked plans, and 2 for JSON without `--yes`. New preflight rejection
  returns 1. Existing unconfigured-default empty success and config-error paths
  are preserved. `--yes` cannot resolve conflicts or bypass blocked actions.
- JSON continues to ignore the TUI visual-delay setting and does not refresh
  status or read config again after execution.

## Verification

All Maven commands used
`env JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home`.
Surefire reports confirm Java 25.0.3 and that JDK path.

- Focused:
  `mvn -o -Dtest=ReviewedJsonApplyTest,ApplyCommandTest,ReviewedExecutionTest,DefaultConfigurationClassificationTest,ReconciliationExecutorTest test`
  — 39 tests passed, zero failures/errors/skips.
- Full clean suite: `mvn -o clean test` — 150 tests passed, zero
  failures/errors/skips; compiled with Java 25.
- Seven new CLI integration tests cover later-relocation drift rejecting all
  mutations with multiple diagnostics; success using the captured plan despite
  config edits; no-change repeat with legacy JSON parity; partial failure with
  completed/failed/pending evidence; conflict/blocked `--yes` guards; missing
  `--yes` before config loading; scheduling rejection; and terminal independence.
  Existing tests retain configured leave-unchanged and unconfigured-default
  coverage. Failure fixtures use temporary files, not permissions or timing races.
- The terminal-independence test runs a separate JVM with redirected stdin,
  stdout, stderr and TERM unset. It succeeds with JSON, empty stderr and no
  `dev/tamboui/` class initialization in the JVM initialization log.
- Initial test runs exposed two fixture issues: duplicate picocli subcommand
  registration and a staging-root fixture rejected during planning. The harness
  now uses picocli's factory and the established target-local staging fixture.
  Both focused and clean full runs passed after those corrections.
- `git diff --check` passed. Spot-checked command routing, result adaptation,
  JSON fields, filesystem assertions, and the final changed-file list.

The existing filtered-resource encoding notice remains. No build/dependency
changes. No real-PTY cleanup claims; that verification belongs to step 3.

## Coordinator handoff

Step 2 is complete locally and ready for coordinator review. #29 stays open;
step 3 (Escape/deferred quit, lifecycle races and real-PTY cleanup) is outstanding
and was not started. No #30 work, commit, push, or issue closure.

This step changes only `cli/ApplyCommand.java`, `cli/ApplyRenderer.java`, adds
`cli/ReviewedJsonApplyTest.java`, and adds this handoff with a continuation pointer
in the step 1 handoff. Pre-existing tracked and untracked edits were preserved,
including the earlier changes within ApplyCommand. The shared execution module,
session, TUI, and filesystem executor were not modified in this step.

## Step 2 correction: exceptional completion reporting (2026-09-14)

JSON apply previously joined completion before reading the retained terminal
result, so exceptional completion bypassed JSON rendering. ApplyCommand now
catches CompletionException at its completion/rendering boundary. If a terminal
failure result exists, it renders that result unchanged and returns 1. Otherwise
it rethrows the original exception, including when no terminal result exists.
It never substitutes a successful or empty result or infers zero mutation.

Three deterministic regressions cover retained diagnostics and pending evidence,
completed/failed/pending evidence after actual partial filesystem mutation, and
propagation of the original cause with no JSON when no terminal result exists.
The tests inject exceptional completion through the exposed CompletableFuture
after real result publication, using obtrudeException; the no-result case settles
a queued completion exceptionally. They do not provoke VM failure or change
ReviewedExecution or filesystem execution. The first two regressions failed
before the catch was added and passed afterwards.

Verification with
`env JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home`:

- Focused: `mvn -o -Dtest=ReviewedJsonApplyTest,ApplyCommandTest,ReviewedExecutionTest,DefaultConfigurationClassificationTest,ReconciliationExecutorTest test`
  — 42 passed, zero failures/errors/skips.
- Full: `mvn -o clean test` — 153 passed, zero failures/errors/skips.
- Surefire confirms Java 25.0.3 and the specified JDK path.
- Existing JSON compatibility, --yes guards, and redirected-I/O/no-TamboUI
  initialization regression tests pass. Normal renderer and execution behavior
  are unchanged. `git diff --check` passed.

Correction files: ApplyCommand.java, ReviewedJsonApplyTest.java, and
docs/research/session-d2-step2-implementation-handoff.md. Existing edits preserved.
#29 remains open for coordinator review. No step 3, #30, commit, or push.

## Continuation

Step 3 was subsequently implemented in a separately authorized session. See
[the Escape/deferred-exit handoff](session-d2-step3-implementation-handoff.md)
for lifecycle tests, Java 25 full-suite results, and real PTY evidence.
