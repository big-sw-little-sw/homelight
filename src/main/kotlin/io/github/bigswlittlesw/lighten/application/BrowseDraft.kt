package io.github.bigswlittlesw.homelight.application

import io.github.bigswlittlesw.homelight.discovery.CandidateDiscovery
import io.github.bigswlittlesw.homelight.discovery.CandidateObservation
import java.nio.file.Path

/**
 * What Browse shows for one Configuration draft: the suggestions found for its source root, joined with its
 * relocations by source path. It is built from the draft whenever Browse needs it, so it never goes stale, and
 * discovery never edits the draft.
 *
 * `sources` holds each relocation's source as the loader resolves it, or null while it cannot be resolved (a row
 * still being typed). `kept` holds sources taken out of the draft during this Browse visit: they stay listed, so a
 * directory no list suggests can be added back.
 */
class BrowseDraft(
    val sourceRoot: Path, sources: List<Path?>, val discovery: CandidateDiscovery.Result?, kept: Set<Path> = setOf(),
) {
    private val sources: List<Path?> = sources.toList()
    private val kept: Set<Path> = kept.toSet()

    /** The draft's relocations in order, then the suggestions not in it, then the kept sources neither lists. */
    fun entries(): List<Entry> {
        val candidates = discovery?.candidates.orEmpty().associateBy { it.catalog.sourcePath }
        val rows = sources.mapIndexed { row, path -> Entry(path, row, path?.let { candidates[it] }) }
        val used = sources.filterNotNull().toSet()
        return rows + candidates.filterKeys { it !in used }.map { (path, candidate) -> Entry(path, null, candidate) } +
            kept.filter { it !in used && it !in candidates }.map { Entry(it, null, null) }
    }

    /**
     * A suggestion can be added when it is not in the draft and was seen as a directory or as missing. A kept source
     * can be added back: it was in the draft a moment ago.
     */
    fun canAdd(entry: Entry): Boolean {
        if (entry.row != null) return false
        val candidate = entry.discovery ?: return entry.sourcePath in kept
        return when (candidate.observation.kind) {
            CandidateObservation.Kind.DIRECTORY, CandidateObservation.Kind.MISSING -> true
            CandidateObservation.Kind.PENDING, CandidateObservation.Kind.LINK, CandidateObservation.Kind.REGULAR_FILE,
            CandidateObservation.Kind.OTHER, CandidateObservation.Kind.INACCESSIBLE,
            CandidateObservation.Kind.BLOCKED_BY_LINK, CandidateObservation.Kind.BLOCKED_BY_NON_DIRECTORY,
            CandidateObservation.Kind.UNKNOWN,
            -> false
        }
    }

    /** `row` is the relocation's index in the draft, or null for a suggestion not in it. */
    data class Entry(val sourcePath: Path?, val row: Int?, val discovery: CandidateDiscovery.Candidate?)
}

/**
 * One discovery for one Configuration session, and the latest result that answers its current check. The UI
 * thread calls it; workers only publish snapshots, so a result for an earlier check is never shown.
 */
class Suggestions(private val worker: CandidateDiscovery) : AutoCloseable {
    private var generation = -1L
    private var latest: CandidateDiscovery.Result? = null
    var request: CandidateDiscovery.Request? = null
        private set

    /** Nonblocking: starts checking `sourceRoot` and the suggestion list at `list`, and forgets the last result. */
    fun check(sourceRoot: Path, list: Path?) {
        generation = worker.refresh(sourceRoot, list)
        request = CandidateDiscovery.Request.of(sourceRoot, list)
        latest = null
    }

    fun result(): CandidateDiscovery.Result? {
        val snapshot = worker.snapshot()
        if (snapshot.generation == generation && snapshot.request == request) latest = snapshot
        return latest
    }

    override fun close() = worker.close()
}
