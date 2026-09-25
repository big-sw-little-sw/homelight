# D4 / #31 coordinator handoff: explicit manual first-run creation

## Final local coordinator acceptance, 2026-09-23

D4 is accepted locally. The user's earlier manual walkthrough satisfies the UX
gate. All three implementation findings are resolved: discard resets setup,
relative source and target paths are guarded, and archive roots require absolute
paths. Independent Java 25 clean verification passed **165 tests**, zero failures,
errors or skips; `git diff --check` passed. The coordinator inspected the updated
PTY driver and recorded results but did not rerun those journeys.

Standards review found no new actionable issues; the prior numeric-field
suggestion remains deferred. Spec review noted one non-blocking coverage gap:
add valid-source/invalid-target cases for blank, root-equal and escaping targets.
The current tests fail on invalid sources first; both fields use the same checked
conversion helper, so this is not an observed implementation defect.

The maintained first-run contract is now in [tui-design.md](../tui-design.md).
Earlier pending-acceptance instructions below are historical. GitHub publication
and issue closure remain pending approval; no tracker changes, commit or push
were made. Before #32 implementation, complete/review the #21 candidate-list
contract and check #7 discovery dependencies.

## Coordinator review, 2026-09-23

The user manually verified the final UX; its walkthrough gate is satisfied.
Technical acceptance remains pending the three corrections below. Independently
reran Java 25 clean tests: **163 passed**, zero failures/errors/skips, and
`git diff --check` passed. Inspected the updated PTY driver and evidence; the
coordinator did not independently rerun PTY journeys.

Required follow-up in `HomeLightApp`:

1. Confirmed discard currently only hides setup. Clear its draft, confirmation,
   selection and validation state so reopening with `i` starts fresh. Preserve
   the draft when the user cancels the discard dialog. Test both paths.
2. Reject empty row paths and normalized paths equal to or outside their chosen
   storage root, before validation succeeds or publication starts. An untouched
   row currently resolves to the entire source/target roots, including the home
   directory by default; `..` can escape the roots. Test blank source/target,
   root-equal and escaping entries, plus valid nested entries. Failure must keep
   the editable draft and leave configuration and relocation paths unchanged.
3. Reject relative archive-root input rather than resolving it against the
   process working directory. This enforces the form's existing absolute-path
   guidance. Cover validation and save, including a valid absolute archive root.

Standards review also noted positional coupling in numeric `setupField` values
across rendering, help and editing. Named fields are a non-blocking improvement,
not authorization for a generic form framework or broader redesign.

Hard-link publication provides create-if-absent behavior; save does not execute
relocations. Preserve those guarantees and the accepted layout. Add regressions,
rerun the full Java 25 suite and targeted PTY journeys at both supported sizes,
update #31/evidence, then stop for coordinator review. Keep #31 open and postpone
promotion into `tui-design.md` until these corrections are accepted. No #32,
commit or push.

## Coordinator follow-up implementation, 2026-09-23

- Confirming discard now resets the complete setup draft before returning to the
  missing-configuration screen: roots, rows, mode, selection, confirmation and
  validation message. Cancelling the dialog only closes it and preserves the
  draft. Reopening with `i` starts a fresh Locations form.
- Form-to-draft conversion rejects blank relative row paths, paths resolving to
  the selected root, and `..` paths escaping it. Valid nested paths continue to
  resolve beneath the selected source and target roots. These checks run for both
  Validate and Save before publisher invocation.
- Archive roots are accepted only when blank or absolute. Relative archive input
  remains in the editable draft and reports an error; it is never resolved from
  the process working directory.
- New `HomeLightAppTest` regressions cover discard cancellation/confirmation and
  reopen, blank/root-equal/escaping source paths, relative archive roots, invalid
  Validate and Save, correction and successful first save. Failed attempts leave
  configuration absent and relocation paths untouched.
- Targeted real-JLine PTY journeys passed at **80×24** and **120×30**, including
  discard → reopen and invalid blank-row correction → successful save, with
  resize in both directions. See [driver](session-d4/pty-check.py) and
  [transcript](session-d4/pty-check.txt).
- Java 25.0.3 `mvn -o clean test` passed **165 tests**, zero failures, errors or
  skips; [full-suite log](session-d4/full-suite.txt). `git diff --check` passed.

Stop for coordinator review. Keep #31 open. Do not publish this follow-up to
GitHub until approval is granted. No #32, commit, push or `tui-design.md` change.

Date: 2026-09-14. Baseline and result are uncommitted. No commit or push was made.

## Final verification update

- Updated the real-JLine PTY driver for the final Locations → Relocations → Row
  details flow. It now covers missing default and explicit configurations, `init`,
  root editing while preserving rows, row addition/removal, complete row-detail
  path fields, validation reset after edits, discard confirmation/cancellation,
  and save returning to Workspace without executing relocation.
