package io.github.bigswlittlesw.homelight.application

import io.github.bigswlittlesw.homelight.config.ConfigurationDraft
import io.github.bigswlittlesw.homelight.config.Relocation
import io.github.bigswlittlesw.homelight.config.WhenAdoptingTarget
import io.github.bigswlittlesw.homelight.config.WhenOnlyTargetExists
import io.github.bigswlittlesw.homelight.config.WhenSourceAndTargetDirectoriesExist
import io.github.bigswlittlesw.homelight.config.defaultArchiveRoot
import io.github.bigswlittlesw.homelight.config.isJavaBlank
import io.github.bigswlittlesw.homelight.config.normalizeSharedList
import io.github.bigswlittlesw.homelight.config.parseSharedList
import io.github.bigswlittlesw.homelight.config.validateConfiguration
import io.github.bigswlittlesw.homelight.discovery.CandidateDiscovery
import io.github.bigswlittlesw.homelight.discovery.CandidateObservation
import java.nio.file.Path

/**
 * Presentation-neutral, single-threaded setup state. Discovery never edits rows.
 * Configured relocations are read-only join/validation inputs, not an existing-file
 * editor. The caller owns discovery lifecycle and explicit create-only publication.
 */
class SetupDraft(sourceRoot: Path, targetRoot: Path, sharedList: Path?, configured: List<Relocation>) {
    private val configured: List<Relocation> = configured.toList()
    var sourceRoot: Path = absolute(sourceRoot)
        private set
    var targetRoot: Path = absolute(targetRoot)
        private set
    var sharedList: Path? = sharedList?.let(::normalizeSharedList)
        private set
    var discovery: CandidateDiscovery.Result? = null
        private set
    private val draftRows = ArrayList<Row>()
    private var generation: Long = -1

    val rows: List<Row> get() = draftRows.toList()

    /** Explicit root edits re-resolve relative row values; observations from the previous request cannot reattach. */
    fun roots(source: Path, target: Path) {
        val nextSource = absolute(source)
        val nextTarget = absolute(target)
        sourceRoot = nextSource
        targetRoot = nextTarget
        invalidateDiscovery()
    }

    fun sharedList(value: String) {
        sharedList = parseSharedList(value)
        invalidateDiscovery()
    }

    /**
     * Nonblocking. Use one discovery instance per setup session; call accept with
     * its snapshots on the same thread that edits this draft.
     */
    fun refresh(worker: CandidateDiscovery) {
        generation = worker.refresh(sourceRoot, sharedList)
        discovery = null
    }

    fun accept(result: CandidateDiscovery.Result): Boolean {
        if (generation < 0 || result.generation != generation
            || result.request != CandidateDiscovery.Request.of(sourceRoot, sharedList)
        ) return false
        discovery = result
        return true
    }

    /**
     * Manual rows may be incomplete while edited. Validate/Save checks all rows;
     * no duplicate or malformed row is silently discarded by the join.
     */
    fun append(row: Row) {
        draftRows.add(row)
    }

    fun edit(index: Int, row: Row) {
        draftRows[index] = row
    }

    fun remove(index: Int) {
        draftRows.removeAt(index)
    }

    /**
     * Eligibility requires an unselected candidate currently observed as a directory
     * or missing. This does not replace whole-draft validation at Add/Save.
     */
    fun canAdd(entry: Entry): Boolean {
        if (entry.configured != null || entry.draft != null) return false
        val candidate = entry.discovery ?: return false
        return when (candidate.observation.kind) {
            CandidateObservation.Kind.DIRECTORY, CandidateObservation.Kind.MISSING -> true
            CandidateObservation.Kind.PENDING, CandidateObservation.Kind.LINK, CandidateObservation.Kind.REGULAR_FILE,
            CandidateObservation.Kind.OTHER, CandidateObservation.Kind.INACCESSIBLE,
            CandidateObservation.Kind.BLOCKED_BY_LINK, CandidateObservation.Kind.BLOCKED_BY_NON_DIRECTORY,
            CandidateObservation.Kind.UNKNOWN,
            -> false
        }
    }

