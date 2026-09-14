# Issue #30: returning-user workspace

## Final coordinator acceptance, 2026-09-14

D3 is accepted. The user accepted the polished UX; the typed-comparison
follow-up resolves the remaining technical finding. Independent standards and
spec reviews found no remaining actionable findings in the correction.

Independently reran the Java 25 clean full suite: **155 passed**, zero failures,
errors or skips. `git diff --check` passed. Inspected the suffix-collision,
exact-target deduplication and adoption-policy regressions. PTY checks were not
rerun for this correction; the prior recorded evidence and user walkthrough
remain the UX evidence.

Close #30/#26/#27. The pending-review instructions below are historical.
Next product slice: #31, explicit manual first-run configuration creation;
#21 candidate-list specification can proceed independently. No production
changes by this review, no #31 implementation, commit or push.

## Coordinator review, 2026-09-14

The user completed the polished walkthrough and accepted the UX. The human gate
is satisfied; the older requests below to stop for that walkthrough are historical.

Independent verification: Java 25 clean suite **153 passed**, zero failures,
errors or skips; `git diff --check` passed. Reviewed the recorded nine workspace
and twelve exit PTY journeys; these were not rerun by the coordinator.

Spec review found no actionable departures from the accepted journey. Standards
review found one bounded issue in two places: presentation strings determine
semantic details. In `ApplyView.details`, compare typed destination and target
paths for equality instead of using the rendered destination's `endsWith`.
In `WorkspaceView.policy`, branch on the saved policy enum instead of comparing
the rendered `Adopt target` phrase. Add regressions for distinct destination paths
sharing a suffix, exact-target deduplication, and adoption-policy details.

Keep #30/#26/#27 open for this technical follow-up, not another general UX audit.
Preserve the accepted presentation, run focused and full Java 25 tests, update
this handoff and the tickets, then stop for coordinator review. No first-run,
discovery, commit or push. After acceptance, the next product slice is #31.

## Coordinator follow-up, 2026-09-14

Implemented only the coordinator's typed-comparison follow-up. `ApplyView` now
compares typed action destination and relocation target paths when deciding
whether to repeat `Target:`; it no longer infers that fact from a rendered
destination string. `WorkspaceView` now uses the saved
`WhenSourceAndTargetDirectoriesExist` enum to decide whether to append the
adoption-source policy detail. The accepted wording and layout are unchanged.

New regressions cover an archive destination with the same trailing path as a
different target, exact-target destination de-duplication, and saved adoption
policy details.

Verification on Java 25.0.3:

- `mvn -o -Dtest=ApplyViewTest,WorkspaceViewTest test`: **11 passed**, no
  failures, errors or skips.
- `mvn -o clean test`: **155 passed**, no failures, errors or skips; 57
  production and 21 test source files compiled.
- `git diff --check` passed before verification.

Updated #30, #26 and #27 with this result. They remain open for coordinator
review. No #31 work, commit or push.

## D3 polish handoff, 2026-09-14

The follow-up polish is implemented locally over the pre-existing uncommitted
D1/D2/D3 work. This section supersedes the original implementation account below,
including its old `3` shortcuts, focus-title annotations and line counters.
The maintained contract is [tui-design.md](../tui-design.md); the originating
review is [ux-review.md](session-d3/ux-review.md).

- Navigation uses `1: Workspace` and `2: Review/Results` from either pane.
  A ready changing draft advertises `a: Review & apply` in both panes. Review
  unavailability identifies conflicts or blocked repairs. Execution labels are
  unnumbered, and stage keys remain guarded. Pressing the current stage key
  preserves selection. `1`, `n` and Escape cancel review without losing draft,
  source identity or prior pane focus. Only lowercase `y` starts mutation.
- One two-row contextual help area groups reader-scrolling keys only when the
  reader overflows. Escape's help reflects cancellation or pane navigation.
  Stable pane titles, cyan focus borders, pointers and radio markers distinguish
  selection, focused choice and chosen draft policy.
- Details lead with current facts, the relevant saved policy, unsaved draft and
  expected consequence, followed by alternatives and one Paths section. Choice
  consequences include source-link creation after archive/discard. Configuration
  is fully wrapped once at screen level; discarded-choice counts are session-level.
  Unresolved, blocked, in-sync, intentionally unchanged and empty states have
  distinct copy. Workspace no longer repeats the low-level action list.
- Review retains the action hierarchy and exact affected paths/destinations,
  without duplicate Source fields. Before execution it shows planned changes and
  destructive action counts. No-change review has no progress or confirmation.
  Running/results retain action progress, failure evidence and not-run accounting.
- Primary relocation counts include semantic glyphs/colors and remain complete
  across two deliberate rows. Independent warnings/destructive properties use
  `Of these` wording. Empty risk and hidden-count rows are omitted.
- Overflowing readers have a visible track/thumb based on wrapped content,
  viewport height and offset. The thumb moves on scroll/resize and disappears
  when content fits. Lists retain their native overflow scrollbars. Prose wraps
  at words; unbreakable paths wrap by character. Numeric line counters are gone.

