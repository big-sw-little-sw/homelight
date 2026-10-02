package io.github.bigswlittlesw.homelight.discovery

import java.nio.file.Path
import java.time.Instant

/**
 * Read-only evidence, never an execution safety or ownership assessment.
 * Discovery never estimates sizes or enumerates directory contents.
 */
data class CandidateObservation(
    val path: Path, val kind: Kind, val rawLinkTarget: Path?,
    val generation: Long, val observedAt: Instant, val stale: Boolean,
    val diagnostics: List<Diagnostic>,
) {
    init {
        require(path.isAbsolute && path == path.normalize()) { "Observation requires normalized absolute identity" }
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

    val ownership: Ownership get() = Ownership.NOT_EVALUATED

    val size: Size get() = Size.NOT_ESTIMATED

    val linkTargetStatus: LinkTargetStatus
        get() = if (kind == Kind.LINK) LinkTargetStatus.UNKNOWN else LinkTargetStatus.NOT_A_LINK

    enum class Size {
        NOT_ESTIMATED;

        val bytes: Long? get() = null
    }

    /** Paths and details are unescaped data; a presentation must escape controls. */
    data class Diagnostic(val path: Path, val reason: Reason, val detail: String)

    internal fun retained(): CandidateObservation = copy(stale = true)

    internal companion object {
        fun unknown(path: Path, generation: Long, reason: Reason, detail: String): CandidateObservation =
            CandidateObservation(
                path, Kind.UNKNOWN, null, generation,
                Instant.now(), false, listOf(Diagnostic(path, reason, detail)),
            )
    }
}
