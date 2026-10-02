package io.github.bigswlittlesw.homelight.discovery

import io.github.bigswlittlesw.homelight.config.CandidateCatalog
import io.github.bigswlittlesw.homelight.config.CandidateDiagnostic
import io.github.bigswlittlesw.homelight.config.CandidateParser
import io.github.bigswlittlesw.homelight.config.CandidateSource
import io.github.bigswlittlesw.homelight.discovery.CandidateDiscovery.Candidate
import io.github.bigswlittlesw.homelight.discovery.CandidateDiscovery.Companion.METADATA_NANOS
import io.github.bigswlittlesw.homelight.discovery.CandidateDiscovery.Lanes
import io.github.bigswlittlesw.homelight.discovery.CandidateDiscovery.Result
import io.github.bigswlittlesw.homelight.discovery.CandidateDiscovery.SourceOutcome
import io.github.bigswlittlesw.homelight.discovery.CandidateDiscovery.SourceProblem
import io.github.bigswlittlesw.homelight.discovery.CandidateDiscovery.SourceStatus
import io.github.bigswlittlesw.homelight.discovery.CandidateObservation.Kind
import io.github.bigswlittlesw.homelight.discovery.CandidateObservation.Ownership
import io.github.bigswlittlesw.homelight.discovery.CandidateObservation.Reason
import io.github.bigswlittlesw.homelight.discovery.CandidateObservation.Size
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTimeout
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import java.util.function.BooleanSupplier
import java.util.function.Predicate

class CandidateDiscoveryTest {
    @TempDir lateinit var temporary: Path

    @Test fun independentlyLoadsRealSharedFileAndRetainsProvenanceAndOverlap() {
        val shared = temporary.resolve("shared.json")
        Files.copy(Path.of("docs/research/session-b-fixtures/nested/shared.json"), shared)
        Files.createDirectories(temporary.resolve(".local/share/uv/tools"))
        CandidateDiscovery().use { discovery ->
            discovery.refresh(temporary, shared)
            val result = awaitResult(discovery, ::finished)
            assertTrue(result.sources.all { s -> s.status == SourceStatus.CURRENT })
            val child = row(result, temporary.resolve(".local/share/uv/tools"))
            assertEquals(listOf(temporary.resolve(".local/share/uv")), child.ancestors)
            assertEquals(Size.NOT_ESTIMATED, child.observation.size)
            assertNull(child.observation.size.bytes)
            val maven = row(result, temporary.resolve(".m2"))
            assertEquals(2, maven.catalog.definitions.size)
            assertEquals(shared.toString(), maven.catalog.definitions.get(1).source.location)
            // Kotlin's read-only `List` has no `clear`; the cast reaches the JDK list's mutator.
            assertThrows(UnsupportedOperationException::class.java) { (result.candidates as MutableList<*>).clear() }
            assertThrows(UnsupportedOperationException::class.java) { (child.ancestors as MutableList<*>).clear() }
            assertEquals(Ownership.NOT_EVALUATED, child.observation.ownership)
        }
    }

    @Test fun missingWrongKindMalformedOversizeAndLinkedSharedInputs() {
        val shared = temporary.resolve("shared.json")
        CandidateDiscovery().use { discovery ->
            discovery.refresh(temporary, shared)
            val result = awaitResult(discovery, ::finished)
            assertProblem(result, SourceProblem.Kind.MISSING)
            assertFalse(result.candidates.isEmpty())
            discovery.refresh(temporary, temporary)
            assertProblem(awaitResult(discovery, ::finished), SourceProblem.Kind.NOT_REGULAR)
            Files.writeString(shared, "{\"directories\": [")
            discovery.refresh(temporary, shared)
            val malformed = awaitResult(discovery, ::finished)
            assertEquals(CandidateDiagnostic.Kind.SYNTAX, shared(malformed).diagnostics.first().kind)
            Files.write(shared, ByteArray(CandidateParser.MAX_BYTES + 1))
            discovery.refresh(temporary, shared)
            assertEquals(CandidateDiagnostic.Kind.LIMIT, shared(awaitResult(discovery,
                    ::finished)).diagnostics.first().kind)
            Files.writeString(shared, "{\"directories\": [{\"path\": \"team-cache\"}]}")
            val link = Files.createSymbolicLink(temporary.resolve("shared-link"), shared)
            discovery.refresh(temporary, link)
            val linked = awaitResult(discovery, ::finished)
            assertEquals(SourceStatus.CURRENT, shared(linked).status)
            assertEquals(link.toString(), checkNotNull(shared(linked).catalog).source.location)
            discovery.refresh(temporary, null)
            val cleared = awaitResult(discovery, ::finished)
            assertEquals(1, cleared.sources.size)
            assertFalse(cleared.candidates.any { c -> c.catalog.sourcePath.endsWith("team-cache") })
        }
    }