Verification on Java 25.0.3:

- [Focused suite](session-d3/polish-focused-tests.txt): **85 passed**, no failures,
  errors or skips. Includes `D3PolishTest`, workspace/review renders and keys,
  session/execution safeguards, JSON, launcher and deferred-exit tests.
- [Full clean suite](session-d3/polish-full-suite.txt): **153 passed**, no failures,
  errors or skips; 57 production and 21 test source files compiled.
- [Final workspace PTY captures](session-d3/polish-pty-check.txt) and
  [exit PTY captures](session-d3/polish-exit-pty-check.txt) use the existing
  real-JLine drivers, updated for numbering, wide glyphs and cursor movement.
  The workspace driver covers 80×24, 120×30 and 200×50, all four choices, growing
  and shrinking, review list/details, cancellation, confirmation, retained
  success, revisit, no-change replan, preflight rejection and partial failure.
  Twelve exit sessions at those same three sizes preserve Escape, Ctrl-C, both quit choices, completion while
  a dialog is open, deferred success/failure exit and terminal restoration.
- Render checks inspect full source/target/archive/publication/failure paths,
  focused choices across 80→120→200→120→80, scrollbar movement and disappearance,
  review discoverability from both panes, count units and empty-state wording.
  String assertions normalize prose whitespace introduced by word wrapping;
  complete path assertions remain intact. `git diff --check` passes.

The empty configured projection is tested directly: the current YAML loader
rejects `relocations: []`; this polish does not change configuration validation.
Captures are decoded terminal text, not screenshots or human UX acceptance.
The previously documented macOS/JLine termios exclusions still apply. No new
platform or font/palette compatibility claim is made.

Reproduce with the focused command below plus `D3PolishTest`, then
`JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home mvn -o clean test`,
`python3 docs/research/session-d3/pty-check.py` and
`python3 docs/research/session-d2-step3/pty-check.py`.

No executor algorithm, JSON behavior/schema, dependency, first-run or discovery
changes. Existing unrelated edits remain. No commit or push. #30/#26/#27 remain
open: stop for the user's conflict → choose → review → cancel → confirm →
results → revisit/replan UX walkthrough.