    /**
     * Currently observed directories and missing paths can be added through discovery.
     * Advice never participates. Validate before mutation so rejected additions
     * preserve all prior selections, including temporarily invalid manual edits.
     */
    fun add(source: Path) {
        val identity = source.toAbsolutePath().normalize()
        require(entries().any { it.sourcePath == identity && canAdd(it) }) {
            "Add requires an unselected path currently observed as a directory or missing: $identity"
        }
        val relative = sourceRoot.relativize(identity).toString()
        val row = Row(relative, relative)
        validate(rows + row)
        draftRows.add(row)
    }

    fun validate(): ConfigurationDraft = validate(rows)

    private fun validate(proposed: List<Row>): ConfigurationDraft {
        val relocations = configured + proposed.map { it.resolve(sourceRoot, targetRoot) }
        val draft = ConfigurationDraft.of(targetRoot, relocations, sharedList)
        validateConfiguration(draft)
        return draft
    }

    /**
     * Configured rows, draft rows (including invalid/duplicate edits), then new
     * candidates. An empty source identity denotes an invalid relative source.
     * Draft membership is selection; candidates without a row are unselected.
     */
    fun entries(): List<Entry> {
        val candidates = candidates()
        val used = HashSet<Path>()
        val entries = ArrayList<Entry>()
        for (relocation in configured) {
            val path = relocation.sourcePath.toAbsolutePath().normalize()
            used.add(path)
            entries.add(Entry(path, relocation, null, candidates[path], !path.startsWith(sourceRoot)))
        }
        for (row in draftRows) {
            val path = source(row)
            if (path != null) used.add(path)
            entries.add(Entry(path, null, row, path?.let { candidates[it] }, false))
        }
        candidates.forEach { (path, candidate) ->
            if (path !in used) entries.add(Entry(path, null, null, candidate, false))
        }
        return entries
    }

    private fun candidates(): Map<Path, CandidateDiscovery.Candidate> =
        discovery?.candidates.orEmpty().associateBy { it.catalog.sourcePath }

    private fun source(row: Row): Path? =
        try {
            relative(sourceRoot, row.sourceRelative)
        } catch (exception: IllegalArgumentException) {
            null
        }

    private fun invalidateDiscovery() {
        generation = -1
        discovery = null
    }

    /**
     * Partial form data: path validity is checked at Add/Validate/Save, not while
     * typing. Original relative text and omitted policies survive refresh/root edits.
     */
    data class Row(
        val sourceRelative: String, val targetRelative: String,
        val both: WhenSourceAndTargetDirectoriesExist? = null,
        val onlyTarget: WhenOnlyTargetExists? = null,
        val adopting: WhenAdoptingTarget? = null, val archiveRoot: Path? = null,
    ) {
        /** A null `archiveRoot` is [defaultArchiveRoot]. */
        internal fun resolve(sourceRoot: Path, targetRoot: Path): Relocation {
            require(archiveRoot == null || archiveRoot.isAbsolute) { "Archive root must be an absolute path" }
            val source = relative(sourceRoot, sourceRelative)
            return Relocation(
                source, relative(targetRoot, targetRelative), both, onlyTarget, adopting,
                archiveRoot?.normalize() ?: defaultArchiveRoot(source),
            )
        }
    }

    data class Entry(
        val sourcePath: Path?, val configured: Relocation?, val draft: Row?,
        val discovery: CandidateDiscovery.Candidate?, val outsideRoot: Boolean,
    )
}

private fun absolute(path: Path): Path {
    require(path.isAbsolute) { "Storage roots must be absolute" }
    return path.normalize()
}

private fun relative(root: Path, value: String): Path {
    require(!value.isJavaBlank()) { "Relocation paths cannot be blank" }
    val path = Path.of(value)
    val resolved = root.resolve(path).normalize()
    require(!path.isAbsolute && resolved != root && resolved.startsWith(root)) {
        "Relocation paths must be relative and nested below their root"
    }
    return resolved
}
