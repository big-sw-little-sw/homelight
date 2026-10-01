package io.github.bigswlittlesw.homelight.config

import java.util.Locale

/** The decision required when the source is absent and the target is a real directory. */
enum class WhenOnlyTargetExists {
    PROMPT,
    ADOPT_TARGET;

    /** Returns the stable configuration and JSON representation. */
    fun value(): String = name.lowercase(Locale.ROOT).replace('_', '-')
}
