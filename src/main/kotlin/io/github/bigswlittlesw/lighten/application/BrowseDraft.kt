package io.github.bigswlittlesw.lighten.application

import io.github.bigswlittlesw.lighten.config.Relocation
import io.github.bigswlittlesw.lighten.config.relocationProblem
import io.github.bigswlittlesw.lighten.discovery.CandidateDiscovery
import io.github.bigswlittlesw.lighten.discovery.CandidateObservation
import io.github.bigswlittlesw.lighten.discovery.CandidateObservation.Link
import java.nio.file.Path

/**
 * What Browse shows for one Configuration draft: the suggestions found for its source root, joined with its
 * relocations by source path. It is built from the draft whenever Browse needs it, so it never goes stale, and
 * discovery never edits the draft.
 *
 * `relocations` holds each relocation's source and target as the loader resolves them, each null while it cannot be
 * resolved (a row still being typed). `kept` holds sources taken out of the draft during this Browse visit: they stay
 * listed, so a directory no list suggests can be added back. `ignored` holds the draft's ignored sources: they are
 * always listed, so they can be seen and no longer ignored, and they can't be added.
 */
class BrowseDraft(
    val sourceRoot: Path, relocations: List<Paths>, val discovery: CandidateDiscovery.Result?, kept: Set<Path> = setOf(),
    ignored: Set<Path> = setOf(),
) {
    private val relocations: List<Paths> = relocations.toList()
    private val sources: List<Path?> = relocations.map { it.source }
    private val kept: Set<Path> = kept.toSet()
    private val ignored: Set<Path> = ignored.toSet()
    // The links that block suggestions under them, by the link's path. A linked parent is listed only once it is in
    // the draft or kept, so its own row finds its link here.
    private val linkedParents: Map<Path, Link> = discovery?.candidates.orEmpty()
        .filter { it.observation.kind == CandidateObservation.Kind.BLOCKED_BY_LINK }
        .mapNotNull { it.observation.link }.associateBy { it.path }

    /**
     * The draft's relocations in order, then the suggestions not in it, then the kept and ignored sources neither
     * lists.
     */
    fun entries(): List<Entry> {
        val candidates = discovery?.candidates.orEmpty().associateBy { it.catalog.sourcePath }
        val rows = sources.mapIndexed { row, path -> Entry(path, row, path?.let { candidates[it] }) }
        val used = sources.filterNotNull().toSet()
        return rows +
            candidates.filterKeys { it !in used }.map { (path, candidate) -> Entry(path, null, candidate, path in ignored) } +
            (kept + ignored).filter { it !in used && it !in candidates }.map { Entry(it, null, null, it in ignored) }
    }

    /**
     * A suggestion can be added when it is not in the draft, not ignored, and was seen as a directory or as missing.
     * A kept source can be added back: it was in the draft a moment ago. A link is never added this way: see
     * [takeOver].
     */
    fun canAdd(entry: Entry): Boolean {
        if (entry.row != null || entry.ignored) return false
        val candidate = entry.discovery ?: return entry.sourcePath in kept && entry.sourcePath !in linkedParents
        return when (candidate.observation.kind) {
            CandidateObservation.Kind.DIRECTORY, CandidateObservation.Kind.MISSING -> true
            CandidateObservation.Kind.PENDING, CandidateObservation.Kind.LINK, CandidateObservation.Kind.REGULAR_FILE,
            CandidateObservation.Kind.OTHER, CandidateObservation.Kind.INACCESSIBLE,
            CandidateObservation.Kind.BLOCKED_BY_LINK, CandidateObservation.Kind.BLOCKED_BY_NON_DIRECTORY,
            CandidateObservation.Kind.UNKNOWN,
            -> false
        }
    }

    /** The link at `entry`'s path or at the parent that holds it, as the last check saw it. */
    fun link(entry: Entry): Link? = entry.discovery?.observation?.link ?: entry.sourcePath?.let { linkedParents[it] }

    /**
     * Taking over the link at or above `entry`: a relocation from the link to where it points, which the planner then
     * finds in sync. It changes nothing on disk, and links inside the linked directory stay as they are. Null when
     * there is no link to take over: `entry` is in the draft or ignored, or its linked parent is already in the draft
     * ([coveredBy]).
     */
    fun takeOver(entry: Entry): TakeOver? {
        if (entry.row != null || entry.ignored) return null
        val link = link(entry) ?: return null
        if (link.path in sources) return null
        val problem = when {
            link.target != Link.Target.DIRECTORY -> TakeOver.Problem.Target(link.target)
            link.path in ignored -> TakeOver.Problem.Ignored
            else -> overlap(Relocation(link.path, link.pointsTo))
        }
        return TakeOver(link, problem)
    }

    /**
     * The draft row of the linked parent that holds `entry`: the relocation moves it with the parent, so it is not
     * added on its own. Null when `entry` is in the draft, ignored, or not under a linked parent in the draft.
     */
    fun coveredBy(entry: Entry): Entry? {
        if (entry.row != null || entry.ignored) return null
        val observation = entry.discovery?.observation ?: return null
        if (observation.kind != CandidateObservation.Kind.BLOCKED_BY_LINK) return null
        val parent = observation.link?.path ?: return null
        return entries().firstOrNull { it.row != null && it.sourcePath == parent }
    }

    /**
     * Compares as written, as a save does. The relocation it names is the earlier one, so a problem between two
     * relocations already in the draft never refuses a new one.
     *
     * shortcut: two targets that are one place through a link show as blocked only on the Workspace after saving. Add
     * a real-path check here when users take over links whose targets are spelled through other links.
     */
    private fun overlap(new: Relocation): TakeOver.Problem.Overlap? {
        if (relocationProblem(listOf(new)) != null) return TakeOver.Problem.Overlap(null)
        val existing = relocations.mapNotNull { paths ->
            paths.source?.let { source -> paths.target?.let { Relocation(source, it) } }
        }
        return existing.firstOrNull { relocationProblem(listOf(it)) == null && relocationProblem(listOf(it, new)) != null }
            ?.let { TakeOver.Problem.Overlap(it.sourcePath) }
    }

    /**
     * `row` is the relocation's index in the draft, or null for a suggestion not in it. `ignored` is never true for a
     * relocation: a draft that lists a path as both is refused on save.
     */
    data class Entry(
        val sourcePath: Path?, val row: Int?, val discovery: CandidateDiscovery.Candidate?, val ignored: Boolean = false,
    )

    /** A draft relocation's source and target as the loader resolves them, each null while it cannot be resolved. */
    data class Paths(val source: Path?, val target: Path?)

    /** A link and why it cannot be taken over, or a null `problem` when it can. */
    data class TakeOver(val link: Link, val problem: Problem?) {
        sealed interface Problem {
            /** What is where the link points is not a real directory outside the source root. */
            data class Target(val target: Link.Target) : Problem

            /** The user ignores the link's path. */
            data object Ignored : Problem

            /**
             * The relocation would overlap the one from `other` in the draft, or its own source and target would
             * overlap when `other` is null.
             */
            data class Overlap(val other: Path?) : Problem
        }
    }
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