    @Test fun sourceFailureRetainsStaleDefinitionsAndObservationsOnlyForSameRequest() {
        val lanes = Lanes()
        val input = AtomicReference(bytes("{\"directories\": [{\"path\": \"team-cache\"}]}"))
        Files.createDirectory(temporary.resolve("team-cache"))
        val location = temporary.resolve("shared")
        discovery(lanes, AtomicLong(), { ignored -> input.get() }, "cache", CandidateMetadata()).use { discovery ->
            val original = discovery.refresh(temporary, location)
            val first = awaitResult(discovery, ::finished)
            val originalRow = row(first, temporary.resolve("team-cache"))
            await { lanes.shared.availablePermits() == 1 }
            input.set(bytes("{\"directories\": ["))
            discovery.refresh(temporary, location)
            val failed = awaitResult(discovery, ::finished)
            assertEquals(SourceStatus.STALE, shared(failed).status)
            val retained = row(failed, temporary.resolve("team-cache"))
            assertTrue(retained.observation.stale)
            assertEquals(original, retained.observation.generation)
            assertEquals(originalRow.catalog, retained.catalog)
            assertEquals(Size.NOT_ESTIMATED, retained.observation.size)
            discovery.refresh(temporary, temporary.resolve("other-location"))
            val changed = awaitResult(discovery, ::finished)
            assertEquals(SourceStatus.FAILED, shared(changed).status)
            assertFalse(changed.candidates.any { c -> c.catalog.sourcePath.endsWith("team-cache") })
        }
    }

    @Test fun unreadableAndMidReadFailureRejectSourceWithoutHidingBundled() {
        for (denied in listOf(true, false)) {
            discovery(Lanes(), AtomicLong(), { path ->
                if (denied) throw AccessDeniedException(path.toString())
                // Simulates a reader failing after receiving a prefix: no prefix is returned for parsing.
                throw IOException("Read failed after partial bytes")
            }, "cache", CandidateMetadata()).use { discovery ->
                discovery.refresh(temporary, temporary.resolve("shared"))
                val result = awaitResult(discovery, ::finished)
                assertProblem(result, if (denied) SourceProblem.Kind.UNREADABLE else SourceProblem.Kind.IO_ERROR)
                assertEquals(1, result.candidates.size)
            }
        }
    }

