package io.github.bigswlittlesw.homelight.discovery

import io.github.bigswlittlesw.homelight.config.CandidateCatalog
import io.github.bigswlittlesw.homelight.config.CandidateDiagnostic
import io.github.bigswlittlesw.homelight.config.CandidateParser
import io.github.bigswlittlesw.homelight.config.CandidateSource
import io.github.bigswlittlesw.homelight.discovery.CandidateDiscovery.SourceProblem
import io.github.bigswlittlesw.homelight.discovery.CandidateObservation.Diagnostic
import io.github.bigswlittlesw.homelight.discovery.CandidateObservation.Kind
import io.github.bigswlittlesw.homelight.discovery.CandidateObservation.Reason
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.util.concurrent.Semaphore

/**
 * Session-scoped discovery. `refresh`, `snapshot`, `cancel` and `close` perform
 * no filesystem I/O and never wait for workers. Candidates are inspected serially
 * in the background; `snapshot` reads evidence and applies response deadlines.
 * Source reads and each root/candidate inspection have a five-second deadline.
 * An expired result is rejected even if no snapshot was requested at the deadline.
 *
 * A refresh replaces the request generation. Only an unchanged root AND shared
 * location retain stale evidence. Cancellation/closure discard all session data.
 * Production worker capacity is process-wide, including across closed sessions:
 * one shared read, one bundled load, and one serial metadata inspection. Permits are
 * released only when work actually returns, never on timeout or interruption.
 */
