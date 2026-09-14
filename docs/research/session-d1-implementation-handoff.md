# Issue #28: coordinator handoff

Continuation: #29 step 1 is now implemented locally; see
[reviewed execution extraction handoff](session-d2-step1-implementation-handoff.md).
The #28 implementation record below is preserved. JSON preflight integration,
Escape/deferred exit, and #30 remain unimplemented.

Implemented locally on 2026-09-14 using the accepted constraints in
`docs/research/session-d-application-design.md`. Ready for coordinator review.
No commit or push; #29 and #30 were not started.

## Implementation

- Added `application/ConfigurationEvaluation.java`: shared loading, one retained
  source/target/archive observation set, saved configuration and saved plan,
  immutable typed draft choices, available choices, and effective plan.
- Choices use normalized source paths, replace the previous choice, and replan
  from retained observations without loading configuration or inspecting/writing
  the filesystem. Saved policy remains separately readable. Unknown, unavailable,
  and ambiguous duplicate-source choices are rejected.
- Explicit replan compares complete resolved relocation definitions and current
  choice availability. Reordering retains choices by source; removal, definition
  changes, unavailable choices, or failed configuration loading discard them with
  typed reasons. Session exposes these through `discardedChoices()`.
- Session Status and Plan adapters consume the same evaluation. Draft edits cancel
  pending review; refresh requires fresh review. Running and retained-result states
  reject edits until explicit replan.
- Migrated JSON status/plan/apply evaluation to the shared evaluator. CLI path
  overrides remain loader inputs. Removed `cli/ReconciliationPlanning.java`;
  its tracked version remains recoverable from Git.
- Retained Status/Plan workflow adapters for existing models and tests. Removed
  obsolete workflow-injection constructors from the TUI and session; navigation
  tests now use the existing session injection seam.

## Coordinator correction: default configuration classification

Fixed the D1 regression where the session classified a directory at the default
configuration path as Invalid while the standalone workflows returned Unconfigured.
Shared evaluation now retains an explicit `Unconfigured` result when the normalized
default path is not a regular file, preserving the previous default-path behavior.
An explicitly supplied non-default directory remains Invalid, as does a malformed
regular file at the default path.

Status and Plan adapters now translate the retained evaluation without filesystem
checks. The legacy JSON callers reuse the same shared default-path predicate;
their empty-response formats and exit behavior are unchanged. Typed draft logic,
execution orchestration, layout, and key handling were not changed by this correction.

Added `DefaultConfigurationClassificationTest`: four isolated-JVM cases use a
temporary `user.home` before `DEFAULT_PATH` initialization, leaving the real home
untouched. They cover default directory/missing/malformed and explicit directory
classification across session construction/refresh, standalone Status/Plan, and
JSON status/plan/apply. They also verify normalized default-path aliases and that
adapting a retained evaluation does not recheck later filesystem state.

## Verification

Runtime: Eclipse Temurin OpenJDK 25.0.3, with Java 25 compilation.

- Focused command:
  `mvn -o -Dtest=DefaultConfigurationClassificationTest,ConfigurationEvaluationTest,PlanWorkflowTest,StatusWorkflowTest,HomeLightSessionTest,HomeLightAppTest,HomeLightCommandTest,PlanCommandTest,ApplyCommandTest test`
  — 91 tests passed, zero failures/errors/skips, rerun after the classification correction.
- Full clean build: `mvn -o clean test` — 135 tests passed, zero
  failures/errors/skips, including unchanged planner/executor safety tests.
- `git diff --check` passed. Inspected final evaluator, session, adapter, CLI,
  and TUI diffs; application inspection now exists only in the evaluator.
- New tests cover all five choices, saved/draft/effective distinctions, immutable
  snapshots, planner parity, source/target/archive/config changes after loading,
  no writes, replacement choices, review cancellation, reorder/removal/path and
  policy changes, unavailable/unknown/duplicate choices, missing/malformed config,
  running/result edit guards, and CLI path override routing/output.

Existing Maven source/target and resource-encoding warnings remain. No toolchain,
dependency, or build configuration changes were made.

## Boundaries and next coordinator action

Execution orchestration, preflight timing, executor algorithms, view layout, key
handling, and JSON renderers/contracts remain in their existing form. JSON apply
still executes directly; shared reviewed execution and JSON preflight belong to
#29. Invalidation reporting is typed application data; displaying it in the
unified workspace belongs to #30. The existing post-execution status refresh is
preserved separately from the retained reviewed plan.

Review this local D1 diff before accepting the slice. #29 remains the next
implementation slice under its accepted sequencing constraints; it was not
started. #30 retains its dependencies. The inspection pass is bounded, not an
atomic filesystem snapshot.

Preserved the pre-existing changes in `docs/next-sessions.md`,
`docs/research/homefree-comparison.md`, and all pre-existing untracked
agent/settings/audit/prototype/design files. Added this handoff, the evaluator,
and its focused tests alongside the scoped tracked changes. Issue #28 is left
open for coordinator review.
