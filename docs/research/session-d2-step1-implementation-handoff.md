# Issue #29, step 1: reviewed execution extraction

Continuation: [step 2 JSON integration handoff](session-d2-step2-implementation-handoff.md)
records the separately implemented and verified JSON preflight change. The text
below retains the step 1 boundary and evidence.

Implemented locally on 2026-09-14, on top of the uncommitted #28 work and its
default-configuration classification correction. Baseline HEAD:
`99bb49d2a6d6201c6b5e100f07c0f33e0b2559dc`. No commit or push.

Read the accepted `docs/research/session-d-application-design.md`, current #29
body, #28 comments and `session-d1-implementation-handoff.md`. Read #25: it remains
open with no findings posted. No executor-internal changes were made.

## Implemented boundary

- Added `application/ReviewedExecution.java`. It owns the exact immutable plan,
  confirmation/running/result snapshots, one start, worker completion, whole-plan
  preflight, progress mapping, existing visual-test delay, and retained evidence.
  Construction rejects unresolved/blocked plans and performs no filesystem I/O.
- `start(Executor)` returns the same future on subsequent calls, including after
  scheduling rejection or completion. Results are published before completion
  settles. Rejection retains pending steps and diagnostics without mutation;
  unexpected worker errors retain known steps before exceptional completion.
- `HomeLightSession` owns creating/discarding review, draft/replan guards, current
  screen state, and post-execution status refresh. Its existing confirmation and
  waiting methods remain compatible with the TUI. The session's completion future
  includes status refresh; the worker future belongs to `ReviewedExecution`.
- Status refresh follows result publication and cannot overwrite the retained
  execution evidence. Configuration evaluation still produces Invalid for malformed
  configuration. An unexpected thrown refresh failure makes the session future
  exceptional without replacing the reviewed result.
- Reused `ApplyModel`, plan-local relocation/action identity, and the existing
  concrete executor. No new executor adapter, package restructuring, or UI model.

## Verification

Both commands used
`env JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home`.
Surefire reports confirm Java 25.0.3 and this JDK path; default Maven otherwise
selects Java 26 on this machine.

- Focused:
  `mvn -o -Dtest=ReviewedExecutionTest,HomeLightSessionTest,HomeLightAppTest,ConfigurationEvaluationTest,DefaultConfigurationClassificationTest,ApplyCommandTest,ReconciliationExecutorTest test`
  — 68 tests passed, zero failures/errors/skips.
- Full clean suite: `mvn -o clean test` — 143 tests passed, zero
  failures/errors/skips, compiled with Java 25. Includes existing planner,
  executor, CLI, view, and default-path regression tests.
- Six new module tests cover capture without mutation/config reload, queued
  repeated starts, publication before completion, immutable snapshots and identity,
  scheduling rejection, whole-plan drift before mutation, unresolved/blocked
  capture, no-change repeat, and deterministic failure during the existing visual
  delay. The executor already translates that delay exception into a failed
  action; the test preserves that existing representation.
- Two new session tests cover scheduling rejection and malformed configuration
  during post-execution status refresh. Existing tests continue covering review
  cancellation, exact plan despite config edits, archive preflight, partial failure,
  completed/failed/pending evidence, retained results and explicit replan, status
  refresh, TUI confirmation/following, and consumption of quit while running.
- `git diff --check` passed. Inspected the session extraction and new module,
  plus the existing executor failure translation. The original dirty session
  content was retained during extraction so #28 changes were preserved.

The existing Maven filtered-resource encoding notice remains. No build or
dependency changes. No real-PTY exit/cleanup verification was performed in this
extraction step; that remains part of step 3. Catastrophic VM errors and an
unexpected thrown status-refresh failure were not fault-injected.

## Handoff and remaining work

Step 1 is complete locally. #29 remains open because steps 2 and 3 are not done.
The next separately authorized step is JSON integration with whole-plan preflight
and its own behavior-change tests. Then implement Escape/deferred exit and verify
lifecycle races and real-PTY cleanup. The existing launcher behavior on exceptional
completion is unchanged and remains part of that step.

JSON apply still executes directly. Escape/quit handling, launcher cleanup,
filesystem algorithms, renderers, and navigation behavior were not edited in this
session. `ApplyModel` failure representation is unchanged; do not infer zero
mutation merely from an absent execution result after unexpected worker failure.
No #30 work was started. No ticket was closed, and no commit or push was made.

Preserved all pre-existing tracked and untracked edits, including #28 production
changes, `docs/next-sessions.md`, `docs/research/homefree-comparison.md`, and
agent/settings/audit/prototype/design files. This step adds the module and its
tests, changes only the session and session tests in production/test code, and
adds this handoff plus a continuation pointer in the #28 handoff. The #29 ticket
receives this step's evidence as a comment without replacing its accepted scope.
