package io.github.bigswlittlesw.homelight.tui

import dev.tamboui.toolkit.Toolkit
import dev.tamboui.toolkit.element.Element
import dev.tamboui.toolkit.event.EventResult
import io.github.bigswlittlesw.homelight.tui.DetailViewport.Line

/** A key as help shows it, `keys: action`. A hint not [inHelpArea] is listed only by the `?` overlay. */
internal data class KeyHint(val keys: String, val action: String, val inHelpArea: Boolean = true) {
    val text: String get() = "$keys: $action"
}

/**
 * A screen's keys in its current state, navigation first. Each screen builds them in one function that both its
 * help lines and the `?` overlay read, so the two cannot disagree.
 */
internal data class ScreenKeys(val navigation: List<KeyHint>, val commands: List<KeyHint>) {
    val all: List<KeyHint> get() = navigation + commands
}

/** The help area's line for `hints`: the ones it shows, joined by ` · `. */
internal fun helpLine(hints: List<KeyHint>): String = hints.filter { it.inHelpArea }.joinToString(" · ") { it.text }

// Fits 80 columns with a cell to spare on each side.
private const val HELP_WIDTH = 76

/**
 * The `?` overlay: how HomeLight works, every key of the screen behind it, then the ideas behind them. It scrolls
 * when it does not fit, and keeps the header and help lines uncovered, as every dialog does.
 */
internal fun helpDialog(keys: List<KeyHint>, viewport: DetailViewport, onClose: () -> Unit): Element {
    val lines = helpLines(keys - HELP_KEY)
    // Border and one cell of padding on each side; DialogElement takes a fixed height and shrinks it to fit.
    val height = lines.sumOf { wrap(it.text, HELP_WIDTH - 4).size } + 5
    fun padded(element: Element) = Toolkit.row(Toolkit.spacer(1), element, Toolkit.spacer(1))
    val dialog = Toolkit.dialog(
        HELP_TITLE, Toolkit.text(""),
        padded(viewport.render(null, lines, focused = false, choiceLine = 0)).fill(),
        padded(viewport.help(HELP_DIALOG_KEYS)).length(2),
    )
        .doubleBorder().borderColor(palette.dialog).width(HELP_WIDTH).length(height)
        .id(DIALOG).focusable()
        .onKeyEvent { key ->
            when {
                key.isChar('?') -> onClose()
                key.isUp() || key.isChar('[') -> viewport.scroll(-1)
                key.isDown() || key.isChar(']') -> viewport.scroll(1)
                key.isPageUp() || key.isPageDown() -> viewport.scrollPage(if (key.isPageUp()) -1 else 1)
                key.isHome() || key.isEnd() -> viewport.scroll(if (key.isEnd()) Int.MAX_VALUE else -Int.MAX_VALUE)
                // DialogElement then closes on Esc and takes every other key.
                else -> return@onKeyEvent EventResult.UNHANDLED
            }
            EventResult.HANDLED
        }
        .onCancel(onClose)
    return Toolkit.column(Toolkit.spacer(1), dialog, Toolkit.spacer(2))
}

private fun helpLines(keys: List<KeyHint>): List<Line> =
    listOf(Line(HOW_IT_WORKS_TITLE, palette.text, true)) +
        HOW_IT_WORKS.mapIndexed { i, step -> Line("${i + 1}. $step") } +
        Line("") + Line(SCREEN_KEYS_TITLE, palette.text, true) +
        keys.map { Line(it.text) } +
        Line("") + Line(KEY_IDEAS_TITLE, palette.text, true) +
        KEY_IDEAS.flatMap { (idea, meaning) -> listOf(Line(idea, palette.focus, false), Line(meaning)) }
