package io.github.bigswlittlesw.lighten.config

import java.nio.file.Path

// Lexical shared-list setting conversion only. Availability is discovery's concern, never
// a prerequisite for loading or saving configuration.

/** Returns null for a blank setting; expands a leading `~/`. */
fun parseSharedList(value: String): Path? {
    if (value.isJavaBlank()) return null
    require(value.indexOf('$') < 0 && value.none { it.isISOControl() }) {
        "Suggestion list must be a filesystem path without variables or controls"
    }
    val expanded = if (value.startsWith("~/")) System.getProperty("user.home") + value.substring(1) else value
    return normalizeSharedList(Path.of(expanded))
}

fun normalizeSharedList(path: Path): Path {
    require(path.isAbsolute) { FULL_PATH }
    val text = path.toString()
    require(text.indexOf('$') < 0 && text.none { it.isISOControl() }) {
        "Suggestion list must not contain variables or controls"
    }
    return path.normalize()
}
