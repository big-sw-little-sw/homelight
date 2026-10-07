package io.github.bigswlittlesw.homelight.tui

import dev.tamboui.markdown.MarkdownStyles
import dev.tamboui.style.Style
import dev.tamboui.text.CharWidth
import dev.tamboui.toolkit.Toolkit
import dev.tamboui.toolkit.element.Element
import dev.tamboui.toolkit.elements.TabsElement

/**
 * A key as help shows it: the help lines show `keys: action`, and Help's This screen tab shows `keys  description`,
 * which names what the key acts on where the short label needs its screen to make sense. A hint not [inHelpArea] is
 * listed only on the Help screen. A hint that [scrolls] is left out of the help lines while the pane has nothing to
 * scroll.
 */
internal data class KeyHint(
    val keys: String, val action: String, val inHelpArea: Boolean = true, val scrolls: Boolean = false,
    val description: String = action,
) {
    val text: String get() = "$keys: $action"
}

/** The steps of using HomeLight, in order; Help's "You are here" line marks the current one. */
internal enum class Step { CONFIGURE, WORKSPACE, REVIEW, APPLY, RESULTS }

/**
 * Where the user is, what the screen is for and its keys, in its current state and focus, navigation first. Each
 * screen builds it in one function that both its help lines and the Help screen read, so the two cannot disagree.
 */
internal data class ScreenHelp(
    val name: String, val purpose: String, val step: Step, val navigation: List<KeyHint>, val commands: List<KeyHint>,
)

/** The help area's line for `hints`: the ones it shows, joined by ` · `. */
internal fun helpLine(hints: List<KeyHint>): String = hints.filter { it.inHelpArea }.joinToString(" · ") { it.text }

internal enum class HelpTab { THIS_SCREEN, GUIDE }

/**
 * The Help screen over `screen`, on `tab`. `interactive` is false while a dialog is open over it.
 *
 * TamboUI moves focus on Tab before any handler sees the key, so Tab switches tabs through focus: the open tab's pane
 * has that tab's id, and the tab bar has the other tab's. Tab moves focus to the bar, and the next frame opens the
 * tab its id names.
 */
internal fun helpScreen(
    screen: ScreenHelp, guide: String, tab: HelpTab, viewports: Map<HelpTab, DetailViewport>, interactive: Boolean,
): Element {
    val header = Toolkit.row(
        Toolkit.text("⌂ HOMELIGHT  ").fg(palette.brand).bold(), Toolkit.text("[$HELP_TITLE]").fg(palette.focus).bold(),
    )
    val other = if (tab == HelpTab.THIS_SCREEN) HelpTab.GUIDE else HelpTab.THIS_SCREEN
    val tabs = TabsElement(THIS_SCREEN_TAB, GUIDE_TAB).selected(tab.ordinal)
        .highlightStyle(Style.EMPTY.fg(palette.focus).bold()).fg(palette.dim)
        .id(helpTabId(other)).focusable(interactive).length(1)
    val viewport = viewports.getValue(tab)
    val pane = when (tab) {
        HelpTab.THIS_SCREEN -> viewport.markdown(screen.name, thisScreen(screen), thisScreenStyles(), helpTabId(tab), interactive)
        HelpTab.GUIDE -> viewport.markdown(GUIDE_TAB, guide, guideStyles(), helpTabId(tab), interactive)
    }
    // `?` and F1 go back too; the line names Esc and q, and where they go.
    val own = ScreenHelp(
        HELP_TITLE, "", screen.step,
        listOf(SCROLL_KEY, KeyHint("PageUp/PageDown", "Page", scrolls = true), KeyHint("Home/End", "Top/bottom", scrolls = true)),
        listOf(KeyHint("Tab/←/→", "Other tab"), KeyHint("Esc/q", backTo(screen.name))),
    )
    return Toolkit.column(header, tabs, pane, viewport.help(own, interactive)).fill()
}

internal fun helpTabId(tab: HelpTab): String = when (tab) {
    HelpTab.THIS_SCREEN -> HELP_THIS_SCREEN
    HelpTab.GUIDE -> HELP_GUIDE
}

/**
 * The This screen tab as Markdown: the purpose; the step line, with the current step emphasized (bold in the focus
 * color); then the keys of `screen`, which work once the reader goes back, in two groups. Each group is one paragraph
 * led by its bold title, so no blank row follows the title. Each key is a code span, which keeps keys such as `[/]`
 * literal, padded so the descriptions line up; a trailing `\` breaks the line. Help's own keys are left out: they
 * are on Help's help lines.
 */
private fun thisScreen(screen: ScreenHelp): String {
    val steps = Step.entries.joinToString(" › ") { step ->
        if (step == screen.step) "*" + stepLabel(step) + "*" else stepLabel(step)
    }
    val navigation = screen.navigation.filter { it.action != HELP_KEY.action }
    val commands = screen.commands.filter { it.action != HELP_KEY.action }
    val width = (navigation + commands).maxOfOrNull { CharWidth.of(it.keys) } ?: 0
    fun group(title: String, hints: List<KeyHint>): String = if (hints.isEmpty()) "" else
        "\n\n**" + title + "**\\\n" + hints.joinToString("\\\n") { hint ->
            "`" + hint.keys + " ".repeat(width - CharWidth.of(hint.keys) + 2) + "`" + hint.description
        }
    return screen.purpose + "\n\n" + STEP + ": " + steps + "\n\n### " + keysOn(screen.name) + "\n\n" + KEYS_LEAD_IN +
        group(MOVE_AROUND, navigation) + group(DO_KEYS, commands) + "\n"
}

/** The guide's headings and emphasis in the palette's roles; the rest keeps TamboUI's defaults. */
private fun guideStyles(): MarkdownStyles = styles(emphasis = Style.EMPTY.italic())

/** As the guide, but emphasis marks the current step: bold, so it shows without color, in the focus color. */
private fun thisScreenStyles(): MarkdownStyles = styles(emphasis = Style.EMPTY.fg(palette.focus).bold())

private fun styles(emphasis: Style): MarkdownStyles = MarkdownStyles.builder()
    .heading(1, Style.EMPTY.fg(palette.brand).bold())
    .heading(2, Style.EMPTY.fg(palette.focus).bold())
    .heading(3, Style.EMPTY.fg(palette.text).bold())
    .heading(4, Style.EMPTY.fg(palette.text).bold())
    .strong(Style.EMPTY.fg(palette.text).bold())
    .emphasis(emphasis)
    .inlineCode(Style.EMPTY.fg(palette.change))
    .codeBlock(Style.EMPTY.fg(palette.text))
    .link(Style.EMPTY.fg(palette.focus).underlined())
    .listMarker(Style.EMPTY.fg(palette.dim))
    .blockquote(Style.EMPTY.fg(palette.dim))
    .horizontalRule(Style.EMPTY.fg(palette.dim))
    .build()
