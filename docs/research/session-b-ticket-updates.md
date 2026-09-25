# Proposed downstream ticket updates for #21

Local completion update, 2026-09-23: revised metadata-only #7b is implemented
and verified, awaiting coordinator review. Focused tests: 37 passed. Java 25
clean verification: 202 passed, no failures/errors/skips. No new publication is
authorized or performed in this completion session. Earlier unfinished-status
statements below are historical; see [the current handoff](session-b7b-implementation-handoff.md).

Publication update, 2026-09-23: at the user's explicit request, updated #7/#21/#32
bodies for metadata-only discovery, `not estimated` sizes and deferred B06.
Updated #10 to implement bounded execution using Java 27 structured concurrency,
including limits and copying/cancellation/shutdown tests in that implementation,
without a separate API evaluation/prototype ticket. #7/#10 titles were updated.
Verified the published bodies/titles; states, labels and dependencies are unchanged.
This publishes those scope decisions only, not every historical draft below or
any implementation-completion claim. See [the handoff](session-b7b-implementation-handoff.md).

Current local contract, 2026-09-23: B01–B03, the nested migration and explicit
bundled `consider` advice are accepted. The user authorized #7b, then accepted
removing automatic sizing. #7b now covers metadata-only candidate observations,
size `not estimated`, and bounded optional I/O: B04/B05/B15. B06 measurement is
deferred to a separately authorized on-demand feature, potentially using native
`du`; it is not a prerequisite for #32. No recursive scan or native size command
belongs in basic discovery. See [the scope/status handoff](session-b7b-implementation-handoff.md).
The implementation is unfinished, not accepted. Older status notes and migration
proposals below are historical. These remain local ticket drafts, not GitHub updates.

Nested migration update, 2026-09-23: the user-authorized parser/resource/test
migration is implemented locally and awaiting coordinator review. See the
[handoff](session-b01-b03-implementation-handoff.md) and
[current evidence](session-b-nested-migration-evidence.md). The flat implementation
update and migration proposal below are historical; the migration prerequisite
is now implemented, not coordinator-accepted. No downstream scope or publication
is authorized by this update.

Local drafts only, 2026-09-23. Do not publish, change labels/dependencies or close
issues until coordinator review and authorization. Source contract:
[session-b-candidate-list-spec.md](session-b-candidate-list-spec.md), including
fixtures and acceptance IDs. Preserve existing ticket history when publishing.

Local implementation update, 2026-09-23: coordinator accepted the flat schema,
strict source-level validation and the all-definitions collapse rule. B01–B03
is implemented for review; visibility UI remains deferred. See
[the implementation handoff](session-b01-b03-implementation-handoff.md).
The earlier proposed comments below predate these decisions. No issue, label,
dependency, comment or completion checkbox has been changed on GitHub. #7 remains
incomplete: observation, sizing, bounded I/O and ownership are later slices.

## #21 — proposed handoff comment

Latest user decision: adopt nested `apps: [{name, directories}]` and optional
top-level `directories` for ungrouped entries. This supersedes the flat-schema
approval above. Both sources use the same format; directory records contain
`path`, optional `advice` and optional `reason`. Directory-level `app` is rejected.
App groups supply association only, with no advice/policy/selection inheritance.
See specification §2 and `session-b-fixtures/nested/` for adopted examples.

Specification prepared locally in `docs/research/session-b-candidate-list-spec.md`
with YAML fixtures in `docs/research/session-b-fixtures/`. It extends accepted D4
manual setup. The schema update changes no production files. Adopts nested app
groups plus ungrouped directories, strict source-level validation,
root-relative normalization, no-follow observation and attributed deduplication.
Configured/manual entries and selections remain independent of refresh. Optional
`homelight.discovery.shared-list` persists separately from explicit relocations.

Coordinator review remains required. The user leans toward collapsing
usually-unnecessary entries with an obvious count/reveal action; confirm this
preference at review. Mixed or omitted advice and configured/selected/manual
entries remain visible. Group selection remains deferred. Review exact schema,
path/link handling, bounded I/O and persistence recommendations before calling
the contract accepted. No implementation authorization is implied.

## #7 — proposed scope/dependency update

Historical B01–B03 migration proposal (now completed and accepted, with bundled
advice subsequently authorized): migrate `CandidateParser`, the actual
`src/main/resources/candidates.yaml`, and parser/packaging tests from the flat
schema to the adopted nested schema. Preserve all 26 current bundled paths,
reasons and app associations, including JBang; introduce no advice. Flatten
definitions internally without losing app association, structural source location,
record order, conflicting advice or normalized deduplication. Enforce aggregate
record limits across groups. Test apps-only, ungrouped-only, mixed/empty/repeated
groups, duplicate paths across groups, invalid group fields, legacy `app` rejection
and nesting/limit boundaries. Adopt `session-b-fixtures/nested/` in tests, then
rerun focused/full Java 25 verification and packaging checks. Existing flat
fixtures remain temporarily for the current implementation; previous passing
results do not verify this newly adopted contract. This document does not perform
or authorize broader implementation work.

