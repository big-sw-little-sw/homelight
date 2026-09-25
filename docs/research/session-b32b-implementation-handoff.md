# #32b candidate setup integration handoff

Date: 2026-09-24. **#32b accepted locally, including the destructive-policy
correction.** Prior browser UX acceptance stands; the user
reported: “manual test of ux is ok.” No commit, push or GitHub publication. Existing uncommitted D4,
B01–B03, #7b, #32a and unrelated work was preserved. The maintained TUI design
document's §7 was reconciled by the coordinator. This correction implements its
existing destructive-disclosure requirement without changing execution semantics.

## Final local coordinator acceptance, 2026-09-24

The previous finding is resolved. Standards and specification reviews found no
remaining actionable findings in this bounded correction. Inspected setup/table
and configured-candidate labels, warning placement, the rendered regression and
its exact planner-action assertions. Planner/executor diffs remain empty.

Independent Temurin Java 25 `mvn -o -q clean verify`: **234 tests passed**, zero
failures, errors or skips. `git diff --check` passed. Inspected the implementation's
recorded 80×24/120×30 PTY captures, including the complete warning and resize;
the coordinator did not rerun those PTY journeys. The user's prior UX acceptance
stands. Historical pending-review instructions below are superseded.

The maintained TUI contract and coordination documents now reflect local
acceptance. No production changes, GitHub publication, commit or push by this
review. Before another feature slice, reconcile tracker status and checkpoint
the accepted work under separate user authorization.

## Destructive-policy wording correction (accepted)

Bounded changes:

- `SetupView.both()` now says **Discard both**, including the Relocations table
  summary. The focused consequence is: **Permanently delete both source and target
  directory trees. Create an empty target directory and link the source to it.**
- That full consequence is placed directly below the focused policy in the
  existing wrapped, scrollable reader, highlighted in yellow. It is not squeezed
  into the fixed two-line footer. The footer separately explains that Save only
  writes configuration and Apply requires review. No shared viewport or new
  presentation framework was introduced.
- Candidate inspection's saved DISCARD policy also says Discard both, rather
  than the ambiguous `discard`. Other candidate-policy labels are unchanged.
  Audited `WorkspaceView` and `DecisionChoice`: their existing copy already
  discloses both deletions and empty-target/source-link creation; no edits needed.
- `ReconciliationPlanner.discardDirectories()` and the executor are unchanged.
  Candidate-browser controls, confirmed-missing Add, policies, draft editing,
  discovery and lifecycle behavior are unchanged.

Rendered regressions exercise the selected label, table summary and complete
consequence at 80×24 and 120×30, including resizing down and back. After saving,
the test asserts the existing planner's exact actions: delete source, delete
target, ensure target parent, create target, ensure source parent, create source
link to target. Both original payloads remain intact; no Apply is performed.
The existing inspection-only candidate regression now checks the saved label.

Verification, explicitly using Temurin **25.0.3**:

- Before the fix, `mvn -o '-Dtest=CandidateSetupTest#discardBothCopyMatchesThePlannedDeletionsAndEmptyTargetAtBothSizes+configuredRowsAreInspectionOnlyAndTextIsEscaped' test`
  reproduced **2 failures / 2 tests**, both wording assertions
  ([red evidence](session-b32b/discard-wording-red.txt)).
- `mvn -o -Dtest=CandidateSetupTest,HomeLightAppTest,SetupDraftTest test`:
  **44 tests passed**, zero failures/errors/skips
  ([focused log](session-b32b/discard-wording-focused.txt)).
- `mvn -o clean verify`: **234 tests passed**, zero failures/errors/skips
  ([clean-suite log](session-b32b/discard-wording-full-suite.txt)).
- `python3 docs/research/session-b32b/pty-check.py --discard-policy`:
  **2 real production-CLI/JLine journeys passed**, starting at 80×24 and 120×30,
  resizing both ways, checking full warning text, table summary, long-path
  scrolling, save without Apply and terminal restoration
  ([captures](session-b32b/discard-wording-pty.txt)).
- `git diff --check` passed. Searches found neither incorrect phrase in production
  TUI code. Planner/executor diffs remain empty.

Rendering audit: the warning is complete in two wrapped lines at 80×24 and one
line at 120×30; the selected label stays visible across resize. The scrollbar
appears with the long fixture configuration path and disappears when content fits.
The footer retains complete policy keys and one scroll hint. The table shows
Discard both without clipping. The PTY wait was tightened to capture the complete
Workspace frame after Save rather than just its arriving header; both journeys
were rerun successfully. Existing PTY font/terminal-compatibility limitations
still apply. This evidence does not constitute coordinator acceptance.

