package io.github.bigswlittlesw.homelight.discovery;

import io.github.bigswlittlesw.homelight.config.CandidateCatalog;
import io.github.bigswlittlesw.homelight.config.CandidateDiagnostic;
import io.github.bigswlittlesw.homelight.config.CandidateParser;
import io.github.bigswlittlesw.homelight.config.CandidateSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import static io.github.bigswlittlesw.homelight.discovery.CandidateDiscovery.*;
import static io.github.bigswlittlesw.homelight.discovery.CandidateObservation.*;
import static org.junit.jupiter.api.Assertions.*;

class CandidateDiscoveryTest {
    @TempDir Path temporary;

    @Test void independentlyLoadsRealSharedFileAndRetainsProvenanceAndOverlap() throws Exception {
        var shared = temporary.resolve("shared.yaml");
        Files.copy(Path.of("docs/research/session-b-fixtures/nested/shared.yaml"), shared);
        Files.createDirectories(temporary.resolve(".local/share/uv/tools"));
        try (var discovery = new CandidateDiscovery()) {
            discovery.refresh(temporary, Optional.of(shared));
            var result = awaitResult(discovery, CandidateDiscoveryTest::finished);
            assertTrue(result.sources().stream().allMatch(s -> s.status() == SourceStatus.CURRENT));
            var child = row(result, temporary.resolve(".local/share/uv/tools"));
            assertEquals(List.of(temporary.resolve(".local/share/uv")), child.ancestors());
            assertEquals(Size.NOT_ESTIMATED, child.observation().size());
            assertTrue(child.observation().size().bytes().isEmpty());
            var maven = row(result, temporary.resolve(".m2"));
            assertEquals(2, maven.catalog().definitions().size());
            assertEquals(shared.toString(), maven.catalog().definitions().get(1).source().location());
            assertThrows(UnsupportedOperationException.class, () -> result.candidates().clear());
            assertThrows(UnsupportedOperationException.class, () -> child.ancestors().clear());
            assertEquals(Ownership.NOT_EVALUATED, child.observation().ownership());
        }
    }

    @Test void missingWrongKindMalformedOversizeAndLinkedSharedInputs() throws Exception {
        var shared = temporary.resolve("shared.yaml");
        try (var discovery = new CandidateDiscovery()) {
            discovery.refresh(temporary, Optional.of(shared));
            var result = awaitResult(discovery, CandidateDiscoveryTest::finished);
            assertProblem(result, SourceProblem.Kind.MISSING);
            assertFalse(result.candidates().isEmpty());
            discovery.refresh(temporary, Optional.of(temporary));
            assertProblem(awaitResult(discovery, CandidateDiscoveryTest::finished), SourceProblem.Kind.NOT_REGULAR);
            Files.writeString(shared, "directories: [");
            discovery.refresh(temporary, Optional.of(shared));
            var malformed = awaitResult(discovery, CandidateDiscoveryTest::finished);
            assertEquals(CandidateDiagnostic.Kind.SYNTAX, shared(malformed).diagnostics().getFirst().kind());
            Files.write(shared, new byte[CandidateParser.MAX_BYTES + 1]);
            discovery.refresh(temporary, Optional.of(shared));
            assertEquals(CandidateDiagnostic.Kind.LIMIT, shared(awaitResult(discovery,
                    CandidateDiscoveryTest::finished)).diagnostics().getFirst().kind());
            Files.writeString(shared, "directories: [{path: team-cache}]");
            var link = Files.createSymbolicLink(temporary.resolve("shared-link"), shared);
            discovery.refresh(temporary, Optional.of(link));
            var linked = awaitResult(discovery, CandidateDiscoveryTest::finished);
            assertEquals(SourceStatus.CURRENT, shared(linked).status());
            assertEquals(link.toString(), shared(linked).catalog().orElseThrow().source().location());
            discovery.refresh(temporary, Optional.empty());
            var cleared = awaitResult(discovery, CandidateDiscoveryTest::finished);
            assertEquals(1, cleared.sources().size());
            assertFalse(cleared.candidates().stream().anyMatch(c -> c.catalog().sourcePath().endsWith("team-cache")));
        }
    }

