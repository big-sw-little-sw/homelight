package io.github.bigswlittlesw.homelight.config

import java.nio.file.Path

/** The disposition required for a source directory when its target is adopted. */
sealed interface WhenAdoptingTarget {
    /** Where the source is archived if adopting archives it; null when archiving is not available. */
    val archiveRoot: Path?

    /** The policy without its data, for views and forms that pick one before every value is known. */
    val kind: Kind

    /** Asks at review. An `archiveRoot` makes archiving one of the answers. */
    data class Prompt(override val archiveRoot: Path? = null) : WhenAdoptingTarget {
        override val kind: Kind get() = Kind.PROMPT
    }

    data object DiscardSource : WhenAdoptingTarget {
        override val archiveRoot: Path? get() = null
        override val kind: Kind get() = Kind.DISCARD_SOURCE
    }

    data class ArchiveSource(override val archiveRoot: Path) : WhenAdoptingTarget {
        override val kind: Kind get() = Kind.ARCHIVE_SOURCE
    }

    enum class Kind { PROMPT, DISCARD_SOURCE, ARCHIVE_SOURCE }
}
