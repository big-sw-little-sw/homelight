package io.github.bigswlittlesw.homelight.config

import java.util.Locale

/** The decision required when both relocation paths are real directories. */
enum class WhenSourceAndTargetDirectoriesExist {
    PROMPT,
    ADOPT,
    LEAVE_UNCHANGED,
    DISCARD;

    /** The stable configuration and JSON representation. */
    val value: String = name.lowercase(Locale.ROOT).replace('_', '-')
}
