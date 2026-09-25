# Candidate discovery specification fixtures

These are inert inputs for [session B](../session-b-candidate-list-spec.md), not
production definitions. Advice is illustrative. Future tests should copy inputs
into temporary storage; never inspect or mutate real home/NFS directories.

Accepted #7b scope revision, 2026-09-23: discovery observes path metadata only.
All candidate sizes are `not estimated`, with no byte value. B04/B05/B15 remain
active; B06's measurement fixtures below are retained for a future separately
authorized on-demand sizing feature. No recursive scanner or native `du` runs
in #7b. See [the scope/status handoff](../session-b7b-implementation-handoff.md).

Current #7b evidence: metadata-only implementation is verified locally, awaiting
coordinator review. `CandidateMetadataTest` and `CandidateDiscoveryTest` cover
B04/B05/B15 with temporary roots, controlled readers and a fake monotonic clock.
The focused run passed 37 tests including B01–B03; Java 25 clean verification
passed 202 tests without failures/errors/skips. B06 remains deferred, not passed.

Current #32a evidence: `SetupDraftTest` uses the adopted nested fixtures with
temporary source/target roots for B07–B09/B12/B14. `DiscoverySettingTest` covers
independent setting persistence and concurrent creation; `HomeLightAppTest` adds
D4's valid-source/invalid-target regressions. The 2026-09-24 correction adds
per-occurrence history regressions for equal rows, repeated Row objects and edits
into another row's value after successful discovery removal. Focused verification
passed 72 tests; Java 25 clean verification passed 219 tests without failures/errors/skips. See
[the #32a handoff](../session-b32a-implementation-handoff.md). Coordinator review
is pending; candidate-browser UI and B06 sizing remain outside this evidence.

## Catalog inputs and expected merge

The adopted nested schema fixtures are [nested/bundled.yaml](nested/bundled.yaml),
[nested/shared.yaml](nested/shared.yaml) and
[nested/shared-refreshed.yaml](nested/shared-refreshed.yaml). The names and counts
below refer to these adopted fixtures. They preserve all original occurrences,
app associations and advice. Root-level flat versions are retained as historical
evidence only, not an alternative accepted schema. The migrated B01–B03 tests
consume the nested versions. `malformed.yaml` and `unsafe.yaml` remain applicable
unchanged. Current results are in the
[migration evidence](../session-b-nested-migration-evidence.md).

Add schema cases for apps-only, directories-only, both keys, empty sequences,
empty app groups and repeated app names. `{}`, null sequences, unnamed groups,
missing group directories, group-level advice/reason and directory-level `app`
must fail. Duplicate paths under different apps and also ungrouped must retain
every occurrence, including omitted advice. Count records across all groups for
limits; verify deterministic app-first flattened indices and structural locations.

- `bundled.yaml`: six unique paths, three app labels plus ungrouped `datasets`.
- `shared.yaml`: seven entries, of which `.m2` and normalized `.cache/uv` match
  bundled entries. Combined view: eleven unique candidates, all initially
  unselected. `.m2` retains Maven/Build tools and opposing advice/reasons;
  `.cache/uv` retains uv/Python tools and both absent/consider advice occurrences.
- `shared-refreshed.yaml`: replace shared snapshot at the same location. The
  combined catalog has seven unique paths. Selected `team-cache` survives as a
  draft without current attribution; new `new-cache` is unselected. `.m2` keeps
  bundled advice; its former shared occurrence remains last-known provenance in
  any selected draft, not a current source assertion.
- `malformed.yaml`: syntax error, reject the entire shared snapshot.
- `unsafe.yaml`: structurally valid YAML with a safe record followed by traversal;
  reject the whole source, including `team-cache`. Bundled results still available.

Run each valid catalog under symbolic fixture roots HOME_ROOT and OTHER_ROOT,
e.g. display `/home/alex` and `/srv/build/alex`, but create real fixtures beneath
a test-owned temporary directory. Use a separate TARGET_ROOT. Exact expected
identity is ROOT plus each normalized relative path; list location is independent.

## Controlled filesystem recipe

Create this tree under each temporary source root. Byte lengths are inert fixture
data for the deferred sizing scenarios, not values #7b should measure:

```text
.m2/repository/a.bin                  regular file, 4096 bytes
.cache/uv/a.bin                       regular file, 1024 bytes
.cache/uv/b.bin                       regular file, 2048 bytes
.cache/example/                      empty directory
.local/share/uv/runtime.bin           regular file, 8192 bytes
.local/share/uv/tools/tool.bin        regular file, 512 bytes
datasets/data.bin                    regular file, 16384 bytes
team-cache/team.bin                  regular file, 128 bytes
new-cache/new.bin                    regular file, 64 bytes
link-cache                          symlink to OUTSIDE_ROOT
linked-parent                       symlink to OUTSIDE_ROOT
not-a-directory                     regular file, 16 bytes
absent-cache                        absent
OUTSIDE_ROOT/cache/secret.bin        regular file, 32768 bytes
```

For #7b, `.m2`, `.cache/uv`, `.cache/example`, and the nested uv parent/child are
observed directories with size `not estimated`, including the empty directory.
Parent/child overlap comes from catalog identities. `link-cache` has raw target
evidence; `linked-parent/cache` is blocked by an intermediate link. Neither reads
`secret.bin`. `absent-cache` is missing; the regular file is not an eligible
directory. Neither receives a byte value. Add a broken leaf link and an inside-root
intermediate link variant: neither is followed. Use a root alias fixture to prove
the explicitly selected root anchor is handled separately from descendant links.
Assert that metadata discovery never enumerates candidate contents. Inject access
denial and changed path-component identity deterministically; affected state is
inaccessible/unknown, with prior observations stale on refresh failure. Do not rely
only on chmod tests, which can pass under privileged users.

## Deferred B06 measurement fixtures

These are historical measurement examples, not active #7b acceptance assertions.
A future on-demand sizing contract must confirm or revise them, including the
old traversal limits, before tests or implementation are required.

Historical logical-byte expectations: `.m2` 4096, `.cache/uv` 3072, empty directory
0, uv parent 8704 and child 512 (overlapping, not additive savings).

Hard-link variant: link `.cache/uv/c.bin` to `a.bin`; still 3072 if file keys are
available, otherwise 4096 with possible-overcount evidence. Permission variant:
reader throws access denied on `b.bin`, yielding partial known bytes, not complete
3072. Prefer deterministic filesystem seams for permission errors; chmod tests
alone may pass under privileged users. Inject changed directory/link identity
during traversal and a filesystem-boundary result; affected branches are excluded
and sizes partial. Simulate 100,001 entries or elapsed scan limit without creating
a large real dataset.

## Input error matrix

| Fixture variant | Expected outcome |
| --- | --- |
| Shared path absent | Missing-source diagnostic; bundled candidates/manual save usable |
| Shared open throws access denied | Unreadable-source diagnostic; no configuration mutation |
| Shared location names a directory/FIFO | Reject non-regular input within response deadline |
| `malformed.yaml` | Parse error with location; stale prior snapshot labelled if available |
| `unsafe.yaml` | Source rejected, not partial acceptance |
| Reader blocks beyond five seconds | Timed-out source; no event-loop block; no unlimited retry tasks |
| Blocked reader completes after discard/root edit | Late generation ignored, discarded state not resurrected |
| Reader fails after partial bytes | Reject new snapshot; do not parse a prefix |
| Shared linked regular YAML | Read bounded contents; retain requested location provenance |
| Empty file / two documents / duplicate key | Schema or syntax error, not empty catalog |
| `directories: []` | Successful empty snapshot; selections survive |
| Input over byte/record/depth/string limit | Typed limit diagnostic |

An unavailable-NFS acceptance test uses a controllable reader that never completes
until released by the test. Assert manual Add, candidate browsing of bundled
results, Save and exit can proceed before release; second Refresh cannot spawn
another stuck request. Then release it and assert no stale callback is applied.
A #7b test proves independent results and nonblocking refresh/cancel/close at the
discovery interface; actual manual Add/Save/UI journeys belong to #32. Use controlled
metadata-reader stalls too, without enumerating trees, to prove deadlines, bounded
outstanding work, stale evidence and generation rejection. Do not introduce a
parallel scanning requirement merely to exercise this test.
A real NFS exercise is optional environment evidence, never needed for deterministic
tests and never a reason to mount or change a user's network filesystem.

Generate table-driven single-entry schema/path fixtures with `path` equal to:
empty, whitespace, `.`, `./`, `/tmp/cache`, `../cache`, `a/../cache`, `~/cache`,
`${HOME}/cache`, `$HOME/cache`, `C:/cache`, backslash/UNC, NUL, newline. Reject each.
Accept `.cache//uv`, `.cache/./uv`, `cache with spaces`. Also reject wrong advice,
null optionals, numeric paths, unknown `policy`/`selected` keys, anchors, aliases,
custom tags and duplicate `path` keys. Validate escaped strings as YAML scalars,
not by shell expansion.

## Draft, refresh and save scenarios

Seed a configured `.m2` with a non-default target and policy. It stays Configured,
retains both fields and gains both catalog attributions. Seed manual `datasets`
with a custom target: Add must offer Edit rather than duplicate it. Seed an
out-of-root configured entry and ensure it stays visible without rebasing.

Select `team-cache`, edit its target and policy, then load `shared-refreshed.yaml`.
The row survives, its edits survive, `new-cache` remains unselected. Compare saved
configuration bytes and reviewed-plan object/value identity before/after refresh.
On shared failure retain stale source evidence. Clear/change the location and
verify only the view/settings draft changes. Change source root and verify relative
rows re-resolve only from that explicit edit and old callbacks are ignored.

Attempt parent/child uv selection, duplicate source, duplicate target, nested
targets, source→other-target intersections and a two-relocation cycle. Reject
invalid Add or Validate/Save and preserve previous rows. Include valid-source with
blank, root-equal and escaping target input from D4's coverage-gap handoff.

Cancel/discard must leave config absent and all fixture contents/links unchanged.
Save one manual plus one selected row and an unavailable shared-list location:
round-trip only those relocations and the optional location, with no definitions,
advice, groups or implicit policies. Verify concurrent creation prevents overwrite,
publication failure retains the draft, and successful Save reloads Workspace
without relocation execution. Existing malformed config must never become a
replacement target. Test both default and explicitly chosen missing config paths.

At 80×24 and 120×30, resize both ways, inspect full paths/multiple app attributions
and reasons, add/edit/remove individually, refresh during input, and exercise
Escape/confirmed discard/cancelled discard. Capture these future implementation
journeys; these fixtures are not claims that the UI is implemented or accepted.