- The driver passed all three journeys at **80×24** and **120×30**, resizing from
  each size to the other and back. It also verified clean exit, alternate-screen
  restoration, cursor restoration and supported termios restoration. See
  [PTY driver](session-d4/pty-check.py) and [transcript](session-d4/pty-check.txt).
- Java 25.0.3 `mvn -o clean test` passed **163 tests**, zero failures, errors or
  skips. See [clean-suite log](session-d4/full-suite.txt). `git diff --check`
  passed.
- PTY verification uses terminal arrow navigation for Location root selection. Setup
  viewport focus anchors now use rendered-line positions rather than raw form-field
  indexes.

## Implemented scope

- Missing default and explicitly supplied paths offer `i: Manual setup`; `homelight init`
  directly opens the same setup for a missing path. Existing or unreadable configurations
  remain errors and are never offered as replacement targets.
- Setup keeps storage roots, relative relocation rows and optional per-row policies in
  memory. It has explicit Locations, Relocations and Row details modes. `a` adds a row,
  `d` removes the selected draft row, `e` revisits roots without dropping rows, and `q`
  confirms draft discard. Validate and save remain explicit. Saving only writes
  configuration and reloads the established workspace; it never requests review or
  executes relocation actions.
- `ConfigurationPublisher` validates empty drafts, source/target intersections, pairwise
  intersections, duplicate targets and archive policy requirements. It writes a sibling
  temporary file then atomically creates a hard link at the destination. That create fails
  if any file appeared concurrently; there is no replace or non-atomic fallback.
- The returning-user workspace, review confirmation (`y` only), Escape navigation,
  retained results and JSON adapters are unchanged.

## Evidence

- Final Java 25.0.3 `mvn -o clean test` passed **165 tests**, zero failures, errors or
  skips; the complete log is [here](session-d4/full-suite.txt).
- [ConfigurationPublisherTest](../../src/test/java/io/github/bigswlittlesw/homelight/config/ConfigurationPublisherTest.java)
  uses temporary filesystems for cancellation, validation/overlap, write failure, malformed
  existing configuration, concurrent creation and successful save/reload with no apply.
- The final [PTY first-run driver](session-d4/pty-check.py) passed the table journeys
  at 80×24 and 120×30, including resize in both directions; its transcript is
  [here](session-d4/pty-check.txt).
- Current focused verification: `mvn -o -Dtest=HomeLightAppTest,ConfigurationPublisherTest test`
  passed **21 tests**, zero failures, errors or skips. `git diff --check` passed.

## Limitations and next gate

This is intentionally a creation-only form. Existing configuration editing,
discovery/shared candidate lists, ownership/managed-link features, executor changes and
JSON redesign remain out of scope. Hard-link publication requires filesystem support; a
filesystem that does not provide it fails safely rather than weakening no-overwrite
semantics.

Stop for coordinator review and a human walkthrough of the first-run form. Do not start
#32, commit or push.

## First-run design-language proposal (historical)

The original field-by-field form was replaced after UX review with an editable,
expandable relocation table. This proposal informed the accepted contract now
maintained in `tui-design.md`; consult that document for current guidance.

- A missing configuration must state the next action prominently in the empty state:
  `No configuration yet. Press i to create one manually, or run homelight init.` The
  footer remains a shortcut reminder, not the only discovery path.
- Setup begins with **Storage locations**: an editable source root defaulting to `$HOME`
  and one required target root. Rows use paths relative to those roots, rather than asking
  for repeated absolute paths.
- **Relocations** is a compact table. `a` always opens a new expanded row; it is never
  conditional on focus being a policy control. A source-relative entry initially copies to
  the target-relative entry. Enter expands a selected row; Escape returns to the table.
- An expanded row carries the source/target-relative paths, the three state-specific
  policy controls and optional archive root. Focused help explains the field’s consequence
  without rendering all help text at once. A collapsed row says `Default (prompt)` when it
  has no policy deviation; otherwise it lists only its changed policies.
- “Default” means the existing per-relocation optional-policy semantics: omitted policy
  values resolve to `Prompt`. This does not introduce a persisted configuration-wide
  defaults layer or policy inheritance.
- Setup uses explicit **Storage locations**, **Relocations**, and **Row details** modes.
  `e: Edit locations` remains visible from the table, so root corrections preserve relative
  rows. Every draft edit resets validation to `not run`.
- The table has Source, Target, and Policies headers. Compact, ellipsized cells keep its
  columns usable at 80 columns; row details retain complete paths. `q` asks before it
  discards an unsaved draft, and `d` removes only the selected draft row.
- The central candidate-list field is deliberately absent. Its format and setup integration
  remain #21/#32 work.

The rework has focused coverage in `HomeLightAppTest`: roots plus one relative row
save as the expected resolved paths, preserve omitted/default policies, and do not
relocate. Updated table PTY evidence is linked above; the user completed the
manual walkthrough before the bounded corrections.
