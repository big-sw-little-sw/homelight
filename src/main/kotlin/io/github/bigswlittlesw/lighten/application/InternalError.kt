package io.github.bigswlittlesw.lighten.application

/**
 * The one line that reports a bug: any failure that is not a configuration, I/O or environment failure.
 * The CLI prints it with exit code 70 and Apply shows it as a diagnostic, never with a stack trace.
 */
internal fun internalErrorMessage(bug: Throwable): String {
    val type = bug.javaClass.simpleName.ifEmpty { bug.javaClass.name }
    return "Internal error (please report): " + (bug.message?.let { "$type: $it" } ?: type)
}
