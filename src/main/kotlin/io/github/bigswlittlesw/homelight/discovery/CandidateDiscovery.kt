package io.github.bigswlittlesw.homelight.discovery

import io.github.bigswlittlesw.homelight.concurrent.DISCOVERY_CONCURRENCY
import io.github.bigswlittlesw.homelight.concurrent.mapBounded
import io.github.bigswlittlesw.homelight.config.CandidateCatalog
import io.github.bigswlittlesw.homelight.config.CandidateDiagnostic
import io.github.bigswlittlesw.homelight.config.CandidateParser
import io.github.bigswlittlesw.homelight.config.CandidateSource
import io.github.bigswlittlesw.homelight.discovery.CandidateDiscovery.SourceOutcome
import io.github.bigswlittlesw.homelight.discovery.CandidateDiscovery.SourceProblem
import io.github.bigswlittlesw.homelight.discovery.CandidateDiscovery.SourceStatus
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Session-scoped discovery. `refresh`, `snapshot`, `cancel` and `close` do no filesystem I/O and never wait for
 * workers. The bundled list is parsed synchronously from bytes read once per process. The shared list is read on
 * its own thread. A run thread resolves the root and then inspects candidates, [Workers.n] at a time, in catalog
 * order; paths that only the shared list names form a second batch once that list is accepted. `snapshot` reads
 * the recorded state and applies the deadlines.
 *
 * The shared read and each root or candidate inspection have five seconds, measured from the start of its I/O. A
 * result that arrives later is rejected even if no snapshot was taken at the deadline.
 *
 * A refresh replaces the request generation: older work stops before its next item and its results are dropped,
 * so rows read pending until this generation inspects them. A failed list read contributes no candidates.
 * Cancellation and closure discard all session data. Thread bounds are process-wide, including across closed
 * sessions; see [Workers].
 */
