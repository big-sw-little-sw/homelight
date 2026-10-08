@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")

package io.github.bigswlittlesw.lighten.config

// Java's String.isBlank and String.strip, for code that relies on their exact semantics.
// Kotlin hides strip, and its isBlank and trim also treat no-break spaces as whitespace.

internal fun String.isJavaBlank(): Boolean = (this as java.lang.String).isBlank

internal fun String.javaStrip(): String = (this as java.lang.String).strip()
