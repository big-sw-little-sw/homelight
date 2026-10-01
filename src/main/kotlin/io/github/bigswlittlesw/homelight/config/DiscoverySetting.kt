package io.github.bigswlittlesw.homelight.config

import java.nio.file.Path
import java.util.Optional

/**
 * Lexical setting conversion only. Availability is discovery's concern, never
 * a prerequisite for loading or saving configuration.
 */
object DiscoverySetting {
    @JvmStatic
    fun parse(value: String): Optional<Path> {
        if (value.isJavaBlank()) return Optional.empty()
        if (value.indexOf('$') >= 0 || value.codePoints().anyMatch { Character.isISOControl(it) }) {
            throw IllegalArgumentException("Shared list must be a filesystem path without variables or controls")
        }
        val expanded = if (value.startsWith("~/")) System.getProperty("user.home") + value.substring(1) else value
        return Optional.of(normalize(Path.of(expanded)))
    }

    @JvmStatic
    fun normalize(path: Path): Path {
        if (!path.isAbsolute) throw IllegalArgumentException("Shared list must be an absolute filesystem path")
        if (path.toString().indexOf('$') >= 0 || path.toString().codePoints().anyMatch { Character.isISOControl(it) }) {
            throw IllegalArgumentException("Shared list must not contain variables or controls")
        }
        return path.normalize()
    }
}