    @Test void sourceFailureRetainsStaleDefinitionsAndObservationsOnlyForSameRequest() throws Exception {
        var lanes = new Lanes();
        var input = new AtomicReference<>(bytes("directories: [{path: team-cache}]"));
        Files.createDirectory(temporary.resolve("team-cache"));
        var location = temporary.resolve("shared");
        try (var discovery = discovery(lanes, new AtomicLong(), ignored -> input.get(), "cache", new CandidateMetadata())) {
            long original = discovery.refresh(temporary, Optional.of(location));
            var first = awaitResult(discovery, CandidateDiscoveryTest::finished);
            var originalRow = row(first, temporary.resolve("team-cache"));
            await(() -> lanes.shared.availablePermits() == 1);
            input.set(bytes("directories: ["));
            discovery.refresh(temporary, Optional.of(location));
            var failed = awaitResult(discovery, CandidateDiscoveryTest::finished);
            assertEquals(SourceStatus.STALE, shared(failed).status());
            var retained = row(failed, temporary.resolve("team-cache"));
            assertTrue(retained.observation().stale());
            assertEquals(original, retained.observation().generation());
            assertEquals(originalRow.catalog(), retained.catalog());
            assertEquals(Size.NOT_ESTIMATED, retained.observation().size());
            discovery.refresh(temporary, Optional.of(temporary.resolve("other-location")));
            var changed = awaitResult(discovery, CandidateDiscoveryTest::finished);
            assertEquals(SourceStatus.FAILED, shared(changed).status());
            assertFalse(changed.candidates().stream().anyMatch(c -> c.catalog().sourcePath().endsWith("team-cache")));
        }
    }

    @Test void unreadableAndMidReadFailureRejectSourceWithoutHidingBundled() {
        for (boolean denied : List.of(true, false)) {
            try (var discovery = discovery(new Lanes(), new AtomicLong(), path -> {
                if (denied) throw new AccessDeniedException(path.toString());
                // Simulates a reader failing after receiving a prefix: no prefix is returned for parsing.
                throw new IOException("Read failed after partial bytes");
            }, "cache", new CandidateMetadata())) {
                discovery.refresh(temporary, Optional.of(temporary.resolve("shared")));
                var result = awaitResult(discovery, CandidateDiscoveryTest::finished);
                assertProblem(result, denied ? SourceProblem.Kind.UNREADABLE : SourceProblem.Kind.IO_ERROR);
                assertEquals(1, result.candidates().size());
            }
        }
    }

    @Test void sharedDeadlineRepeatedRefreshAndShutdownDoNotWaitOrReleaseCapacity() throws Exception {
        var gate = new Gate();
        var lanes = new Lanes();
        var clock = new AtomicLong();
        var calls = new AtomicInteger();
        var worker = new AtomicReference<Thread>();
        var discovery = discovery(lanes, clock, path -> {
            calls.incrementAndGet();
            worker.set(Thread.currentThread());
            gate.block();
            return bytes("directories: [{path: late}]");
        }, "cache", new CandidateMetadata());
        try {
            assertTimeout(Duration.ofMillis(500), () -> discovery.refresh(temporary,
                    Optional.of(temporary.resolve("shared"))));
            assertTrue(gate.entered.await(3, TimeUnit.SECONDS));
            awaitResult(discovery, r -> !r.candidates().isEmpty()
                    && r.candidates().getFirst().observation().kind() == Kind.MISSING);
            worker.get().interrupt();
            clock.set(5_000_000_000L);
            assertProblem(discovery.snapshot(), SourceProblem.Kind.DEADLINE);
            for (int i = 0; i < 20; i++) {
                assertTimeout(Duration.ofMillis(500), () -> discovery.refresh(temporary,
                        Optional.of(temporary.resolve("shared"))));
                assertProblem(discovery.snapshot(), SourceProblem.Kind.PREVIOUS_PENDING);
            }
            assertEquals(1, calls.get());
            assertEquals(0, lanes.shared.availablePermits());
            assertTimeout(Duration.ofMillis(500), discovery::close);
            assertTrue(discovery.snapshot().candidates().isEmpty());
            // Reopening a session with the same process lanes cannot replace a stuck worker.
            try (var reopened = discovery(lanes, clock, path -> { fail("Extra read"); return new byte[0]; },
                    "cache", new CandidateMetadata())) {
                reopened.refresh(temporary, Optional.of(temporary.resolve("shared")));
                assertProblem(reopened.snapshot(), SourceProblem.Kind.PREVIOUS_PENDING);
            }
        } finally {
            discovery.close();
            gate.release.countDown();
            await(() -> lanes.shared.availablePermits() == 1 && lanes.filesystem.availablePermits() == 1
                    && lanes.bundled.availablePermits() == 1);
        }
        assertTrue(discovery.snapshot().sources().isEmpty());
        assertThrows(IllegalStateException.class, () -> discovery.refresh(temporary, Optional.empty()));
    }

