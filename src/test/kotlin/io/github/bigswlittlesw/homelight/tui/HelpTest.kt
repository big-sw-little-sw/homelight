package io.github.bigswlittlesw.homelight.tui

import dev.tamboui.tui.event.KeyCode
import io.github.bigswlittlesw.homelight.application.HomeLightSession
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class HelpTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun helpListsEveryKeyTheHelpLinesShowOnEachScreen() {
        val ui = HeadlessTui(HomeLightSession(conflictConfiguration()))
        checkHelp(ui, WORKSPACE_NAME, PURPOSE_WORKSPACE, "c", "PageUp/PageDown")
        ui.press(KeyCode.TAB)
        checkHelp(ui, WORKSPACE_NAME, PURPOSE_WORKSPACE, "↑/↓", "←")
        ui.press(KeyCode.ENTER)
        ui.press(KeyCode.ESCAPE)
        ui.press('a')
        checkHelp(ui, REVIEW_NAME, PURPOSE_REVIEW, "y", "Home/End")

        val setup = HeadlessTui(HomeLightSession(temporary.resolve("new.json")))
        checkHelp(setup, WORKSPACE_NAME, PURPOSE_NO_CONFIGURATION, "i", "Home/End")
        setup.press('i')
        setup.press(KeyCode.ENTER)
        setup.press('a')
        setup.type(".cache/tool")
        setup.press(KeyCode.ESCAPE)
        checkHelp(setup, CONFIGURATION_NAME, PURPOSE_CONFIGURATION, "b", "[/]")
        setup.press(KeyCode.ENTER)
        repeat(2) { setup.press(KeyCode.DOWN) }
        checkHelp(setup, CONFIGURATION_NAME, PURPOSE_CONFIGURATION, "Space", "q", note = true)
    }

    /**
     * Opens Help over the screen as it is and checks its header, title and purpose, that its key table has a row for
     * each key the help lines show and for the `extra` keys they leave out, and that Esc returns to the screen exactly
     * as it was. A field note takes the first help line when `note` is set.
     */
    private fun checkHelp(ui: HeadlessTui, name: String, purpose: String, vararg extra: String, note: Boolean = false) {
        val before = ui.screen(80, 24)
        val focused = ui.focused()
        val shown = before.lines().subList(if (note) 23 else 22, 24).flatMap { it.trim().split(" · ") }
            .map { it.replace("↑/↓/[/]: Scroll", "↑/↓: Scroll") }.filter { it.isNotEmpty() && it != "[/]: Scroll" }
        assertTrue("?: Help" in shown, "$name: $before")
        ui.press('?')
        assertEquals(HELP_SCREEN, ui.focused(), name)
        // Tall enough to show the whole key table.
        val help = ui.screen(100, 120)
        val rows = help.lines()
        assertTrue(rows[0].startsWith("⌂ HOMELIGHT  [Help]"), help)
        assertTrue(rows[1].contains("Help · $name"), help)
        assertTrue(help.contains(onThisScreenTitle(name)), help)
        assertTrue(paneText(help).contains(purpose), help)
        // A key row is the key, padding, then its action.
        fun listed(keys: String, action: String?) = paneRows(help).any { row ->
            row.startsWith("$keys ") && (action == null || row.substringAfter("$keys ").trim() == action)
        }
        for (hint in shown) {
            val (keys, action) = hint.split(": ", limit = 2)
            assertTrue(listed(keys, action), "$name lists $hint: $help")
        }
        for (keys in extra) assertTrue(listed(keys, null), "$name lists $keys: $help")
        ui.press(KeyCode.ESCAPE)
        assertEquals(focused, ui.focused(), name)
        assertEquals(before, ui.screen(80, 24), name)
    }

    @Test
    fun helpScrollsTheGuideAndOtherKeysDoNothing() {
        val ui = HeadlessTui(HomeLightSession(conflictConfiguration()))
        ui.press(KeyCode.DOWN)
        val before = ui.screen(80, 24)
        ui.press('?')
        val top = ui.screen(80, 24)
        val rows = top.lines()
        assertTrue(rows[22].startsWith("↑/↓/[/]: Scroll · PageUp/PageDown: Page · Home/End: Top/bottom"), top)
        assertTrue(rows[23].startsWith("?/Esc: Back · q: Quit"), top)
        assertFalse(top.contains("What HomeLight does"), top)

        for (c in listOf('a', 'r', 'c', '2', 'i', 'y')) ui.press(c)
        ui.press(KeyCode.TAB)
        ui.press(KeyCode.ENTER)
        assertEquals(top, ui.screen(80, 24))

        ui.press(KeyCode.PAGE_DOWN)
        val page = ui.screen(80, 24)
        // A page keeps one line of context: the last line of the first page is now the first.
        assertEquals(paneRows(top).last(), paneRows(page).first(), page)
        ui.press(KeyCode.END)
        val end = ui.screen(80, 24)
        assertTrue(end.contains("More help"), end)
        ui.press(KeyCode.HOME)
        assertEquals(top, ui.screen(80, 24))

        ui.press('?')
        assertEquals(before, ui.screen(80, 24))
        assertEquals(1, ui.app.selectedIndex(), "the selection is kept")
        assertEquals(WORKSPACE_LIST, ui.focused())
    }

    @Test
    fun theGuideRendersAsMarkdownWithEntitiesDecoded() {
        val ui = HeadlessTui(HomeLightSession(conflictConfiguration()))
        ui.press('?')
        val text = ui.screen(100, 400)
        assertTrue(text.contains("When source & target both exist"), text)
        assertFalse(text.contains("&amp;"), text)
        assertFalse(text.contains("## "), "headings render without their markers: $text")
    }

    @Test
    fun qInHelpDoesWhatItDoesOnTheScreenBehind() {
        val ui = HeadlessTui(HomeLightSession(conflictConfiguration()))
        ui.press('?')
        ui.press('q')
        assertTrue(ui.app.exitRequested())

        val setup = HeadlessTui(HomeLightSession(temporary.resolve("new.json")))
        setup.press('i')
        setup.press(KeyCode.ENTER)
        setup.press('?')
        val help = setup.screen(80, 24)
        assertTrue(help.lines()[23].startsWith("?/Esc: Back · q: Discard"), help)
        setup.press('q')
        val dialog = setup.screen(80, 24)
        assertTrue(dialog.contains("╔$DISCARD_SETUP_TITLE"), dialog)
        assertFalse(setup.app.exitRequested())
        setup.press('y')
        val workspace = setup.screen(80, 24)
        assertTrue(workspace.contains("[1: Workspace]"), workspace)
    }

    @Test
    fun aTextFieldTypesTheQuestionMark() {
        val ui = HeadlessTui(HomeLightSession(temporary.resolve("new.json")))
        ui.press('i')
        ui.press(KeyCode.DOWN)
        ui.type("/srv/what?")
        val screen = ui.screen(80, 24)
        assertTrue(screen.contains("Target root: /srv/what?"), screen)
        assertFalse(screen.contains("[Help]"), screen)
        // Where every field is a text field, help does not offer `?`.
        assertFalse(screen.contains("?: Help"), screen)
    }

    @Test
    fun anEmptyWorkspaceSaysHowToGetHelp() {
        val missing = HeadlessTui(HomeLightSession(temporary.resolve("missing.json"))).screen(80, 24)
        assertTrue(missing.contains(HELP_HINT), missing)
        val empty = Files.writeString(
            temporary.resolve("empty.json"), "{\"homelight\": {\"target-root\": \"$temporary\", \"relocations\": []}}\n",
        )
        val ui = HeadlessTui(HomeLightSession(empty))
        val none = ui.screen(80, 24)
        assertTrue(none.contains(NO_RELOCATIONS), none)
        assertTrue(none.contains(HELP_HINT), none)
        ui.press('?')
        assertTrue(paneText(ui.screen(100, 60)).contains(PURPOSE_NO_RELOCATIONS))
    }

    /** The rows inside the Help pane's border, without the scrollbar. */
    private fun paneRows(screen: String): List<String> = screen.lines().filter { it.startsWith("│") }
        .map { row -> row.removePrefix("│").removeSuffix("│").trimEnd('│', '█', ' ') }

    /** The Help pane's text with wrapped lines joined, for matching sentences. */
    private fun paneText(screen: String): String = paneRows(screen).joinToString(" ").replace(Regex("\\s+"), " ")

    /** One relocation that needs a choice, one to move and one in sync. */
    private fun conflictConfiguration(): Path {
        val root = temporary.toRealPath()
        for (path in listOf("home/both", "local/both", "home/move", "local/synced")) Files.createDirectories(root.resolve(path))
        Files.createSymbolicLink(root.resolve("home/synced"), root.resolve("local/synced"))
        val relocations = listOf("both", "move", "synced").joinToString(",\n") { name ->
            "{\"source-path\": \"${root.resolve("home/$name")}\", \"target-path\": \"${root.resolve("local/$name")}\"}"
        }
        return Files.writeString(
            root.resolve("config.json"),
            "{\"homelight\": {\"target-root\": \"${root.resolve("local")}\", \"relocations\": [\n$relocations\n]}}\n",
        )
    }
}
