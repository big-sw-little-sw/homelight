package io.github.bigswlittlesw.homelight.application

import io.github.bigswlittlesw.homelight.config.CandidateDefinition
import io.github.bigswlittlesw.homelight.config.ConfigurationDraft
import io.github.bigswlittlesw.homelight.config.normalizeSharedList
import io.github.bigswlittlesw.homelight.config.parseSharedList
import io.github.bigswlittlesw.homelight.config.validateConfiguration
import io.github.bigswlittlesw.homelight.config.Relocation
import io.github.bigswlittlesw.homelight.config.WhenAdoptingTarget
import io.github.bigswlittlesw.homelight.config.WhenOnlyTargetExists
import io.github.bigswlittlesw.homelight.config.WhenSourceAndTargetDirectoriesExist
import io.github.bigswlittlesw.homelight.config.isJavaBlank
import io.github.bigswlittlesw.homelight.discovery.CandidateDiscovery
import io.github.bigswlittlesw.homelight.discovery.CandidateObservation
import java.nio.file.Path
import java.util.Objects
import java.util.Optional

/**
 * Presentation-neutral, single-threaded setup state. Discovery never edits rows.
 * Configured relocations are read-only join/validation inputs, not an existing-file
 * editor. The caller owns discovery lifecycle and explicit create-only publication.
 */
class SetupDraft(sourceRoot: Path, targetRoot: Path, sharedList: Optional<Path>, configured: List<Relocation>) {
    private var sourceRoot: Path = absolute(sourceRoot)
    private var targetRoot: Path = absolute(targetRoot)
    private var sharedList: Optional<Path> = sharedList.map(::normalizeSharedList)
    private val configured: List<Relocation> = java.util.List.copyOf(configured)
    private val rows = ArrayList<RowOccurrence>()
    private var discovery: Optional<CandidateDiscovery.Result> = Optional.empty()
    private var generation: Long = -1

    fun sourceRoot(): Path = sourceRoot
    fun targetRoot(): Path = targetRoot
    fun sharedList(): Optional<Path> = sharedList
    fun rows(): List<Row> = rows.stream().map(RowOccurrence::value).toList()
    fun discovery(): Optional<CandidateDiscovery.Result> = discovery

    /**
     * Explicit root edits re-resolve relative row values. Historical attribution
     * stays historical; observations from the previous request cannot reattach.
     */
    fun roots(source: Path, target: Path) {
        val nextSource = absolute(source)
        val nextTarget = absolute(target)
        sourceRoot = nextSource
        targetRoot = nextTarget
        invalidateDiscovery()
    }

    fun sharedList(value: String) {
        sharedList = Optional.ofNullable(parseSharedList(value))
        invalidateDiscovery()
    }

    /**
     * Nonblocking. Use one discovery instance per setup session; call accept with
     * its snapshots on the same thread that edits this draft.
     */
    fun refresh(worker: CandidateDiscovery) {
        generation = worker.refresh(sourceRoot, sharedList.orElse(null))
        discovery = Optional.empty()
    }

    fun accept(result: CandidateDiscovery.Result): Boolean {
        if (generation < 0 || result.generation != generation
            || result.request != CandidateDiscovery.Request.of(sourceRoot, sharedList.orElse(null))
        ) return false
        discovery = Optional.of(result)
        val candidates = candidates()
        rows.replaceAll { row -> remember(row, candidates) }
        return true
    }

    /**
     * Manual rows may be incomplete while edited. Validate/Save checks all rows;
     * no duplicate or malformed row is silently discarded by the join.
     */
    fun append(row: Row) {
        rows.add(remember(RowOccurrence(row, java.util.List.of()), candidates()))
    }

    fun edit(index: Int, row: Row) {
        val previous = rows[index]
        rows[index] = remember(RowOccurrence(row, previous.history), candidates())
    }

    fun remove(index: Int) {
        rows.removeAt(index)
    }

