package io.github.bigswlittlesw.homelight.config

/** A configuration that cannot be loaded or saved. The message is shown to the user as is. */
open class ConfigurationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