class CandidateDiscovery internal constructor(
    private val lanes: Lanes,
    private val clock: () -> Long,
    private val sharedReader: (Path) -> ByteArray,
    private val bundledReader: (Path) -> CandidateCatalog.Snapshot,
    private val metadata: CandidateMetadata,
) : AutoCloseable {
    // Guarded by this instance's monitor. Worker threads only publish a completion under it.
    private var generation: Long = 0
    private var closed = false
    private var request: Request? = null
    private val sources = LinkedHashMap<CandidateSource, SourceOutcome>()
    private val sourceWork = LinkedHashMap<CandidateSource, Work<CandidateCatalog.Snapshot>>()
    private var anchorWork: Work<CandidateMetadata.Anchor>? = null
    private var anchor: CandidateMetadata.Anchor? = null
    private var rootFailure: Diagnostic? = null
    private var inspectionWork: Work<CandidateObservation>? = null
    private var inspectionPath: Path? = null
    private val observations = HashMap<Path, CandidateObservation>()
    private val attempted = HashSet<Path>()
    private val pending = LinkedHashSet<Path>()

    constructor() : this(PROCESS_LANES, System::nanoTime, ::readShared, CandidateCatalog::bundled, CandidateMetadata())

    /** Paths must already be absolute; no home expansion or location persistence. */
    @Synchronized
    fun refresh(root: Path, sharedLocation: Path?): Long {
        check(!closed) { "Discovery is closed" }
        val next = Request.of(root, sharedLocation)
        val retain = next == request
        generation++
        if (!retain) {
            sources.clear()
            observations.clear()
        } else {
            observations.replaceAll { _, value -> value.retained() }
        }
        request = next
        sourceWork.clear()
        inspectionWork = null
        inspectionPath = null
        attempted.clear()
        pending.clear()
        anchor = null
        rootFailure = null
        val currentRoot = next.root
        startSource(CandidateCatalog.BUNDLED, lanes.bundled) { bundledReader(currentRoot) }
        next.sharedLocation?.let { location ->
            val source = CandidateSource(CandidateSource.Kind.SHARED, location.toString())
            startSource(source, lanes.shared) { CandidateParser().parse(source, currentRoot, sharedReader(location)) }
        }
        anchorWork = start(lanes.filesystem) { metadata.anchor(currentRoot) }
        if (anchorWork == null) {
            rootFailure = Diagnostic(
                root, Reason.CAPACITY, "Filesystem capacity occupied; refresh when earlier operations finish",
            )
        }
        return generation
    }

    /**
     * Returned collections are unmodifiable JDK copies and observations are immutable.
     * No aggregate byte total is supplied. Ancestors identify overlapping catalog candidates.
     */
    @Synchronized
    fun snapshot(): Result {
        val request = this.request ?: return Result(generation, null, listOf(), listOf(), null)
        collectSources()
        collectAnchor()
        collectInspection()
        val candidates = CandidateCatalog.merge(sources.values.mapNotNull { it.catalog }).candidates
        val identities = candidates.mapTo(HashSet()) { it.sourcePath }
        observations.keys.retainAll(identities)
        val rows = candidates.map { candidate ->
            val path = candidate.sourcePath
            // Every merged definition comes from a catalog held in `sources`.
            val sourceStale = candidate.definitions.any { sources.getValue(it.source).status != SourceStatus.CURRENT }
            val failure = rootFailure
            var observation = if (failure != null) failedObservation(path, failure.reason, failure.detail)
            else observations[path] ?: CandidateObservation(
                path, Kind.PENDING, null, generation, Instant.now(), false,
                if (path == inspectionPath) listOf()
                else listOf(Diagnostic(path, Reason.CAPACITY, "Waiting for serial inspection")),
            )
            if (sourceStale) observation = observation.retained()
            val ancestors = generateSequence(path.parent) { it.parent }
                .takeWhile { it.startsWith(request.root) }
                .filter { it in identities }
                .toList()
            Candidate(candidate, observation, java.util.List.copyOf(ancestors))
        }
        return Result(
            generation, request, java.util.List.copyOf(sources.values), java.util.List.copyOf(rows), rootFailure,
        )
    }

    @Synchronized
    fun cancel() {
        generation++
        request = null
        sources.clear()
        sourceWork.clear()
        observations.clear()
        inspectionWork = null
        inspectionPath = null
        attempted.clear()
        pending.clear()
        anchorWork = null
        anchor = null
        rootFailure = null
    }

    @Synchronized
    override fun close() {
        if (closed) return
        cancel()
        closed = true
    }

    private fun startSource(source: CandidateSource, lane: Semaphore, read: () -> CandidateCatalog.Snapshot) {
        sources[source] = SourceOutcome(source, sources[source]?.catalog, SourceStatus.PENDING, listOf(), listOf())
        val work = start(lane, read)
        if (work == null) {
            sourceFailure(
                source, listOf(),
                SourceProblem(
                    SourceProblem.Kind.PREVIOUS_PENDING,
                    "Previous read still pending; manual setup remains available",
                ),
            )
        } else sourceWork[source] = work
    }

    private fun collectSources() {
        val iterator = sourceWork.entries.iterator()
        while (iterator.hasNext()) {
            val (source, work) = iterator.next()
            val done = work.completion
            when {
                expired(work, done, SOURCE_NANOS) -> sourceFailure(
                    source, listOf(), SourceProblem(SourceProblem.Kind.DEADLINE, "Source response deadline exceeded"),
                )
                done == null -> continue
                done.failure != null -> sourceFailure(source, listOf(), sourceProblem(done.failure))
                else -> {
                    // A completion without a failure carries the read's value.
                    val catalog = done.value!!
                    if (!catalog.accepted()) {
                        sourceFailure(source, catalog.diagnostics, null)
                    } else {
                        sources[source] = SourceOutcome(source, catalog, SourceStatus.CURRENT, listOf(), listOf())
                        catalog.definitions.forEach { if (it.sourcePath !in attempted) pending.add(it.sourcePath) }
                    }
                }
            }
            iterator.remove()
        }
    }

    private fun sourceFailure(source: CandidateSource, diagnostics: List<CandidateDiagnostic>, problem: SourceProblem?) {
        // `startSource` registers a source before its work can fail.
        val catalog = sources.getValue(source).catalog
        sources[source] = SourceOutcome(
            source, catalog, if (catalog != null) SourceStatus.STALE else SourceStatus.FAILED,
            diagnostics, listOfNotNull(problem),
        )
    }

    private fun collectAnchor() {
        val anchorWork = this.anchorWork ?: return
        val done = anchorWork.completion
        // Anchor work exists only for an active request: `refresh` sets both and `cancel` clears both.
        when {
            expired(anchorWork, done, METADATA_NANOS) ->
                rootFailure = Diagnostic(request!!.root, Reason.DEADLINE, "Root inspection timed out")
            done == null -> return
            done.failure != null -> {
                val reason = if (done.failure is AccessDeniedException) Reason.ACCESS_DENIED else Reason.IO_ERROR
                rootFailure = Diagnostic(request!!.root, reason, done.failure.toString())
            }
            else -> anchor = done.value
        }
        this.anchorWork = null
    }

    private fun collectInspection() {
        val inspectionWork = this.inspectionWork ?: return
        // Set and cleared together with `inspectionWork`.
        val inspectionPath = this.inspectionPath!!
        val done = inspectionWork.completion
        when {
            expired(inspectionWork, done, METADATA_NANOS) -> observations[inspectionPath] = failedObservation(
                inspectionPath, Reason.DEADLINE, "Inspection response deadline exceeded",
            )
            done == null -> return
            done.failure != null ->
                observations[inspectionPath] = failedObservation(inspectionPath, Reason.IO_ERROR, done.failure.toString())
            else -> {
                // A completion without a failure carries the inspection's value.
                val value = done.value!!
                val prior = observations[inspectionPath]
                observations[inspectionPath] =
                    if ((value.kind == Kind.INACCESSIBLE || value.kind == Kind.UNKNOWN) && prior != null) {
                        retainedFailure(prior, value.diagnostics)
                    } else value
            }
        }
        this.inspectionWork = null
        this.inspectionPath = null
    }

    // Completion of one background operation starts the next. Reading a snapshot
    // never drives discovery. Pending paths are bounded by the catalog limits;
    // they are data, not submitted tasks waiting behind a blocked operation.
    private fun continueInspection() {
        collectSources()
        collectAnchor()
        collectInspection()
        val anchor = anchor ?: return
        if (inspectionWork != null || rootFailure != null) return
        val path = pending.firstOrNull() ?: return
        val currentGeneration = generation
        val work = start(lanes.filesystem) { metadata.inspect(anchor, path, currentGeneration) } ?: return
        attempted.add(path)
        pending.remove(path)
        inspectionPath = path
        inspectionWork = work
    }

    private fun failedObservation(path: Path, reason: Reason, detail: String): CandidateObservation {
        val previous = observations[path] ?: return CandidateObservation.unknown(path, generation, reason, detail)
        return retainedFailure(previous, listOf(Diagnostic(path, reason, detail)))
    }

    private fun <T> expired(work: Work<T>, completion: Completion<T>?, limit: Long): Boolean =
        (completion?.finished ?: clock()) - work.started >= limit

    /**
     * Starts `action` on its own daemon thread if `lane` has a permit. The worker owns the permit
     * until it publishes its completion; a timed-out worker keeps it until the action returns.
     */
    private fun <T> start(lane: Semaphore, action: () -> T): Work<T>? {
        if (!lane.tryAcquire()) return null
        val work = Work<T>(clock())
        val startedGeneration = generation
        try {
            Thread.ofPlatform().daemon().name("homelight-discovery").start {
                val done: Completion<T> = try {
                    Completion(action(), null, clock())
                } catch (e: Exception) {
                    Completion(null, e, clock())
                } catch (e: Error) {
                    lane.release()
                    throw e
                }
                synchronized(this@CandidateDiscovery) {
                    // Publish completion with availability so an immediate refresh
                    // cannot mistake an already completed read for stuck work.
                    lane.release()
                    work.completion = done
                    if (request != null && generation == startedGeneration) continueInspection()
                }
            }
        } catch (e: Throwable) {
            // The worker never started, so it cannot release the permit.
            lane.release()
            throw e
        }
        return work
    }

    @ConsistentCopyVisibility
    data class Request private constructor(val root: Path, val sharedLocation: Path?) {
        companion object {
            /** Both paths must be absolute; identity uses their normalized forms. */
            fun of(root: Path, sharedLocation: Path?): Request =
                Request(normalized(root), sharedLocation?.let(::normalized))
        }
    }

    enum class SourceStatus { PENDING, CURRENT, STALE, FAILED }

    data class SourceProblem(val kind: Kind, val detail: String) {
        enum class Kind { MISSING, UNREADABLE, NOT_REGULAR, IO_ERROR, DEADLINE, PREVIOUS_PENDING }
    }

    data class SourceOutcome(
        val source: CandidateSource, val catalog: CandidateCatalog.Snapshot?,
        val status: SourceStatus, val diagnostics: List<CandidateDiagnostic>,
        val problems: List<SourceProblem>,
    )

    data class Candidate(
        val catalog: CandidateCatalog.Candidate, val observation: CandidateObservation,
        val ancestors: List<Path>,
    )

    data class Result(
        val generation: Long, val request: Request?, val sources: List<SourceOutcome>,
        val candidates: List<Candidate>, val rootFailure: Diagnostic?,
    )

    internal class Lanes {
        val shared = Semaphore(1)
        val bundled = Semaphore(1)
        val filesystem = Semaphore(1)
    }

    private class Work<T>(val started: Long) {
        @Volatile
        var completion: Completion<T>? = null
    }

    private data class Completion<T>(val value: T?, val failure: Exception?, val finished: Long)

    companion object {
        private const val SOURCE_NANOS = 5_000_000_000L

        internal const val METADATA_NANOS = 5_000_000_000L

        private val PROCESS_LANES = Lanes()
    }
}

