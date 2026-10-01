@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")

package io.github.bigswlittlesw.homelight.config

// Java's String.isBlank and String.strip. Kotlin hides strip, and its isBlank and trim also treat
// no-break spaces as whitespace; these keep the Java semantics the configuration checks use.

internal fun String.isJavaBlank(): Boolean = (this as java.lang.String).isBlank

internal fun String.javaStrip(): String = (this as java.lang.String).strip()