    @Test fun sharedDeadlineRepeatedRefreshAndShutdownDoNotWaitOrReleaseCapacity() {
        val gate = Gate()
        val lanes = Lanes()
        val clock = AtomicLong()
        val calls = AtomicInteger()
        val worker = AtomicReference<Thread>()
        val discovery = discovery(lanes, clock, { path ->
            calls.incrementAndGet()
            worker.set(Thread.currentThread())
            gate.block()
            bytes("{\"directories\": [{\"path\": \"late\"}]}")
        }, "cache", CandidateMetadata())
        try {
            assertTimeout(Duration.ofMillis(500), Executable { discovery.refresh(temporary,
                    temporary.resolve("shared")) })
            assertTrue(gate.entered.await(3, TimeUnit.SECONDS))
            awaitResult(discovery) { r -> !r.candidates.isEmpty()
                    && r.candidates.first().observation.kind == Kind.MISSING }
            worker.get().interrupt()
            clock.set(5_000_000_000L)
            assertProblem(discovery.snapshot(), SourceProblem.Kind.DEADLINE)
            for (i in 0 until 20) {
                assertTimeout(Duration.ofMillis(500), Executable { discovery.refresh(temporary,
                        temporary.resolve("shared")) })
                assertProblem(discovery.snapshot(), SourceProblem.Kind.PREVIOUS_PENDING)
            }
            assertEquals(1, calls.get())
            assertEquals(0, lanes.shared.availablePermits())
            assertTimeout(Duration.ofMillis(500), Executable(discovery::close))
            assertTrue(discovery.snapshot().candidates.isEmpty())
            // Reopening a session with the same process lanes cannot replace a stuck worker.
            discovery(lanes, clock, { path -> fail<Unit>("Extra read"); ByteArray(0) },
                    "cache", CandidateMetadata()).use { reopened ->
                reopened.refresh(temporary, temporary.resolve("shared"))
                assertProblem(reopened.snapshot(), SourceProblem.Kind.PREVIOUS_PENDING)
            }
        } finally {
            discovery.close()
            gate.release.countDown()
            await { lanes.shared.availablePermits() == 1 && lanes.filesystem.availablePermits() == 1
                    && lanes.bundled.availablePermits() == 1 }
        }
        assertTrue(discovery.snapshot().sources.isEmpty())
        assertThrows(IllegalStateException::class.java) { discovery.refresh(temporary, null) }
    }

    @Test fun lateCompletionBeyondDeadlineRejectedEvenWithoutEarlierPoll() {
        val gate = Gate()
        val clock = AtomicLong()
        val lanes = Lanes()
        try {
            discovery(lanes, clock, { path ->
                gate.block()
                bytes("{\"directories\": [{\"path\": \"late\"}]}")
            }, "cache", CandidateMetadata()).use { discovery ->
                discovery.refresh(temporary, temporary.resolve("shared"))
                assertTrue(gate.entered.await(3, TimeUnit.SECONDS))
                clock.set(5_000_000_001L)
                gate.release.countDown()
                await { lanes.shared.availablePermits() == 1 }
                val result = discovery.snapshot()
                assertProblem(result, SourceProblem.Kind.DEADLINE)
                assertNull(shared(result).catalog)
            }
        } finally { gate.release.countDown() }
    }

    @Test fun rootAndLocationChangeAndCancellationIgnoreObsoleteSourceResults() {
        for (cancel in listOf(false, true)) {
            val gate = Gate()
            val lanes = Lanes()
            val other = Files.createTempDirectory(temporary, "other")
            try {
                discovery(lanes, AtomicLong(), { path ->
                    gate.block()
                    bytes("{\"directories\": [{\"path\": \"obsolete\"}]}")
                }, "cache", CandidateMetadata()).use { discovery ->
                    val first = discovery.refresh(temporary, temporary.resolve("shared"))
                    assertTrue(gate.entered.await(3, TimeUnit.SECONDS))
                    if (cancel) discovery.cancel()
                    else discovery.refresh(other, other.resolve("new-location"))
                    gate.release.countDown()
                    await { lanes.shared.availablePermits() == 1 }
                    val result = discovery.snapshot()
                    assertTrue(result.generation > first)
                    assertFalse(result.candidates.any { c -> c.catalog.sourcePath.endsWith("obsolete") })
                    if (cancel) {
                        assertNull(result.request)
                        assertTrue(result.sources.isEmpty())
                    } else {
                        assertEquals(other, checkNotNull(result.request).root)
                        assertProblem(result, SourceProblem.Kind.PREVIOUS_PENDING)
                    }
                }
            } finally { gate.release.countDown() }
        }
    }