class CandidateDiscovery internal constructor(
    private val workers: Workers,
    private val clock: () -> Long,
    private val sharedReader: (Path) -> ByteArray,
    private val bundledReader: (Path) -> CandidateCatalog.Snapshot,
    private val metadata: CandidateMetadata,
) : AutoCloseable {
    // Guarded by this instance's monitor. Worker threads take it only to record an attempt.
    private var generation: Long = 0
    private var closed = false
    private var request: Request? = null
    // Outcomes as of the refresh. The shared entry is resolved against `sharedRead` when read.
    private val sources = LinkedHashMap<CandidateSource, SourceOutcome>()
    private var sharedRead: Attempt<CandidateCatalog.Snapshot>? = null
    private var anchor: Attempt<CandidateMetadata.Anchor>? = null
    private val inspections = HashMap<Path, Attempt<CandidateObservation>>()
    // Paths handed to a batch in this generation, so a path both lists name is inspected once.
    private val scheduled = HashSet<Path>()

    constructor() : this(PROCESS_WORKERS, System::nanoTime, ::readShared, CandidateCatalog::bundled, CandidateMetadata())

    /** Paths must already be absolute; no home expansion or location persistence. */
    @Synchronized
    fun refresh(root: Path, sharedLocation: Path?): Long {
        check(!closed) { "Discovery is closed" }
        val next = Request.of(root, sharedLocation)
        cancel()
        request = next
        val generation = generation
        val bundled = resolved(CandidateCatalog.BUNDLED, catching { bundledReader(next.root) })
        sources[bundled.source] = bundled
        val paths = schedule(bundled)
        workers.chain { run(generation, next.root, paths) }
        next.sharedLocation?.let { startShared(generation, next.root, it) }
        return generation
    }

    /**
     * Returned collections are read-only copies and observations are immutable.
     * No aggregate byte total is supplied. Ancestors identify overlapping catalog candidates.
     */
    @Synchronized
    fun snapshot(): Result {
        val request = this.request ?: return Result(generation, null, listOf(), listOf(), null)
        val sources = currentSources()
        val rootFailure = rootFailure(request.root)
        val candidates = CandidateCatalog.merge(sources.mapNotNull { it.catalog }).candidates
        val identities = candidates.mapTo(HashSet()) { it.sourcePath }
        val rows = candidates.map { candidate ->
            val path = candidate.sourcePath
            val observation = rootFailure?.let { CandidateObservation.unknown(path, generation, it.reason, it.detail) }
                ?: observe(path) ?: pending(path)
            val ancestors = generateSequence(path.parent) { it.parent }
                .takeWhile { it.startsWith(request.root) }
                .filter { it in identities }
                .toList()
            Candidate(candidate, observation, ancestors)
        }
        return Result(generation, request, sources, rows, rootFailure)
    }

    @Synchronized
    fun cancel() {
        generation++
        request = null
        sources.clear()
        sharedRead = null
        anchor = null
        inspections.clear()
        scheduled.clear()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        cancel()
        closed = true
    }

    private fun startShared(generation: Long, root: Path, location: Path) {
        val source = CandidateSource(CandidateSource.Kind.SHARED, location.toString())
        if (!workers.sharedRead.compareAndSet(false, true)) {
            sources[source] = failed(
                source, listOf(),
                SourceProblem(SourceProblem.Kind.PREVIOUS_PENDING, "Previous read still pending; manual setup remains available"),
            )
            return
        }
        sources[source] = SourceOutcome(source, null, SourceStatus.PENDING, listOf(), listOf())
        val started = clock()
        sharedRead = Attempt.Running(started)
        Thread.ofVirtual().name("homelight-shared-list").start {
            val result = catching { CandidateParser().parse(source, root, sharedReader(location)) }
            synchronized(this@CandidateDiscovery) {
                // Freed together with the publish, so an immediate refresh cannot mistake a finished read for a stuck one.
                workers.sharedRead.set(false)
                if (this.generation == generation) publishShared(generation, source, Attempt.Done(started, clock(), result))
            }
        }
    }

    private fun publishShared(generation: Long, source: CandidateSource, done: Attempt.Done<CandidateCatalog.Snapshot>) {
        sharedRead = done
        val paths = schedule(sharedOutcome(sources.getValue(source), done))
        if (paths.isNotEmpty()) workers.chain { inspectBatch(generation, paths) }
    }

    /** Claims the accepted catalog's paths that no earlier batch of this generation holds. */
    private fun schedule(outcome: SourceOutcome): List<Path> {
        if (outcome.status != SourceStatus.CURRENT) return listOf()
        return outcome.catalog?.definitions.orEmpty().map { it.sourcePath }.filter { scheduled.add(it) }
    }

    // Both run on a chained run thread, so the previous run has returned and this one owns all `workers.n` slots.
    private fun run(generation: Long, root: Path, paths: List<Path>) {
        attempt(generation, { anchor = it }) { metadata.anchor(root) }
        inspectBatch(generation, paths)
    }

    private fun inspectBatch(generation: Long, paths: List<Path>) {
        val anchor = synchronized(this) { usableAnchor(generation) } ?: return
        mapBounded(paths, workers.n, { synchronized(this) { this.generation != generation } }) { path ->
            attempt(generation, { inspections[path] = it }) { metadata.inspect(anchor, path, generation) }
        }
    }

    private fun usableAnchor(generation: Long): CandidateMetadata.Anchor? {
        val done = anchor as? Attempt.Done ?: return null
        if (this.generation != generation || expired(done, METADATA_NANOS)) return null
        return done.result.getOrNull()
    }

    /**
     * Records `Running`, does [io] outside the monitor, then records `Done`. Both records happen under the monitor
     * and only while [generation] is current, so obsolete work never publishes.
     */
    private inline fun <T> attempt(generation: Long, record: (Attempt<T>) -> Unit, io: () -> T) {
        val started = synchronized(this) {
            if (this.generation != generation) return
            clock().also { record(Attempt.Running(it)) }
        }
        val result = catching(io)
        synchronized(this) { if (this.generation == generation) record(Attempt.Done(started, clock(), result)) }
    }

    private fun currentSources(): List<SourceOutcome> = sources.values.map {
        if (it.source.kind == CandidateSource.Kind.SHARED) sharedOutcome(it, sharedRead) else it
    }

    private fun sharedOutcome(pending: SourceOutcome, attempt: Attempt<CandidateCatalog.Snapshot>?): SourceOutcome {
        if (attempt == null) return pending
        if (expired(attempt, SOURCE_NANOS)) {
            return failed(pending.source, listOf(), SourceProblem(SourceProblem.Kind.DEADLINE, "Source response deadline exceeded"))
        }
        return when (attempt) {
            is Attempt.Running -> pending
            is Attempt.Done -> resolved(pending.source, attempt.result)
        }
    }

    /** This generation's evidence for [path]; `null` while queued or running. */
    private fun observe(path: Path): CandidateObservation? {
        val attempt = inspections[path] ?: return null
        if (expired(attempt, METADATA_NANOS)) {
            return CandidateObservation.unknown(path, generation, Reason.DEADLINE, "Inspection response deadline exceeded")
        }
        return when (attempt) {
            is Attempt.Running -> null
            is Attempt.Done -> attempt.result.getOrElse {
                CandidateObservation.unknown(path, generation, Reason.IO_ERROR, it.toString())
            }
        }
    }

    private fun pending(path: Path): CandidateObservation =
        CandidateObservation(path, Kind.PENDING, null, generation, Instant.now(), listOf())

    private fun rootFailure(root: Path): Diagnostic? {
        val attempt = anchor ?: return null
        if (expired(attempt, METADATA_NANOS)) return Diagnostic(root, Reason.DEADLINE, "Root inspection timed out")
        val failure = (attempt as? Attempt.Done)?.result?.exceptionOrNull() ?: return null
        val reason = if (failure is AccessDeniedException) Reason.ACCESS_DENIED else Reason.IO_ERROR
        return Diagnostic(root, reason, failure.toString())
    }

    private fun expired(attempt: Attempt<*>, limit: Long): Boolean = when (attempt) {
        is Attempt.Running -> clock() - attempt.started
        is Attempt.Done -> attempt.finished - attempt.started
    } >= limit

    @ConsistentCopyVisibility
    data class Request private constructor(val root: Path, val sharedLocation: Path?) {
        companion object {
            /** Both paths must be absolute; identity uses their normalized forms. */
            fun of(root: Path, sharedLocation: Path?): Request =
                Request(normalized(root), sharedLocation?.let(::normalized))
        }
    }

    enum class SourceStatus { PENDING, CURRENT, FAILED }

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

    /** One piece of background I/O. Times come from the injected clock. */
    private sealed interface Attempt<out T> {
        val started: Long

        data class Running(override val started: Long) : Attempt<Nothing>

        data class Done<T>(override val started: Long, val finished: Long, val result: kotlin.Result<T>) : Attempt<T>
    }
}

