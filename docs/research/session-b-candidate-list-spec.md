# Session B / #21: candidate-list discovery and setup integration

Date: 2026-09-23. Status: specification for coordinator review, not implementation
authorization. Baseline HEAD: `fcb343bee5cb1405f32086c7ce7df87a4afe25f9`, with
accepted D4 work and unrelated uncommitted changes present. No existing files were
changed by this specification session. Proposed ticket text is in
[session-b-ticket-updates.md](session-b-ticket-updates.md); concrete inputs and
fixture recipes are in [session-b-fixtures](session-b-fixtures/README.md).

Accepted UX revision, 2026-09-24: #32b uses direct Space/a Add, Enter inspection
and e editing in a grouped checklist. Current-generation confirmed-missing
paths are eligible alongside directories, allowing configuration before app
creation. This supersedes #32a's directory-only rule. The maintained presentation
contract is [TUI design §7](../tui-design.md#7-first-run-configuration-creation).
Human UX acceptance and local technical acceptance, including the destructive
policy-copy correction, are recorded in [the #32b handoff](session-b32b-implementation-handoff.md).

Accepted scope revision, 2026-09-23: after authorizing #7b, the user removed
automatic size estimation. Basic discovery inspects candidate path metadata only;
it does not enumerate candidate contents or run `du`. Report size as `not estimated`,
never zero. B06's recursive measurement scenarios are deferred to a separately
authorized on-demand feature, potentially using a native tool. B04/B05/B15 remain
in #7b; its small read-only interface, source isolation, stale evidence and generation
rules remain. This supersedes earlier sizing requirements and scan bounds below
and in historical handoffs. Current sections are updated accordingly. See the
[scope/status handoff](session-b7b-implementation-handoff.md); #7b is now
implemented and verified locally (37 focused tests, 202 Java 25 clean-suite
tests), awaiting coordinator review. The nested parser/resource slice stays accepted.

Historical coordinator update, 2026-09-23: the flat schema, strict source-level validation
and B01–B03 implementation are authorized. Bundled contents use
`src/main/resources/candidates.yaml`. Usually-unnecessary entries will collapse
under §6's all-definitions rule; that UI is outside this slice. §6 cancellation
wording below is corrected to accepted D4. Earlier review gates in this document
are historical where superseded by these decisions. Implementation and verification
are recorded in [the B01–B03 handoff](session-b01-b03-implementation-handoff.md).

## 1. Authority and scope

Subsequent user decision: explicitly assign `advice: consider` to all 26 bundled
directories. This supersedes the no-invented-advice restriction for those entries
only. Omitted advice remains valid and is not defaulted by the parser. No
`usually-unnecessary` exceptions are approved.

Implementation follow-up, 2026-09-23: the user authorized the nested-schema
migration only. Parser, bundled resource and tests are migrated and verified
locally; see the [handoff](session-b01-b03-implementation-handoff.md). Coordinator
review now accepts B01–B03, the nested migration and explicit bundled advice
locally, with 180 independently verified tests and no actionable review findings.
Earlier flat-schema approvals and verification below are historical. This does
did not authorize #7b or #32 at that time; the later #7b authorization and scope
revision above supersede that historical gate. #32 remains outside this slice.

User schema decision, 2026-09-23 (supersedes the flat-schema approval above):
adopt top-level `apps` with nested `directories`, plus top-level `directories`
for ungrouped entries, as specified in §2. The existing B01–B03 implementation
and its verification describe the earlier flat format. The specification update
changed assets only; the subsequent implementation follow-up is recorded above.

Read [#21](https://github.com/big-sw-little-sw/homelight/issues/21),
[#7 and its comments](https://github.com/big-sw-little-sw/homelight/issues/7),
[#32](https://github.com/big-sw-little-sw/homelight/issues/32), and supporting
[#5](https://github.com/big-sw-little-sw/homelight/issues/5),
[#8](https://github.com/big-sw-little-sw/homelight/issues/8) and
[#17](https://github.com/big-sw-little-sw/homelight/issues/17), including comments,
on the date above. #21/#32 had no comments. The 2026-09-23 agreed requirements
are authoritative; older wizard/Plan-screen wording in #8 is superseded by
[the maintained TUI contract](../tui-design.md#7-first-run-configuration-creation).
[Next sessions](../next-sessions.md) and
[D4 final local acceptance](session-d4-implementation-handoff.md#final-local-coordinator-acceptance-2026-09-23)
establish that manual setup is accepted, with 165 tests reported by the coordinator.
That suite was not rerun for this documentation-only work.

Settled: bundled and optional shared YAML use one schema; optional app groups and
ungrouped directories coexist. Advice is `consider`, `usually-unnecessary`, or
absent, with optional reasons. It is neither a safety assessment nor selection,
ownership, enforcement, or relocation policy. Shared input supplements discovery.
Only explicit user edits and save produce relocations. Refresh preserves configured
entries, manual drafts, selections and reviewed plans. Group-wide selection is
deferred. The user leans toward collapsed usually-unnecessary entries; this is the
preferred direction, pending confirmation at coordinator review.

Everything below specifying an exact format, limit, or interaction is a
recommendation for review unless identified as settled. This document does not
replace the accepted TUI contract before review. No full editor, managed links,
ownership detector, planner/executor change, or automatic apply is proposed.

## 2. Smallest concrete input schema

Use one mapping document with optional `apps` and `directories` sequences; at
least one key must be present. Each app has a required nonblank string `name`
and required `directories` sequence. Each directory, grouped or ungrouped, has
one required string `path` and optional strings `advice` and `reason`.
An app associates its name with its directories, without advice, policy or
selection inheritance. Top-level `directories` entries are ungrouped.

```yaml
apps:
  - name: Maven
    directories:
      - path: .m2
        advice: consider
        reason: Can become large; inspect its contents before choosing.
  - name: uv
    directories:
      - path: .cache/uv
        advice: consider
      - path: .local/share/uv
      - path: .local/share/uv/tools
        advice: usually-unnecessary
        reason: Often small in this environment.
directories:
  - path: datasets
    reason: Locally generated datasets.
```

`apps: []` or `directories: []` is a valid empty catalog; an empty file or `{}`
is malformed. Empty app directory sequences are valid and create no candidates
or visible empty groups. Omitted top-level sequences mean empty; explicit null
is invalid. Repeated app names are allowed and combine visually by exact name,
while preserving each definition's location. A path may occur under multiple
apps or also ungrouped; all occurrences survive path deduplication.

The former directory-level `app` key is rejected, not supported as a second
format. This is a pre-release replacement of the local flat proposal, not a
dual-schema compatibility layer. Both bundled and shared sources must migrate
together. Only `name` and `directories` are allowed on an app; advice/reasons
belong to individual directories. Optional directory
fields are absent or nonblank strings, never null. A reason may appear without
advice and supplies description only. No inferred advice. Enum values are exact,
case-sensitive strings. App `name` is a display association, not executable identity
or evidence that an application is installed. Reject leading/trailing whitespace
in app labels; group by exact case-sensitive label. Do not infer aliases for apps.
Reason text is displayed literally with control characters escaped, never rendered
as terminal commands/markup. Preserve original text for details.

Reject duplicate mapping keys, unknown keys, wrong types, multiple documents,
custom tags, aliases/anchors and merge keys. No interpolation, includes, globs,
URLs, scripts, destinations, policies, enabled flags, or selected flags. Start
without a version field: unsupported structure is an explicit error, never guessed.
An incompatible later schema will need an explicit migration/version decision.
Use a safe YAML data parser, separate from configuration property expansion.

Recommended defensive limits: 1 MiB UTF-8 input, 10,000 directory records across
all groups and ungrouped entries, 10,000 app groups, nesting depth 8,
4,096 characters per string. Reject a source exceeding any limit. Treat syntax,
schema and unsafe-path failures atomically per source: report record/key locations
and accept none of that source's new snapshot. Bundled and shared inputs fail
independently. This avoids a partly accepted typo-ridden catalog. A packaged
resource failure is also a packaging defect, but must not prevent manual setup.

Move the current catalog's paths into a bundled resource using relative paths
(remove `~/`); existing labels can become reasons. Assign app labels deliberately.
Do not invent advice merely because an entry was previously in the catalog. The
fixture advice illustrates behavior, not an approved recommendation about products.
The current catalog includes nested uv entries; preserve their discoverability.

## 3. Roots, path rejection and links

The chosen source root defaults to the home directory. It is independent of the
directory containing the shared list and of the process working directory.
For root `R` and catalog `path` `p`, identity is `R.resolve(p).normalize()` where
`R` is absolute and normalized. It must be a strict descendant of `R`, compared
by path components, not string prefix. Preserve original spelling in provenance.

Reject blank paths, absolute paths, `~` prefixes, drive/UNC forms, backslashes,
NUL/control characters, `.`/root-equal results, any `..` component (even one that
would normalize back inside), and attempted `${...}`/`$NAME` expansion. No shell
expansion. Repeated `/` and interior `.` components may normalize to one path;
`.cache//uv` and `.cache/./uv` deduplicate. Path strings containing spaces are valid.
This deliberately portable `/` subset applies to candidate files, not a redesign
of the existing manual configuration loader.

| Catalog path | Home root `/home/alex` | Non-home root `/srv/build/alex` |
| --- | --- | --- |
| `.m2` | `/home/alex/.m2` | `/srv/build/alex/.m2` |
| `.local/share/uv/tools` | `/home/alex/.local/share/uv/tools` | `/srv/build/alex/.local/share/uv/tools` |
| `datasets` | `/home/alex/datasets` | `/srv/build/alex/datasets` |

The shared file could be `/net/team/homelight/candidates.yaml` in either example;
its directory never becomes a source root. Targets initially mirror relative
paths beneath the chosen target root, e.g. `/mnt/fast/alex/.m2`. Save explicit
absolute source AND target paths, including for non-home roots; do not rely on
the loader's home-relative target inference.

Root/link recommendations:

- Resolve and record an explicitly chosen root's physical anchor once, off the
  input thread. A symlink in the root's own ancestry is allowed because the user
  selected that root. Retain the lexical root for display/identity. If the anchor
  is inaccessible or changes during inspection, mark results unknown/stale.
- Inspect descendants with no-follow attributes, checking each intermediate
  component. A symlink below the anchor is never traversed, even if it seems to
  point back inside. A candidate below such a component is `blocked-by-link`,
  size not estimated. Do not read its destination or suggest it is contained safely.
- A candidate that is itself a link is shown as a link with its raw link target;
  relative link targets may be displayed lexically resolved. Do not recurse into
  it or include destination bytes. Use existing bounded inspection evidence to
  distinguish live/broken links where available; otherwise report target status
  unknown. Links to live non-configured destinations remain conflicts under #5's
  baseline, never ownership evidence. Configured links retain the configured
  source identity and target; deduplication does not use `toRealPath()`.
- Missing paths, regular files, inaccessible paths and links remain inspectable
  with accurate states. Current-generation observed directories and confirmed
  missing paths are offered as new selectable candidates. Other states can be inspected or entered through
  the existing manual path workflow; this is not a new global save prohibition.
  Existing drafts/configured rows are never deselected on a state change.
- Inspection is read-only and best-effort under concurrent filesystem changes.
  Check path components without following descendant links; do not enumerate
  candidate directory contents. Stop affected inspection on changed evidence.
  This is not an atomic containment/safety proof. Validation, preflight and action
  guards still own execution safety. Never label discovery results “safe”.

Lexical normalized identity deliberately does not equate case variants, hard links,
or different root aliases. Report detectable alias uncertainty; do not rewrite
configured paths to physical paths. Broader physical-alias validation is separate
from catalog deduplication and cannot be inferred from metadata observations.

## 4. Read-only result and merged view

#7 returns immutable presentation-neutral observations, source outcomes and typed
diagnostics. Each candidate needs normalized absolute source identity; observations
(kind, time/generation, freshness, and explicit `not estimated` size status); all
definition occurrences; and nested-path
relationships. An occurrence retains bundled resource identity or shared location,
record index (line if available), original path, app, advice and reason together.
Flatten app directories in app sequence order, then top-level directories, for
stable one-based record indices regardless of YAML mapping-key order. Retain the
structural location too, e.g. `apps[1].directories[0]` (zero-based indices), so an
invalid entry and its provenance can be traced to the nested input. Empty groups
consume no directory indices. The internal optional app association remains useful
even though the YAML no longer repeats an `app` field on every directory.
Do not flatten advice into one winning value or detach a reason from its author.
Ownership is `not evaluated` until #5 supplies evidence, never assumed unowned.

Deduplicate by normalized absolute source path. Bundled-before-shared order can
stabilize display, but is not precedence. Keep all app associations and conflicting
advice, including multiple definitions within one file. Identical occurrences may
be summarized visually while retaining their locations. Advice absent from one
occurrence does not negate another occurrence's advice.

For `.m2`, bundled `Maven / consider` and shared `Build tools /
usually-unnecessary` produce one candidate with two associations and both reasons,
labelled `Mixed advice`. That label is information, not a validation conflict.

The setup adapter joins observations with configured relocations and manual or
candidate-origin draft rows by normalized source identity. The join changes only
the view:

| Existing state | Result of discovery/refresh |
| --- | --- |
| Configured path | Mark `Configured`, retain saved target and policies; no Add checkbox and no second relocation |
| Manual draft with same source | Show `In draft`; attach discovery information without changing target, policy, text or selection |
| Selected candidate draft | Retain row and user edits regardless of list removal, advice change, missing path or scan error |
| Newly discovered path | Unselected, even when its app or advice matches selected entries |
| Removed unselected path | Remove from a successful refreshed discovery snapshot; do not remove independent rows |
| Configured/manual path absent from all catalogs | Keep visible; show no catalog attribution rather than inventing one |

Malformed configurations and duplicate manual rows remain errors to resolve, not
inputs to silently merge away. A configured source outside the active discovery
root is still visible, marked outside this root; no rebasing. In #32 the configured
join is a read-only contract and test seam, not authorization to add an existing-file
editor. Reconciliation decision drafts and reviewed plans are separate data from
setup selections; refreshing discovery never invalidates or regenerates those plans.

Nested candidates, e.g. `.local/share/uv` and `.local/share/uv/tools`, both appear
with an overlap indication. Selecting one does not silently select/deselect the
other. Reject an Add that would intersect an existing configured/draft relocation,
explain the paths, and retain prior choices. Manual edits may still temporarily
form invalid rows, as D4 permits; Validate and Save reject all duplicate sources,
nested sources/targets, duplicate targets, cross source/target intersections and
cycles over the complete proposed set. No policies are filled from advice.

## 5. Metadata-only discovery and responsive failure behavior

Basic discovery does not estimate size. Every candidate reports `not estimated`
with no byte value, including observed directories, empty fixture directories,
regular files, missing paths, links and failed inspections. Do not substitute zero,
directory metadata length, allocated blocks, or a claimed saving. No recursive
enumeration, emptiness check, hard-link accounting, mount-boundary scan or native
size command runs during discovery or refresh. Nested relationships are computed
from catalog path identities, not by visiting directory contents.

B06's measurement scenarios are deferred to an explicit, separately authorized
on-demand feature. Native `du` is an option to evaluate then; it still traverses
directory contents and is not a constant-time size lookup. Future sizing must
define its measurement semantics, platform behavior, failure and cancellation
handling before implementation, and must not add overlapping sizes as independent
savings. The former 100,000-entry/two-second traversal bounds and approximate/
partial byte states are not requirements of #7b. No sizing UI is added to #32.

Load bundled input independently. Read the shared file once per explicit refresh,
with a five-second response deadline and the byte/schema limits above. Candidate
metadata observation and shared-file access never run on the input/render thread.
Results carry a request generation and root/list identity; ignore late results
after edit, cancellation, save, refresh or exit. No watcher, scheduled reread,
unbounded retries or persistent catalog cache in the first slice.

A deadline cannot promise to cancel a kernel-blocked NFS operation. Recommend a
separate bounded shared-I/O worker lane with at most one outstanding request;
after deadline, detach its result from the session. A retry while it is still
blocked reports `Previous read still pending; manual setup remains available`
without spawning more workers. Bundled parsing/manual edits must not queue behind
that lane. Metadata probes need bounded outstanding work too, since even a path
attribute lookup can stall. A serial background metadata pass is sufficient;
there is no requirement for parallel scanning, a generic scheduler, or a polling
API that drives work. Keep the interface small and presentation-neutral. Mark
unavailable observations pending/unknown rather than starting replacement workers
on every refresh. Shutdown must
not join an indefinitely blocked optional task. #7 must prove this lifecycle using
a controllable blocked-reader fixture before integration. Process isolation is a
fallback if the runtime cannot meet exit responsiveness; do not claim interruption
alone solves unavailable NFS.

| Input condition | Required view and recovery |
| --- | --- |
| No shared location | Bundled candidates only, no error |
| File missing | `Shared list not found` with full path; edit location or Refresh |
| Permission denied / wrong file kind | `Cannot read shared list` and cause; no blocking startup retry |
| Bad YAML/schema/unsafe entry | Source diagnostic with location; no partial new snapshot from that source |
| NFS read stalls | At deadline show unavailable/timed out; input, manual rows and bundled results remain responsive |
| Root/candidate inaccessible | State unknown/inaccessible for affected paths; size stays not estimated; available evidence remains usable |

On refresh failure at an unchanged root/location, retain the last successful
snapshot visibly marked stale, with its provenance; never present it as freshly
read. Existing selections remain editable. New selection from stale observations
requires fresh candidate path inspection, independent of advice freshness. On a
location/root change, do not attach the old snapshot as current input; keep selected
rows with last-known provenance and an explicit “no longer in current discovery”
note. Clearing the setting removes only the shared discovery source. Manual save
must not wait for list accessibility or metadata observations, and may persist a syntactically
valid currently unavailable location. Diagnostics do not silently clear that setting.

## 6. Extend the accepted setup journey

Missing default and explicit config paths retain `i: Manual setup` and
`homelight init`. An existing malformed/unreadable config remains an error, never
a replacement target. Locations → Relocations → Row details remains the accepted
journey; preserve `a`, Enter, `d`, `e`, explicit Validate/Save and existing Escape
and discard behavior.

1. Add an optional `Shared candidate list` field to Storage locations after the
   roots. Blank means bundled only. Accept an absolute filesystem path or `~/...`
   expanded against the user's home; reject other relative paths, variables and
   URLs. This setting may point outside the source root, including an NFS mount.
   A linked regular YAML file may be read through its location, within the same
   deadline/limits; the no-follow rule for candidate directories is separate.
   Show the resolved absolute location. Editing it changes in-memory setup only.
2. Add `Browse candidates` from the Relocations table, proposed `b` binding.
   It opens a subordinate candidate view; manual table edits remain available
   immediately, without waiting for discovery. Start the first read on entry;
   subsequent reads require explicit Refresh, proposed `r` in this view only.
3. Group presentation uses app headings plus `Other directories`. Each candidate
   occurs once: assign its row to its first bundled association, otherwise first
   shared association, and show `Also: ...` for other apps. Full details list every
   association and attributed reason. Stable grouping is presentation order, not
   advice precedence. Headers expand/collapse but cannot select a group. Counts
   count distinct paths and distinguish configured, in-draft and unselected.
4. Arrow/j/k navigates, Enter opens details, Space/a adds an eligible candidate
   directly, and e edits a draft member. Add creates the existing row type with
   relative source/target paths and omitted policies (`Default (prompt)`). Details
   also offer explicit Add/Edit where eligible. Repeated Add never removes or
   duplicates a row; removal remains in the table/editor. A manual draft match
   offers Edit, preserving its values and membership. Configured
   matches offer inspection only. Selection here means draft membership, not
   focus/highlight. Adding is never saving or applying.
5. Escape from candidate details returns to the candidate list, then to the
   Relocations table, retaining selections. This is Back, not discard. Setup
   cancellation via Escape at Locations cancels setup directly, as accepted in
   D4. Explicit discard uses confirmation: cancel the dialog retains all edits;
   confirmed discard clears roots, list location, rows and session discovery
   state, writing nothing. Escape never exits the app.
6. Refresh updates observations and attribution only, preserving focus by path
   when possible. Selected removed entries remain in the table with last-known
   provenance; newly appearing entries are unselected. Row edits/add/remove reset
   validation. Refresh may update evidence and require revalidation at Save, but
   cannot edit row values or selections. Save always validates anew.
7. Explicit root edits retain relative rows, as accepted in D4. Show that these
   rows now resolve beneath the new root, clear old observations and validation,
   and request discovery for that root. Preserve draft membership/user values;
   this re-resolution is caused by the user's root edit, never by refresh.
   Late results for the prior root cannot attach to rebased rows.
8. Validate and Save evaluate the whole draft, including manual rows. Keep D4's
   requirement of at least one relocation: a discovery-setting-only file is not
   introduced here. Save atomically creates at the selected config path, with
   concurrent-create/no-overwrite behavior and failure retaining all edits.
   Reload the established workspace; no automatic review, policy decision or
   filesystem relocation. Existing guarded review/apply remains separate.

**Visibility preference, pending coordinator review:** the user leans toward
collapsed entries. Collapse a candidate only when every current definition gives
`usually-unnecessary` advice. Provide an obvious `Show N usually-unnecessary
directories` action, counting distinct hidden paths, and a way to collapse them
again. Revealed entries retain their advice and attributed reasons and permit
individual selection. Mixed advice, any omitted advice, configured entries and
selected/manual entries remain visible. Selecting a revealed entry keeps it visible
even after collapsing the rest. This filter changes presentation only, never
selection or safety validation. The earlier show-all recommendation is superseded
by this preferred direction; confirm it at coordinator review before implementation.

## 7. Persistence proposal

Extend the existing configuration namespace with one optional discovery setting:

```yaml
homelight:
  target-root: /mnt/fast/alex
  discovery:
    shared-list: /net/team/homelight/candidates.yaml
  relocations:
    - source-path: /srv/build/alex/.m2
      target-path: /mnt/fast/alex/.m2
```

Omit `discovery` entirely when the setting is blank. Store the normalized absolute
location; preserve it on round-trip independently of relocation entries. Do not
persist bundled definitions, groups, reasons, advice, observations, selection UI
state, or provenance in relocation configuration. Session provenance is retained
through refresh and draft editing; after restart it is reconstructed from available
lists, never invented as durable history. A configured entry remains configured
even when its former catalog cannot be read.

The source root remains a setup parameter, as in D4; explicit absolute relocation
paths make a new persisted source-root field unnecessary for this slice. Config
loading/status/planning must not read the shared list. A missing setting remains
backward compatible. Extend the configuration draft, load and save boundary only
enough to round-trip the optional setting; no new catalog-backed policy defaults.
Existing-config setting edits are #17 work, not enabled through creation-only #32.

## 8. Fixtures and acceptance scenarios

The [fixture manifest](session-b-fixtures/README.md) supplies exact YAML, controlled
filesystem recipes, expected observations and fault injection. These are spec
assets, not a production resource or executable implementation. Acceptance IDs:

| ID | Scenario and required assertion | Owner |
| --- | --- | --- |
| B01 | Load bundled/shared using the same nested schema; apps-only, ungrouped-only, mixed, empty and repeated groups work; reject legacy per-directory app and group-level advice; omitted advice/reason and all enum values work | #7a |
| B02 | Both roots resolve the same relative definitions; reject absolute, traversal, root-equal, expansion, drive/UNC and control-character inputs | #7a |
| B03 | `.m2` conflict and `.cache//uv` duplicate produce one path each with all occurrences/apps/advice/reasons; no winning source | #7a |
| B04 | Missing/unreadable/malformed/oversize/shared-NFS failure leaves bundled and manual work usable; stale snapshot is labelled | #7b/#32b |
| B05 | Root alias, leaf link, broken link, intermediate link, regular file, missing path, permission error and nested candidates have explicit states; do not enumerate candidate contents or follow descendant links; all sizes are not estimated with no byte value | #7b |
| B06 | Deferred: size measurement, known fixture bytes, hard-link uncertainty, partial traversal, mount boundaries and measurement timeout semantics | Future separately authorized on-demand sizing, not #7b/#32 |
| B07 | Configured and manual matches retain targets/policies and cannot become duplicate rows; configured entries outside the root remain visible | #32a |
| B08 | Refresh add/remove/reason/group/state changes preserve all selected drafts and edits, config bytes and reviewed-plan identity; new paths unselected | #32a |
| B09 | Nested selections and every source/target overlap/duplicate/cycle reject Add or Save without silently deselecting prior choices | #32a/#32b |
| B10 | Missing default and explicit paths, list-field skip/edit/clear, app details, Add/edit/remove, refresh and Back work at 80×24 and 120×30, resize both ways | #32b |
| B11 | Cancel dialog retains edits; confirmed discard/reopen resets all state and writes nothing, including after blocked read and late completion | #32b |
| B12 | Explicit save/load persists setting and only chosen/manual rows, omitted policies intact; failure/concurrent create retains draft and never replaces a file | #32a/#32b |
| B13 | Source/target contents and links unchanged by discovery/save; Save reloads workspace with no apply; reviewed plan untouched by refresh | #32b |
| B14 | Root/location change ignores old generations, retains relative draft rows and selection, resets validation and displays new resolved paths | #32a/#32b |
| B15 | Failed/stalled shared read cannot consume unlimited tasks, block bundled parsing, manual editing, Save or exit; late result cannot resurrect discarded state | #7b/#32b |
| B16 | Advice changes never set policies, select, block valid directories or imply safety; human-chosen visibility retains conflicting/selected entries | #32b |

Also add D4's non-blocking valid-source/invalid-target regression cases (blank,
root-equal, escaping) when touching setup validation. Do not reinterpret the
reported coverage gap as a defect or reopen accepted manual setup.

## 9. Bounded slices and decision gate

Proposed sequencing, with local ticket text supplied separately:

- **#7a: catalogs and resolution.** Strict common parser, bundled YAML migration,
  safe lexical resolution, immutable occurrence/provenance merge and diagnostics.
  No UI, file publication, ownership or sizing. B01–B03 and invalid-input fixtures.
- **#7b: metadata observations and bounded I/O.** No-follow states, no size estimates,
  nested annotations, per-source errors/stale snapshots, bounded scheduling and
  request-generation lifecycle. B04/B05/B15; B06 deferred. #5 ownership explicitly not evaluated.
  Read-only basic discovery is usable after these two slices.
- **#32a: draft join and persistence.** Pure merge with configured/manual rows,
  stable selections, overlap validation, optional setting round-trip and existing
  create-only publication. B07–B09/B12/B14. No full editor or new screen yet.
- **#32b: accepted setup integration.** Optional Locations field, candidate browser,
  app/advice/details, individual Add, refresh/cancel/save and responsiveness.
  B04/B10–B16 plus focused/full Java 25 tests and real PTY evidence. Reconcile
  `tui-design.md` with the accepted extension only at that implementation handoff.
- **#7 ownership follow-up after #5.** Add external ownership annotations from #5's
  typed evidence, preserving its fail-closed conflict rules. Do not make #7a/#7b
  wait for it. Keep the original ownership acceptance item open until delivered.

These labels describe proposed slices, not created issue numbers. Coordinator
should split #7's blanket #5 dependency: only ownership enrichment requires #5.
Do not remove actual dependency edges or mark work ready in this session.
#32 still needs accepted #21 and basic #7 results; D4/#31 is locally accepted,
but tracker publication remains pending. #8 and #17 remain umbrella work.

Human preference: collapse usually-unnecessary entries with an obvious reveal
action under the rule above. Confirm this tentative preference at coordinator review.
The nested schema is adopted by user instruction, superseding the flat format.
Coordinator follow-up: review its parser/resource migration and strict source-level rejection,
root/link metadata semantics, input limits, absolute list-location
persistence, and the bounded blocked-I/O contract. These are proposed concrete
defaults, not gaps to be improvised by implementation. No other product redesign
is required. Catalog content/advice beyond migrated labels needs review before
shipping; this spec does not claim tool-specific relocation safety.

Handoff: review this spec, fixtures and local ticket proposals; resolve visibility;
then authorize #7a as the next bounded session. Do not start #32 before basic
discovery is available and reviewed. No production implementation, GitHub writes,
commit or push occurred. Existing uncommitted changes remain owned by the user.

Verification for this session: read the ticket bodies/comments and accepted local
docs, inspect catalog/configuration boundaries, check fixture YAML structure and
document links, and run whitespace checks. See the supporting
[source audit](session-b-source-audit.md) for source-level evidence and limitations.

Results: Ruby's safe YAML reader parsed the four structurally valid fixture files;
`malformed.yaml` produced the intended syntax error. `unsafe.yaml` intentionally
parses but requires the proposed path validator to reject it. A normalized-path
spot-check confirmed eleven merged paths initially and seven after refresh. All
relative document link targets exist. `git diff --check` and explicit whitespace
checks of the new Markdown files reported no whitespace errors. This checks the
spec assets, not an implemented strict-schema parser or discovery behavior. No
production tests or UX walkthrough were run in this specification session.
