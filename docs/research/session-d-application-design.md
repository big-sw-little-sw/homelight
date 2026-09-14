# Session D: application design for coordinator review

Date: 2026-09-14. Issue: #23, under #15. Baseline HEAD:
`99bb49d2a6d6201c6b5e100f07c0f33e0b2559dc`. Status: accepted by the user after
coordinator review, with the constraints below. No production changes, commit,
or push accompanied this decision.

## Accepted constraints and next session

- Accept shared configuration evaluation, extracted reviewed execution, and
  TUI-owned navigation/exit intent. Keep one Maven module.
- Implement #28 first, then #29, then #30, with a handoff after each slice.
- Within #29, verify execution extraction first, JSON preflight integration
  second, and Escape/deferred exit third. Keep refactoring and behavior changes
  separately reviewable; do not combine them into an unverified rewrite.
- On explicit replan retain a draft choice only when its resolved relocation
  definition is unchanged and the choice remains available. Report discarded
  choices and always require fresh review.
- Deferred exit happens after execution settles, including failure. Results
  remain session-local; the quit confirmation must explain that they will not
  remain available after exit. Keep running remains the default.

#23's design gate is complete; #28 is the next implementation session. Later
slices retain their dependencies and require their own verification. Historical
proposal/review-gate wording below records the design process, not a second
pending approval. GitHub ticket bodies own current implementation scope.

## Recommendation

Keep one Maven module. Introduce one shared `ConfigurationEvaluation` module;
extract `ReviewedExecution` from the existing session; narrow `HomeLightSession`
to evaluation, typed draft choices, and review lifecycle. Keep navigation, focus,
selection, dialogs, following and exit intent in `HomeLightApp`. Retain the pure
planner and guarded executor in their existing packages.

These are two extractions, not a package redesign. Discovery and configuration
save are separate future modules because reading suggestions and committing
explicit user selections have different effects. Their interfaces need not exist
until the first-run slices implement them.

The codebase-design skill informs this proposal: each seam hides repeated
knowledge, callers and tests use the same interface, and no dependency ports are
added solely to mirror existing classes. Filesystem tests use temporary real
directories; deterministic worker tests use the existing `Executor` seam.

## Evidence and accepted scope

