# #7 first bounded slice: B01–B03 coordinator handoff

## Local coordinator acceptance, 2026-09-23

B01–B03, the nested-schema migration and explicit bundled advice follow-up are
accepted locally. Separate standards and specification reviews found no
actionable findings in this bounded candidate-parser/resource slice.

Independent Java 25 `mvn -o -q clean verify`: **180 tests passed**, zero failures,
errors or skips. `git diff --check` passed. Verified all 26 bundled entries have
explicit `consider` advice and the actual Maven JAR's YAML SHA-256 matches the
source resource. The suite includes isolated-JAR loading checks; the coordinator
did not rerun the separate standalone Java launcher recorded below.

This accepts parsing, lexical resolution, source rejection, provenance merge and
packaging only. Advice is informational, never selection, policy or a safety
guarantee. #7 remains incomplete. Next proposed slice is #7b observations,
approximate sizing and bounded optional I/O; it requires separate authorization.
No #32 integration, GitHub publication, commit or push in this review. Earlier
pending-review instructions below are historical for the accepted slice.

## Explicit bundled advice follow-up

The user subsequently approved `advice: consider` for all 26 bundled directories.
Each directory now declares it explicitly; the parser still preserves absent
advice in other inputs. Paths, app associations and descriptions are unchanged.
No entries are marked `usually-unnecessary`: the existing catalog alone does not
justify that distinction. Earlier no-advice statements below describe the
migration before this follow-up. Verification is recorded in the
[local evidence](session-b-nested-migration-evidence.md#explicit-bundled-advice-follow-up).

## Nested-schema migration, 2026-09-23

Implemented locally at the user's request; stopped for coordinator review.
The parser and bundled resource now accept only the revised nested schema:
optional `apps` with required `name`/`directories`, and optional ungrouped
`directories`, with at least one top-level key required. Directory-level `app`
and group-level advice/reason are rejected. Groups supply only an association.

Definitions retain app-first flattened one-based indices plus zero-based structural
locations, such as `apps[1].directories[0]`. Schema/path diagnostics identify the
enclosing group, collection or directory and offending key, with line/column.
Source-wide and pre-schema YAML failures retain the existing location limitations.
Strict source rejection, lexical validation and normalized-path merging remain.
Limits include 10,000 groups and 10,000 total directories across all groups and
ungrouped entries; collection depth remains 8.

All 26 pre-migration path/app/description triples are asserted exactly in the
resource test, including JBang and both nested uv entries. No advice was added.
Tests now consume the adopted nested fixtures: initial merge is 11 paths / 13
occurrences, refreshed merge is 7 paths / 8 occurrences. All attributions survive.
Empty sequences/groups, repeated exact app names, case-distinct names, app-first
ordering independent of mapping order, cross-group/ungrouped duplicates, invalid
groups/fields, legacy rejection and aggregate/depth boundaries are covered.

Verification on Temurin OpenJDK 25.0.3:

- `mvn -q -Dtest=CandidateCatalogTest test`: 15 tests passed.
- `mvn clean verify`: 180 tests, no failures, errors or skips; BUILD SUCCESS.
- Isolated test JAR: valid nested resource loads 26 definitions; missing and
  malformed resources are rejected independently.
- Actual `target/homelight-1.0-SNAPSHOT.jar`, loaded with only that JAR and
  SnakeYAML: 26 nested definitions, exact source/resource byte match, JBang and
  nested uv preserved, no advice.

See [nested migration evidence](session-b-nested-migration-evidence.md) for check
details and scope. Only parser, occurrence/diagnostic location records, bundled
YAML, catalog tests and local session-B documentation changed in this migration.
Existing unrelated uncommitted work was preserved. No #7b, shared-file worker,
#32 integration, GitHub publication, commit or push. #7 remains incomplete.

## Historical flat-schema implementation and evidence

Everything below records the superseded flat-schema implementation. Its passing
results do not verify the nested schema; use the migration evidence above.

Follow-up requested by the user, 2026-09-23: all bundled entries now have explicit
app labels, with each app's entries kept adjacent in the flat schema. Added
`.jbang/cache` under `JBang`, bringing the total to 26. JBang's
[installation documentation](https://www.jbang.dev/documentation/jbang/latest/installation.html)
and [caching documentation](https://www.jbang.dev/documentation/jbang/latest/caching.html)
identify this default cache for compiled scripts, downloaded content and JDKs.
Custom `JBANG_DIR`/`JBANG_CACHE_DIR` locations are not expanded by this catalog.
Existing path/reason pairs and nested uv candidates remain intact; no advice was
added. The original migration evidence below describes the earlier 25-entry state.
Resource and isolated-JAR assertions now expect 26 entries and check app grouping
and JBang resolution. Follow-up verification: Java 25 `mvn clean verify`, 176 tests
passed, no failures/errors/skips.

Date: 2026-09-23. Status: implemented locally, awaiting review. Baseline HEAD:
`fcb343bee5cb1405f32086c7ce7df87a4afe25f9`, with accepted D4 and other uncommitted
work present. No commit, push, GitHub publication, label or dependency change.

## Decisions and scope

Read #21/#7 bodies, labels and comments using `gh`, the session-B specification,
all six fixture/manifest files, source audit and proposed ticket updates.
The coordinator authorized the flat schema, strict source-level rejection and
B01–B03 only. Bundled data lives at `src/main/resources/candidates.yaml`.
The all-definitions rule for collapsing usually-unnecessary candidates is accepted;
its UI remains deferred.

Corrected spec §6 first: Escape at Locations cancels setup directly. Explicit
discard uses confirmation; cancelling that dialog preserves edits. No production
setup behavior changed.

## Implementation

- [CandidateParser](../../src/main/java/io/github/bigswlittlesw/homelight/config/CandidateParser.java)
  accepts a source identity, an absolute chosen root and already-read UTF-8 bytes.
  Bundled/shared input uses exactly the same parser. It returns an immutable
  accepted snapshot or an empty rejected snapshot with a typed diagnostic.
  The parser does not open the shared location or inspect candidate paths.
- Safe YAML events reject anchors, aliases and unsupported tags before node
  composition. Schema validation rejects unknown/duplicate keys, merge keys,
  non-string fields, null/blank optionals, invalid advice, untrimmed app labels,
  empty input and multiple documents. No Java object construction or configuration
  property expansion. SnakeYAML 2.4 is now an explicit dependency, matching the
  version already used transitively by SmallRye.
- Fixed internal limits: 1,048,576 UTF-8 bytes, 10,000 records, collection nesting
  depth 8 (root mapping is depth 1), and 4,096 Unicode code points per scalar.
  These are not user-facing options. Malformed UTF-8 is rejected, not replaced.
  Diagnostics report the first failure per source, line/column where available,
  and record/key for schema and path failures. Zero location fields mean that
  the failure is source-wide or occurred before record/schema association.
- Resolution is lexical beneath the normalized absolute root. Absolute/drive/UNC,
  URI, tilde, backslash, parent components, root-equal, control-character,
  variable-expansion and glob inputs are rejected. Interior `.` and repeated `/`
  normalize; literal spaces remain valid. No `toRealPath`, existence checks,
  symlink resolution or case folding. The source location never supplies the root.
- [CandidateDefinition](../../src/main/java/io/github/bigswlittlesw/homelight/config/CandidateDefinition.java)
  retains normalized path, source kind/location, one-based record and YAML
  line/column, original path spelling and optional app/advice/reason together.
  Reasons remain literal, including control characters; future renderers must
  escape them. No inferred policy, ownership or safety assessment.
- [CandidateCatalog](../../src/main/java/io/github/bigswlittlesw/homelight/config/CandidateCatalog.java)
  loads the bounded classpath resource and merges snapshots by absolute normalized
  path. Supply bundled then shared for that display order; order is not precedence.
  Every occurrence survives, even identical same-source reasons at different
  records. Conflicting advice and absent advice remain separate. Failed sources
  contribute diagnostics only; mixing roots is a caller error. Nested paths remain
  distinct, without inspection or overlap-selection rules.
- All 25 original paths and descriptions were migrated in their existing order,
  stripping only `~/` and mapping labels to reasons. A direct comparison confirmed
  every pair. No app associations or relocation advice were invented. Both uv
  parent and child entries remain. Maven copies the YAML without resource filtering.
  Removed the unused `defaults()` data-stub API; source search found no callers.

## Verification and evidence

[CandidateCatalogTest](../../src/test/java/io/github/bigswlittlesw/homelight/config/CandidateCatalogTest.java)
adds 11 test methods with table-driven cases:

| Acceptance | Evidence |
| --- | --- |
| B01 | Both source kinds parse the same schema; grouped/ungrouped entries, optional fields and both advice values; strict schema, UTF-8 and malformed fixture rejection; limits at and above boundaries, including multibyte input and supplementary characters |
| B02 | Fixture definitions resolve under `/home/alex` and `/srv/build/alex`, independent of shared-file location; unsafe input matrix, component normalization, spaces, normalized root and relative-root rejection |
| B03 | Fixture merge gives 11 paths; refresh input gives 7; `.m2` retains opposing app/advice/reason occurrences; uv retains original duplicate spelling and absent advice; identical literal reasons retain separate locations; nested paths survive; immutable defensive copies and independent source failures |
| Packaging | Classpath contents match source bytes; isolated JAR loading succeeds with 25 entries; missing/malformed packaged resources yield rejected snapshots; actual Maven JAR separately loads 25 entries and matches source bytes |

Commands/results:

- `mvn -q -Dtest=CandidateCatalogTest test`: passed.
- Final `mvn clean verify` on Temurin OpenJDK 25.0.3: **176 tests, 0 failures,
  0 errors, 0 skipped**, BUILD SUCCESS. Includes all existing D4 tests.
- Actual artifact `target/homelight-1.0-SNAPSHOT.jar` checked with a temporary
  Java source launcher using only that JAR and SnakeYAML on the classpath:
  `PASS: actual Maven jar loads 25 definitions; resource bytes match exactly`.
  An earlier JShell attempt hit sandbox socket/preferences restrictions; the
  standalone Java check completed successfully without those facilities.
- `git diff --check`: passed. Source search confirms candidate APIs remain
  isolated from setup, planner, executor and configuration publication callers.
- Existing compiler/resource warnings remain (source/target versus release,
  deprecated TUI API, properties encoding); no unrelated cleanup performed.

## Review boundary

Modified only the candidate catalog/source stub, `pom.xml`, new candidate parser,
definition/diagnostic files, resource and tests, plus the session-B specification,
source-audit/ticket-update addenda and this handoff. Existing D4, CLI/TUI, setup
fixture, next-sessions and other uncommitted work was preserved.

This is not completion of #7. Filesystem inspection/sizing, no-follow observations,
NFS/shared-reader workers and deadlines, request generations/stale snapshots,
ownership, UI, selection, persistence and executor work are excluded. Shared
contents are provided by callers; no production shared-file reader is introduced.
No PTY walkthrough is warranted for this presentation-neutral slice.

Coordinator action: review B01–B03 and this evidence. No next-slice work has begun.
The next bounded implementation, only after review/authorization, is #7b as
described in the specification. Tracker publication remains pending separately.