Implement accepted #21 in two basic discovery slices before #32 integration:

1. #7a: common strict YAML parser for bundled and optional shared sources, migrate
   the existing catalog to a resource, normalize/reject paths, deduplicate while
   retaining every provenance/app/advice/reason occurrence. Acceptance B01–B03.
2. #7b: immutable metadata observations, no-follow link/path states, size explicitly
   `not estimated` with no byte value, nested relationships, independent source
   failures and bounded background reads. Acceptance B04/B05/B15; B06 measurement
   is deferred. Do not enumerate candidate contents or invoke size tools. Prove
   responsiveness and bounded outstanding reads at the discovery interface,
   including stalled optional NFS input, deadlines, stale results, obsolete
   generations, cancellation and shutdown. Actual manual Add/Save/UI journeys
   belong to #32. No parallel scan pool or generic asynchronous framework is required.

Both are read-only and presentation-neutral. No selection, configuration mutation,
policy derivation or catalog-backed defaults. Catalog occurrence order is not
advice precedence; conflicting advice survives. Missing/unreadable/malformed
sources do not prevent use of other sources. Use the session-B fixture manifest.

Proposed dependency correction: basic #7a/#7b depends on accepted #21, not #5.
Move external-ownership enrichment to a bounded follow-up dependent on #5;
basic results say ownership not evaluated. Keep original ownership acceptance
open until that follow-up ships; do not claim #7 fully complete prematurely.
Retain #5's fail-closed symlink baseline. Coordinator should reconcile actual
dependency edges when approving the split; this local draft changes none.

## #32 — proposed body refinement

Part of #8/#17. Extend locally accepted #31 Locations → Relocations → Row details.
Blocked on coordinator acceptance of #21 and verified #7 basic discovery; do not
wait for #5 ownership enrichment. Full existing-file editing remains #17.

Slice #32a: join discovery with configured/manual rows without rewriting either;
stable individual selections, removed-selected retention and new-unselected
behavior; complete overlap validation; independently round-trip optional
`homelight.discovery.shared-list` with explicit saved relocation entries. Preserve
create-only atomic/no-overwrite publication. Acceptance B07–B09/B12/B14.

Slice #32b: optional list location at Locations and a subordinate candidate browser
from Relocations, app headings plus ungrouped entries, attributed mixed advice,
using the nested source associations from the revised #7 contract;
explicit per-directory Add/edit, Refresh, Back/discard, Validate and Save. Preserve
existing manual keys/row editing. Group-wide selection is deferred. Implement the
human-approved usually-unnecessary visibility choice, not an assumed default.
Acceptance B04/B10–B16, at 80×24 and 120×30 with bidirectional resize and real PTY
evidence, focused tests and Java 25 full-suite verification.

Save only manual and explicitly added rows, never bundled definitions or inferred
policy. Save can retain a syntactically valid unavailable shared-list location;
source access/metadata observation must not block manual creation. Show size as
not estimated; do not add automatic or on-demand sizing UI in #32. Save reloads Workspace and
never applies. Existing configured rows are read-only in the merged view, with
their saved targets/policies authoritative. No existing-config editor is added.
Refresh changes observations only, never configuration bytes, selected row values
or reviewed plans; late results after root/location edits, cancel or save are ignored.
Update the maintained TUI design at the accepted implementation handoff.

## #8 — proposed coordination comment

#31 manual setup is accepted locally (D4 final acceptance, 2026-09-23); publication
is pending. #32 extends that accepted flow using #21/#7 discovery, not a replacement
wizard. Keep optional list entry, grouped presentation, individual selection and
explicit save within the same setup/session. Omitted policies remain Prompt;
onboarding does not force invented choices before saving. Subsequent reconciliation
still resolves actual conflicts before guarded apply. Older direct-to-Plan and
Escape-exits wording is superseded by `docs/tui-design.md`. Ownership annotations
remain later #5 enrichment. See session-B B10–B16 for UX acceptance.

## #17 — proposed coordination comment

The #32 creation-only persistence slice adds one optional discovery setting,
`homelight.discovery.shared-list`, separate from explicit relocations. Blank omits
the section. Round-trip the absolute list location without reading it during
configuration load/planning; unavailable list input does not block manual save.
Do not serialize bundled definitions, groups, advice, provenance or observed sizes.
Retain D4 validated atomic creation and at-least-one-relocation requirement.
Existing-file setting edits, replacement publication and the full editor remain
#17; managed-link #6 dependencies apply there, not to basic discovery/creation.
See B07–B09/B12–B14; include D4's valid-source/invalid-target regression cases when
touching setup conversion. No dependency/label change has yet been made.
