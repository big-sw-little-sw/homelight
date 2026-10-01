package io.github.bigswlittlesw.homelight.config

import java.util.Locale

/** The decision required when both relocation paths are real directories. */
enum class WhenSourceAndTargetDirectoriesExist {
    PROMPT,
    ADOPT,
    LEAVE_UNCHANGED,
    DISCARD;

    /** Returns the stable configuration and JSON representation. */
    fun value(): String = name.lowercase(Locale.ROOT).replace('_', '-')
}
