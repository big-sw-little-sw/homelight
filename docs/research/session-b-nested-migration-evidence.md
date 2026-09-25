# #7 nested candidate-schema migration evidence

Date: 2026-09-23. Implemented locally, awaiting coordinator review. This is the
current B01–B03 schema evidence; prior flat-schema verification is historical.

## Explicit bundled advice follow-up

The user subsequently approved `advice: consider` for every bundled directory.
All 26 entries now contain that field explicitly. The exact path/app/reason
assertion is unchanged; the resource test now requires `CONSIDER` for every entry.
Absent advice remains supported in other inputs, with no parser default or group
inheritance. No `usually-unnecessary` exceptions were added.

Follow-up verification on Java 25: `mvn -q -Dtest=CandidateCatalogTest test`
passed (15 tests), then `mvn -q clean verify` passed (180 tests, zero failures,
errors or skips). The updated standalone packaged-JAR check passed with exact
resource bytes, all 26 entries, JBang and nested uv, and all advice `consider`.
`git diff --check` passed. Earlier no-advice assertions and output below record
the migration before this user-approved follow-up.

## Scope and inspected inputs

Read the revised candidate-list specification, fixture manifest and nested
bundled/shared/refreshed inputs, local ticket proposals, source audit and B01–B03
handoff. Inspected the parser, definition/diagnostic records, catalog merger,
bundled YAML, catalog tests and Maven resource configuration. Existing working
tree changes were inventoried before editing and preserved.

Only `CandidateParser`, `CandidateDefinition`, `CandidateDiagnostic`,
`src/main/resources/candidates.yaml`, `CandidateCatalogTest` and session-B local
documentation were changed. No catalog merge algorithm, Maven configuration,
CLI/TUI, configuration persistence or filesystem inspection changes were needed.

## Executed checks

Temurin OpenJDK 25.0.3+9-LTS:

- `mvn -q -Dtest=CandidateCatalogTest test`: passed, 15 test methods.
- `mvn clean verify`: BUILD SUCCESS; 180 tests, 0 failures, 0 errors, 0 skipped.
  Includes 15 candidate tests and the existing D4/CLI/TUI suite.
- Packaged-resource test loads isolated JAR classes/resources and checks valid,
  missing and malformed resource outcomes. Valid input yields 26 definitions.
- A temporary Java source launcher ran against only
  `target/homelight-1.0-SNAPSHOT.jar` and `snakeyaml-2.4.jar`. It required a `jar:`
  resource URL, compared packaged YAML bytes with source bytes, parsed at
  `/srv/build/alex`, checked 26 definitions with grouped structural locations,
  app/reason present and advice absent, and checked JBang plus both nested uv
  identities. Output:
  `PASS: actual Maven jar loads 26 nested definitions; resource bytes match exactly; JBang and nested uv preserved; no advice`.
- `git diff --check` and explicit whitespace checks on migrated untracked source,
  tests, resource and documentation: passed.

The existing compiler source/target warning, deprecated TUI API note and resource
properties-encoding notice remain. No unrelated warning cleanup was attempted.

## Contract evidence

| Area | Assertion |
| --- | --- |
| Bundled migration | Exact ordered comparison of all 26 pre-migration path/app/reason triples; no advice; JBang and uv parent/child preserved |
| Accepted shapes | Apps-only, ungrouped-only, mixed, empty sequences and empty groups; omitted sequences; repeated exact names and case-distinct names |
| Ordering and provenance | Apps before ungrouped regardless of YAML key order; empty groups consume no record indices; each occurrence retains structural location, line/column, original path, app, advice and reason |
| Rejection | Old directory `app`, group advice/reason, unknown/duplicate keys, missing/wrong/null fields, unsafe paths, aliases/anchors, tags, multiple documents and invalid UTF-8 reject the source atomically |
| Limits | Byte/string boundaries retained; 10,000 app groups; 10,000 directories aggregated across groups and ungrouped; collection depth 8, including nested-group overhead |
| Deduplication | Cross-group and ungrouped normalized duplicates preserve every occurrence and conflicting/omitted advice; repeated labels do not overwrite definitions |
| Adopted fixtures | Initial: 11 normalized paths, 13 occurrences. Refreshed: 7 paths, 8 occurrences. Maven retains Maven/Build tools advice; uv retains uv/Python tools and original duplicate spelling |
| Root/source isolation | Same schema under both source kinds and roots; lexical normalization; rejected sources contribute diagnostics only |

Schema/path failures report the enclosing nested structural location and key.
Source-wide and event/syntax failures before schema association can have an empty
structural location, with line/column where available. No source reads or candidate
filesystem operations were introduced.

No #7b scanning/sizing, shared-file workers, #32 integration, GitHub publication,
commit or push. Stopped for coordinator review; #7 as a whole remains incomplete.