Published the [polish evidence](session-d3/polish-issue-update.md) to
[#30](https://github.com/big-sw-little-sw/homelight/issues/30#issuecomment-5663525676),
[#26](https://github.com/big-sw-little-sw/homelight/issues/26#issuecomment-5663526102)
and [#27](https://github.com/big-sw-little-sw/homelight/issues/27#issuecomment-5663526557).
All nine final workspace PTY sessions and all twelve exit PTY sessions passed.

## Original implementation account (historical)

User review identified unresolved presentation issues after this implementation
handoff. See [the UX review and coordinator actions](session-d3/ux-review.md)
before continuing or closing #30/#26/#27. Functional verification below does not
constitute UX acceptance. The follow-up review changed documentation only.
The user explicitly chose visible scrollbars over numeric line counters; carry
that decision into the design language and the D3 polish ticket.

Implemented locally on 2026-09-14 over the existing uncommitted #28 and #29 work.
Baseline HEAD remains `99bb49d2a6d6201c6b5e100f07c0f33e0b2559dc`.
No commit or push. This is the coordinator review handoff, not authorization to
start first-run/discovery work.

## Behavior and ownership

- `HomeLightApp` owns `Screen`, selection, focus and the two detail viewports.
  Status, plan, apply and the default human command enter one workspace. There
  is no separate Status/Plan navigation or configuration placeholder.
- The relocation pane starts at 45% of the available width. Compact rows retain
  badges and names; complete paths are in the adjacent reader. Cyan borders,
  explicit focused-pane titles, Unicode radio choices and scroll-position hints
  make focus and overflow visible. The main panes use the available height.
- Current observation, saved policy, draft (not saved), expected outcome and
  execution history have distinct labels. Every choice includes its consequence;
  archive choices include the complete archive destination. Broken and wrong
  source links retain independent warning classification.
- `Tab/l` enters details; arrows/j/k choose (or scroll read-only details);
  Space/Enter selects a choice. `[/]` scrolls complete details without changing
  the chosen option. `Tab/h/Escape` returns to the relocation list. The `c` filter
  preserves source identity when visible and retains the all-in-sync fallback.
- `3/a` reviews the resolved draft. Source selection and choice focus survive
  `n` or Escape cancellation. Enter never confirms mutation; only lowercase `y`
  executes a changing plan. Blocked/unresolved plans cannot reach confirmation.
- Execution follows each new running action. Manual inspection lasts until the
  next action transition. Each action has an affected-path label and complete
  archive, publication, copy or link destination where applicable. Failure
  messages and global diagnostics use the same scrollable reader.
- Results remain retained across return to the workspace and `3` revisit.
  Return shows refreshed observations and the reviewed draft history. Explicit
  `r` replans, reports discarded choices, and clears results. Success can be
  reviewed as a no-change plan without executing again.
- Primary counts partition entries into actionable/conflict/blocked/in-sync/
  unchanged. Warning and destructive counts explicitly overlap. Mutating action
  counts distinguish completed, failed, not-run/pending and running; NoOp and
  LeaveUnchanged are separate. Preflight rejection is distinguished from partial
  execution, and unexpected worker failure does not claim zero mutations.
- Escape never requests exit. The previous Keep running default and confirmed
  deferred exit remain intact, including settlement after result publication,
  progress during the quit dialog and terminal cleanup after settlement.

`HomeLightSession` retains the evaluated draft and reviewed execution without
screen state. Its current `PlanModel` is refreshed after execution; its retained
evaluation still describes the reviewed draft until explicit replanning. The
existing shared evaluation/planning adapters remain; the redundant Status
projection (`StatusWorkflow`, `StatusModel`, `StatusSummary`,
`RelocationStatusItem`) and both old TUI views were removed after caller migration.
Status observation tests moved to `WorkspaceObservationTest`; old presentation
tests were replaced with workspace/viewport tests at supported sizes. CLI legacy
renderers remain in #19's scope.

## Verification

All Maven runs used
`JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home`.
Surefire reports identify Java 25.0.3.

- Focused suite: **82 passed**, zero failures/errors/skips. Includes workspace
  renders, observations, controller, Apply, exit, launcher, session, evaluation,
  reviewed execution, reviewed JSON apply and default-configuration classification.
- Java 25 full clean suite: **150 passed**, zero failures/errors/skips;
  57 production and 20 test source files compiled. The count differs from the
  prior 162-test suite because obsolete Status/Plan presentation tests were
  replaced; execution and JSON coverage remain.
- Render tests exercise 80×24 → 120×30 → 80×24, every choice and consequence,
  visible focus, review/cancel source identity, complete long paths, destinations
  and failure messages, independent warnings and post-success unchanged counts.
- Six new real-JLine PTY sessions exercise returning-user success, preflight
  rejection and retained partial failure at each supported size. They use
  temporary fixtures, resize both ways, inspect all four choices, confirm only
  with `y`, revisit results and explicitly replan to no changes. Partial failure
  preserves the second source payload after the first relocation completes and
  leaves the third relocation not run; the complete long staging failure path
  is accessible by scrolling.
- Eight existing real-JLine exit PTY sessions pass against this implementation:
  Escape routing, both quit choices across completion, Ctrl-C, deferred exit on
  success and partial failure, and missing configuration. The inherited driver's
  old Status/Plan caption names are historical; the captured UI is the workspace.
- `git diff --check` passes. Spot-checked navigation ownership, source restoration,
  result retention, affected-path dispatch, warning classification, session
  settlement and terminal-lifecycle boundaries.

Evidence: [focused tests](session-d3/focused-tests.txt),
[full suite](session-d3/full-suite.txt), [workspace PTY driver](session-d3/pty-check.py),
[workspace captures](session-d3/pty-check.txt),
[exit captures](session-d3/exit-pty-check.txt).

Focused command:
`mvn -o -Dtest=WorkspaceViewTest,WorkspaceObservationTest,HomeLightAppTest,ApplyViewTest,HomeLightExitTest,TuiLauncherTest,HomeLightSessionTest,ReviewedExecutionTest,ReviewedJsonApplyTest,ConfigurationEvaluationTest,DefaultConfigurationClassificationTest test`.
Run that command or `mvn -o clean test`, then
`python3 docs/research/session-d3/pty-check.py`. The exit driver remains
`python3 docs/research/session-d2-step3/pty-check.py`.

Captures are ANSI-decoded terminal text, not screenshots or a human usability
assessment. Terminal restoration checks cover mode flags, defined control
characters, one alternate-screen exit and cursor restoration. As in #29,
macOS/JLine speed normalization, unused control slots and transient PENDIN are
excluded; no other-platform or serial-terminal claim is made.

Verification caught a null choice lookup and a layout constraint overwritten by
`fill()`, plus a nested column that did not fill available height. These were
fixed before the passing focused/full runs. PTY driver corrections avoided
clearing the decoder on a no-op resize and recognized a partial state-drift
failure's actual result heading.

## Review gate

Updated [#30](https://github.com/big-sw-little-sw/homelight/issues/30#issuecomment-5663123268),
[#26](https://github.com/big-sw-little-sw/homelight/issues/26#issuecomment-5663123782)
and [#27](https://github.com/big-sw-little-sw/homelight/issues/27#issuecomment-5663124196)
with the [ticket comment](session-d3/issue-update.md); all remain open for review.
No first-run/discovery implementation, executor
algorithm changes, JSON schema changes, dependency changes, commit or push.

Existing edits were integrated in overlapping session/TUI files. Earlier shared
evaluation, typed decisions, reviewed execution, exit and JSON work remain.
Unrelated documentation, IDE/agent files and prior handoff artifacts were
preserved. The old Status projection's shared-evaluation behavior was migrated
before its removal. The CLI `ReconciliationPlanning` deletion predates this slice.

Stop here. The next step is human review of this returning-user journey.
