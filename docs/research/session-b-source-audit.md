# Session B: local source audit for #21

## Current #7b completion evidence, 2026-09-23

The subsequent authorized completion removed `CandidateScanner` and measurement
tests, replacing them with metadata-only `CandidateMetadata` and revised lifecycle
tests. All sizes are `NOT_ESTIMATED` without bytes; no directory enumeration or
emptiness probe remains. Independent sources, stale evidence, generations and
bounded serial metadata/shared reads are tested. Focused verification passed 37
tests; Java 25 `mvn -o clean verify` passed 202 tests, zero failures/errors/skips.
See [the current handoff](session-b7b-implementation-handoff.md) for interface,
acceptance mapping, limitations and the coordinator review gate. The unfinished
code statements below are historical and superseded. No new GitHub writes,
commits or pushes occurred in this completion session.

## #7b scope revision and implementation status, 2026-09-23

The user accepted metadata-only discovery after reviewing the sizing complexity.
Updated the specification, fixture manifest, local ticket drafts and next-session
contract: all sizes are `not estimated`, candidate contents are not enumerated,
and B06 measurement is deferred beyond #7b/#32. B04/B05/B15 still require typed
states, source failure isolation, stale evidence and bounded read lifecycles.
The original B01–B03 parser/resource acceptance remains unchanged.

Unfinished discovery sources/tests were added earlier in this session. They still
contain recursive sizing and an incompletely verified scheduling simplification;
they do not implement the revised contract and are not accepted evidence.
The earlier focused run passed 36 tests (15 accepted catalog tests plus 21 new
discovery tests) before that simplification. No Java 25 clean verification was
completed for #7b. Do not carry that focused result forward as verification of
the revised scope. See [the status handoff](session-b7b-implementation-handoff.md).

