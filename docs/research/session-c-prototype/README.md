# Session C: compare on the existing HomeLight TUI

## Accepted workflow and implementation handoff

The user chose Demo B: one workspace for observation and decisions, followed by
explicit review, confirmation, execution, and retained results. Separate
Status/Plan navigation is rejected as the preferred workflow, not as a source of
useful styling or execution behavior.

- Keep B's compact relocation rows, Unicode radio choices, readable scrolling,
  and distinct current state, saved policy, draft choice, and expected outcome.
- Start the final layout from A's pane proportions and visual styling. B's
  narrower, capped relocation pane was not an accepted product decision.
- Preserve A's default hiding of in-sync items and `c` toggle, explicit
  destructive confirmation, action following with manual inspection, retained
  results, and explicit replanning.
- Preserve full-path access and visible focus at 80×24, 120×30, and during resize.
- Escape backs out of the current pane or cancels a dialog; at the top level it
  does nothing. Escape never exits the application.
- Quit during execution opens a confirmation with **Keep running** selected by
  default and **Exit when execution finishes** as the supported exit action.
  Confirmation must not interrupt filesystem operations or imply safe cancellation.

Escape and quit behavior are requirements, not implemented prototype fixes.
The prototype blocks quitting during execution but can still exit on Escape in
some top-level contexts. Visual checks reported by the prototype session covered
80×24, 120×30, and 200×50; they do not verify these new requirements.

Next: session D / #23, bounded design work using this accepted journey. Prototype
controller/session copies remain throwaway. No production changes, result commit,
commit or push accompanied the user's acceptance. The experiment instructions
and earlier measurements below remain as evidence of what was demonstrated.

This replaces the standalone simulation. The question is now visible in the
actual application: **keep separate Status and Plan screens, or inspect and
decide in one workspace?** Review and Apply remain the existing screens.

From the repository root:

```sh
bash docs/research/session-c-prototype/launch.sh
```

Starts on **A: existing tabs**. Press **v** to switch to **B: workspace**.
Switching retains the same fixture, draft decisions and selected relocation.
Use `--workspace` to start directly in B. Requires the existing cached Maven
dependencies and Java 25+. The launcher selects installed Temurin 25 on this Mac
when JAVA_HOME is unset, otherwise uses JAVA_HOME or PATH.

## Try just this comparison first

1. In A, inspect the initially selected conflict-cache. Both source and target
   contain a directory. Press **2** for Plan, **l** to focus its decisions, then
   **Space** to select the first option. Press **3** to review, then **n** to cancel.
2. Press **v**. You are now looking at the same relocation and draft in the
   workspace. Current observation, saved policy, draft and expected outcome sit
   beside the directory list. **l** focuses choices, **j/k** changes the focused
   option, and **Space** selects it. **h** returns to the list.
3. Press **3**, then **n** again. Compare the navigation and where focus returns.
   Press **v** to inspect the same decision through the existing Status/Plan tabs.

That is the comparison. **y** on review simulates execution if you want to go
further; it is not necessary to judge the navigation.

The original tabs retain their current clipping defects. **i** is a shared
prototype-only full-detail reader for complete paths, choices and results.
In the workspace, **[/]** scrolls the detail pane. **?** explains the experiment.
**q** quits when idle. Close help/inspection with **n** or Escape.

## Optional scenarios

Press **!** to open a labelled scenario menu; choosing one resets the in-memory
session. It offers returning-user success, preflight rejection, partial execution,
missing default configuration and missing explicit configuration.

- Execution uses the actual planner's action list. At a review, only **y**
  confirms; Enter does not. During progress, manual inspection lasts until the
  next action transition, using the existing app's following behavior.
- Results remain in the existing Apply view. **i** shows explicit completed,
  failed and not-run mutation counts plus complete action paths/messages.
  Preflight rejects before any simulated mutation. Partial execution stops
  after four completed mutating actions; the remaining count comes from the
  real plan, not a hardcoded ten-action fixture.
- **1** returns from a result to observations; **3** revisits it. **r** explicitly
  replans and clears results. Success gives a no-change plan. After failure,
  the fixture remains blocked; reset via **!** to start another demonstration.
