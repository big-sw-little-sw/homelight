package io.github.bigswlittlesw.lighten.discovery

import io.github.bigswlittlesw.lighten.fs.systemReason
import java.nio.file.AccessDeniedException
import java.nio.file.Path
import java.time.Instant

/**
 * What discovery saw at one suggested path, for Browse to show. It does not say whether moving the path is safe or
 * who owns it. Discovery never measures sizes or lists what is inside a directory.
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

    /** Paths and details are not escaped: a screen must escape control characters before it shows them. */
    data class Diagnostic(val path: Path, val reason: Reason, val detail: String)

    internal companion object {
        fun unknown(path: Path, generation: Long, reason: Reason, detail: String): CandidateObservation =
            CandidateObservation(
                path, Kind.UNKNOWN, null, generation,
                Instant.now(), listOf(Diagnostic(path, reason, detail)),
            )
    }
}

/** Why discovery could not read a path, for Browse: a denied read says so in plain words, the rest in the system's. */
internal fun readFailure(exception: Throwable): String =
    if (exception is AccessDeniedException || exception is SecurityException) "can't read: permission denied"
    else systemReason(exception)
