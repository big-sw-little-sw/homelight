package io.github.bigswlittlesw.homelight.discovery

import io.github.bigswlittlesw.homelight.config.CandidateCatalog
import io.github.bigswlittlesw.homelight.config.CandidateDiagnostic
import io.github.bigswlittlesw.homelight.config.CandidateParser
import io.github.bigswlittlesw.homelight.config.CandidateSource
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
import java.util.Objects
import java.util.Optional
import java.util.concurrent.Callable
import java.util.concurrent.Semaphore
import java.util.function.Function
import java.util.function.LongSupplier

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
    private val clock: LongSupplier,
    private val sharedReader: SharedReader,
    private val bundledReader: Function<Path, CandidateCatalog.Snapshot>,
    private val metadata: CandidateMetadata,
) : AutoCloseable {
    // Fields guarded by this instance's monitor, as in the Java original.
    private var generation: Long = 0
    private var closed = false
    private var request: Request? = null
    private val sources = LinkedHashMap<CandidateSource, SourceOutcome>()
    private val sourceWork = LinkedHashMap<CandidateSource, Work<CandidateCatalog.Snapshot>>()
    private var anchorWork: Work<CandidateMetadata.Anchor>? = null
    private var anchor: CandidateMetadata.Anchor? = null
    private var rootFailure: Optional<Diagnostic> = Optional.empty()
    private var inspectionWork: Work<CandidateObservation>? = null
    private var inspectionPath: Path? = null
    private val observations = HashMap<Path, CandidateObservation>()
    private val attempted = HashSet<Path>()
    private val pending = LinkedHashSet<Path>()

    constructor() : this(
        PROCESS_LANES, LongSupplier { System.nanoTime() }, SharedReader { readShared(it) },
        Function { CandidateCatalog.bundled(it) }, CandidateMetadata(),
    )

    /** Paths must already be absolute; no home expansion or location persistence. */
    @Synchronized
    fun refresh(root: Path, sharedLocation: Optional<Path>): Long {
        if (closed) throw IllegalStateException("Discovery is closed")
        val next = Request(normalized(root), sharedLocation.map { normalized(it) })
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
        rootFailure = Optional.empty()
        val currentRoot = next.root
        startSource(CandidateCatalog.BUNDLED, lanes.bundled) { bundledReader.apply(currentRoot) }
        next.sharedLocation.ifPresent { location ->
            val source = CandidateSource(CandidateSource.Kind.SHARED, location.toString())
            startSource(source, lanes.shared) {
                CandidateParser().parse(
                    source, currentRoot,
                    sharedReader.read(location),
                )
            }
        }
        anchorWork = start(lanes.filesystem) { metadata.anchor(currentRoot) }
        if (anchorWork == null) {
            rootFailure = Optional.of(
                Diagnostic(
                    root, Reason.CAPACITY,
                    "Filesystem capacity occupied; refresh when earlier operations finish",
                ),
            )
        }
        return generation
    }

    /**
     * Returned collections and observations are immutable. No aggregate byte
     * total is supplied. Ancestors identify overlapping catalog candidates.
     */
    @Synchronized
    fun snapshot(): Result {
        val request = this.request
            ?: return Result(generation, Optional.empty(), java.util.List.of(), java.util.List.of(), Optional.empty())
        collectSources()
        collectAnchor()
        collectInspection()
        val catalogs = sources.values.stream().flatMap { s -> s.catalog.stream() }.toList()
        val candidates = CandidateCatalog.merge(catalogs).candidates
        val identities = HashSet<Path>()
        candidates.forEach { c -> identities.add(c.sourcePath) }
        observations.keys.retainAll(identities)
        val rows = ArrayList<Candidate>()
        for (candidate in candidates) {
            val path = candidate.sourcePath
            val sourceStale = candidate.definitions.stream()
                .anyMatch { d -> sources[d.source]!!.status != SourceStatus.CURRENT }
            var observation = observations[path]
            if (rootFailure.isPresent) {
                val failure = rootFailure.orElseThrow()
                observation = failedObservation(path, failure.reason, failure.detail)
            } else if (observation == null) {
                observation = CandidateObservation(
                    path, Kind.PENDING,
                    Optional.empty(), generation,
                    Instant.now(), false,
                    if (path == inspectionPath) java.util.List.of()
                    else java.util.List.of(Diagnostic(path, Reason.CAPACITY, "Waiting for serial inspection")),
                )
            }
            if (sourceStale) observation = observation.retained()
            val ancestors = ArrayList<Path>()
            var parent: Path? = path.parent
            while (parent != null && parent.startsWith(request.root)) {
                if (identities.contains(parent)) ancestors.add(parent)
                parent = parent.parent
            }
            rows.add(Candidate(candidate, observation, ancestors))
        }
        return Result(generation, Optional.of(request), ArrayList(sources.values), rows, rootFailure)
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
        rootFailure = Optional.empty()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        cancel()
        closed = true
    }

    private fun startSource(source: CandidateSource, lane: Semaphore, read: Callable<CandidateCatalog.Snapshot>) {
        val old = sources[source]
        sources[source] = SourceOutcome(
            source, if (old == null) Optional.empty() else old.catalog,
            SourceStatus.PENDING, java.util.List.of(), java.util.List.of(),
        )
        val work = start(lane, read)
        if (work == null) {
            sourceFailure(
                source, java.util.List.of(),
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
            val entry = iterator.next()
            val work = entry.value
            val done = work.completion
            if (expired(work, done, SOURCE_NANOS)) {
                sourceFailure(
                    entry.key, java.util.List.of(),
                    SourceProblem(
                        SourceProblem.Kind.DEADLINE,
                        "Source response deadline exceeded",
                    ),
                )
            } else if (done == null) continue
            else if (done.failure != null) {
                sourceFailure(entry.key, java.util.List.of(), sourceProblem(done.failure))
            } else if (!done.value!!.accepted()) {
                sourceFailure(entry.key, done.value.diagnostics, null)
            } else {
                sources[entry.key] = SourceOutcome(
                    entry.key, Optional.of(done.value),
                    SourceStatus.CURRENT, java.util.List.of(), java.util.List.of(),
                )
                done.value.definitions.forEach { d ->
                    if (!attempted.contains(d.sourcePath)) pending.add(d.sourcePath)
                }
            }
            iterator.remove()
        }
    }

    private fun sourceFailure(source: CandidateSource, diagnostics: List<CandidateDiagnostic>, problem: SourceProblem?) {
        val catalog = sources[source]!!.catalog
        sources[source] = SourceOutcome(
            source, catalog,
            if (catalog.isPresent) SourceStatus.STALE else SourceStatus.FAILED,
            diagnostics, if (problem == null) java.util.List.of() else java.util.List.of(problem),
        )
    }

    private fun collectAnchor() {
        val anchorWork = this.anchorWork ?: return
        val done = anchorWork.completion
        if (expired(anchorWork, done, METADATA_NANOS)) {
            rootFailure = Optional.of(Diagnostic(request!!.root, Reason.DEADLINE, "Root inspection timed out"))
        } else if (done == null) return
        else if (done.failure != null) {
            val reason = if (done.failure is AccessDeniedException) Reason.ACCESS_DENIED else Reason.IO_ERROR
            rootFailure = Optional.of(Diagnostic(request!!.root, reason, done.failure.toString()))
        } else anchor = done.value
        this.anchorWork = null
    }

    private fun collectInspection() {
        val inspectionWork = this.inspectionWork ?: return
        val inspectionPath = this.inspectionPath!!
        val done = inspectionWork.completion
        if (expired(inspectionWork, done, METADATA_NANOS)) {
            observations[inspectionPath] = failedObservation(
                inspectionPath, Reason.DEADLINE,
                "Inspection response deadline exceeded",
            )
        } else if (done == null) return
        else if (done.failure != null) {
            observations[inspectionPath] = failedObservation(inspectionPath, Reason.IO_ERROR, done.failure.toString())
        } else {
            var value = done.value!!
            if (value.kind == Kind.INACCESSIBLE || value.kind == Kind.UNKNOWN) {
                val prior = observations[inspectionPath]
                if (prior != null) value = retainedFailure(prior, value.diagnostics)
            }
            observations[inspectionPath] = value
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
        if (anchor == null || inspectionWork != null || rootFailure.isPresent) return
        if (!pending.isEmpty()) {
            val path = pending.first()
            val currentAnchor = anchor!!
            val currentGeneration = generation
            val work = start(lanes.filesystem) { metadata.inspect(currentAnchor, path, currentGeneration) }
            if (work != null) {
                attempted.add(path)
                pending.remove(path)
                inspectionPath = path
                inspectionWork = work
            }
        }
    }

    private fun failedObservation(path: Path, reason: Reason, detail: String): CandidateObservation {
        val previous = observations[path]
        return if (previous == null) CandidateObservation.unknown(path, generation, reason, detail)
        else retainedFailure(previous, java.util.List.of(Diagnostic(path, reason, detail)))
    }

    private fun <T> expired(work: Work<T>, completion: Completion<T>?, limit: Long): Boolean =
        (if (completion == null) clock.asLong else completion.finished) - work.started >= limit

    private fun <T> start(lane: Semaphore, action: Callable<T>): Work<T>? {
        if (!lane.tryAcquire()) return null
        val work = Work<T>(clock.asLong)
        val startedGeneration = generation
        try {
            Thread.ofPlatform().daemon().name("homelight-discovery").start {
                val done: Completion<T> = try {
                    val value = action.call()
                    Completion(value, null, clock.asLong)
                } catch (e: Exception) {
                    Completion(null, e, clock.asLong)
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
        } catch (e: RuntimeException) {
            lane.release()
            throw e
        } catch (e: Error) {
            lane.release()
            throw e
        }
        return work
    }

    /**
     * Not a `@JvmRecord data class`: the constructor normalizes its paths, which a Kotlin record cannot do.
     * Accessors keep the record names; equality and `toString` match the record this replaces.
     */
    class Request(root: Path, sharedLocation: Optional<Path>) {
        @get:JvmName("root")
        val root: Path = normalized(root)

        @get:JvmName("sharedLocation")
        val sharedLocation: Optional<Path> = sharedLocation.map { normalized(it) }

        override fun equals(other: Any?): Boolean = other is Request
                && root == other.root
                && sharedLocation == other.sharedLocation

        override fun hashCode(): Int = Objects.hash(root, sharedLocation)

        override fun toString(): String = "Request[root=$root, sharedLocation=$sharedLocation]"
    }

    enum class SourceStatus { PENDING, CURRENT, STALE, FAILED }

    @JvmRecord
    data class SourceProblem(val kind: Kind, val detail: String) {
        enum class Kind { MISSING, UNREADABLE, NOT_REGULAR, IO_ERROR, DEADLINE, PREVIOUS_PENDING }
    }

    // SourceOutcome, Candidate and Result are plain classes rather than `@JvmRecord data class`es because
    // their constructors copy lists. Accessors keep the record names; equality and `toString` match the
    // records they replace.

    class SourceOutcome(
        source: CandidateSource, catalog: Optional<CandidateCatalog.Snapshot>,
        status: SourceStatus, diagnostics: List<CandidateDiagnostic>,
        problems: List<SourceProblem>,
    ) {
        @get:JvmName("source")
        val source: CandidateSource = source

        @get:JvmName("catalog")
        val catalog: Optional<CandidateCatalog.Snapshot> = catalog

        @get:JvmName("status")
        val status: SourceStatus = status

        @get:JvmName("diagnostics")
        val diagnostics: List<CandidateDiagnostic> = java.util.List.copyOf(diagnostics)

        @get:JvmName("problems")
        val problems: List<SourceProblem> = java.util.List.copyOf(problems)

        override fun equals(other: Any?): Boolean = other is SourceOutcome
                && source == other.source
                && catalog == other.catalog
                && status == other.status
                && diagnostics == other.diagnostics
                && problems == other.problems

        override fun hashCode(): Int = Objects.hash(source, catalog, status, diagnostics, problems)

        override fun toString(): String = "SourceOutcome[source=$source, catalog=$catalog, status=$status, " +
                "diagnostics=$diagnostics, problems=$problems]"
    }

    class Candidate(
        catalog: CandidateCatalog.Candidate, observation: CandidateObservation,
        ancestors: List<Path>,
    ) {
        @get:JvmName("catalog")
        val catalog: CandidateCatalog.Candidate = catalog

        @get:JvmName("observation")
        val observation: CandidateObservation = observation

        @get:JvmName("ancestors")
        val ancestors: List<Path> = java.util.List.copyOf(ancestors)

        override fun equals(other: Any?): Boolean = other is Candidate
                && catalog == other.catalog
                && observation == other.observation
                && ancestors == other.ancestors

        override fun hashCode(): Int = Objects.hash(catalog, observation, ancestors)

        override fun toString(): String = "Candidate[catalog=$catalog, observation=$observation, ancestors=$ancestors]"
    }

    class Result(
        generation: Long, request: Optional<Request>, sources: List<SourceOutcome>,
        candidates: List<Candidate>, rootFailure: Optional<Diagnostic>,
    ) {
        @get:JvmName("generation")
        val generation: Long = generation

        @get:JvmName("request")
        val request: Optional<Request> = request

        @get:JvmName("sources")
        val sources: List<SourceOutcome> = java.util.List.copyOf(sources)

        @get:JvmName("candidates")
        val candidates: List<Candidate> = java.util.List.copyOf(candidates)

        @get:JvmName("rootFailure")
        val rootFailure: Optional<Diagnostic> = rootFailure

        override fun equals(other: Any?): Boolean = other is Result
                && generation == other.generation
                && request == other.request
                && sources == other.sources
                && candidates == other.candidates
                && rootFailure == other.rootFailure

        override fun hashCode(): Int = Objects.hash(generation, request, sources, candidates, rootFailure)

        override fun toString(): String = "Result[generation=$generation, request=$request, sources=$sources, " +
                "candidates=$candidates, rootFailure=$rootFailure]"
    }

    // Package-private in Java. Internal keeps these out of the public Kotlin API; the Java tests in
    // this package still reach them because internal types and constructors are not name-mangled.

    internal fun interface SharedReader {
        @Throws(IOException::class)
        fun read(location: Path): ByteArray
    }

    internal class Lanes {
        @JvmField
        val shared = Semaphore(1)

        @JvmField
        val bundled = Semaphore(1)

        @JvmField
        val filesystem = Semaphore(1)
    }

    private class Work<T>(val started: Long) {
        @Volatile
        var completion: Completion<T>? = null
    }

    @JvmRecord
    private data class Completion<T>(val value: T?, val failure: Exception?, val finished: Long)

    private class NotRegular(path: Path) : IOException("Shared source is not a regular file: $path")

    companion object {
        private const val SOURCE_NANOS = 5_000_000_000L

        internal const val METADATA_NANOS = 5_000_000_000L

        private val PROCESS_LANES = Lanes()

        @Throws(IOException::class)
        private fun readShared(location: Path): ByteArray {
            // Following the explicitly selected shared-file location is permitted.
            // A later FIFO replacement may block open; the lane/deadline still bounds it.
            if (!Files.readAttributes(location, BasicFileAttributes::class.java).isRegularFile) {
                throw NotRegular(location)
            }
            Files.newInputStream(location).use { input ->
                return input.readNBytes(CandidateParser.MAX_BYTES + 1)
            }
        }

        private fun sourceProblem(failure: Exception): SourceProblem {
            val kind = when (failure) {
                is NoSuchFileException -> SourceProblem.Kind.MISSING
                is NotRegular -> SourceProblem.Kind.NOT_REGULAR
                is AccessDeniedException -> SourceProblem.Kind.UNREADABLE
                is SecurityException -> SourceProblem.Kind.UNREADABLE
                else -> SourceProblem.Kind.IO_ERROR
            }
            return SourceProblem(kind, failure.toString())
        }

        private fun normalized(path: Path): Path {
            if (!path.isAbsolute) throw IllegalArgumentException("Absolute path required")
            return path.normalize()
        }

        private fun retainedFailure(previous: CandidateObservation, failures: List<Diagnostic>): CandidateObservation {
            val diagnostics = ArrayList(previous.diagnostics)
            for (failure in failures) if (!diagnostics.contains(failure)) diagnostics.add(failure)
            return CandidateObservation(
                previous.path, previous.kind, previous.rawLinkTarget,
                previous.generation, previous.observedAt, true, diagnostics,
            )
        }
    }
}