    @Test fun failedCandidateRefreshRetainsOldStateMarkedStaleWithNewDiagnostic() {
        Files.createDirectory(temporary.resolve("cache"))
        Files.write(temporary.resolve("cache/a"), ByteArray(17))
        val fail = AtomicInteger()
        val lanes = Lanes()
        val metadata = CandidateMetadata(object : CandidateMetadata.Access() {
            override fun attributes(path: Path): BasicFileAttributes {
                if (path.endsWith("cache") && fail.get() == 1) throw AccessDeniedException(path.toString())
                return super.attributes(path)
            }
        })
        discovery(lanes, AtomicLong(), { path -> bytes("{\"directories\": []}") }, "cache", metadata).use { discovery ->
            val first = discovery.refresh(temporary, null)
            awaitResult(discovery, ::finished)
            await { lanes.filesystem.availablePermits() == 1 && lanes.bundled.availablePermits() == 1 }
            fail.set(1)
            discovery.refresh(temporary, null)
            val result = awaitResult(discovery) { r -> !r.candidates.isEmpty() && r.candidates.first()
                    .observation.diagnostics.any { d -> d.reason == Reason.ACCESS_DENIED } }
            val retained = result.candidates.first().observation
            assertTrue(retained.stale)
            assertEquals(Kind.DIRECTORY, retained.kind)
            assertNull(retained.size.bytes)
            assertEquals(first, retained.generation)
        }
    }

    @Test fun filesystemWorkStaysBoundedAcrossTimeoutRefreshAndClose() {
        val gate = Gate()
        val entered = CountDownLatch(1)
        val calls = AtomicInteger()
        val lanes = Lanes()
        val clock = AtomicLong()
        for (i in 0 until 6) Files.createDirectory(temporary.resolve("cache$i"))
        val metadata = CandidateMetadata(object : CandidateMetadata.Access() {
            override fun attributes(path: Path): BasicFileAttributes {
                if (path.fileName.toString().startsWith("cache")) {
                    calls.incrementAndGet()
                    entered.countDown()
                    gate.block()
                }
                return super.attributes(path)
            }
        })
        val discovery = discovery(lanes, clock, { path -> bytes("{\"directories\": []}") },
                "cache0,cache1,cache2,cache3,cache4,cache5", metadata)
        try {
            discovery.refresh(temporary, null)
            awaitResult(discovery) { r -> r.candidates.size == 6 && lanes.filesystem.availablePermits() == 0 }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            clock.set(CandidateDiscovery.METADATA_NANOS)
            val timed = discovery.snapshot()
            assertEquals(1, timed.candidates.count { c -> c.observation.kind == Kind.UNKNOWN })
            assertEquals(5, timed.candidates.count { c -> c.observation.kind == Kind.PENDING })
            for (i in 0 until 20) {
                discovery.refresh(temporary, null)
                assertEquals(Reason.CAPACITY, checkNotNull(discovery.snapshot().rootFailure).reason)
            }
            assertEquals(1, calls.get())
            assertTimeout(Duration.ofMillis(500), Executable(discovery::close))
            assertEquals(0, lanes.filesystem.availablePermits())
            discovery(lanes, clock, { p -> bytes("{\"directories\": []}") },
                    "cache0", CandidateMetadata()).use { reopened ->
                reopened.refresh(temporary, null)
                assertEquals(Reason.CAPACITY, checkNotNull(reopened.snapshot().rootFailure).reason)
                assertEquals(1, calls.get())
            }
        } finally {
            discovery.close()
            gate.release.countDown()
            await { lanes.filesystem.availablePermits() == 1 && lanes.bundled.availablePermits() == 1 }
        }
        assertTrue(discovery.snapshot().candidates.isEmpty())
    }