- **4** opens the minimal setup demonstration. Select candidates with **j/k**
  and Space; **u/f** simulate list availability/refresh. Only **s**, then **y**
  saves the explicit selections, in memory. **n** cancels. Those saved selections
  then feed the real planner and actual views.
- Setup allows editing source/target roots and the optional shared-list path.
  It demonstrates missing-config creation only; it does not replace an existing
  configured set. Refresh never changes existing relocations or draft selections.
  Candidate format, sizing and input validation are still simulation details.

## What is reused, and what is isolated

The launcher compiles the **current repository sources** into a temporary
directory, with two prototype replacements:

- `anchored/HomeLightApp.java`: a copy of the existing application controller,
  with comparison controls and workspace routing added. Existing baseline
  handlers remain intact.
- `anchored/HomeLightSession.java`: an entirely in-memory session that constructs
  real observations and calls the real pure reconciliation planner. It replaces
  configuration loading, inspection, saving and execution.

`StatusView.java`, `PlanView.java`, `ApplyView.java`, the decision vocabulary,
model types, summaries, sorting and planner are compiled directly from production
source, unchanged. `anchored/WorkspaceView.java` is the new layout.

Neither the production session nor its execution path is used. The prototype
session never loads configuration, inspects paths or calls an executor; it only
constructs execution-result data. Source and target paths need not exist.
The simulation updates observations after completed actions, including partial
execution. This demonstrates UI behavior, not real execution safeguards.

A one-line comparison footer reduces the available view height by one row.
The shared full-detail reader is an inspection aid, not a production proposal.
The controller copy is throwaway and can diverge if production changes later.
Temporary compilation directories are retained under the system temporary
directory; no production build outputs or tracked production files are changed.

## Verification and feedback

Run the agent-operated terminal walkthrough from the repository root:

```sh
python3 docs/research/session-c-prototype/anchored/walkthrough.py
```

`anchored/capture.txt` records the actual TamboUI views at 80×24 and 120×30,
live resizing, all four real conflict choices, draft retention across variants,
review/cancel, manual action inspection, results, replanning and simulated setup.
Its bounded ANSI text decoder can leave Unicode alignment artifacts; these
captures are not screenshots or human usability measurements.

The primary comparison has the actual baseline's four-key route
(`2 l Space 3`) versus workspace's three-key route (`l Space 3`), starting
from the selected conflict. Both lead to the same reviewed actions. The workspace
retains its selected item and pane on cancel. The user subsequently chose B,
subject to the layout and keyboard requirements recorded above.

The older `WorkflowPrototype.java`, top-level walkthrough and captures are
retained as superseded experiment material. Their instructions and measurements
do not describe this rebuilt comparison.

Baseline commit: `99bb49d`. Production diff remains empty. Existing edits are
preserved. No commit, push or issue update was made by the prototype session. The prototype
skill is applied to an existing interface; its browser and commit steps are
overridden by the requested TUI-only, local-only scope.

The human decision gate is complete; see the accepted handoff above.

### Visual refinement

Before acceptance, B received a closer-to-final visual walkthrough.
B now uses compact single-line badge/name rows, the existing Unicode radio
choices `(●)` / `(○)`, cyan focus and section headings, green chosen indicators,
gray descriptions, and independent destructive warnings. The detail pane scrolls
to keep the focused choice and its consequence readable. Tall terminals use the
available height instead of leaving a large blank area beneath the workspace.

No decision was made to show all in-sync items. B continues to call the same
`PlanView.visibleItems` filter and use the same `c` toggle as A. The missing
hidden-count indicator has been added beneath the list. In the six-item fixture,
five rows show initially and one in-sync relocation is hidden. `c` reveals it.
The baseline's fallback when no active items exist is unchanged.

Visual walkthrough captures: `anchored/styling-capture.txt` covers all choices,
the in-sync toggle and resize; `anchored/styling-final.txt` checks the final
styling at 80×24, 120×30 and 200×50, including review/cancel focus retention.