Stop here for coordinator review of this correction. No unrelated feature work,
GitHub publication, commit or push was performed.

## Coordinator review, 2026-09-24

Independent Java 25 `mvn -o -q clean verify` passed all **233 tests**, with zero
failures, errors or skips. Reviewed setup/browser integration, shared Add
eligibility, generation invalidation and save/discard/launcher disposal. The
implementation's PTY evidence and the user's manual acceptance remain distinct
from this source/test review; the coordinator did not rerun PTY journeys.

**Acceptance blocker:** `SetupView.both()` labels DISCARD “Discard target”, and
`bothConsequence()` says “delete target contents and relocate source.” The
unchanged `ReconciliationPlanner.discardDirectories()` deletes **both** source
and target trees, creates an empty target and links the source. This copy hides
source-data deletion. Correct the label/table summary and focused explanation;
add rendered regressions and check the consequence at 80×24 and 120×30. Preserve
planner/executor semantics. This is one underlying finding on both review axes:
divergent shared policy meaning (Standards) and incorrect destructive disclosure
(Spec). No other actionable findings were confirmed in this bounded review.

The accepted browser contract is now reconciled into `docs/tui-design.md` §7.
Confirmed-missing Add explicitly supersedes #32a's directory-only restriction;
unknown, blocked and prior-generation evidence remains ineligible. Human UX
acceptance stands; technical acceptance awaits the copy correction and evidence.
No production edits, GitHub publication, commit or push by this review.

## Implementation handoff (historical review request)

Review #32b for integration. The user's manual UX acceptance follows the final
quiet-list revision, including direct Add and pre-creation configuration. No
specific terminal/emulator or manual scenario inventory was supplied; automated
coverage is recorded separately below.

Latest evidence is the 233-test Java 25 clean suite in
`session-b32b/quiet-list-full-suite.txt`, ten full terminal journeys in
`session-b32b/missing-pty.txt`, and the two affected journeys rerun after the final
wording change in `session-b32b/quiet-list-pty.txt`. `git diff --check` was rechecked
at handoff and passed. Older totals/captures below are implementation history.

Coordinator follow-up: review code/lifecycle integration, reconcile the maintained
TUI contract with the accepted screen/key map and missing-path eligibility, and
record the supersession of #32a's directory-only Add restriction. Do not include
unrelated dirty/untracked work in the slice. This handoff does not authorize issue
publication, a commit or push, execution, or additional feature scope.

Read #32/#21 bodies, labels and comments via `gh`, `docs/tui-design.md`, the
session-B specification and nested fixtures, and the accepted D4/#7b/#32a
handoffs. The current user authorization supersedes historical pending gates in
the issue/spec text. The nested schema, metadata-only discovery and accepted
SetupDraft interface were initially unchanged; the user-authorized missing-path
revision below extends candidate Add eligibility without changing discovery.

## Missing-path addition, authorized during the user walkthrough

The user requested configuration before apps create their directories. This
supersedes #32a's directory-only Add restriction: an unselected path currently
observed as either a directory or missing may be added. Pending, unknown,
inaccessible, links, files, blocked paths and earlier-generation observations
remain ineligible. `SetupDraft.canAdd(entry)` is shared by Add and UI action
presentation; full-draft validation still rejects duplicate/overlapping mappings
before mutation. Configured rows remain inspection-only.

Missing entries offer the normal empty checkbox and Space/a Add. Following the
user's next wording request, they have no routine metadata label in the list;
details use “Not created yet”. Earlier-observation and blocking-state warnings
remain visible. Details explain the existing execution behavior:
if source and target are absent, Apply creates the target directory and source
link; if only the target exists, the row's policy defaults to Prompt, with Adopt
target available explicitly in Row details. Discovery does not inspect targets,
infer policies or promise execution success. Save only writes configuration;
Apply re-observes paths. No executor or discovery changes were needed.

Regression coverage includes all observation kinds and old generations, missing
parent/child overlap rejection, direct missing-path Add, edit/policy/refresh,
save/reload and proof that source/target remain absent. The PTY driver now has a
missing-path journey at both terminal sizes with resize in both directions.
The behavior and final UX are user-accepted; coordinator review remains open.
Earlier evidence and contract descriptions
below are historical where this subsection supersedes them.

