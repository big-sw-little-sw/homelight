package io.github.bigswlittlesw.homelight.discovery

import java.nio.file.Path
import java.time.Instant
import java.util.Objects
import java.util.Optional
import java.util.OptionalLong

/**
 * Read-only evidence, never an execution safety or ownership assessment.
 * Discovery never estimates sizes or enumerates directory contents.
 *
 * Not a `@JvmRecord data class`: the constructor copies `diagnostics`, which a Kotlin record cannot do.
 * Accessors keep the record names; equality and `toString` match the record this replaces.
 */
class CandidateObservation(
    path: Path, kind: Kind, rawLinkTarget: Optional<Path>,
    generation: Long, observedAt: Instant, stale: Boolean,
    diagnostics: List<Diagnostic>,
) {
    @get:JvmName("path")
    val path: Path = path

    @get:JvmName("kind")
    val kind: Kind = kind

    @get:JvmName("rawLinkTarget")
    val rawLinkTarget: Optional<Path> = rawLinkTarget

    @get:JvmName("generation")
    val generation: Long = generation

    @get:JvmName("observedAt")
    val observedAt: Instant = observedAt

    @get:JvmName("stale")
    val stale: Boolean = stale

    @get:JvmName("diagnostics")
    val diagnostics: List<Diagnostic> = java.util.List.copyOf(diagnostics)

    init {
        if (!this.path.isAbsolute || this.path != this.path.normalize()) {
            throw IllegalArgumentException("Observation requires normalized absolute identity")
        }
    }

    enum class Kind {
        PENDING, DIRECTORY, LINK, MISSING, REGULAR_FILE, OTHER, INACCESSIBLE,
        BLOCKED_BY_LINK, BLOCKED_BY_NON_DIRECTORY, UNKNOWN,
    }

    enum class Reason {
        SYMLINK_EXCLUDED, ACCESS_DENIED, IO_ERROR, CHANGED, DEADLINE,
        CAPACITY, ALIAS_UNCERTAINTY, NOT_DIRECTORY, MISSING,
    }

    enum class Ownership { NOT_EVALUATED }

    enum class LinkTargetStatus { NOT_A_LINK, UNKNOWN }

    fun ownership(): Ownership = Ownership.NOT_EVALUATED

    fun size(): Size = Size.NOT_ESTIMATED

    fun linkTargetStatus(): LinkTargetStatus =
        if (kind == Kind.LINK) LinkTargetStatus.UNKNOWN else LinkTargetStatus.NOT_A_LINK

    enum class Size {
        NOT_ESTIMATED;

        fun bytes(): OptionalLong = OptionalLong.empty()
    }

    /** Paths and details are unescaped data; a presentation must escape controls. */
    @JvmRecord
    data class Diagnostic(val path: Path, val reason: Reason, val detail: String)

    internal fun retained(): CandidateObservation =
        CandidateObservation(path, kind, rawLinkTarget, generation, observedAt, true, diagnostics)

    override fun equals(other: Any?): Boolean = other is CandidateObservation
            && path == other.path
            && kind == other.kind
            && rawLinkTarget == other.rawLinkTarget
            && generation == other.generation
            && observedAt == other.observedAt
            && stale == other.stale
            && diagnostics == other.diagnostics

    override fun hashCode(): Int = Objects.hash(path, kind, rawLinkTarget, generation, observedAt, stale, diagnostics)

    override fun toString(): String = "CandidateObservation[path=$path, kind=$kind, rawLinkTarget=$rawLinkTarget, " +
            "generation=$generation, observedAt=$observedAt, stale=$stale, diagnostics=$diagnostics]"

    internal companion object {
        fun unknown(path: Path, generation: Long, reason: Reason, detail: String): CandidateObservation =
            CandidateObservation(
                path, Kind.UNKNOWN, Optional.empty(), generation,
                Instant.now(), false, java.util.List.of(Diagnostic(path, reason, detail)),
            )
    }
}