System-option checks read local macOS `du(1)`/`stat(1)` documentation and the
[GNU `du` manual](https://www.gnu.org/software/coreutils/manual/html_node/du-invocation.html).
Native `du` still visits the tree; its use would remove custom traversal code,
not supply a cheap directory-total metadata lookup. No size command was run on
user data. Java 27's [structured-concurrency contract](https://docs.oracle.com/en/java/javase/27/docs/api/java.base/java/util/concurrent/StructuredTaskScope.html)
still permits indefinite close delay for subtasks that do not respond to interruption;
no JDK upgrade was made. These checks do not authorize a future sizing implementation.

Documentation-only revision: checked scope references, acceptance IDs, relative
links and whitespace. No GitHub writes, commits or pushes. The audit below is
historical and predates #7b work.

Current nested-schema implementation evidence is recorded in the
[migration handoff](session-b01-b03-implementation-handoff.md) and
[verification note](session-b-nested-migration-evidence.md). All findings and
flat-schema implementation statements below are historical. The current bundled
resource has 26 preserved entries with explicit app groups and user-approved
`advice: consider` on each directory.

Implementation update, 2026-09-23: the pre-implementation findings below remain
historical evidence. B01–B03 now replaces the unused hard-coded catalog with
`src/main/resources/candidates.yaml`, a strict contents parser, lexical resolution
and immutable attributed merging. All 25 original paths/descriptions are retained;
no advice or app labels are inferred. See the
[implementation handoff](session-b01-b03-implementation-handoff.md) for tests,
entry points and deferred work. Setup/configuration publication is unchanged.

Read-only source review on 2026-09-23, including the uncommitted D4 implementation. This note supports the candidate-list specification; it does not alter or re-accept D4. No tests were run because this work changes documentation only. Findings below are based on inspected production source and test assertions, not a fresh execution of those tests.

## Current discovery and persistence

- `CandidateCatalog.defaults()` contains 25 hard-coded `~/…` paths with labels. It already includes nested candidates (`.local/share/uv` and `.local/share/uv/tools`). `CandidateSource` has only `sourcePath` and `label`. Searching all `src` for both type names found only those declarations and catalog construction, so this is currently a data stub, not an integrated discovery workflow. Sources: [CandidateCatalog.java](../../src/main/java/io/github/bigswlittlesw/homelight/config/CandidateCatalog.java#L10), [CandidateSource.java](../../src/main/java/io/github/bigswlittlesw/homelight/config/CandidateSource.java#L4).
- User configuration maps `homelight.target-root`, optional `staging-root`, explicit `relocations`, and optional `ignored-source-paths`. Relocations carry source/target and state-specific reconciliation decisions. There is no shared-list location, app association, provenance, or advice field. Those discovery concerns need their own representation; advice must not enter reconciliation policy fields. Source: [HomeLightConfiguration.java](../../src/main/java/io/github/bigswlittlesw/homelight/config/HomeLightConfiguration.java#L17).
- The loader expands `~`, `~/…`, and `${USER}`, then makes paths absolute and normalizes them. Omitted targets derive from the source's path relative to the current home; sources outside home require an explicit target. This is existing relocation configuration behavior, not a required template for the candidate schema. Source: [ConfigurationLoader.java](../../src/main/java/io/github/bigswlittlesw/homelight/config/ConfigurationLoader.java#L60).
- A new configuration draft contains target root and explicit relocations only. Publication validates, writes quoted absolute source/target paths and optional policies, and atomically creates a new destination without replacing an existing file. Saving neither copies discovery definitions nor executes relocations today. A separate shared-list setting will require an explicit mapping/draft/serialization extension. Existing-config replacement is not provided by `saveNew`; the specification must not silently assume it. Sources: [ConfigurationDraft.java](../../src/main/java/io/github/bigswlittlesw/homelight/config/ConfigurationDraft.java#L9), [ConfigurationPublisher.java](../../src/main/java/io/github/bigswlittlesw/homelight/config/ConfigurationPublisher.java#L12).

## Accepted setup constraints to preserve

- Setup has locations, table, and details modes. It defaults source root to home and lets a user choose a non-home source root. Row paths resolve relative to source/target roots into explicit absolute relocations. It rejects blank row paths, absolute row paths, root-equal results, and normalized escapes; archive roots must be absolute when present. Candidate discovery should feed this journey with relative paths and leave the existing manual path intact. Sources: [HomeLightApp.java](../../src/main/java/io/github/bigswlittlesw/homelight/tui/HomeLightApp.java#L44), [root and row resolution](../../src/main/java/io/github/bigswlittlesw/homelight/tui/HomeLightApp.java#L418).
- Save is explicit; successful creation refreshes the session and exits setup. Failure leaves setup open. Validation alone does not save. Sources: [HomeLightApp.java](../../src/main/java/io/github/bigswlittlesw/homelight/tui/HomeLightApp.java#L440), [ConfigurationPublisherTest.java](../../src/test/java/io/github/bigswlittlesw/homelight/config/ConfigurationPublisherTest.java#L17).
- D4 final local acceptance explicitly supersedes historical pending-acceptance sections and requires #21 completion/review before #32 implementation. Source: [D4 handoff](session-d4-implementation-handoff.md#final-local-coordinator-acceptance-2026-09-23).

## Safety and integration gaps

- Draft validation requires at least one relocation and rejects source/target overlap, duplicate targets, and every pairwise ancestry intersection across relocation sources and targets. It also requires an archive root for archive-source. Therefore nested candidates may coexist in discovery, but selecting both cannot produce a valid saved relocation set; the UI needs an explicit conflict instead of silently deselecting one. Source: [ConfigurationValidator.java](../../src/main/java/io/github/bigswlittlesw/homelight/config/ConfigurationValidator.java#L12). Relevant assertions: [ConfigurationPublisherTest.java](../../src/test/java/io/github/bigswlittlesw/homelight/config/ConfigurationPublisherTest.java#L34).
- These overlap checks are lexical normalization checks, not symlink containment checks. The setup helper likewise does not inspect intermediate path components. Candidate unsafe-path and symlink rules therefore need to be stated explicitly instead of described as already enforced. Sources: [ConfigurationValidator.java](../../src/main/java/io/github/bigswlittlesw/homelight/config/ConfigurationValidator.java#L38), [HomeLightApp.java](../../src/main/java/io/github/bigswlittlesw/homelight/tui/HomeLightApp.java#L423).
- `PathInspector` distinguishes a final symlink from a real directory using `NOFOLLOW_LINKS`, reads its target, and probes target availability. Directory inspection opens the directory to test emptiness. These synchronous calls have no timeout/isolation boundary and are not a ready-made bounded NFS discovery mechanism. `NOFOLLOW_LINKS` on final attributes does not itself reject intermediate symlink traversal. Source: [PathInspector.java](../../src/main/java/io/github/bigswlittlesw/homelight/fs/PathInspector.java#L13).
- Existing tests inspect create/reload-without-apply, invalid overlaps, failed writes, existing malformed destinations, concurrent creation, and cancel-without-write. Candidate acceptance should extend these contracts with load failure isolation, refresh selection retention, provenance/advice merging, root changes, and nested-selection validation rather than replacing the manual setup tests. Source: [ConfigurationPublisherTest.java](../../src/test/java/io/github/bigswlittlesw/homelight/config/ConfigurationPublisherTest.java#L17).

## Recommendations for the main specification

Keep discovery entries separate from `Relocation`. Deduplicate by normalized resolved source path while retaining all contributions. Preserve saved and manual relocation objects verbatim during view merges. Treat candidate selection as an explicit draft action with target review, and save only explicit relocations plus the independently named discovery setting. Preserve create-only D4 publication; identify existing-config setting updates as a separately bounded persistence decision. Specify candidate syntax and filesystem rejection independently of the current permissive relocation loader, with no shell/environment expression evaluation. Test unavailable input through a controllable reader boundary and require the UI to remain usable even when a filesystem operation cannot promptly return.