    @Test void lateCompletionBeyondDeadlineRejectedEvenWithoutEarlierPoll() throws Exception {
        var gate = new Gate();
        var clock = new AtomicLong();
        var lanes = new Lanes();
        try (var discovery = discovery(lanes, clock, path -> {
            gate.block();
            return bytes("directories: [{path: late}]");
        }, "cache", new CandidateMetadata())) {
            discovery.refresh(temporary, Optional.of(temporary.resolve("shared")));
            assertTrue(gate.entered.await(3, TimeUnit.SECONDS));
            clock.set(5_000_000_001L);
            gate.release.countDown();
            await(() -> lanes.shared.availablePermits() == 1);
            var result = discovery.snapshot();
            assertProblem(result, SourceProblem.Kind.DEADLINE);
            assertTrue(shared(result).catalog().isEmpty());
        } finally { gate.release.countDown(); }
    }

    @Test void rootAndLocationChangeAndCancellationIgnoreObsoleteSourceResults() throws Exception {
        for (boolean cancel : List.of(false, true)) {
            var gate = new Gate();
            var lanes = new Lanes();
            var other = Files.createTempDirectory(temporary, "other");
            try (var discovery = discovery(lanes, new AtomicLong(), path -> {
                gate.block();
                return bytes("directories: [{path: obsolete}]");
            }, "cache", new CandidateMetadata())) {
                long first = discovery.refresh(temporary, Optional.of(temporary.resolve("shared")));
                assertTrue(gate.entered.await(3, TimeUnit.SECONDS));
                if (cancel) discovery.cancel();
                else discovery.refresh(other, Optional.of(other.resolve("new-location")));
                gate.release.countDown();
                await(() -> lanes.shared.availablePermits() == 1);
                var result = discovery.snapshot();
                assertTrue(result.generation() > first);
                assertFalse(result.candidates().stream().anyMatch(c -> c.catalog().sourcePath().endsWith("obsolete")));
                if (cancel) {
                    assertTrue(result.request().isEmpty());
                    assertTrue(result.sources().isEmpty());
                } else {
                    assertEquals(other, result.request().orElseThrow().root());
                    assertProblem(result, SourceProblem.Kind.PREVIOUS_PENDING);
                }
            } finally { gate.release.countDown(); }
        }
    }

