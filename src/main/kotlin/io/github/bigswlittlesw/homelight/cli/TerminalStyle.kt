package io.github.bigswlittlesw.homelight.cli

import dev.tamboui.style.Color
import dev.tamboui.style.Style
import dev.tamboui.terminal.AnsiStringBuilder

/** Applies restrained terminal emphasis while preserving plain output for pipes and `--no-color`. */
internal class TerminalStyle(private val noColor: Boolean) {
    fun heading(text: String): String = format(text, Style.EMPTY.bold())

    fun success(text: String): String = format(text, Style.EMPTY.fg(Color.GREEN).bold())

    fun pending(text: String): String = format(text, Style.EMPTY.fg(Color.DARK_GRAY))

    fun active(text: String): String = format(text, Style.EMPTY.fg(Color.CYAN))

    fun warning(text: String): String = format(text, Style.EMPTY.fg(Color.YELLOW).bold())

    fun error(text: String): String = format(text, Style.EMPTY.fg(Color.RED).bold())

    private fun format(text: String, style: Style): String =
        if (noColor) text else AnsiStringBuilder.styleToAnsi(style) + text + AnsiStringBuilder.RESET
}
