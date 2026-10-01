package io.github.bigswlittlesw.homelight.config

import java.io.IOException
import java.nio.file.Path

/**
 * Catalog contents only: no candidate filesystem access, selection, or policy.
 *
 * Every list it returns is an unmodifiable JDK copy, so neither a later change to a list passed in nor a
 * cast to a mutable list can alter a snapshot.
 */
object CandidateCatalog {
    val BUNDLED = CandidateSource(CandidateSource.Kind.BUNDLED, "/candidates.yaml")

    fun bundled(root: Path): Snapshot {
        val parser = CandidateParser()
        try {
            CandidateCatalog::class.java.getResourceAsStream(BUNDLED.location).use { input ->
                if (input == null) {
                    return parser.failure(
                        BUNDLED, root, CandidateDiagnostic.Kind.RESOURCE,
                        "Bundled candidate resource is missing",
                    )
                }
                return parser.parse(BUNDLED, root, input.readNBytes(CandidateParser.MAX_BYTES + 1))
            }
        } catch (e: IOException) {
            return parser.failure(
                BUNDLED, root, CandidateDiagnostic.Kind.RESOURCE,
                "Cannot read bundled candidate resource: " + e.message,
            )
        }
    }

    /**
     * Input order stabilizes output order; it never gives advice precedence.
     * Failed sources contribute diagnostics and no definitions.
     */
    fun merge(snapshots: List<Snapshot>): Merged {
        require(snapshots.distinctBy { it.root }.size <= 1) { "Cannot merge snapshots from different roots" }
        val candidates = snapshots.flatMap { it.definitions }
            .groupBy { it.sourcePath }
            .map { (path, definitions) -> Candidate(path, java.util.List.copyOf(definitions)) }
        return Merged(java.util.List.copyOf(candidates), java.util.List.copyOf(snapshots.flatMap { it.diagnostics }))
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
                            && definition.sourcePath == CandidateParser.resolve(root, definition.originalPath)
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
                source, CandidateParser.normalizedRoot(root),
                java.util.List.copyOf(definitions), java.util.List.copyOf(diagnostics),
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
