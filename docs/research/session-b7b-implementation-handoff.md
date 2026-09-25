# #7b metadata-only implementation handoff

## Local coordinator acceptance, 2026-09-23

The revised metadata-only B04/B05/B15 slice is accepted locally. Independent
standards and specification reviews found no actionable findings. Independently
ran Java 25 `mvn -o -q clean verify`: **202 tests passed**, zero failures, errors
or skips; `git diff --check` passed. Inspected the metadata filesystem seam,
process-wide worker permits, deadlines, generation checks, stale evidence and
controlled blocked-reader/child-JVM exit tests.

This acceptance does not assert real-NFS fault behavior, atomic path containment,
ownership evaluation or size measurement. The limitations below remain part of
the contract. B06 remains deferred, and #7 as a whole is not complete.

Next proposed slice: #32a, presentation-neutral draft joining and independent
shared-list-setting persistence, preserving explicit selection and create-only
save. It requires separate authorization; #32b UI integration follows its review.
No production changes, GitHub publication, commit or push by this coordinator
review. Earlier pending-review instructions below are historical for this slice.

Date: 2026-09-23. Status: implemented and verified locally, awaiting coordinator
review. Baseline HEAD: `fcb343bee5cb1405f32086c7ce7df87a4afe25f9`.
No commit, push or GitHub publication in this completion session. Existing
uncommitted B01–B03/D4 and unrelated work was preserved.

## Accepted contract and scope

The user removed automatic sizing, then authorized finishing #7b under the
metadata-only contract. B04/B05/B15 are implemented at the discovery interface.
Every candidate reports `NOT_ESTIMATED` and an empty byte value, including pending,
missing, inaccessible, regular-file, link and directory observations. Ownership
remains `NOT_EVALUATED`. B06 measurement is deferred, not passed.

Removed the unfinished recursive scanner and its measurement tests. No directory
enumeration, emptiness check, hard-link accounting, filesystem-boundary traversal,
native size command, copy/move or other mutation is performed by discovery.
The accepted nested parser/resource and catalog tests were not edited.

No #32 integration, TUI, selection/persistence, executor, JDK upgrade or relocation
work was done. Java 27 structured concurrency remains an execution decision for
#10, not part of this Java 25 discovery implementation.

## Implementation and interface

- [CandidateDiscovery](../../src/main/java/io/github/bigswlittlesw/homelight/discovery/CandidateDiscovery.java)
  exposes `refresh(root, optionalSharedLocation)`, `snapshot()`, `cancel()` and
  `close()`. These calls perform no filesystem I/O. Background completions advance
  serial metadata inspection; calling `snapshot` is not required to drive work.
  Results and nested collections are immutable and presentation-neutral.
- [CandidateMetadata](../../src/main/java/io/github/bigswlittlesw/homelight/discovery/CandidateMetadata.java)
  uses only root real-path resolution, no-follow attributes and raw symbolic-link
  text. Its filesystem seam has no enumeration or content-reading operation.
  The explicitly selected root can be an alias. Descendant components are checked
  before proceeding; intermediate links/non-directories block inspection.
  Guard rechecks detect changed evidence. Directories, leaf links, regular files,
  missing paths, inaccessible paths and other file kinds remain distinct.
- [CandidateObservation](../../src/main/java/io/github/bigswlittlesw/homelight/discovery/CandidateObservation.java)
  retains lexical path, kind, raw link text, generation, observation time,
  freshness and typed diagnostics. Size cannot contain a byte value. Link
  destinations are not probed, so leaf-link target availability is explicitly unknown.
- Catalog merge preserves every definition occurrence and source identity. The
  result carries root/list request identity, source outcomes and ancestor edges
  among catalog candidates. Relationships are computed lexically, not by walking
  directories. No aggregate size or ownership inference is supplied.

Bundled input, optional shared input and metadata have separate process-wide
limits: at most one outstanding operation of each kind, including across session
closure/reopening. Candidate metadata runs serially. Pending paths are bounded
catalog data, not an executor queue of submitted filesystem tasks. The small
private worker helper is confined to discovery; there is no generic scheduler.

Source reads and each root/candidate metadata inspection use a five-second response
deadline. This preserves the specified shared-read deadline and uses the same
bound for metadata. The shared reader reads at most 1 MiB plus one detection byte,
then passes contents to the unchanged strict parser. The explicitly chosen shared
location may be a linked regular file; non-regular inputs are rejected.

Snapshots apply elapsed deadlines when read, and completion timestamps reject late
results even without a snapshot call at the deadline. There is no timer, watcher or
automatic source reread. A deadline detaches evidence but does not release the
operation's permit. Permits are released only when the worker actually returns.
Repeated refresh or reopened sessions report occupied capacity instead of spawning
replacement blocked workers. Daemon workers are not joined on close.

