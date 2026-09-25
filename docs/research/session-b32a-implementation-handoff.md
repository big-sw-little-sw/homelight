# #32a draft joining and shared-list persistence handoff

Subsequent accepted user decision, 2026-09-24: #32b extends Add eligibility to
current-generation confirmed-missing paths as well as directories. The original
directory-only requirement below is historical. All other validation, generation
and no-execution guarantees remain. See [the #32b handoff](session-b32b-implementation-handoff.md)
for implementation and coordinator disposition.

## Local coordinator acceptance, 2026-09-24

#32a is accepted locally, including the row-occurrence correction. Independent
standards and specification reviews confirm the previous finding is resolved
with no new actionable findings in the bounded correction. Inspected the private
occurrence model and regressions for equal values, repeated object references,
independent histories after source removal, edits/removal and immutable snapshots.

Independent Java 25 `mvn -o -q clean verify`: **219 tests passed**, zero failures,
errors or skips; `git diff --check` passed. No UI walkthrough is claimed for this
presentation-neutral slice. Previous pending-review instructions below are
historical for #32a.

Next proposed slice: #32b candidate-browser integration into the accepted setup
journey, requiring separate authorization and eventual human UX acceptance.
Preserve metadata-only discovery, explicit draft membership, create-only save,
no-overwrite publication and no execution on save. No new production edits,
GitHub publication, commit or push by this review; #32 as a whole is incomplete.

## Row-occurrence correction, 2026-09-24

The coordinator finding below is fixed locally and awaits review. `SetupDraft`
now stores each row value and its immutable history together in a private
`RowOccurrence` list entry. Append creates a new occurrence even for the same
`Row` object; edit retains only that occurrence's history, and removal deletes
only that list entry. Refresh updates each occurrence independently. No public
interface, validation, persistence or discovery-worker behavior changed.

Three regression tests in `SetupDraftTest` cover equal-valued distinct objects
and the same object appended twice, editing/removing either duplicate, appending
an equal row after candidate removal without inheriting earlier history, and
editing either of two rows with distinct histories into the other's exact object.
Successful empty-source refreshes remove current attribution before the edits,
then refresh again to verify history cannot be reconstructed to mask the bug.
Duplicate validation and rejected Save remain asserted; previously returned
entry snapshots remain unchanged.

Verification on Temurin OpenJDK 25.0.3:

- `mvn -o -q -Dtest=SetupDraftTest,DiscoverySettingTest,ConfigurationPublisherTest,ConfigurationLoaderTest,HomeLightAppTest,CandidateDiscoveryTest,CandidateCatalogTest test`:
  **72 tests passed**, zero failures/errors/skips.
- `mvn -o clean verify`: **219 tests passed**, zero failures/errors/skips,
  BUILD SUCCESS. Existing build notices are unchanged.
- `git diff --check` passed. Inspected append, edit, remove, refresh, explicit Add,
  view snapshots and validation projection for occurrence-local history.

Changed only `SetupDraft.java`, `SetupDraftTest.java`, this handoff and local
next-sessions/fixture evidence. Existing uncommitted work is preserved. No #32b,
GitHub publication, commit or push. Stop for coordinator review; the review below
records the original finding, not acceptance of this correction.

## Coordinator review, 2026-09-23

Independent Java 25 `mvn -o -q clean verify` passed **216 tests**, zero failures,
errors or skips; `git diff --check` passed. Technical acceptance remains pending
one bounded row-identity correction before #32b integration.

`SetupDraft` stores history in `Map<Row, ...>`, but `Row` is value-equal and the
model deliberately retains duplicate manual rows while editing. Editing or
removing one equal-valued occurrence removes the other occurrence's history;
editing into another row's value can overwrite its history. Preserve provenance
per row occurrence, rather than per value or Java object identity. Appending the
same immutable Row object twice must still create two independent occurrences.

Keep the fix local to draft state. Add regressions for equal-valued rows and the
same Row object appended twice, editing/removing either occurrence, and editing
one row into another's value. Exercise removal/refresh so historical provenance
cannot be accidentally reconstructed from current discovery and mask the bug.
Retain duplicate-row validation, explicit membership and all save guarantees.
Rerun focused/full Java 25 verification, update local evidence, then stop for
coordinator review. No #32b, GitHub publication, commit or push.

Date: 2026-09-23. Implemented and verified locally, awaiting coordinator review.
Baseline and current HEAD: `fcb343bee5cb1405f32086c7ce7df87a4afe25f9`.
All results are uncommitted. Existing D4, B01–B03, #7b and unrelated changes were
preserved. No GitHub publication, commit or push. Stop before #32b integration.

Read #32/#21 bodies, labels and comments through `gh`, `docs/next-sessions.md`,
the session-B specification and fixture manifest, and the accepted D4, B01–B03
and metadata-only #7b handoffs. Local acceptance and the user's #32a authorization
supersede older pending gates in the issue text. Sizing remains deferred.

## Implementation and integration contract

[SetupDraft](../../src/main/java/io/github/bigswlittlesw/homelight/application/SetupDraft.java)
is a presentation-neutral setup model with immutable row/view snapshots and
explicit edits. It has no terminal, executor, reviewed-plan or publication state.

- Construct it with absolute source/target roots, optional shared location and
  read-only configured relocations. Relative row text remains unchanged through
  refresh and explicit root edits; resolution occurs against the chosen roots.
- `entries()` joins discovery by normalized lexical source identity. Configured
  rows retain their exact relocation values, including targets and policies;
  outside-root entries remain visible. Manual and candidate-origin rows share
  draft membership semantics. New candidates have no draft row. Invalid or
  duplicate manual rows remain visible instead of being silently merged away.
- `append`, `edit` and `remove` manage manual/selected rows. Incomplete form values
  are allowed while editing. `add(source)` requires a directory observed in the
  active generation, mirrors its relative path under the target root and omits
  every policy. It validates the whole proposed configuration before changing
  membership. Rejected Add leaves every previous row intact.
- `refresh(worker)` starts the existing nonblocking discovery request and records
  its generation. `accept(worker.snapshot())` joins evidence only when generation,
  source root and shared location match. Use one `CandidateDiscovery` instance per
  setup session, and call these methods on the same thread as draft edits.
  Root/location edits invalidate the expected generation immediately, including
  an edit away from and back to the same root.
- Current evidence and session-only historical definition occurrences are separate.
  Removed sources/advice do not rewrite rows or policies. Historical provenance
  survives row edits and root changes but never becomes a current observation or
  durable history. Fresh metadata may permit Add despite stale source advice;
  prior-generation metadata cannot permit Add. Manual setup remains available.
- `validate()` resolves all rows and includes configured entries in the existing
  complete-configuration validator. It returns a `ConfigurationDraft` for explicit
  create-only publication. There is no cached validation flag to become stale.
  The publisher validates again. #32b must obtain this draft at Save, retain the
  editable model on failure, and dispose of setup/discovery on save or discard.

[DiscoverySetting](../../src/main/java/io/github/bigswlittlesw/homelight/config/DiscoverySetting.java)
performs lexical conversion only: blank means absent, absolute paths and `~/...`
are accepted, other relative paths, variables, URLs and controls are rejected.
No existence/readability check occurs. Normalized absolute locations round-trip as
`homelight.discovery.shared-list`; an absent setting omits the whole section.

`ConfigurationDraft`, `HomeLightConfiguration`, loader and publisher now carry
the optional location independently of relocations. Existing constructor call
sites remain compatible. `ConfigurationEvaluation.Loaded` preserves the setting
when copying its saved configuration. Loading/evaluation never reads the shared
location. Publication still writes a sibling temporary file and atomically creates
a hard link without replacement or fallback. Save does not execute relocations.

No definitions, app associations, advice, provenance, observations or selection
state are serialized. No production TUI, key, layout, planner, executor, ownership,
JDK or discovery-worker changes. The original setup UI is not wired to this model
yet; that is the separately reviewed #32b slice. Existing-config replacement and
editing remain excluded. A setting-only configuration is still rejected.

## Verification and local evidence

Temurin OpenJDK 25.0.3, macOS arm64:

- Focused: `mvn -o -q -Dtest=SetupDraftTest,DiscoverySettingTest,ConfigurationPublisherTest,ConfigurationLoaderTest,HomeLightAppTest,CandidateDiscoveryTest,CandidateCatalogTest test`
  passed **69 tests**, zero failures/errors/skips.
- Full: `mvn -o clean verify` passed **216 tests**, zero failures/errors/skips,
  BUILD SUCCESS; produced `target/homelight-1.0-SNAPSHOT.jar`.
- `git diff --check` passed. Spot-checked the load/evaluation copy paths, complete
  validation before publication, and unchanged hard-link publication code.
- Existing source/target-versus-release, deprecated TUI API and resource encoding
  notices remain. No unrelated build cleanup.

An initial clean run exposed a race in the new test completion predicate: retained
observations were mistaken for completed refresh metadata. The helper now waits
for the current generation for candidates supplied by current sources. The final
focused and clean results above include that correction. No production discovery
changes were required.

| Acceptance | Evidence |
| --- | --- |
| B07 | `SetupDraftTest`: normalized configured/manual matches, exact configured target/policy preservation, out-of-root visibility, duplicate Add rejection and unchanged rows |
| B08 | Nested initial/refreshed fixtures, edited target/policy, malformed source and successful removal, changed app/advice/state, new unselected paths, historical attribution; saved config bytes and reviewed-plan object identity unchanged |
| B09 | Parent/child Add in both orders, duplicate source, target and nested target, cross intersections, cycle and invalid manual target matrix; complete validation rejects Save and preserves editable rows |
| B12 | `DiscoverySettingTest` and `SetupDraftTest`: only manual/explicitly chosen rows persist, policies omitted, normalized location round-trip, old/blank setting compatibility, missing/malformed/directory locations, failed publication followed by editing and saving, concurrent whole-file winner, no definitions/advice/history in YAML |
| B14 | Root edit re-resolves relative source and target, preserves row objects and history, clears current evidence, rejects prior generation before and after returning to the original root; clearing location preserves rows |
| Failure/no apply | Controlled shared reader remains blocked through manual edit, failed Save and successful Save; fake deadline, unchanged source bytes/link text, no target relocation; existing publisher tests retain malformed-file/no-overwrite protections |
| D4 deferred regression | `HomeLightAppTest.rejectsInvalidTargetsWithValidSourcesUntilCorrected`: blank, root-equal and escaping targets fail Validate/Save with valid source; correction saves expected paths |

Tests use temporary filesystems, the adopted nested YAML fixtures, controlled
readers/latches and a fake monotonic clock. No real home contents or NFS were read.
No PTY walkthrough is claimed for this presentation-neutral slice. #32b owns
actual UI responsiveness, layout, lifecycle/discard, and save-to-workspace journeys.

New files: `SetupDraft.java`, `DiscoverySetting.java`, `SetupDraftTest.java`,
`DiscoverySettingTest.java`, and this handoff. Extended the existing draft,
configuration load/save/evaluation boundaries, added one D4 regression test, and
updated next-sessions/fixture evidence. Existing uncommitted implementation files
remain in place. GitHub issues and their states/labels/dependencies are unchanged.

Coordinator gate: review #32a implementation and evidence. Only after acceptance
and separate authorization should #32b connect the model to the existing setup
flow. No sizing or ownership work is implied by this handoff.
