package io.github.bigswlittlesw.homelight.tui

import dev.tamboui.markdown.MarkdownStyles
import dev.tamboui.style.Style
import dev.tamboui.text.CharWidth
import dev.tamboui.toolkit.Toolkit
import dev.tamboui.toolkit.element.Element

/** A key as help shows it, `keys: action`. A hint not [inHelpArea] is listed only on the Help screen. */
internal data class KeyHint(val keys: String, val action: String, val inHelpArea: Boolean = true) {
    val text: String get() = "$keys: $action"
}

/**
 * What a screen is for and its keys, in its current state and focus, navigation first. Each screen builds it in one
 * function that both its help lines and the Help screen read, so the two cannot disagree.
 */
internal data class ScreenHelp(
    val name: String, val purpose: String, val navigation: List<KeyHint>, val commands: List<KeyHint>,
) {
    val keys: List<KeyHint> get() = navigation + commands
}

/** The help area's line for `hints`: the ones it shows, joined by ` · `. */
internal fun helpLine(hints: List<KeyHint>): String = hints.filter { it.inHelpArea }.joinToString(" · ") { it.text }

/**
 * The Help screen over `screen`: what that screen is for and every key it takes now, then the user guide.
 * `interactive` is false while a dialog is open over it.
 */
internal fun helpScreen(screen: ScreenHelp, guide: String, viewport: DetailViewport, interactive: Boolean): Element {
    val header = Toolkit.row(
        Toolkit.text("⌂ HOMELIGHT  ").fg(palette.brand).bold(), Toolkit.text("[$HELP_TITLE]").fg(palette.focus).bold(),
    )
    // Help passes `q` to the screen behind, so it shows that screen's `q`, or none where `q` does nothing there.
    val quit = screen.commands.firstOrNull { it.keys == "q" }?.copy(inHelpArea = true)
    val own = ScreenHelp(
        HELP_TITLE, "",
        listOf(SCROLL_KEY, KeyHint("PageUp/PageDown", "Page"), KeyHint("Home/End", "Top/bottom")),
        listOfNotNull(KeyHint("?/Esc", "Back"), quit),
    )
    val source = onThisScreen(screen) + "\n" + guide
    return Toolkit.column(
        header,
        viewport.markdown("$HELP_TITLE · ${screen.name}", source, markdownStyles(), HELP_SCREEN, interactive),
        viewport.help(own, interactive),
    ).fill()
}

/**
 * The screen's part of Help as Markdown, so it scrolls and reads like the guide below it. Each key is a code span,
 * which keeps keys such as `[/]` literal, padded so the actions line up; a trailing `\` breaks the line.
 */
private fun onThisScreen(screen: ScreenHelp): String {
    val width = screen.keys.maxOfOrNull { CharWidth.of(it.keys) } ?: 0
    val keys = screen.keys.joinToString("\\\n") { hint ->
        "`" + hint.keys + " ".repeat(width - CharWidth.of(hint.keys) + 2) + "`" + hint.action
    }
    return "## " + onThisScreenTitle(screen.name) + "\n\n" + screen.purpose + "\n\n" + keys + "\n"
}

/** Headings and emphasis in the palette's roles; the rest keeps TamboUI's defaults. */
private fun markdownStyles(): MarkdownStyles = MarkdownStyles.builder()
    .heading(1, Style.EMPTY.fg(palette.brand).bold())
    .heading(2, Style.EMPTY.fg(palette.focus).bold())
    .heading(3, Style.EMPTY.fg(palette.text).bold())
    .strong(Style.EMPTY.fg(palette.text).bold())
    .inlineCode(Style.EMPTY.fg(palette.change))
    .codeBlock(Style.EMPTY.fg(palette.text))
    .link(Style.EMPTY.fg(palette.focus).underlined())
    .listMarker(Style.EMPTY.fg(palette.dim))
    .blockquote(Style.EMPTY.fg(palette.dim))
    .horizontalRule(Style.EMPTY.fg(palette.dim))
    .build()