Verification: Temurin 25.0.3 `mvn -o clean verify` passed **233 tests**, zero
failures/errors/skips ([log](session-b32b/missing-full-suite.txt)). All **ten**
real-JLine PTY journeys passed, including missing-path Add at 80×24 and 120×30
and resize in both directions ([captures](session-b32b/missing-pty.txt)). Inspected
both missing-path list/detail captures: checkbox/action eligibility, stable focus,
wrapped explanation, explicit policy editing and save-only behavior are visible.
The new PTY assertion initially expected an unquoted policy scalar; corrected
the harness to match the existing YAML writer, then reran all journeys successfully.
No application change was needed for that assertion. `git diff --check` and
smoke-script syntax passed. The safe walkthrough now includes missing-path Add
and checking that Save does not create its source or target.

Follow-up wording verification: the Java 25 clean suite still passes all 233 tests
([log](session-b32b/quiet-list-full-suite.txt)). Reran and inspected the two
missing-path PTY journeys at 80×24 and 120×30, including resize
([captures](session-b32b/quiet-list-pty.txt)). Neither Missing nor Not created yet
appears in the list; details show Not created yet. Blocking-state labels remain,
and direct Add/edit/refresh/save behavior is unchanged. This supersedes the labels
in earlier captures, without changing discovery or execution semantics.

## Candidate-list revision following user feedback

The user requested a quieter, more polished list and direct marking for addition.
The browser now uses aligned checklist rows: `[ ]` for eligible directories,
`[x]` for draft members, `[=]` plus Configured for saved rows, and a dash plus the
observed exception for unavailable entries. App headings use disclosure triangles
and bold color; focused rows retain the cyan pointer, and added rows are green.
Routine Directory/Not added/Consider wording and the expansion legend are omitted.
Full metadata, advice, omissions, reasons and attribution remain in Enter inspection;
mixed advice, usually-unnecessary advice and metadata exceptions retain short list notes.
The summary shows total candidates and draft membership, with source status shown
when pending, stale or unavailable. Current-source details remain under `i`.

Space or `a` adds the focused directory without opening details. `e` edits a draft
row directly from the list. App headings, configured rows and ineligible observations
cannot be added; repeated Add on a draft member cannot duplicate or remove it.
Overlap rejection stays in the list with a visible notice and full error on Enter.
Catalog order remains stable when membership changes. Candidate navigation now
scrolls only enough to keep focus visible, rather than moving each focused row
to the top; other screens keep their existing viewport behavior.

The new lifecycle regression covers direct Add, heading/disabled-row no-ops,
repeated Add, stable ordering, direct Edit and overlap rejection retaining prior
choices. Updated PTY journeys exercise Space Add and direct Edit at both sizes,
plus the existing refresh/discard/save/failure coverage. Temurin 25.0.3 clean
verification passed **230 tests**, with zero failures/errors/skips; all eight
PTY journeys passed at 80×24 and 120×30, including resize. Inspected the final
checklist captures for aligned paths, distinct markers, stable list positioning
and complete contextual help. `git diff --check` and smoke-script syntax passed.
Current evidence:
[Java 25 clean suite](session-b32b/checklist-full-suite.txt) and
[checklist PTY captures](session-b32b/checklist-pty.txt). Earlier captures below
document the first implementation. Human UX acceptance was subsequently received
after the final quiet-list revision; the maintained design contract remains unchanged.

## Screen and key map

| Existing journey | #32b extension |
| --- | --- |
| Storage locations | Third optional shared-list field; blank means bundled only. Absolute paths and `~/...` use DiscoverySetting. No read occurs until first Browse. |
| Relocations | Existing manual `a`, Enter details, `d`, `e`, Validate and Save remain. `b: Browse candidates` is in contextual help. |
| Browse candidates, subordinate to Relocations | Arrows/j/k focus; Space/a adds an eligible directory in place; e edits an existing draft row. Enter expands an app or inspects a directory. `u` reveals/hides the counted usually-unnecessary entries; `r` refreshes; `i` shows full source diagnostics. |
| Candidate details | Per-directory `a: Add to draft` only for eligible observations; `e: Edit draft row` for draft members; configured rows inspect only. Add retains details and updates membership. Edit opens the existing Row details. |
| Back/discard | Escape: candidate details/sources → browser → table → locations → cancel setup. `q` confirms discard in table/browser; Escape cancels that dialog. Confirmed discard closes discovery and drops setup state. |