    /**
     * Eligibility requires an unselected candidate currently observed as a directory
     * or missing. This does not replace whole-draft validation at Add/Save.
     */
    fun canAdd(entry: Entry): Boolean {
        if (entry.configured.isPresent || entry.draft.isPresent) return false
        val candidate = entry.discovery.orElse(null)
        // Source attribution may be stale even after fresh metadata inspection.
        if (candidate == null || candidate.observation.generation != generation) return false
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
        val entry = entries().stream().filter { e -> e.sourcePath == Optional.of(identity) }.findFirst()
        if (entry.filter(this::canAdd).isEmpty) {
            throw IllegalArgumentException(
                "Add requires an unselected path currently observed as a directory or missing: $identity",
            )
        }
        val relative = sourceRoot.relativize(identity).toString()
        val row = Row(relative, relative)
        val proposed = ArrayList(rows())
        proposed.add(row)
        validate(proposed)
        rows.add(RowOccurrence(row, entry.orElseThrow().discovery.orElseThrow().catalog.definitions))
    }

    fun validate(): ConfigurationDraft = validate(rows())

    private fun validate(proposed: List<Row>): ConfigurationDraft {
        val relocations = ArrayList(configured)
        proposed.forEach { row -> relocations.add(row.resolve(sourceRoot, targetRoot)) }
        val draft = ConfigurationDraft.of(targetRoot, relocations, sharedList.orElse(null))
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
            entries.add(
                Entry(
                    Optional.of(path), Optional.of(relocation), Optional.empty(),
                    Optional.ofNullable(candidates[path]), java.util.List.of(), !path.startsWith(sourceRoot),
                ),
            )
        }
        for (occurrence in rows) {
            val row = occurrence.value
            val path = source(row)
            path.ifPresent { used.add(it) }
            entries.add(
                Entry(
                    path, Optional.empty(), Optional.of(row), path.map { candidates[it] },
                    occurrence.history, false,
                ),
            )
        }
        candidates.forEach { path, candidate ->
            if (!used.contains(path)) entries.add(
                Entry(
                    Optional.of(path), Optional.empty(), Optional.empty(),
                    Optional.of(candidate), java.util.List.of(), false,
                ),
            )
        }
        return java.util.List.copyOf(entries)
    }

    private fun candidates(): Map<Path, CandidateDiscovery.Candidate> {
        val result = LinkedHashMap<Path, CandidateDiscovery.Candidate>()
        discovery.ifPresent { snapshot ->
            snapshot.candidates.forEach { candidate ->
                result[candidate.catalog.sourcePath] = candidate
            }
        }
        return result
    }

    private fun source(row: Row): Optional<Path> {
        try {
            return Optional.of(relative(sourceRoot, row.sourceRelative))
        } catch (exception: IllegalArgumentException) {
            return Optional.empty()
        }
    }

    private fun remember(row: RowOccurrence, candidates: Map<Path, CandidateDiscovery.Candidate>): RowOccurrence =
        source(row.value).map<CandidateDiscovery.Candidate> { candidates[it] }.map { candidate ->
            val definitions = LinkedHashSet(row.history)
            definitions.addAll(candidate.catalog.definitions)
            RowOccurrence(row.value, java.util.List.copyOf(definitions))
        }.orElse(row)

    /**
     * Each list position owns its history, even when row values or object references match.
     *
     * Not a `@JvmRecord data class`: the constructor copies `history`, which a Kotlin record cannot do.
     * Equality and `toString` match the record this replaces.
     */
    private class RowOccurrence(val value: Row, history: List<CandidateDefinition>) {
        val history: List<CandidateDefinition> = java.util.List.copyOf(history)

        override fun equals(other: Any?): Boolean = other is RowOccurrence
                && value == other.value
                && history == other.history

        override fun hashCode(): Int = Objects.hash(value, history)

        override fun toString(): String = "RowOccurrence[value=$value, history=$history]"
    }

    private fun invalidateDiscovery() {
        generation = -1
        discovery = Optional.empty()
    }

    /**
     * Partial form data: path validity is checked at Add/Validate/Save, not while
     * typing. Original relative text and omitted policies survive refresh/root edits.
     */
    @JvmRecord
    data class Row(
        val sourceRelative: String, val targetRelative: String,
        val both: Optional<WhenSourceAndTargetDirectoriesExist>,
        val onlyTarget: Optional<WhenOnlyTargetExists>,
        val adopting: Optional<WhenAdoptingTarget>, val archiveRoot: Optional<Path>,
    ) {
        constructor(sourceRelative: String, targetRelative: String) : this(
            sourceRelative, targetRelative, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
        )

        // Private in Java, where the enclosing class could still call it; Kotlin needs internal for that.
        internal fun resolve(sourceRoot: Path, targetRoot: Path): Relocation {
            if (archiveRoot.filter { path -> !path.isAbsolute }.isPresent) {
                throw IllegalArgumentException("Archive root must be an absolute path")
            }
            return Relocation(
                relative(sourceRoot, sourceRelative), relative(targetRoot, targetRelative),
                both.orElse(null), onlyTarget.orElse(null), adopting.orElse(null), archiveRoot.map(Path::normalize).orElse(null),
            )
        }
    }

    /**
     * `lastKnownDefinitions` is session history, never a current assertion or
     * persisted configuration. Use discovery/source outcomes for current freshness.
     *
     * Not a `@JvmRecord data class`: the constructor copies `lastKnownDefinitions`, which a Kotlin record
     * cannot do. Accessors keep the record names; equality and `toString` match the record this replaces.
     */
    class Entry(
        sourcePath: Optional<Path>, configured: Optional<Relocation>, draft: Optional<Row>,
        discovery: Optional<CandidateDiscovery.Candidate>,
        lastKnownDefinitions: List<CandidateDefinition>, outsideRoot: Boolean,
    ) {
        @get:JvmName("sourcePath")
        val sourcePath: Optional<Path> = sourcePath

        @get:JvmName("configured")
        val configured: Optional<Relocation> = configured

        @get:JvmName("draft")
        val draft: Optional<Row> = draft

        @get:JvmName("discovery")
        val discovery: Optional<CandidateDiscovery.Candidate> = discovery

        @get:JvmName("lastKnownDefinitions")
        val lastKnownDefinitions: List<CandidateDefinition> = java.util.List.copyOf(lastKnownDefinitions)

        @get:JvmName("outsideRoot")
        val outsideRoot: Boolean = outsideRoot

        override fun equals(other: Any?): Boolean = other is Entry
                && sourcePath == other.sourcePath
                && configured == other.configured
                && draft == other.draft
                && discovery == other.discovery
                && lastKnownDefinitions == other.lastKnownDefinitions
                && outsideRoot == other.outsideRoot

        override fun hashCode(): Int =
            Objects.hash(sourcePath, configured, draft, discovery, lastKnownDefinitions, outsideRoot)

        override fun toString(): String = "Entry[sourcePath=$sourcePath, configured=$configured, draft=$draft, " +
                "discovery=$discovery, lastKnownDefinitions=$lastKnownDefinitions, outsideRoot=$outsideRoot]"
    }

    private companion object {
        fun absolute(path: Path): Path {
            if (!path.isAbsolute) throw IllegalArgumentException("Storage roots must be absolute")
            return path.normalize()
        }

        fun relative(root: Path, value: String): Path {
            if (value.isJavaBlank()) throw IllegalArgumentException("Relocation paths cannot be blank")
            val path = Path.of(value)
            val resolved = root.resolve(path).normalize()
            if (path.isAbsolute || resolved == root || !resolved.startsWith(root)) {
                throw IllegalArgumentException("Relocation paths must be relative and nested below their root")
            }
            return resolved
        }
    }
}