    @Test void failedCandidateRefreshRetainsOldStateMarkedStaleWithNewDiagnostic() throws Exception {
        Files.createDirectory(temporary.resolve("cache"));
        Files.write(temporary.resolve("cache/a"), new byte[17]);
        var fail = new AtomicInteger();
        var lanes = new Lanes();
        var metadata = new CandidateMetadata(new CandidateMetadata.Access() {
            @Override public BasicFileAttributes attributes(Path path) throws IOException {
                if (path.endsWith("cache") && fail.get() == 1) throw new AccessDeniedException(path.toString());
                return super.attributes(path);
            }
        });
        try (var discovery = discovery(lanes, new AtomicLong(), path -> bytes("directories: []"), "cache", metadata)) {
            long first = discovery.refresh(temporary, Optional.empty());
            awaitResult(discovery, CandidateDiscoveryTest::finished);
            await(() -> lanes.filesystem.availablePermits() == 1 && lanes.bundled.availablePermits() == 1);
            fail.set(1);
            discovery.refresh(temporary, Optional.empty());
            var result = awaitResult(discovery, r -> !r.candidates().isEmpty() && r.candidates().getFirst()
                    .observation().diagnostics().stream().anyMatch(d -> d.reason() == Reason.ACCESS_DENIED));
            var retained = result.candidates().getFirst().observation();
            assertTrue(retained.stale());
            assertEquals(Kind.DIRECTORY, retained.kind());
            assertTrue(retained.size().bytes().isEmpty());
            assertEquals(first, retained.generation());
        }
    }

    @Test void filesystemWorkStaysBoundedAcrossTimeoutRefreshAndClose() throws Exception {
        var gate = new Gate();
        var entered = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var lanes = new Lanes();
        var clock = new AtomicLong();
        for (int i = 0; i < 6; i++) Files.createDirectory(temporary.resolve("cache" + i));
        var metadata = new CandidateMetadata(new CandidateMetadata.Access() {
            @Override public BasicFileAttributes attributes(Path path) throws IOException {
                if (path.getFileName().toString().startsWith("cache")) {
                    calls.incrementAndGet();
                    entered.countDown();
                    gate.block();
                }
                return super.attributes(path);
            }
        });
        var discovery = discovery(lanes, clock, path -> bytes("directories: []"),
                "cache0,cache1,cache2,cache3,cache4,cache5", metadata);
        try {
            discovery.refresh(temporary, Optional.empty());
            awaitResult(discovery, r -> r.candidates().size() == 6 && lanes.filesystem.availablePermits() == 0);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            clock.set(CandidateDiscovery.METADATA_NANOS);
            var timed = discovery.snapshot();
            assertEquals(1, timed.candidates().stream().filter(c -> c.observation().kind() == Kind.UNKNOWN).count());
            assertEquals(5, timed.candidates().stream().filter(c -> c.observation().kind() == Kind.PENDING).count());
            for (int i = 0; i < 20; i++) {
                discovery.refresh(temporary, Optional.empty());
                assertEquals(Reason.CAPACITY, discovery.snapshot().rootFailure().orElseThrow().reason());
            }
            assertEquals(1, calls.get());
            assertTimeout(Duration.ofMillis(500), discovery::close);
            assertEquals(0, lanes.filesystem.availablePermits());
            try (var reopened = discovery(lanes, clock, p -> bytes("directories: []"),
                    "cache0", new CandidateMetadata())) {
                reopened.refresh(temporary, Optional.empty());
                assertEquals(Reason.CAPACITY, reopened.snapshot().rootFailure().orElseThrow().reason());
                assertEquals(1, calls.get());
            }
        } finally {
            discovery.close();
            gate.release.countDown();
            await(() -> lanes.filesystem.availablePermits() == 1 && lanes.bundled.availablePermits() == 1);
        }
        assertTrue(discovery.snapshot().candidates().isEmpty());
    }

    @Test void serialMetadataPassCompletesWithoutSnapshotDrivingWork() throws Exception {
        for (int i = 0; i < 8; i++) Files.createDirectory(temporary.resolve("cache" + i));
        var last = new CountDownLatch(1);
        var metadata = new CandidateMetadata(new CandidateMetadata.Access() {
            @Override public BasicFileAttributes attributes(Path path) throws IOException {
                if (path.endsWith("cache7")) last.countDown();
                return super.attributes(path);
            }
        });
        try (var discovery = discovery(new Lanes(), new AtomicLong(), path -> bytes("directories: []"),
                "cache0,cache1,cache2,cache3,cache4,cache5,cache6,cache7", metadata)) {
            discovery.refresh(temporary, Optional.empty());
            assertTrue(last.await(3, TimeUnit.SECONDS), "Work depended on snapshot polling");
            var result = awaitResult(discovery, CandidateDiscoveryTest::finished);
            assertEquals(8, result.candidates().size());
            assertTrue(result.candidates().stream().allMatch(c -> c.observation().kind() == Kind.DIRECTORY));
        }
    }

