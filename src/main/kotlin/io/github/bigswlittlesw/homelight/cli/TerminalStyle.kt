package io.github.bigswlittlesw.homelight.cli

import dev.tamboui.style.Color
import dev.tamboui.style.Style
import dev.tamboui.terminal.AnsiStringBuilder

/** Applies restrained terminal emphasis while preserving plain output for pipes and `--no-color`. */
internal class TerminalStyle(private val noColor: Boolean) {
    fun heading(text: String): String = format(text, headingStyle())

    fun success(text: String): String = format(text, successStyle())

    fun pending(text: String): String = format(text, pendingStyle())

    fun active(text: String): String = format(text, activeStyle())

    fun warning(text: String): String = format(text, warningStyle())

    fun error(text: String): String = format(text, errorStyle())

    fun headingStyle(): Style = Style.EMPTY.bold()

    fun successStyle(): Style = Style.EMPTY.fg(Color.GREEN).bold()

    fun pendingStyle(): Style = Style.EMPTY.fg(Color.DARK_GRAY)

    fun activeStyle(): Style = Style.EMPTY.fg(Color.CYAN)

    fun warningStyle(): Style = Style.EMPTY.fg(Color.YELLOW).bold()

    fun errorStyle(): Style = Style.EMPTY.fg(Color.RED).bold()

    private fun format(text: String, style: Style): String =
        if (noColor) text else AnsiStringBuilder.styleToAnsi(style) + text + AnsiStringBuilder.RESET
}