Read the accepted [prototype handoff](session-c-prototype/README.md#accepted-workflow-and-implementation-handoff),
`docs/next-sessions.md`, current #23, and related #7/#8/#11/#15/#17/#19/#21/#22/#24–#27
issue bodies and available comments. #22 is closed; #21 and #25 remain open.
The earlier `Blocked by #22` text in #23 is historical, not a pending UX choice.

Source evidence (paths below are relative to `src/main/java/io/github/bigswlittlesw/homelight/`):

- `application/StatusWorkflow.loadStatus`, `PlanWorkflow.loadPlan` and
  `cli/ReconciliationPlanning.plan` each load, inspect source/target/archive,
  construct `RelocationState`, and invoke the planner. Status inspects the source
  again to classify it; Plan derives classification from its existing observation.
- `HomeLightSession.reloadModels` loads saved Status and overridden Plan
  separately. `resolveDecision` finds a source in a newly loaded list, then writes
  indexed SmallRye property keys. Saved policy and draft provenance are lost in
  Plan's effective `Relocation`; indices can outlive a changed configuration.
- `HomeLightSession.requestApply` captures the exact plan. Confirmation never
  reloads it. `executeReviewed` preflights all retained observations, then executes;
  CLI `ApplyCommand` calls `execute` directly. The executor's `execute` performs
  per-action guards but does not itself call whole-plan `preflight`.
- `ApplyModel` already has immutable Confirmation/Running/Result snapshots.
  Progress correlates relocation/action by object identity within that plan.
  Results are retained until explicit refresh; no journal survives application exit.
- `HomeLightApp` follows action transitions while allowing manual inspection.
  It consumes all other keys while running, and calls `quit()` on Escape in
  master/result contexts. `onStop` joins execution; `TuiLauncher` owns launch/error
  handling. Joining is not the accepted deferred-exit interaction.
- `StatusView` and `PlanView` start the relocation pane at 45%. `StatusSummary`
  drops SKIPPED and treats WARNING as a primary state; `PlanSummary` also derives
  warning totals from exclusive badges. #27 records the resulting fixture mismatch.

No prototype controller/session is a production implementation candidate.

## Proposed modules and caller examples

Examples are interface sketches, not compiling code or committed names. Existing
calls in the “before” snippets are condensed from the named callers.

### 1. Configuration evaluation: one observation set and explicit draft provenance

`ConfigurationEvaluation` owns loading, constructing inspected states (including
archive destinations), applying typed decisions, and calling the pure planner.
Its immutable evaluation contains saved configuration, observations, typed draft,
effective relocations, available choices and resulting plan. No terminal state.
Derive current source classification from the same observations used for planning.
This is a bounded inspection pass, not an atomic filesystem snapshot.

Before, in the session and CLI:

```java
statusModel = statusWorkflow.loadStatus(configPath);
planModel = planWorkflow.loadPlan(configPath, overrides);
// CLI independently reconstructs the same inspected states.
var plan = new ReconciliationPlanning().plan(configPath, overrides);
```

After:

```java
var evaluation = evaluator.load(configPath); // Loaded / Missing / Invalid
// TUI renders current observations and proposed outcomes from the same evaluation.
// CLI translates that evaluation to its command-specific response.
```

Before, in `HomeLightSession.resolveDecision`:

```java
var configuration = configurationLoader.load(configPath, overrides);
// Search the new list for the selected source, obtain index.
overrides.put("homelight.relocations[" + index + "]." + property, value);
reloadModels();
```

After:

```java
session.choose(selectedSourcePath, DecisionChoice.ADOPT_AND_ARCHIVE_SOURCE);
// Inside session: evaluator.choose(currentEvaluation, sourcePath, choice).
var workspace = session.evaluation();
```

Use normalized source path as identity within one loaded configuration, given
existing duplicate/overlap validation. A choice replaces that relocation's typed
draft choice, validates availability against observations, and replans from those
observations without I/O or YAML keys. Saved policy remains separately readable.
Reject an unknown source or unavailable choice; do not silently ignore it.
On explicit replan, reload/reinspect and retain choices only for unchanged resolved
relocation definitions with still-available choices. Report removed/changed choices;
never transfer by array index. A draft edit cancels pending review. Running and
retained-result states reject edits until explicit replan.

`ConfigurationLoader` continues to own storage parsing and CLI path-override
translation. Existing `--source-path`/`--target-path` behavior remains covered by
CLI tests; these are input overrides, not draft decisions. A temporary internal
property-key translator is acceptable during migration, but application callers
must not retain indexed override maps.

Removal: retire duplicate inspection methods and `ReconciliationPlanning` once
all command callers migrate. Remove Status/Plan workflow wrappers once the unified
workspace uses evaluation directly. Keep command renderers until #19's cutover.
Do not replace three workflows with three new pass-through wrappers.

### 2. Reviewed execution: one exact plan, one start, retained evidence

Extract existing execution behavior into `ReviewedExecution`, a concrete module
created by the session when review is requested. Its interface exposes immutable
snapshot, one idempotent `start(Executor)`, and completion waiting. It owns the
captured plan, worker, preflight, progress and final result. The session owns
whether review may be created/discarded, and whether a new evaluation is allowed.

Before:

```java
// TUI session:
var drift = executor.preflight(plan);
// Map diagnostics/progress into ApplyModel, retain result, manage future.
// CLI:
var result = new ReconciliationExecutor().execute(plan);
```

After:

```java
// TUI: request review captures a resolved evaluation; only y starts mutation.
var review = session.review();
review.start(worker); // same reviewed object, at most once
var snapshot = review.snapshot();
// JSON: --yes is checked by the CLI; no screen or session navigation is needed.
var run = ReviewedExecution.capture(evaluation);
run.start(Runnable::run);
renderJson(run.snapshot());
```

Capture rejects unresolved/blocked evaluations, and stores immutable plan/list
data. Repeat starts return the same completion. No-change review is successful
without mutation. Start always preflights the captured observations before any
action; per-action executor guards remain mandatory afterwards. Shared preflight
adds protection to JSON and is a deliberate behavior change, not mechanical cleanup.

Preserve the existing test contract: editing the config file after review does
not substitute a plan or silently alter its actions. Preflight freshness means
path state, link destination/availability and directory emptiness, not content
hashes or a configuration-file lock. A content-level freshness promise is out of scope.

Retain plan-local relocation/action identity; no UUID registry or new action-ID
hierarchy is justified for in-process following. JSON can serialize relocation
and action ordinals scoped to the returned plan under #19; no cross-command
review-token persistence is promised. New evaluation means new plan identity.

Result distinguishes preflight rejection (zero attempted mutations), successful
execution, partial failure, and worker/start failure. A failure report retains all
known completed/failed/not-run actions, plus full diagnostics. An unexpected failure
after an action starts may leave its effect uncertain; never report zero mutations
just because no `ExecutionResult` was returned. “Stale” is an additional diagnostic,
not a replacement for partial execution history. Count mutating actions separately
from NoOp/LeaveUnchanged. Publish terminal result before completing the future.
Refresh observations after execution separately; refresh failure cannot erase results.

Removal: move the execution/progress/future helpers out of `HomeLightSession`,
reuse `ApplyModel` initially, and remove direct CLI execution orchestration when it
migrates. Keep executor/planner safety tests. Do not add an executor abstraction
without a demonstrated second adapter.

### 3. TUI lifecycle: navigation and exit intent stay local

Before:

```java
if (running) return HANDLED; // q ignored
if (escape || quitKey) quit();
// onStop: session.awaitExecution();
```

After, inside `HomeLightApp` (pseudocode):

```java
if (escape) return cancelDialogOrBackToParentPane();
if (quitKey && review.isRunning()) return openExitDialog(KEEP_RUNNING);
if (exitWhenFinishedChosen) exitWhenFinished = true;
if (exitWhenFinished && review.isTerminal()) quit(); // UI thread only
```

This is a small extension to the existing controller, not a new navigation
framework. Move `Screen` and `activeScreen` responsibility into TUI when replacing
the two screens. CLI commands choose a TUI entry context at launch; JSON never
constructs `HomeLightApp` or a screen-bearing session.

| Event | Owner and transition |
| --- | --- |
| Escape in dialog | App cancels dialog; restore prior focus; no execution command |
| Escape in detail | App returns focus to relocation/action list |
| Escape at top level | App consumes it; no quit, including result/no-change pages |
| q or toolkit quit intent during execution | App opens dialog with Keep running selected; consume toolkit default quit handling |
| Keep running / Escape in quit dialog | Dismiss; worker continues; no deferred exit intent |
| Exit when execution finishes | Set app-local exit intent, dismiss dialog, keep progress responsive |
| Completion/failure with deferred exit | Worker publishes result/completion; UI observes it and calls quit once, on UI thread |
| Completion while dialog is open | Retain result; Keep running means remain open, exit choice may now quit immediately; never restart work |
| Start rejection/unexpected worker failure | Execution publishes failure evidence and settles completion; app can exit after settlement |
| Terminal loop exits unexpectedly | Launcher/finally waits for any active worker, then ensures terminal cleanup on success or failure |

Do not use `cancel`, interrupts, `shutdownNow`, or a cancelled future for deferred
exit. Keep one named platform worker; #24 evaluates other threading proposals.
Use a queued/rejecting test Executor and latches, not timing sleeps, for races.
Final cleanup must run even when joining an exceptional completion. Verify toolkit
shutdown ordering with a real PTY; current `onStop` alone is not proof that all
exception paths restore the terminal after worker settlement. OS force-kill and
power loss are outside the safe-exit contract. Results remain in memory until
explicit replan or exit; persistent result logging is not part of this journey.

## Accepted requirements → owners, tests, implementation slices

| Requirement | Owner | Required evidence | Slice |
| --- | --- | --- | --- |
| One observe/decide workspace, then explicit review | HomeLightApp + session | Returning-user journey: choose, review, cancel preserves source selection/focus/draft | D3 |
| Current state, saved policy, draft choice, expected outcome distinct | Evaluation + workspace view | Saved conflict + draft adopt visibly differ; observations stable across choice; no save/mutation | D1, D3, #27 |
| Compact rows, Unicode radio choices, scrolling | Workspace view | All four conflict choices/consequences keyboard accessible | #26 / D3 |
| A's 45% starting pane proportion and styling | Workspace view | Render at 80×24, 120×30; no B capped-width transplant | #26 / D3 |
| Default hide in-sync, c toggle, hidden count | App/view | Mixed fixture hides converged only; all-in-sync fallback shows items; explicit toggle retained | D3 |
| Full paths and visible focus during resize | Workspace + ApplyView | Real PTY/render checks at both sizes and resize in both directions; source/target/archive/publication/failure text readable | #26 |
| Explicit destructive review, y confirmation | Session + ReviewedExecution; app maps key | No mutation on request/cancel/Enter; unresolved/blocked denied; repeat y starts once | D2, D3 |
| Action following with manual inspection | App + plan-local action identity | Inspection persists until next action transition; correct relocation/action selected | D3 |
| Retained results and explicit replanning | ReviewedExecution + session | Leave/revisit retains exact result; successful repeat is no-change; partial retry requires new plan | D2, D3 |
| Escape never exits | App | Dialog/detail/top-level tests in workspace, review, running, result, setup; toolkit fallback covered | D2 |
| Quit while running: default Keep running, deferred exit | App + ReviewedExecution + launcher | Slow controlled execution, completion/dialog race, failure and worker rejection; terminal restored; no interrupt | D2 |
| Complete primary counts, independent warnings | Evaluation facts + view summary | Six rows = 3 actionable + 1 conflict + 1 in-sync + 1 unchanged; warning/destructive overlap explicit | #27 |
| Preflight rejection vs partial execution, recovery paths | ReviewedExecution + ApplyView | Zero-attempt preflight vs completed/failed/not-run mutation totals; full cause accessible | D2, #26, #27 |
| Missing default AND explicit config → manual setup | Configuration save module + app | Both absent paths enter setup; malformed/unreadable existing file does not become an overwrite invitation | D4 / #8/#17 |
| Explicit save/cancel; save before apply | Configuration save module | Cancel writes nothing; validate all relationships; failed save preserves editable draft; atomic publication/reload | D4 |
| Optional list setting + explicit candidate selection | Discovery + setup/save | Lists are suggestions; only selected entries saved; list unavailable leaves manual/other candidates usable | #21 → #7, D5 |
| Refresh list cannot alter config, draft or review | Discovery read-only result; session/save own changes | Change/remove/refresh list with selections and reviewed plan present; all unchanged | #7, D5 |
| Terminal-independent automation shares policy/evaluation/execution | CLI adapters | Redirected I/O, no TamboUI initialization, --yes never resolves decisions, parity tests | D1, D2, #19 |

## Bounded implementation tickets

All proposed production slices wait for coordinator acceptance of #23. Each must
finish with tests and a handoff; do not automatically start its successor.

Published tickets (all `needs-triage`, none closed or marked implementation-ready):

| Slice | Ticket | Dependencies after coordinator review |
| --- | --- | --- |
| D1 | [#28](https://github.com/big-sw-little-sw/homelight/issues/28) | Accepted #23 design |
| D2 | [#29](https://github.com/big-sw-little-sw/homelight/issues/29) | Accepted #23; read #25, wait if executor internals change |
| D3 | [#30](https://github.com/big-sw-little-sw/homelight/issues/30) | #28, #29; coordinates #26/#27 |
| D4 | [#31](https://github.com/big-sw-little-sw/homelight/issues/31) | #28; integrates with #30 |
| D5 | [#32](https://github.com/big-sw-little-sw/homelight/issues/32) | #31 and accepted #21/#7 discovery contract |

**D1 — Shared evaluation and typed draft decisions.** Replace repeated
load/inspect/plan construction, preserve archive observations and existing CLI
input overrides, expose saved/draft/effective values, and remove indexed draft
storage. Migrate TUI session and CLI evaluation callers. Tests: loader errors,
single observation set, all choices, unavailable choice, config reorder/remove,
draft refresh, no writes, planner parity, current CLI output/routing contracts.
Exclude layout, JSON envelopes, execution changes and package-wide moves.

**D2 — Exact reviewed execution and safe deferred exit.** Extract current session
execution, route JSON apply through preflight, retain results through worker and
observation-refresh failures, implement Escape/quit semantics and launcher cleanup.
Tests: existing session safety suite plus deterministic repeated-start, rejection,
completion/dialog races, worker failure and real-PTY cleanup. No executor algorithm
changes. Read #25 findings; any change inside filesystem execution waits for #25.
Can precede D3; coordinate `HomeLightSession`/`HomeLightApp` edits sequentially.

**D3 — Accepted returning-user workspace.** Depends on D1/D2. Replace Status/Plan
navigation with one view, preserve focus by source identity, review/cancel,
follow/manual inspection, retained results and explicit replan. #26 owns this
slice's complete accessibility acceptance; #27 owns counts/copy. Remove redundant
Status/Plan views/workflows/models only as their last callers migrate; preserve
CLI renderers until #19. No first-run writer or candidate parser. Real PTY at
80×24, 120×30 and resize required. Do not import prototype controller copies.

**D4 — Missing-config manual setup and atomic create.** Child scope of #8/#17;
depends on accepted design and D1, integrates with D3. In-memory source/target/
policy editing, absent default and explicit paths, `init` entry, explicit validated
save followed by shared workspace. Reuse loader validation through one shared
validator if needed, not duplicated policy. Create must fail without replacing a
file that appeared since setup began; atomic publication support is required.
Existing-file editing/replacement, managed links and ownership enrichment remain
in #17/#5/#6. Tests: both absent paths, cancel, malformed existing config, overlap,
write failure, concurrent creation, successful reload, no apply before save.
Basic creation is independent of shared-list format and managed-link features.

**D5 — Connect candidate discovery to explicit setup/save.** Under #8/#17 after
D4 and #21/#7. Discovery interface takes chosen root, optional list location and
configured paths and returns immutable candidates/provenance/diagnostics. Save
interface accepts the separate discovery setting and explicit relocation draft.
No discovery refresh callback may edit that draft. Tests cover missing/unreadable/
malformed/unavailable list, home/non-home roots, duplicates, already configured,
unsafe entries, preserved selections and reviewed plans. #21 must first settle
format, resolution, sizing and unavailable-NFS details; this ticket is not ready
for implementation until that contract is accepted.

For D4/D5 the before caller is the Config placeholder (production has no init/save
journey). The proposed caller is `discovery.scan(inputs)` → explicit selection in
an in-memory draft → `configuration.saveNew(path, draft)` → `evaluator.load(path)`.
Cancel discards the draft. Save does not apply. No generic repository interface,
discovery policy inheritance, or parallel planning path is needed.

**Existing-ticket reconciliation:** #26 and #27 keep their evidence and own the
accepted workspace accessibility and state/copy work. #19 follows D1/D2 for
versioned envelopes, diagnostics/exit codes, validation and remaining legacy human
renderer removal; do not freeze its schema around TUI models. #15's screen names
are historical; human deep links enter contexts in the accepted workspace.
#7 basic discovery need not wait on #5 ownership enrichment; #17's full editor
keeps #6 dependency, while D4 creation does not. #11 stays open pending independent
integration review and #25. #24 stays separate for Java/toolchain changes; no
preview, dependency, or concurrency change is included here.

## Review gate and verification

Coordinator decisions: accept the two extractions and ticket ordering; confirm
explicit-replan draft retention only for unchanged definitions; confirm results
remain session-local when deferred exit is selected. These last two are proposed
details of the accepted journey, not newly accepted product behavior.

Source and existing test inspection establishes the design evidence; no new
keyboard behavior is claimed to work. Inspected `HomeLightSessionTest` exact-plan,
cancel, blocked, preflight, immutable snapshot, repeat confirmation and partial
failure cases; `HomeLightAppTest` navigation/follow tests; executor preflight,
per-action drift, publication and recovery paths. Production tests are not rerun
for this documentation-only proposal. Implementation tickets require their own
tests plus preserved planner/executor idempotence, staging and publication coverage.

Existing edits in `docs/next-sessions.md`, `docs/research/homefree-comparison.md`
and untracked agent/prototype/audit files are preserved. Result commit: none.
Created #28–#32 with `needs-triage`; added ownership/handoff comments to
#7/#8/#15/#17/#19/#23/#26/#27 without replacing their bodies or closing issues.
Recommended next action: coordinator review of this proposal; #21 and #25 may
proceed independently. Production implementation starts only after that review.
