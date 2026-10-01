package io.github.bigswlittlesw.homelight.tui

import dev.tamboui.toolkit.Toolkit
import dev.tamboui.tui.event.KeyCode
import dev.tamboui.tui.event.KeyEvent
import io.github.bigswlittlesw.homelight.application.ApplyModel
import io.github.bigswlittlesw.homelight.application.HomeLightSession
import io.github.bigswlittlesw.homelight.application.PlanModel
import io.github.bigswlittlesw.homelight.application.PlanSummary
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlan
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
        val view = Toolkit.column(viewport.render("Details", lines, true, 0), viewport.help("Tab: Back", "q: Quit")).fill()
        val small = WorkspaceViewTest.render(view, 80, 24)
        assertTrue(small.contains("█"), small)
        assertTrue(small.contains("[/]: Scroll"), small)
        viewport.scroll(Int.MAX_VALUE)
        val bottom = WorkspaceViewTest.render(view, 80, 24)
        assertTrue(bottom.contains("Row 27"), bottom)
        assertNotEquals(small.indexOf('█'), bottom.indexOf('█'))
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
        assertEquals(listOf("one two", "three"), DetailViewport.wrap("one two three", 8))
        assertEquals("/unbreakable/path", DetailViewport.wrap("/unbreakable/path", 5).joinToString(""))
    }

    @Test
    fun reviewIsDiscoverableFromBothPanesAndNumberOnePreservesDraftFocus() {
        val session = HomeLightSession(WorkspaceViewTest.fixture(temporary))
        val app = HomeLightApp(session)
        app.handleKeyEvent(KeyEvent.ofChar('2'))
        assertEquals(Screen.WORKSPACE, app.activeScreen())
        app.handleKeyEvent(KeyEvent.ofChar('l'))
        app.handleKeyEvent(KeyEvent.ofChar(' '))
        for (size in listOf(intArrayOf(80, 24), intArrayOf(120, 30), intArrayOf(200, 50), intArrayOf(80, 24))) {
            val screen = WorkspaceViewTest.render(app.render(), size[0], size[1])
            assertTrue(screen.contains("a: Review & apply"), screen)
            assertTrue(screen.contains("[2: Review]"), screen)
            assertFalse(screen.contains("3: "))
            assertFalse(screen.contains("never quit"))
            assertFalse(screen.contains("Planned actions"))
            app.handleKeyEvent(KeyEvent.ofChar('a'))
            val review = WorkspaceViewTest.render(app.render(), size[0], size[1])
            assertTrue(review.contains("planned changes"), review)
            assertFalse(review.contains("actions completed"), review)
            assertEquals(1, review.split("Cancel review").size - 1, review)
            assertFalse(review.contains("Esc: Back"), review)
            app.handleKeyEvent(KeyEvent.ofChar('2'))
            app.handleKeyEvent(KeyEvent.ofKey(KeyCode.ENTER))
            assertInstanceOf(ApplyModel.Confirmation::class.java, session.applyModel())
            app.handleKeyEvent(KeyEvent.ofChar('1'))
            assertEquals(PaneFocus.DETAIL, app.paneFocus())
            assertFalse(Files.isSymbolicLink(temporary.resolve("home/conflict")))
        }
        app.handleKeyEvent(KeyEvent.ofKey(KeyCode.ESCAPE))
        assertTrue(WorkspaceViewTest.render(app.render(), 80, 24).contains("a: Review & apply"))
        app.handleKeyEvent(KeyEvent.ofChar('j'))
        val selected: Int = app.selectedIndex()
        app.handleKeyEvent(KeyEvent.ofChar('1'))
        assertEquals(selected, app.selectedIndex())
    }

    @Test
    fun emptyWorkspaceAndUnresolvedWorkspaceHaveDifferentExplanations() {
        val config = Files.writeString(temporary.resolve("empty.yaml"),
            "homelight:\n  target-root: " + temporary.resolve("target") + "\n  relocations: []\n")
        val session = object : HomeLightSession(config) {
            override fun planModel(): PlanModel =
                PlanModel.Configured.of(config, temporary, ReconciliationPlan(listOf(), listOf()),
                    listOf(), PlanSummary.from(listOf()))
            override fun isPlanReady(): Boolean = true
        }
        val empty = WorkspaceViewTest.render(HomeLightApp(session).render(), 80, 24)
        assertTrue(empty.contains("No configured relocations."), empty)
        assertFalse(empty.contains("already in sync"), empty)
        assertFalse(empty.contains("in sync hidden"), empty)
        val conflict = WorkspaceViewTest.render(HomeLightApp(WorkspaceViewTest.fixture(temporary)).render(), 200, 50)
        assertTrue(conflict.contains("Choose a decision to see planned changes."), conflict)
        assertFalse(conflict.contains("Planned actions"), conflict)
        assertFalse(conflict.contains("Overlapping risks"), conflict)
    }
}