    @Test void obsoleteFilesystemCompletionCannotAttachToNewRoot() throws Exception {
        var gate = new Gate();
        var lanes = new Lanes();
        var firstRoot = Files.createDirectory(temporary.resolve("first"));
        var physicalFirstRoot = firstRoot.toRealPath();
        var secondRoot = Files.createDirectory(temporary.resolve("second"));
        Files.createDirectory(firstRoot.resolve("cache"));
        var metadata = new CandidateMetadata(new CandidateMetadata.Access() {
            @Override public BasicFileAttributes attributes(Path path) throws IOException {
                if (path.equals(physicalFirstRoot.resolve("cache"))) gate.block();
                return super.attributes(path);
            }
        });
        try (var discovery = discovery(lanes, new AtomicLong(), path -> bytes("directories: []"), "cache", metadata)) {
            discovery.refresh(firstRoot, Optional.empty());
            awaitResult(discovery, r -> gate.entered.getCount() == 0);
            long generation = discovery.refresh(secondRoot, Optional.empty());
            var second = awaitResult(discovery, CandidateDiscoveryTest::finished);
            assertEquals(Kind.UNKNOWN, second.candidates().getFirst().observation().kind());
            gate.release.countDown();
            await(() -> lanes.filesystem.availablePermits() == 1);
            var result = discovery.snapshot();
            assertEquals(generation, result.candidates().getFirst().observation().generation());
            assertEquals(secondRoot.resolve("cache"), result.candidates().getFirst().observation().path());
            assertEquals(Kind.UNKNOWN, result.candidates().getFirst().observation().kind());
        } finally { gate.release.countDown(); }
    }

    @Test void rootProbeDeadlineDoesNotBlockSharedParsingOrClose() throws Exception {
        var gate = new Gate();
        var clock = new AtomicLong();
        var lanes = new Lanes();
        var metadata = new CandidateMetadata(new CandidateMetadata.Access() {
            @Override public Path realPath(Path path) throws IOException {
                gate.block();
                return super.realPath(path);
            }
        });
        try (var discovery = discovery(lanes, clock, p -> bytes("directories: [{path: team}]"), "cache", metadata)) {
            discovery.refresh(temporary, Optional.of(temporary.resolve("shared")));
            assertTrue(gate.entered.await(3, TimeUnit.SECONDS));
            awaitResult(discovery, r -> shared(r).status() == SourceStatus.CURRENT);
            clock.set(CandidateDiscovery.METADATA_NANOS);
            var result = discovery.snapshot();
            assertEquals(Reason.DEADLINE, result.rootFailure().orElseThrow().reason());
            assertTrue(result.candidates().stream().allMatch(c -> c.observation().size().bytes().isEmpty()));
            assertTimeout(Duration.ofMillis(500), discovery::close);
        } finally {
            gate.release.countDown();
            await(() -> lanes.filesystem.availablePermits() == 1);
        }
    }

