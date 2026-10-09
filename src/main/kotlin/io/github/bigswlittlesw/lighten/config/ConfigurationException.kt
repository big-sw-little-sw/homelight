package io.github.bigswlittlesw.lighten.config

import io.github.bigswlittlesw.lighten.fs.PathText
import java.nio.file.Path

/**
 * A configuration that cannot be loaded or saved. [text] is shown to the user as is, with `~` for home
 * ([PathText.shown]); the exception's message has every path in full.
 */
open class ConfigurationException(val text: PathText, cause: Throwable? = null) : RuntimeException(text.toString(), cause)

/**
 * The configuration file at [path] exists, but its text or values are wrong, so the user fixes it in the file. The
 * message says what is wrong; [line] is where, or 0 when no one line is at fault, such as a missing key.
 */
class InvalidConfigurationException(val path: Path, text: PathText, val line: Int = 0) : ConfigurationException(text)