    @Test fun serialMetadataPassCompletesWithoutSnapshotDrivingWork() {
        for (i in 0 until 8) Files.createDirectory(temporary.resolve("cache$i"))
        val last = CountDownLatch(1)
        val metadata = CandidateMetadata(object : CandidateMetadata.Access() {
            override fun attributes(path: Path): BasicFileAttributes {
                if (path.endsWith("cache7")) last.countDown()
                return super.attributes(path)
            }
        })
        discovery(Lanes(), AtomicLong(), { path -> bytes("{\"directories\": []}") },
                "cache0,cache1,cache2,cache3,cache4,cache5,cache6,cache7", metadata).use { discovery ->
            discovery.refresh(temporary, null)
            assertTrue(last.await(3, TimeUnit.SECONDS), "Work depended on snapshot polling")
            val result = awaitResult(discovery, ::finished)
            assertEquals(8, result.candidates.size)
            assertTrue(result.candidates.all { c -> c.observation.kind == Kind.DIRECTORY })
        }
    }

    @Test fun obsoleteFilesystemCompletionCannotAttachToNewRoot() {
        val gate = Gate()
        val lanes = Lanes()
        val firstRoot = Files.createDirectory(temporary.resolve("first"))
        val physicalFirstRoot = firstRoot.toRealPath()
        val secondRoot = Files.createDirectory(temporary.resolve("second"))
        Files.createDirectory(firstRoot.resolve("cache"))
        val metadata = CandidateMetadata(object : CandidateMetadata.Access() {
            override fun attributes(path: Path): BasicFileAttributes {
                if (path.equals(physicalFirstRoot.resolve("cache"))) gate.block()
                return super.attributes(path)
            }
        })
        try {
            discovery(lanes, AtomicLong(), { path -> bytes("{\"directories\": []}") }, "cache", metadata).use { discovery ->
                discovery.refresh(firstRoot, null)
                awaitResult(discovery) { r -> gate.entered.count == 0L }
                val generation = discovery.refresh(secondRoot, null)
                val second = awaitResult(discovery, ::finished)
                assertEquals(Kind.UNKNOWN, second.candidates.first().observation.kind)
                gate.release.countDown()
                await { lanes.filesystem.availablePermits() == 1 }
                val result = discovery.snapshot()
                assertEquals(generation, result.candidates.first().observation.generation)
                assertEquals(secondRoot.resolve("cache"), result.candidates.first().observation.path)
                assertEquals(Kind.UNKNOWN, result.candidates.first().observation.kind)
            }
        } finally { gate.release.countDown() }
    }

    @Test fun rootProbeDeadlineDoesNotBlockSharedParsingOrClose() {
        val gate = Gate()
        val clock = AtomicLong()
        val lanes = Lanes()
        val metadata = CandidateMetadata(object : CandidateMetadata.Access() {
            override fun realPath(path: Path): Path {
                gate.block()
                return super.realPath(path)
            }
        })
        try {
            discovery(lanes, clock, { p -> bytes("{\"directories\": [{\"path\": \"team\"}]}") }, "cache", metadata).use { discovery ->
                discovery.refresh(temporary, temporary.resolve("shared"))
                assertTrue(gate.entered.await(3, TimeUnit.SECONDS))
                awaitResult(discovery) { r -> shared(r).status == SourceStatus.CURRENT }
                clock.set(CandidateDiscovery.METADATA_NANOS)
                val result = discovery.snapshot()
                assertEquals(Reason.DEADLINE, checkNotNull(result.rootFailure).reason)
                assertTrue(result.candidates.all { c -> c.observation.size.bytes == null })
                assertTimeout(Duration.ofMillis(500), Executable(discovery::close))
            }
        } finally {
            gate.release.countDown()
            await { lanes.filesystem.availablePermits() == 1 }
        }
    }

