package io.github.bigswlittlesw.lighten.config

import java.io.IOException
import java.nio.file.Path

/**
 * Reads and merges suggestion lists. It never looks at the suggested directories on disk and never decides which
 * suggestions Browse shows.
 *
 * Each [Snapshot] copies the lists it is given, so a later change to a list passed in cannot change it.
 */
object CandidateCatalog {
    val BUNDLED = CandidateSource(CandidateSource.Kind.BUNDLED, "/candidates.json")

    /** Parses the bundled list for [root]. The resource is read once per process, so later calls do no I/O. */
    fun bundled(root: Path): Snapshot {
        val parser = CandidateParser()
        return bundledBytes.fold(
            { bytes ->
                if (bytes == null) {
                    parser.failure(BUNDLED, root, CandidateDiagnostic.Kind.RESOURCE, "The built-in list is missing from Lighten")
                } else parser.parse(BUNDLED, root, bytes)
            },
            { e ->
                parser.failure(
                    BUNDLED, root, CandidateDiagnostic.Kind.RESOURCE,
                    "Cannot read the built-in list: " + e.message,
                )
            },
        )
    }

    // `null` when the resource is missing. The parser only reads the array, so sharing it is safe.
    private val bundledBytes: Result<ByteArray?> by lazy {
        try {
            Result.success(
                CandidateCatalog::class.java.getResourceAsStream(BUNDLED.location)
                    ?.use { it.readNBytes(CandidateParser.MAX_BYTES + 1) },
            )
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    /**
     * Candidates keep the order of `snapshots`. Within a candidate the user's list comes before the built-in list.
     * So the user's list decides the app and advice that Browse shows, and Details lists it first.
     * A list that failed gives its diagnostics and no definitions.
     */
    fun merge(snapshots: List<Snapshot>): Merged {
        require(snapshots.distinctBy { it.root }.size <= 1) { "Cannot merge snapshots from different roots" }
        val candidates = snapshots.flatMap { it.definitions }
            .groupBy { it.sourcePath }
            .map { (path, definitions) -> Candidate(path, definitions.sortedBy { it.source.kind != CandidateSource.Kind.SHARED }) }
        return Merged(candidates, snapshots.flatMap { it.diagnostics })
    }

    /** One source's parse result: either definitions or the diagnostics that rejected it, never both. */
    @ConsistentCopyVisibility
    data class Snapshot private constructor(
        val source: CandidateSource, val root: Path, val definitions: List<CandidateDefinition>,
        val diagnostics: List<CandidateDiagnostic>,
    ) {
        init {
            require(definitions.isEmpty() || diagnostics.isEmpty()) { "A rejected source cannot contain definitions" }
            require(
                definitions.all { definition ->
                    definition.source == source
                            && definition.sourcePath == resolveCandidatePath(root, definition.originalPath)
                },
            ) { "Definition does not belong to this source/root" }
            require(diagnostics.all { diagnostic -> diagnostic.source == source }) {
                "Diagnostic does not belong to this source"
            }
        }

        fun accepted(): Boolean = diagnostics.isEmpty()

        companion object {
            fun of(
                source: CandidateSource, root: Path, definitions: List<CandidateDefinition>,
                diagnostics: List<CandidateDiagnostic>,
            ): Snapshot = Snapshot(
                source, normalizedSourceRoot(root),
                definitions.toList(), diagnostics.toList(),
            )
        }
    }

    data class Candidate(val sourcePath: Path, val definitions: List<CandidateDefinition>) {
        init {
            require(definitions.isNotEmpty() && definitions.all { d -> d.sourcePath == sourcePath }) {
                "Candidate needs matching definition occurrences"
            }
        }
    }

    data class Merged(val candidates: List<Candidate>, val diagnostics: List<CandidateDiagnostic>)
}