Refresh preserves prior evidence as stale only for an unchanged root AND shared
location. Source failures retain provenance and typed failure details. Metadata
failure retains earlier state/time/generation as stale with the new diagnostic.
Successful missing-path observations are current evidence, not failed refreshes.
Root/location changes, cancellation and closure discard the old generation;
obsolete completions cannot reattach data. A successful empty source removes its
discovery-only entries.

## Verification

Temurin OpenJDK 25.0.3, macOS arm64:

- `mvn -o -q -Dtest=CandidateMetadataTest,CandidateDiscoveryTest,CandidateCatalogTest test`:
  **37 tests passed**: 6 metadata, 16 discovery lifecycle, 15 accepted catalog tests.
- `mvn -o clean verify`: **202 tests passed, 0 failures, 0 errors, 0 skips**,
  BUILD SUCCESS. This includes the final production changes and creates the Maven JAR.
- `git diff --check`: passed. Documentation relative links and whitespace checked.
- Source spot-check: discovery has no `Files.walk`, `Files.list`,
  `newDirectoryStream`, `getFileStore`, byte-size read or recursive scanner.
  Only the shared source reader opens file contents.
- Existing compiler/resource notices remain: source/target versus release,
  deprecated TUI API and filtered-properties encoding. No unrelated cleanup.

| Acceptance | Current evidence |
| --- | --- |
| B04 | Actual temporary shared files: missing, directory, malformed YAML, oversize and linked regular input; controlled access denial and failure after partial input; independent bundled failure/stall and shared success; retained stale source definitions on malformed/timeout refresh; successful empty-source removal |
| B05 | Temporary roots: empty/nonempty and nested directories, regular file, missing path, root alias, live/broken leaf links, inside/outside intermediate links and non-directory blockers; controlled permission/I/O errors, intermediate replacement and root-anchor change; allowed-path guards reject descendant/target probes; no size values for any state; provenance, lexical identities and catalog ancestor relationships |
| B15 | Controlled non-interruptible readers and monotonic fake clock: deadlines, late completion without an earlier poll, repeated refresh, capacity retained until return, close/reopen bounds, obsolete root/location/cancel/close results and stale metadata after timeout; serial progress without snapshot-driven scheduling; independent sources despite blocked metadata; child JVM exits with both metadata and shared reads still blocked |

Tests use temporary filesystem fixtures, controlled reader seams and latches.
No real home contents or NFS mount were accessed. No UI walkthrough is appropriate
for this presentation-neutral boundary; actual manual Add/Save and TUI journeys
remain #32.

## Limitations and remaining gaps

No known unmet requirement within revised metadata-only B04/B05/B15 after these
checks; coordinator review is still required. This is not full #7 completion.

- Java path-based no-follow checks and identity rechecks are best-effort under
  concurrent replacement, not atomic containment or an execution-safety proof.
  Missing stable file keys and root aliases carry alias-uncertainty diagnostics.
  Physical alias/case/hard-link deduplication is not inferred.
- Live and broken leaf links both report target availability unknown: discovery
  deliberately does not probe their destinations. Raw link text is retained.
- A kernel-blocked call may outlive timeout, cancellation and close. A blocked
  metadata operation prevents later metadata work in the serial pass; those rows
  remain pending/unknown while catalog/source results and control methods remain
  usable. Capacity failures on a new request require explicit refresh after the
  old operation returns. No forced kernel cancellation or real-NFS behavior is claimed.
- Nonblocking close and JVM exit are verified with controlled blocked readers.
  Platform-specific OS/JVM teardown behavior under real NFS faults is not exercised.
- Results become visible when the caller obtains a snapshot; there is no UI timer
  or callback integration. Metadata and source freshness are conservative.
- B06 measurement, ownership evidence from #5 and all #32 UI/draft/persistence
  journeys remain outside this slice.

Next step: coordinator review of this implementation and its evidence. Do not
begin #32, native sizing, executor work or a Java upgrade in this session.

## Historical scope revision and publication

The initial recursive-sizing attempt was paused at the user's direction.
Its earlier 36-test focused result preceded an unverified scheduling change and
did not verify the revised contract. Those implementation gaps are resolved by
the metadata-only code and current clean verification above.

At the user's earlier explicit request, #7/#21/#32 were updated to metadata-only
discovery with deferred B06, and #10 to Java 27 structured concurrency directly
within bounded execution implementation. #7/#10 titles were updated. No separate
API evaluation/prototype ticket is required; limits, copy/publication guarantees,
progress and cancellation/shutdown tests belong in execution implementation.
States, labels and dependencies were unchanged. That publication preceded this
completion session; there were no new GitHub writes, commits or pushes here.