    @Test fun blockedWorkerDoesNotKeepJvmAlive() {
        val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), ExitProbe::class.java.name, temporary.toString())
                .redirectErrorStream(true).start()
        try {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Daemon discovery worker kept JVM alive")
            assertEquals(0, process.exitValue(), String(process.inputStream.readAllBytes(), StandardCharsets.UTF_8))
        } finally { if (process.isAlive) process.destroyForcibly() }
    }

    class ExitProbe {
        companion object {
            // JVM entry point for the subprocess this test launches.
            @JvmStatic fun main(args: Array<String>) {
                val gate = Gate()
                val metadataGate = Gate()
                val metadata = CandidateMetadata(object : CandidateMetadata.Access() {
                    override fun realPath(path: Path): Path {
                        metadataGate.block()
                        return super.realPath(path)
                    }
                })
                discovery(Lanes(), AtomicLong(), { path ->
                    gate.block()
                    bytes("{\"directories\": []}")
                }, "cache", metadata).use { discovery ->
                    val root = Path.of(args[0])
                    discovery.refresh(root, root.resolve("shared"))
                    if (!gate.entered.await(3, TimeUnit.SECONDS)) throw AssertionError("Reader did not start")
                    if (!metadataGate.entered.await(3, TimeUnit.SECONDS)) throw AssertionError("Metadata did not start")
                }
            }
        }
    }

    @Test fun sharedResultsProceedWhenBundledSourceStallsOrFails() {
        val gate = Gate()
        val clock = AtomicLong()
        val lanes = Lanes()
        Files.createDirectory(temporary.resolve("team"))
        try {
            CandidateDiscovery(lanes, clock::get,
                    { p -> bytes("{\"directories\": [{\"path\": \"team\"}]}") }, { root ->
                        gate.block()
                        CandidateParser().parse(CandidateCatalog.BUNDLED, root, bytes("{\"directories\": []}"))
                    }, CandidateMetadata()).use { discovery ->
                discovery.refresh(temporary, temporary.resolve("shared"))
                assertTrue(gate.entered.await(3, TimeUnit.SECONDS))
                val result = awaitResult(discovery) { r -> !r.candidates.isEmpty()
                        && r.candidates.first().observation.kind == Kind.DIRECTORY }
                assertEquals(SourceStatus.PENDING, result.sources.first().status)
                assertEquals(SourceStatus.CURRENT, shared(result).status)
                clock.set(5_000_000_000L)
                assertEquals(SourceProblem.Kind.DEADLINE, discovery.snapshot().sources.first().problems.first().kind)
            }
        } finally {
            gate.release.countDown()
            await { lanes.bundled.availablePermits() == 1 }
        }
        CandidateDiscovery(Lanes(), System::nanoTime,
                { p -> bytes("{\"directories\": [{\"path\": \"team\"}]}") }, { root -> CandidateCatalog.Snapshot.of(
                CandidateCatalog.BUNDLED, root, listOf(), listOf(CandidateDiagnostic(
                CandidateCatalog.BUNDLED, CandidateDiagnostic.Kind.RESOURCE, 0, 0, 0, "", "",
                "Controlled packaging failure"))) },
                CandidateMetadata()).use { discovery ->
            discovery.refresh(temporary, temporary.resolve("shared"))
            val result = awaitResult(discovery, ::finished)
            assertEquals(SourceStatus.FAILED, result.sources.first().status)
            assertEquals(SourceStatus.CURRENT, shared(result).status)
            assertEquals(Kind.DIRECTORY, result.candidates.first().observation.kind)
        }
    }

    @Test fun lateMetadataCompletionIsUnknownAndCannotOverwriteRetainedState() {
        val gate = Gate()
        val clock = AtomicLong()
        val lanes = Lanes()
        val block = AtomicInteger()
        Files.createDirectory(temporary.resolve("cache"))
        val metadata = CandidateMetadata(object : CandidateMetadata.Access() {
            override fun attributes(path: Path): BasicFileAttributes {
                if (path.endsWith("cache") && block.get() == 1) gate.block()
                return super.attributes(path)
            }
        })
        try {
            discovery(lanes, clock, { p -> bytes("{\"directories\": []}") }, "cache", metadata).use { discovery ->
                val original = discovery.refresh(temporary, null)
                awaitResult(discovery, ::finished)
                block.set(1)
                discovery.refresh(temporary, null)
                assertTrue(gate.entered.await(3, TimeUnit.SECONDS))
                clock.set(METADATA_NANOS)
                // No snapshot until the late completion has returned.
                gate.release.countDown()
                await { lanes.filesystem.availablePermits() == 1 }
                val observation = discovery.snapshot().candidates.first().observation
                assertEquals(Kind.DIRECTORY, observation.kind)
                assertTrue(observation.stale)
                assertEquals(original, observation.generation)
                assertTrue(observation.diagnostics.any { d -> d.reason == Reason.DEADLINE })
                assertNull(observation.size.bytes)
            }
        } finally { gate.release.countDown() }
    }

    @Test fun sharedTimeoutRetainsStaleSnapshotAndSuccessfulRefreshRemovesOldEntries() {
        val gate = Gate()
        val lanes = Lanes()
        val clock = AtomicLong()
        val contents = AtomicReference(bytes("{\"directories\": [{\"path\": \"team\"}]}"))
        val block = AtomicInteger()
        try {
            discovery(lanes, clock, { p ->
                if (block.get() == 1) gate.block()
                contents.get()
            }, "cache", CandidateMetadata()).use { discovery ->
                val location = temporary.resolve("shared")
                val original = discovery.refresh(temporary, location)
                awaitResult(discovery, ::finished)
                block.set(1)
                discovery.refresh(temporary, location)
                assertTrue(gate.entered.await(3, TimeUnit.SECONDS))
                clock.set(5_000_000_000L)
                val timed = discovery.snapshot()
                assertEquals(SourceStatus.STALE, shared(timed).status)
                val old = row(timed, temporary.resolve("team"))
                assertTrue(old.observation.stale)
                assertEquals(original, old.observation.generation)
                gate.release.countDown()
                await { lanes.shared.availablePermits() == 1 && lanes.filesystem.availablePermits() == 1 }
                block.set(0)
                contents.set(bytes("{\"directories\": []}"))
                discovery.refresh(temporary, location)
                val refreshed = awaitResult(discovery, ::finished)
                assertEquals(SourceStatus.CURRENT, shared(refreshed).status)
                assertTrue(refreshed.candidates.none { c -> c.catalog.sourcePath.endsWith("team") })
            }
        } finally { gate.release.countDown() }
    }

    companion object {
        private fun discovery(lanes: Lanes, clock: AtomicLong, reader: (Path) -> ByteArray,
                              paths: String, metadata: CandidateMetadata): CandidateDiscovery {
            val json = paths.split(",").joinToString(", ", "{\"directories\": [", "]}") { path -> "{\"path\": \"$path\"}" }
            return CandidateDiscovery(lanes, clock::get, reader,
                    { root -> CandidateParser().parse(CandidateCatalog.BUNDLED, root, bytes(json)) }, metadata)
        }
        private fun bytes(value: String): ByteArray = value.toByteArray(StandardCharsets.UTF_8)
        private fun shared(result: Result): SourceOutcome {
            return result.sources.first { s -> s.source.kind == CandidateSource.Kind.SHARED }
        }
        private fun row(result: Result, path: Path): Candidate {
            return result.candidates.first { c -> c.catalog.sourcePath.equals(path) }
        }
        private fun assertProblem(result: Result, kind: SourceProblem.Kind) {
            assertEquals(kind, shared(result).problems.first().kind, result.toString())
        }
        private fun finished(result: Result): Boolean {
            return !result.sources.isEmpty() && result.sources.none { s -> s.status == SourceStatus.PENDING }
                    && result.candidates.none { c -> c.observation.kind == Kind.PENDING }
        }
        private fun awaitResult(discovery: CandidateDiscovery, condition: Predicate<Result>): Result {
            val result = AtomicReference<Result>()
            await { result.set(discovery.snapshot()); condition.test(result.get()) }
            return result.get()
        }
        private fun await(condition: BooleanSupplier) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
            while (!condition.getAsBoolean()) {
                if (System.nanoTime() >= deadline) fail<Unit>("Controlled work did not complete")
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1))
            }
        }
    }

    private class Gate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fun block() {
            entered.countDown()
            var finished = false
            while (!finished) {
                try { release.await(); finished = true }
                catch (ignored: InterruptedException) { /* Models I/O that interruption cannot cancel. */ }
            }
        }
    }
}