private class NotRegular(path: Path) : IOException("Shared source is not a regular file: $path")

private fun readShared(location: Path): ByteArray {
    // Following the explicitly selected shared-file location is permitted.
    // A later FIFO replacement may block open; the lane/deadline still bounds it.
    if (!Files.readAttributes(location, BasicFileAttributes::class.java).isRegularFile) {
        throw NotRegular(location)
    }
    return Files.newInputStream(location).use { it.readNBytes(CandidateParser.MAX_BYTES + 1) }
}

private fun sourceProblem(failure: Exception): SourceProblem {
    val kind = when (failure) {
        is NoSuchFileException -> SourceProblem.Kind.MISSING
        is NotRegular -> SourceProblem.Kind.NOT_REGULAR
        is AccessDeniedException, is SecurityException -> SourceProblem.Kind.UNREADABLE
        else -> SourceProblem.Kind.IO_ERROR
    }
    return SourceProblem(kind, failure.toString())
}

private fun normalized(path: Path): Path {
    require(path.isAbsolute) { "Absolute path required" }
    return path.normalize()
}

private fun retainedFailure(previous: CandidateObservation, failures: List<Diagnostic>): CandidateObservation {
    val diagnostics = previous.diagnostics.toMutableList()
    for (failure in failures) if (failure !in diagnostics) diagnostics.add(failure)
    return previous.copy(stale = true, diagnostics = diagnostics)
}