/**
 * Process-wide bounds on discovery threads, shared by every session so that reopening setup cannot add threads
 * stuck on a hung mount. A blocked `stat` ignores interrupts, so the bound comes from never starting more work:
 *
 * - Runs are chained. Each starts only after the previous run has returned, and a run inspects at most [n] paths
 *   at a time, so at most [n] inspections are in flight across refreshes and sessions.
 * - At most one shared-list read is in flight; a refresh during it reports `PREVIOUS_PENDING`.
 */
internal class Workers(val n: Int = DISCOVERY_CONCURRENCY) {
    val sharedRead = AtomicBoolean()
    private var last: Thread? = null

    @Synchronized
    fun chain(run: () -> Unit) {
        val previous = last
        last = Thread.ofVirtual().name("homelight-discovery").start {
            previous?.join()
            run()
        }
    }
}

private const val SOURCE_NANOS = 5_000_000_000L

internal const val METADATA_NANOS = 5_000_000_000L

private val PROCESS_WORKERS = Workers()

private class NotRegular(path: Path) : IOException("Shared source is not a regular file: $path")

private fun readShared(location: Path): ByteArray {
    // Following the explicitly selected shared-file location is permitted. A later FIFO replacement may
    // block open; the single in-flight read and the deadline still bound it.
    if (!Files.readAttributes(location, BasicFileAttributes::class.java).isRegularFile) {
        throw NotRegular(location)
    }
    return Files.newInputStream(location).use { it.readNBytes(CandidateParser.MAX_BYTES + 1) }
}

// Only an Exception is an outcome of the work; an Error still propagates.
private inline fun <T> catching(work: () -> T): Result<T> =
    try {
        Result.success(work())
    } catch (e: Exception) {
        Result.failure(e)
    }

private fun resolved(source: CandidateSource, result: Result<CandidateCatalog.Snapshot>): SourceOutcome =
    result.fold(
        { catalog ->
            if (catalog.accepted()) SourceOutcome(source, catalog, SourceStatus.CURRENT, listOf(), listOf())
            else failed(source, catalog.diagnostics, null)
        },
        { failed(source, listOf(), sourceProblem(it)) },
    )

private fun failed(source: CandidateSource, diagnostics: List<CandidateDiagnostic>, problem: SourceProblem?): SourceOutcome =
    SourceOutcome(source, null, SourceStatus.FAILED, diagnostics, listOfNotNull(problem))

private fun sourceProblem(failure: Throwable): SourceProblem {
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
