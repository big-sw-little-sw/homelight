package io.github.bigswlittlesw.lighten.config

import java.nio.file.Path

/** A configuration that cannot be loaded or saved. The message is shown to the user as is. */
open class ConfigurationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * The configuration file at [path] exists, but its text or values are wrong, so the user fixes it in the file. The
 * message says what is wrong; [line] is where, or 0 when no one line is at fault, such as a missing key.
 */
class InvalidConfigurationException(val path: Path, message: String, val line: Int = 0) :
    ConfigurationException(message)