Returning with `b` retains the browser's last focused path and inspection context;
it does not reread sources unless locations changed or Refresh is requested.

One representative production-CLI browse → inspect → add → edit → save flow was
built and inspected at 80×24 **before** completing edge coverage. Its initial
capture is [representative-80x24.txt](session-b32b/representative-80x24.txt).
It records an intermediate presentation; final rendering is in the broader
[PTY transcript](session-b32b/pty-check.txt).

## Implementation and lifecycle

- `HomeLightApp` owns a nullable, session-scoped `SetupView`. Setup presentation
  moved out of the workspace controller; the accepted journey was extended rather
  than duplicated. `SetupDraft` holds row values, membership, validation projection
  and history. Incomplete location strings and the active archive text are UI data.
- One `CandidateDiscovery` instance is created lazily per setup. Snapshot acceptance
  and every draft edit run on the UI thread. Workers have no callbacks into setup.
  Location keystrokes immediately cancel/invalidate old discovery, including edits
  away from and back to the same values. Valid explicit location changes request
  the new generation when discovery has started.
- Refresh changes evidence only. Selected rows, targets, policies and historical
  occurrences survive removal and changed advice. Save calls `draft.validate()`
  again, then the existing atomic create-without-overwrite publisher. Failed saves
  retain the editable view. Success closes discovery and reloads Workspace without
  requesting review or executing relocations. Launcher cleanup closes discovery
  on abnormal terminal exit as well.
- `CandidateBrowser` uses normalized source identity for focus and deduplication.
  It does not assign selection to app headings. Primary grouping uses the first
  available app association in bundled-before-shared occurrence order; remaining
  associations and every attributed reason remain in details. Ungrouped rows use
  Other directories. The list shows distinct membership counts and a short path;
  details retain the complete path. A single path index avoids rebuilding the full
  joined draft separately for every displayed candidate.
- App expansion, focused identity, advice visibility and membership are independent.
  Only unselected candidates whose definitions all recommend usually-unnecessary
  from current source snapshots are hidden by the advice filter. Omitted, conflicting,
  retained/stale advice and selected/configured entries stay visible. Selected rows
  also remain visible when their app is collapsed.
- Metadata kind/request age, source freshness, attributed advice and historical
  occurrences are separate. Every size says not estimated; ownership says not
  evaluated. All external text escapes terminal controls. Advice changes never
  set policies, select rows or affect Add validation.
- Overlap rejection reports the conflicting paths and preserves prior rows. Manual
  matches offer Edit, not duplicate Add. Configured matches show saved target and
  policies without Add/Edit. That read-only configured seam is tested; no existing
  configuration editor or entry point was introduced.

## Verification and evidence

Java 25.0.3, macOS arm64. `mvn -o clean verify`: **229 tests passed**, zero failures,
errors or skips. [Full clean-suite log](session-b32b/full-suite.txt).
The initial unqualified Maven runs used Homebrew Java 26. The final suite explicitly
sets `JAVA_HOME` and `PATH` to Temurin 25; the final PTY driver obtains its Java
executable from that clean suite's report. No project JDK settings were changed.
`git diff --check` and `bash -n scripts/setup-smoke-fixture.sh` passed; the smoke
script was run and its temporary fixture instructions inspected.

Ten new `CandidateSetupTest` cases cover:

- Add/edit/refresh/removal with target, policy and history preservation, save/reload
  and no execution; normalized manual matches and inspection-only configured rows.
- App expansion without membership changes, opposing and omitted advice, counted
  reveal/collapse and selected-row visibility; parent/child overlap rejection.
- Missing/malformed input with usable bundled/manual setup; blocked shared input
  through manual editing, concurrent-file save failure and successful save.
- Root/location invalidation, cancelled discard, confirmed discard/reopen, completion
  after discard, active text surviving background arrival and stable path focus.
- Explicit root rebasing while preserving relative rows, target edits and policies;
  control-character escaping and disposal of discovery on save/discard.

