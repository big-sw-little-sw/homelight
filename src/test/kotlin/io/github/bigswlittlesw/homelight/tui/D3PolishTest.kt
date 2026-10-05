package io.github.bigswlittlesw.homelight.tui

import dev.tamboui.text.CharWidth
import dev.tamboui.toolkit.Toolkit
import dev.tamboui.tui.event.KeyCode
import dev.tamboui.tui.event.KeyEvent
import io.github.bigswlittlesw.homelight.application.ApplyModel
import io.github.bigswlittlesw.homelight.application.HomeLightSession
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class D3PolishTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun readerScrollbarTracksWrappedContentAndDisappearsOnGrowth() {
        val viewport = DetailViewport()
        val lines = (0 until 28).map { i -> DetailViewport.Line("Row $i") }
        val view = Toolkit.column(viewport.render("Details", lines, true, 0), viewport.help(ScreenHelp("Test", "", listOf(KeyHint("Tab", "Back")), listOf(QUIT_KEY)))).fill()
        val small = WorkspaceViewTest.render(view, 80, 24)
        assertTrue(small.contains("█"), small)
        // TamboUI's Scrollbar: thumb length ceil(20 * 20 / 28) = 15 rows, offset round(top / 8 * (20 - 15)).
        assertEquals((1..15).toList(), thumbRows(small, 78), small)
        assertTrue(small.contains("[/]: Scroll"), small)
        viewport.scroll(Int.MAX_VALUE)
        val bottom = WorkspaceViewTest.render(view, 80, 24)
        assertTrue(bottom.contains("Row 27"), bottom)
        assertNotEquals(small.indexOf('█'), bottom.indexOf('█'))
        assertEquals((6..20).toList(), thumbRows(bottom, 78), bottom)
        for (size in listOf(intArrayOf(120, 30), intArrayOf(200, 50))) {
            val grown = WorkspaceViewTest.render(view, size[0], size[1])
            if (size[1] == 50) {
                assertFalse(grown.contains("█"), grown)
                assertFalse(grown.contains("[/]: Scroll"), grown)
                assertTrue(grown.contains("Row 0"))
            }
            assertFalse(grown.matches(Regex("(?s).*\\d+–\\d+/\\d+.*")))
        }
        assertTrue(WorkspaceViewTest.render(view, 80, 24).contains("█"))
        assertEquals(listOf("one two", "three"), wrap("one two three", 8))
        assertEquals("/unbreakable/path", wrap("/unbreakable/path", 5).joinToString(""))
    }

    @Test
    fun wrapMeasuresEmojiSequencesAsTheTerminalDrawsThem() {
        val warning = "\u26A0\uFE0F" // ⚠ with VS16: emoji presentation, two cells.
        val coder = "\uD83D\uDC69\u200D\uD83D\uDCBB" // 👩‍💻, a ZWJ sequence: one glyph, two cells.
        assertEquals(2, CharWidth.of(warning))
        assertEquals(2, CharWidth.of(coder))
        // Measured per code point, ⚠️ was one cell and 👩‍💻 four.
        assertEquals(listOf("ab", warning + "c", "d"), wrap("ab${warning}cd", 3))
        assertEquals(listOf(coder + coder), wrap(coder + coder, 4))
        assertEquals(listOf("/a$coder", warning + "b"), wrap("/a$coder${warning}b", 4))

        val path = "/home/me/${warning}alerts/$coder-work/$coder$coder$warning/cache"
        for (width in 2..12) {
            val lines = wrap(path, width)
            assertEquals(path, lines.joinToString(""), "width $width")
            for (line in lines) {
                assertTrue(CharWidth.of(line) <= width, "'$line' overflows $width cells")
                assertFalse(line.startsWith("\uFE0F") || line.startsWith("\u200D") || line.endsWith("\u200D"), "'$line' splits a cluster")
            }
        }
        val viewport = DetailViewport()
        val screen = WorkspaceViewTest.render(viewport.render("Details", listOf(DetailViewport.Line(path)), true, 0), 14, 8)
        val rows = screen.lines().drop(1).take(6)
        for (row in rows) assertTrue(row.endsWith("│"), screen)
        assertEquals(path, rows.joinToString("") { row -> row.removePrefix("│").removeSuffix("│").trimEnd() }, screen)
    }

    @Test
    fun reviewIsDiscoverableFromBothPanesAndNumberOnePreservesDraftFocus() {
        val session = HomeLightSession(WorkspaceViewTest.fixture(temporary))
        val ui = HeadlessTui(session)
        ui.press('2')
        assertEquals(Screen.WORKSPACE, ui.app.activeScreen)
        ui.press(KeyCode.RIGHT)
        ui.press(' ')
        for (size in listOf(intArrayOf(80, 24), intArrayOf(120, 30), intArrayOf(200, 50), intArrayOf(80, 24))) {
            val screen = ui.screen(size[0], size[1])
            assertTrue(screen.contains("a: Review & apply"), screen)
            assertTrue(screen.contains("[2: Review]"), screen)
            assertFalse(screen.contains("3: "))
            assertFalse(screen.contains("never quit"))
            assertFalse(screen.contains("Planned actions"))
            ui.press('a')
            val review = ui.screen(size[0], size[1])
            assertTrue(review.contains("planned changes"), review)
            assertFalse(review.contains("changes done"), review)
            assertEquals(1, review.split("n/Esc/1: Cancel").size - 1, review)
            assertFalse(review.contains("Esc: Back"), review)
            ui.press('2')
            ui.press(KeyCode.ENTER)
            assertInstanceOf(ApplyModel.Confirmation::class.java, session.applyModel())
            ui.press('1')
            assertEquals(WORKSPACE_DETAILS, ui.focused())
            assertFalse(Files.isSymbolicLink(temporary.resolve("home/conflict")))
        }
        ui.press(KeyCode.ESCAPE)
        assertTrue(ui.screen(80, 24).contains("a: Review & apply"))
        ui.press(KeyCode.DOWN)
        val selected: Int = ui.app.selectedIndex()
        ui.press('1')
        assertEquals(selected, ui.app.selectedIndex())
    }

    @Test
    fun emptyWorkspaceAndUnresolvedWorkspaceHaveDifferentExplanations() {
        val config = Files.writeString(temporary.resolve("empty.json"),
            "{\"homelight\": {\"target-root\": \"" + temporary.resolve("target") + "\", \"relocations\": []}}\n")
        val empty = HeadlessTui(HomeLightSession(config)).screen(80, 24)
        assertTrue(empty.contains("No relocations in the configuration."), empty)
        assertFalse(empty.contains("already in sync"), empty)
        assertFalse(empty.contains("c: show"), empty)
        val conflict = HeadlessTui(HomeLightSession(WorkspaceViewTest.fixture(temporary))).screen(200, 50)
        assertTrue(conflict.contains("Will do: nothing until you choose."), conflict)
        assertFalse(conflict.contains("Planned actions"), conflict)
        assertFalse(conflict.contains("Overlapping risks"), conflict)
    }

    private fun thumbRows(screen: String, column: Int): List<Int> =
        screen.lines().withIndex().filter { (_, row) -> row.getOrNull(column) == '█' }.map { (index, _) -> index }
}