    @Test void blockedWorkerDoesNotKeepJvmAlive() throws Exception {
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), ExitProbe.class.getName(), temporary.toString())
                .redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Daemon discovery worker kept JVM alive");
            assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    public static final class ExitProbe {
        public static void main(String[] args) throws Exception {
            var gate = new Gate();
            var metadataGate = new Gate();
            var metadata = new CandidateMetadata(new CandidateMetadata.Access() {
                @Override public Path realPath(Path path) throws IOException {
                    metadataGate.block();
                    return super.realPath(path);
                }
            });
            try (var discovery = discovery(new Lanes(), new AtomicLong(), path -> {
                gate.block();
                return bytes("directories: []");
            }, "cache", metadata)) {
                var root = Path.of(args[0]);
                discovery.refresh(root, Optional.of(root.resolve("shared")));
                if (!gate.entered.await(3, TimeUnit.SECONDS)) throw new AssertionError("Reader did not start");
                if (!metadataGate.entered.await(3, TimeUnit.SECONDS)) throw new AssertionError("Metadata did not start");
            }
        }
    }

    @Test void sharedResultsProceedWhenBundledSourceStallsOrFails() throws Exception {
        var gate = new Gate();
        var clock = new AtomicLong();
        var lanes = new Lanes();
        Files.createDirectory(temporary.resolve("team"));
        try (var discovery = new CandidateDiscovery(lanes, clock::get,
                p -> bytes("directories: [{path: team}]"), root -> {
                    gate.block();
                    return new CandidateParser().parse(CandidateCatalog.BUNDLED, root, bytes("directories: []"));
                }, new CandidateMetadata())) {
            discovery.refresh(temporary, Optional.of(temporary.resolve("shared")));
            assertTrue(gate.entered.await(3, TimeUnit.SECONDS));
            var result = awaitResult(discovery, r -> !r.candidates().isEmpty()
                    && r.candidates().getFirst().observation().kind() == Kind.DIRECTORY);
            assertEquals(SourceStatus.PENDING, result.sources().getFirst().status());
            assertEquals(SourceStatus.CURRENT, shared(result).status());
            clock.set(5_000_000_000L);
            assertEquals(SourceProblem.Kind.DEADLINE, discovery.snapshot().sources().getFirst().problems().getFirst().kind());
        } finally {
            gate.release.countDown();
            await(() -> lanes.bundled.availablePermits() == 1);
        }
        try (var discovery = new CandidateDiscovery(new Lanes(), System::nanoTime,
                p -> bytes("directories: [{path: team}]"), root -> new CandidateCatalog.Snapshot(
                CandidateCatalog.BUNDLED, root, List.of(), List.of(new CandidateDiagnostic(
                CandidateCatalog.BUNDLED, CandidateDiagnostic.Kind.RESOURCE, 0, 0, 0, "", "",
                "Controlled packaging failure"))),
                new CandidateMetadata())) {
            discovery.refresh(temporary, Optional.of(temporary.resolve("shared")));
            var result = awaitResult(discovery, CandidateDiscoveryTest::finished);
            assertEquals(SourceStatus.FAILED, result.sources().getFirst().status());
            assertEquals(SourceStatus.CURRENT, shared(result).status());
            assertEquals(Kind.DIRECTORY, result.candidates().getFirst().observation().kind());
        }
    }

    @Test void lateMetadataCompletionIsUnknownAndCannotOverwriteRetainedState() throws Exception {
        var gate = new Gate();
        var clock = new AtomicLong();
        var lanes = new Lanes();
        var block = new AtomicInteger();
        Files.createDirectory(temporary.resolve("cache"));
        var metadata = new CandidateMetadata(new CandidateMetadata.Access() {
            @Override public BasicFileAttributes attributes(Path path) throws IOException {
                if (path.endsWith("cache") && block.get() == 1) gate.block();
                return super.attributes(path);
            }
        });
        try (var discovery = discovery(lanes, clock, p -> bytes("directories: []"), "cache", metadata)) {
            long original = discovery.refresh(temporary, Optional.empty());
            awaitResult(discovery, CandidateDiscoveryTest::finished);
            block.set(1);
            discovery.refresh(temporary, Optional.empty());
            assertTrue(gate.entered.await(3, TimeUnit.SECONDS));
            clock.set(METADATA_NANOS);
            // No snapshot until the late completion has returned.
            gate.release.countDown();
            await(() -> lanes.filesystem.availablePermits() == 1);
            var observation = discovery.snapshot().candidates().getFirst().observation();
            assertEquals(Kind.DIRECTORY, observation.kind());
            assertTrue(observation.stale());
            assertEquals(original, observation.generation());
            assertTrue(observation.diagnostics().stream().anyMatch(d -> d.reason() == Reason.DEADLINE));
            assertTrue(observation.size().bytes().isEmpty());
        } finally { gate.release.countDown(); }
    }

    @Test void sharedTimeoutRetainsStaleSnapshotAndSuccessfulRefreshRemovesOldEntries() throws Exception {
        var gate = new Gate();
        var lanes = new Lanes();
        var clock = new AtomicLong();
        var contents = new AtomicReference<>(bytes("directories: [{path: team}]"));
        var block = new AtomicInteger();
        try (var discovery = discovery(lanes, clock, p -> {
            if (block.get() == 1) gate.block();
            return contents.get();
        }, "cache", new CandidateMetadata())) {
            var location = Optional.of(temporary.resolve("shared"));
            long original = discovery.refresh(temporary, location);
            awaitResult(discovery, CandidateDiscoveryTest::finished);
            block.set(1);
            discovery.refresh(temporary, location);
            assertTrue(gate.entered.await(3, TimeUnit.SECONDS));
            clock.set(5_000_000_000L);
            var timed = discovery.snapshot();
            assertEquals(SourceStatus.STALE, shared(timed).status());
            var old = row(timed, temporary.resolve("team"));
            assertTrue(old.observation().stale());
            assertEquals(original, old.observation().generation());
            gate.release.countDown();
            await(() -> lanes.shared.availablePermits() == 1 && lanes.filesystem.availablePermits() == 1);
            block.set(0);
            contents.set(bytes("directories: []"));
            discovery.refresh(temporary, location);
            var refreshed = awaitResult(discovery, CandidateDiscoveryTest::finished);
            assertEquals(SourceStatus.CURRENT, shared(refreshed).status());
            assertTrue(refreshed.candidates().stream().noneMatch(c -> c.catalog().sourcePath().endsWith("team")));
        } finally { gate.release.countDown(); }
    }

    private static CandidateDiscovery discovery(Lanes lanes, AtomicLong clock, SharedReader reader,
                                                String paths, CandidateMetadata metadata) {
        var yaml = new StringBuilder("directories:\n");
        for (var path : paths.split(",")) yaml.append("  - path: ").append(path).append('\n');
        return new CandidateDiscovery(lanes, clock::get, reader,
                root -> new CandidateParser().parse(CandidateCatalog.BUNDLED, root, bytes(yaml.toString())), metadata);
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static SourceOutcome shared(Result result) {
        return result.sources().stream().filter(s -> s.source().kind() == CandidateSource.Kind.SHARED)
                .findFirst().orElseThrow();
    }
    private static Candidate row(Result result, Path path) {
        return result.candidates().stream().filter(c -> c.catalog().sourcePath().equals(path)).findFirst().orElseThrow();
    }
    private static void assertProblem(Result result, SourceProblem.Kind kind) {
        assertEquals(kind, shared(result).problems().getFirst().kind(), result.toString());
    }
    private static boolean finished(Result result) {
        return !result.sources().isEmpty() && result.sources().stream().noneMatch(s -> s.status() == SourceStatus.PENDING)
                && result.candidates().stream().noneMatch(c -> c.observation().kind() == Kind.PENDING);
    }
    private static Result awaitResult(CandidateDiscovery discovery, Predicate<Result> condition) {
        var result = new AtomicReference<Result>();
        await(() -> { result.set(discovery.snapshot()); return condition.test(result.get()); });
        return result.get();
    }
    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) fail("Controlled work did not complete");
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
    }
    private static final class Gate {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        void block() {
            entered.countDown();
            boolean finished = false;
            while (!finished) {
                try { release.await(); finished = true; }
                catch (InterruptedException ignored) { /* Models I/O that interruption cannot cancel. */ }
            }
        }
    }
}