The [real-JLine PTY driver](session-b32b/pty-check.py) covers eight complete journeys:
production bundled-only, adopted bundled/shared fixtures, blocked-reader root changes
and discard/reopen, and save while blocked, each at 80×24 and 120×30. Shared journeys
resize in both directions. Captures include mixed/omitted advice, app and ungrouped
rows, reveal/collapse, selected visibility, Add/edit/refresh, removed candidates and
historical attribution, overlap rejection, complete long paths/reasons, malformed
source diagnostics, failed save, and Workspace after save without execution.
Each journey checks clean exit and terminal restoration. The blocked-reader
journeys use the test-only discovery seam, not an actual unavailable NFS mount.

The production CLI is used for the representative flows. The additional test main
reuses the same HomeLightApp, SetupView, CandidateDiscovery, JLine and terminal
renderer; it substitutes only bundled input/shared-reader seams already accepted
in #7b. There is no production fault-injection flag. Filesystem fixtures and config
publication are real temporary paths. No real home contents or NFS were inspected.

## Rendering audit and decisions

Inspected the actual captured terminal cells, not just model assertions. Findings
fixed within this slice:

- JLine reports Ctrl-U as a control-modified `u`, unlike the old synthetic fixture.
  Both forms now clear fields. An archive edit buffer preserves trailing separators
  while typing instead of losing them through repeated Path normalization.
- Duplicate Add/Edit/source-help text was removed. Keys live in contextual help;
  membership and consequences live in content. Text fields do not advertise policy
  cycling/removal, and ineligible/configured rows do not advertise Add/Edit.
- Very long browse paths made rows unnecessarily tall. Compact labels preserve a
  distinguishing prefix/suffix; wrapped, scrollable details retain full paths and
  reasons. Footers and policy consequences were shortened to fit 80 columns.
- Advice absent from one definition is labelled as omitted, distinct from opposing
  advice. Source-status labels are separate from the metadata observation generation.
  Failure diagnostics now lead with plain recovery guidance and retain full detail.
- A PTY-driver resize bug reset its screen when dimensions had not changed; the
  driver now skips that no-op. Standalone Escape waits out JLine's escape-sequence
  disambiguation window. Neither correction changes application semantics.

Final captures show visible focus pointers and borders, separate membership labels,
overflow scrollbars, complete reader content and contextual help. There are no
numeric viewport line counters or raw enum names in the candidate presentation.
Input-file line/column diagnostics remain available to locate YAML errors.

## Design-contract reconciliation for coordinator review

Human acceptance is now recorded above. Reconcile §7 of `docs/tui-design.md` with the optional
Locations field and subordinate browser/key map above. Remove the historical
statement that the shared-list field is absent. Add these bounded presentation rules:

1. Browse never replaces the manual table or implies membership. Expand/collapse
   and focus do not select. Add is explicit and per directory; an existing draft
   opens its usual editor, while configured entries remain inspection-only.
2. Show each normalized path once, with all associations/reasons in details.
   Membership counts use unique paths. Use current-source all-definitions advice
   filtering with a counted reveal control; retain selected entries visibly.
3. Keep metadata, recommendations, current/retained source attribution and historical
   attribution separate. Not estimated and not evaluated remain explicit.
4. Background evidence must not change pane, field text, draft choices or focused
   path. A removed focused path remains inspectable until navigation changes focus.
   Diagnostics are an inspectable reader; source failure never disables manual work.
5. Keep the accepted Escape/discard/create-only save guarantees and disposal rules.
6. Allow current directory and confirmed-missing observations to be added. Keep
   routine existence labels out of the list; details say Not created yet for absent
   paths and explain future creation. Earlier/unknown/blocked evidence remains
   distinct; no target probes or inferred policies are introduced.

These are reconciliation notes, not a competing maintained design contract.
Human acceptance comes from the user's manual test, not tests or the agent audit.

## Limitations and review boundary

No sizing, ownership implementation, existing-config editor, executor/JDK change,
watcher or automatic reread. Metadata inspection remains best-effort, not an atomic
containment/safety proof. Stale source attribution may coexist with current metadata;
Add eligibility follows the accepted observation-generation rule. Hard-link
publication still requires filesystem support and fails safely when unsupported.

PTY captures are rendered terminal cells, not screenshots from a specific terminal
emulator. The user's manual UX acceptance is recorded above; no broader
terminal-compatibility claim is made. The inherited harness checks supported macOS termios state; JLine
normalizes speed fields and unused control slots. No real-NFS behavior is claimed.

Safe walkthrough: [session-b32b/smoke.md](session-b32b/smoke.md), supported by the
updated `scripts/setup-smoke-fixture.sh`. Stop here for coordinator review.
No tracker state changes, commit or push are authorized.
