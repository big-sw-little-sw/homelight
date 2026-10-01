package io.github.bigswlittlesw.homelight.config

import java.util.Locale

/** The disposition required for a source directory when its target is adopted. */
enum class WhenAdoptingTarget {
    PROMPT,
    DISCARD_SOURCE,
    ARCHIVE_SOURCE;

    /** Returns the stable configuration and JSON representation. */
    fun value(): String = name.lowercase(Locale.ROOT).replace('_', '-')
}
