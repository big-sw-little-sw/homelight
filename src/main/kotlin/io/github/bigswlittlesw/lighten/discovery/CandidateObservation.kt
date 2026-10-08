package io.github.bigswlittlesw.lighten.discovery

import java.nio.file.Path
import java.time.Instant

/**
 * Read-only evidence, never an execution safety or ownership assessment.
 * Discovery never estimates sizes or enumerates directory contents.
 */
data class CandidateObservation(
    val path: Path, val kind: Kind, val rawLinkTarget: Path?,
    val generation: Long, val observedAt: Instant, val diagnostics: List<Diagnostic>,
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
        ALIAS_UNCERTAINTY, NOT_DIRECTORY, MISSING,
    }

    /** Paths and details are unescaped data; a presentation must escape controls. */
    data class Diagnostic(val path: Path, val reason: Reason, val detail: String)

    internal companion object {
        fun unknown(path: Path, generation: Long, reason: Reason, detail: String): CandidateObservation =
            CandidateObservation(
                path, Kind.UNKNOWN, null, generation,
                Instant.now(), listOf(Diagnostic(path, reason, detail)),
            )
    }
}
