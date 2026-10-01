package io.github.bigswlittlesw.homelight.config

import java.io.IOException
import java.nio.file.Path
import java.util.Objects

/** Catalog contents only: no candidate filesystem access, selection, or policy. */
object CandidateCatalog {
    @JvmField
    val BUNDLED = CandidateSource(CandidateSource.Kind.BUNDLED, "/candidates.yaml")

    @JvmStatic
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
    @JvmStatic
    fun merge(snapshots: List<Snapshot>): Merged {
        val paths = LinkedHashMap<Path, MutableList<CandidateDefinition>>()
        val diagnostics = ArrayList<CandidateDiagnostic>()
        var root: Path? = null
        for (snapshot in snapshots) {
            if (root != null && root != snapshot.root) {
                throw IllegalArgumentException("Cannot merge snapshots from different roots")
            }
            root = snapshot.root
            diagnostics.addAll(snapshot.diagnostics)
            for (definition in snapshot.definitions) {
                paths.computeIfAbsent(definition.sourcePath) { ArrayList() }.add(definition)
            }
        }
        val candidates = ArrayList<Candidate>()
        paths.forEach { path, definitions -> candidates.add(Candidate(path, definitions)) }
        return Merged(candidates, diagnostics)
    }

    // Snapshot, Candidate and Merged are plain classes rather than `@JvmRecord data class`es because their
    // constructors normalize components. Accessors keep the record names; equality and `toString` match
    // the records they replace.

    class Snapshot(
        source: CandidateSource, root: Path, definitions: List<CandidateDefinition>,
        diagnostics: List<CandidateDiagnostic>,
    ) {
        @get:JvmName("source")
        val source: CandidateSource = source

        @get:JvmName("root")
        val root: Path = CandidateParser.normalizedRoot(root)

        @get:JvmName("definitions")
        val definitions: List<CandidateDefinition> = java.util.List.copyOf(definitions)

        @get:JvmName("diagnostics")
        val diagnostics: List<CandidateDiagnostic> = java.util.List.copyOf(diagnostics)

        init {
            if (!this.definitions.isEmpty() && !this.diagnostics.isEmpty()) {
                throw IllegalArgumentException("A rejected source cannot contain definitions")
            }
            for (definition in this.definitions) {
                if (definition.source != this.source
                    || definition.sourcePath != CandidateParser.resolve(this.root, definition.originalPath)
                ) {
                    throw IllegalArgumentException("Definition does not belong to this source/root")
                }
            }
            if (this.diagnostics.stream().anyMatch { diagnostic -> diagnostic.source != this.source }) {
                throw IllegalArgumentException("Diagnostic does not belong to this source")
            }
        }

        fun accepted(): Boolean = diagnostics.isEmpty()

        override fun equals(other: Any?): Boolean = other is Snapshot
                && source == other.source
                && root == other.root
                && definitions == other.definitions
                && diagnostics == other.diagnostics

        override fun hashCode(): Int = Objects.hash(source, root, definitions, diagnostics)

        override fun toString(): String =
            "Snapshot[source=$source, root=$root, definitions=$definitions, diagnostics=$diagnostics]"
    }

    class Candidate(sourcePath: Path, definitions: List<CandidateDefinition>) {
        @get:JvmName("sourcePath")
        val sourcePath: Path = sourcePath

        @get:JvmName("definitions")
        val definitions: List<CandidateDefinition> = java.util.List.copyOf(definitions)

        init {
            if (this.definitions.isEmpty() || this.definitions.stream().anyMatch { d -> d.sourcePath != this.sourcePath }) {
                throw IllegalArgumentException("Candidate needs matching definition occurrences")
            }
        }

        override fun equals(other: Any?): Boolean = other is Candidate
                && sourcePath == other.sourcePath
                && definitions == other.definitions

        override fun hashCode(): Int = Objects.hash(sourcePath, definitions)

        override fun toString(): String = "Candidate[sourcePath=$sourcePath, definitions=$definitions]"
    }

    class Merged(candidates: List<Candidate>, diagnostics: List<CandidateDiagnostic>) {
        @get:JvmName("candidates")
        val candidates: List<Candidate> = java.util.List.copyOf(candidates)

        @get:JvmName("diagnostics")
        val diagnostics: List<CandidateDiagnostic> = java.util.List.copyOf(diagnostics)

        override fun equals(other: Any?): Boolean = other is Merged
                && candidates == other.candidates
                && diagnostics == other.diagnostics

        override fun hashCode(): Int = Objects.hash(candidates, diagnostics)

        override fun toString(): String = "Merged[candidates=$candidates, diagnostics=$diagnostics]"
    }
}
