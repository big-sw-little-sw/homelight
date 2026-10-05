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
    fun theOverlayListsEveryKeyTheHelpAreaShowsOnEachScreen() {
        val ui = HeadlessTui(HomeLightSession(conflictConfiguration()))
        checkOverlay(ui, "Workspace list", "c: Show 1 in sync", "PageUp/PageDown: Move a page")
        ui.press(KeyCode.TAB)
        checkOverlay(ui, "Workspace details", "↑/↓: Choose", "←: Back")
        ui.press(KeyCode.ENTER)
        ui.press(KeyCode.ESCAPE)
        ui.press('a')
        checkOverlay(ui, "Review", "y: Apply", "Home/End: First/last")

        val setup = HeadlessTui(HomeLightSession(temporary.resolve("new.json")))
        checkOverlay(setup, "empty Workspace", "i: Create configuration", "Home/End: Top/bottom")
        setup.press('i')
        setup.press(KeyCode.ENTER)
        setup.press('a')
        setup.type(".cache/tool")
        setup.press(KeyCode.ESCAPE)
        checkOverlay(setup, "Setup relocations", "b: Browse", "[/]: Scroll")
        setup.press(KeyCode.ENTER)
        repeat(2) { setup.press(KeyCode.DOWN) }
        checkOverlay(setup, "Setup rule field", "Space: Change rule", "q: Discard", note = true)
    }

    /**
     * Opens help over the screen as it is, checks that the overlay lists each key the help area shows and `extra`
     * keys it does not, then closes it with Esc and checks that the screen is as it was. A field note takes the
     * first help line when `note` is set.
     */
    private fun checkOverlay(ui: HeadlessTui, screen: String, vararg extra: String, note: Boolean = false) {
        val before = ui.screen(80, 24)
        val focused = ui.focused()
        val shown = before.lines().subList(if (note) 23 else 22, 24).flatMap { it.trim().split(" · ") }
            .map { it.replace("↑/↓/[/]: Scroll", "↑/↓: Scroll") }.filter { it.isNotEmpty() && it != "[/]: Scroll" }
        assertTrue("?: Help" in shown, "$screen: $before")
        ui.press('?')
        assertEquals(DIALOG, ui.focused(), screen)
        // Tall enough that the overlay does not scroll.
        val listed = ui.screen(80, 80).lines().map { it.substringAfter('║').substringBefore('║').trim() }
        for (key in shown - "?: Help" + extra) assertTrue(key in listed, "$screen lists $key: ${listed.joinToString("\n")}")
        assertFalse("?: Help" in listed, screen)
        ui.press(KeyCode.ESCAPE)
        assertEquals(focused, ui.focused(), screen)
        assertEquals(before, ui.screen(80, 24), screen)
    }

    @Test
    fun theOverlayScrollsAt80x24AndLeavesTheHeaderAndHelpLinesUncovered() {
        val ui = HeadlessTui(HomeLightSession(conflictConfiguration()))
        val header = ui.screen(80, 24).lines()[0]
        ui.press('?')
        val top = ui.screen(80, 24)
        val rows = top.lines()
        assertEquals(header, rows[0], top)
        assertTrue(rows[1].contains("╔Help"), top)
        assertTrue(rows[21].contains("╚"), top)
        assertTrue(rows[22].isBlank() && rows[23].isBlank(), top)
        assertTrue(top.contains("1. Setup: say where storage is."), top)
        assertTrue(top.contains("↑/↓/[/]: Scroll · ?/Esc: Close"), top)
        assertFalse(top.contains("change files outside HomeLight."), top)

        ui.press(KeyCode.PAGE_DOWN)
        val page = ui.screen(80, 24)
        assertFalse(page.contains("1. Setup"), page)
        ui.press(KeyCode.END)
        val end = ui.screen(80, 24)
        assertTrue(end.contains("change files outside HomeLight."), end)
        ui.press(KeyCode.HOME)
        assertEquals(top, ui.screen(80, 24))

        // At 120x30 it still scrolls, and fits without cutting a line.
        val wide = ui.screen(120, 30)
        assertTrue(wide.contains("2. Workspace: see what HomeLight found and what it plans for each"), wide)
    }

    @Test
    fun theOverlayTakesEveryKeyAndQuestionMarkClosesIt() {
        val ui = HeadlessTui(HomeLightSession(conflictConfiguration()))
        val before = ui.screen(80, 24)
        ui.press('?')
        val open = ui.screen(80, 24)
        for (c in listOf('q', 'a', 'r', 'c', '2', 'i')) ui.press(c)
        ui.press(KeyCode.TAB)
        ui.press(KeyCode.ENTER)
        assertEquals(open, ui.screen(80, 24))
        assertFalse(ui.app.exitRequested())
        ui.press('?')
        assertEquals(before, ui.screen(80, 24))
        assertEquals(WORKSPACE_LIST, ui.focused())
    }

    @Test
    fun aTextFieldTypesTheQuestionMark() {
        val ui = HeadlessTui(HomeLightSession(temporary.resolve("new.json")))
        ui.press('i')
        ui.press(KeyCode.DOWN)
        ui.type("/srv/what?")
        val screen = ui.screen(80, 24)
        assertTrue(screen.contains("Target root: /srv/what?"), screen)
        assertFalse(screen.contains("╔Help"), screen)
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
        val none = HeadlessTui(HomeLightSession(empty)).screen(80, 24)
        assertTrue(none.contains(NO_RELOCATIONS), none)
        assertTrue(none.contains(HELP_HINT), none)
    }

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
