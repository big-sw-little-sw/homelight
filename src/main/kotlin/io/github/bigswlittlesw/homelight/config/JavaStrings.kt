@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")

package io.github.bigswlittlesw.homelight.config

// Java's String.isBlank, String.strip and String.split, for code that relies on their exact semantics.
// Kotlin hides strip, its isBlank and trim also treat no-break spaces as whitespace, and its split
// keeps trailing empty strings.

internal fun String.isJavaBlank(): Boolean = (this as java.lang.String).isBlank

internal fun String.javaStrip(): String = (this as java.lang.String).strip()

internal fun String.javaSplit(regex: String): Array<String> = (this as java.lang.String).split(regex)
