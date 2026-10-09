package io.github.bigswlittlesw.lighten.tui

import dev.tamboui.toolkit.event.EventResult
import dev.tamboui.tui.event.KeyCode
import dev.tamboui.tui.event.KeyEvent
import dev.tamboui.tui.event.KeyModifiers
import dev.tamboui.tui.event.MouseButton
import dev.tamboui.tui.event.MouseEvent
import io.github.bigswlittlesw.lighten.application.ApplyModel
import io.github.bigswlittlesw.lighten.application.LightenSession
import io.github.bigswlittlesw.lighten.application.PlanBadge
import io.github.bigswlittlesw.lighten.application.ConfigurationEvaluation
import io.github.bigswlittlesw.lighten.config.ConfigurationLoader
import io.github.bigswlittlesw.lighten.config.Relocation
import io.github.bigswlittlesw.lighten.config.WhenSourceAndTargetDirectoriesExist
import io.github.bigswlittlesw.lighten.reconcile.ReconciliationAction
import io.github.bigswlittlesw.lighten.reconcile.ReconciliationPlan
import io.github.bigswlittlesw.lighten.reconcile.RelocationOutcome
import io.github.bigswlittlesw.lighten.reconcile.RelocationPlan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executor
import kotlin.io.path.createDirectories

class LightenAppTest {

    @Test
    fun finishingMovesTheSelectionToTheFirstFailureOrElseTheLastCompletedStep() {
        val (plan, firstPlan, secondPlan) = twoRelocations(Path.of("/home/second"), Path.of("/local/second"))
        val (pending, completed, failed) =
            listOf(ApplyModel.StepStatus.PENDING, ApplyModel.StepStatus.COMPLETED, ApplyModel.StepStatus.FAILED)
        // Rows: /home/first, its two steps, /home/second, its step.
        fun selection(vararg statuses: ApplyModel.StepStatus) = finishedSelection(ApplyView.rows(steps(plan) { statuses[it] }))

        assertEquals(2, selection(completed, failed, pending))
        assertEquals(1, selection(failed, completed, failed))
        assertEquals(4, selection(completed, completed, completed))
        assertEquals(2, selection(completed, completed, pending))
        assertNull(selection(pending, pending, pending))
        assertEquals(listOf(firstPlan, firstPlan, secondPlan), steps(plan) { pending }.map { it.relocation })
    }

    @Test
    fun selectionStaysWhereTheUserPutItWhileIndependentRelocationsRun(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val names = listOf("one", "two", "three")
        for (name in names) Files.createDirectories(root.resolve("home/$name"))
        // Siblings under an existing parent are independent; with a missing parent they would share one group.
        Files.createDirectories(root.resolve("local"))
        val relocations = names.joinToString(",\n") { name ->
            "{\"source-path\": \"${root.resolve("home/$name")}\", \"target-path\": \"${root.resolve("local/$name")}\"}"
        }
        val config = Files.writeString(root.resolve("config.json"),
            "{\"lighten\": {\"target-root\": \"${root.resolve("local")}\", \"relocations\": [\n$relocations\n]}}\n")
        // Each changing step stays running for this long, so frames render while steps start and finish.
        val ui = HeadlessTui(LightenSession(config, debugStepDelayMillis = 150))
        ui.press('a')
        val steps = ApplyView.steps(ui.app.session.applyModel())
        val rows = ApplyView.rows(steps)
        ui.press(KeyCode.END)
        ui.press(KeyCode.UP)
        val chosen = rows.size - 2
        assertEquals(chosen, ui.app.selectedIndex())
        ui.press('y')

        var mostRunning = 0
        while (true) {
            val running = ui.app.session.applyModel() as? ApplyModel.Running ?: break
            mostRunning = maxOf(mostRunning, running.steps.count { it.status == ApplyModel.StepStatus.RUNNING })
            val screen = ui.screen(120, 30)
            // The result can be published between the read above and the render, which then makes the finishing jump.
            // The header shows which model the render used; only a running render must keep the selection.
            if (!screen.contains("[Applying]")) {
                assertInstanceOf(ApplyModel.Result::class.java, ui.app.session.applyModel(), screen)
                break
            }
            assertEquals(chosen, ui.app.selectedIndex(), screen)
            assertFalse(screen.contains("Workspace"), screen)
            Thread.sleep(10)
        }
        assertTrue(mostRunning > 1, "relocations should run at once")
        ui.app.session.awaitExecution()
        val result = ui.screen(120, 30)
        // The last row is the last relocation's last step.
        assertEquals(rows.size - 1, ui.app.selectedIndex(), result)
        assertTrue(result.contains("${steps.count { it.action.mutatesFilesystem }} of ${steps.count { it.action.mutatesFilesystem }} changes done"), result)
        // The jump happens once: afterwards the user's selection stands.
        ui.press(KeyCode.HOME)
        ui.screen(120, 30)
        assertEquals(0, ui.app.selectedIndex())
    }

    @Test
    fun reviewSelectionMovesThroughRelocationAndStepRows(@TempDir temporary: Path) {
        val ui = HeadlessTui(LightenSession(twoMissingSources(temporary)))
        ui.press('a')
        val rows = ApplyView.rows(ApplyView.steps(ui.app.session.applyModel()))
        assertEquals(REVIEW_LIST, ui.focused())
        assertEquals(0, ui.app.selectedIndex())
        for (i in 1 until rows.size) {
            ui.press(KeyCode.DOWN)
            assertEquals(i, ui.app.selectedIndex())
        }
        ui.press(KeyCode.DOWN)
        assertEquals(rows.size - 1, ui.app.selectedIndex())
        ui.press(KeyCode.HOME)
        assertEquals(0, ui.app.selectedIndex())
        // A relocation row shows the relocation in Details; a step row shows the step.
        assertTrue(WorkspaceViewTest.rightPane(ui.screen(120, 30), 120).contains("Paths"))
        ui.press(KeyCode.END)
        assertEquals(rows.size - 1, ui.app.selectedIndex())
        val screen = ui.screen(120, 30)
        assertTrue(screen.contains("❯  ○ Link source to target"), screen)
        assertTrue(WorkspaceViewTest.rightPane(screen, 120).startsWith("Link source to target"), screen)
        // A path ends its row, shortened in the middle when it does not fit.
        for (name in listOf("one", "two")) assertTrue(Regex("home/$name *│").containsMatchIn(lightBorders(screen)), screen)
    }

    /**
     * The plan list moves its selection with the arrows, PageUp/PageDown and Home/End. ←, Space and Enter change
     * nothing in it, → opens Details, and Enter still leaves Results.
     */
    @Test
    fun theReviewListKeepsReviewKeys(@TempDir temporary: Path) {
        val ui = HeadlessTui(LightenSession(twoMissingSources(temporary)))
        ui.press('a')
        val rows = ApplyView.rows(ApplyView.steps(ui.app.session.applyModel()))
        val start = ui.screen()
        for (key in listOf(KeyCode.LEFT, KeyCode.ENTER)) {
            ui.press(key)
            assertEquals(start, ui.screen(), "$key")
        }
        ui.press(' ')
        assertEquals(start, ui.screen())
        assertEquals(REVIEW_LIST, ui.focused())
        ui.press(KeyCode.PAGE_DOWN)
        assertEquals(rows.size - 1, ui.app.selectedIndex())
        ui.press(KeyCode.PAGE_UP)
        assertEquals(0, ui.app.selectedIndex())
        ui.press(KeyCode.RIGHT)
        assertEquals(REVIEW_DETAILS, ui.focused())
        ui.press(KeyCode.LEFT)
        assertEquals(REVIEW_LIST, ui.focused())

        ui.press('y')
        ui.app.session.awaitExecution()
        assertTrue(ui.screen().contains("[2: Results]"))
        ui.press(KeyCode.ENTER)
        assertEquals(Screen.WORKSPACE, ui.app.activeScreen)
    }

    /** The wheel over the list moves its selection one row; clicks on it neither focus it nor change it. */
    @Test
    fun theWheelMovesTheReviewSelectionAndClicksChangeNothing(@TempDir temporary: Path) {
        val ui = HeadlessTui(LightenSession(twoMissingSources(temporary)))
        ui.press('a')
        val top = ui.screen().lines().indexOfFirst { it.contains("Plan") } + 1
        ui.press(MouseEvent.scrollDown(3, top + 1))
        assertEquals(1, ui.app.selectedIndex())
        ui.press(MouseEvent.scrollUp(3, top + 1))
        assertEquals(0, ui.app.selectedIndex())
        ui.press(KeyCode.TAB)
        assertEquals(REVIEW_DETAILS, ui.focused())
        val start = ui.screen()
        // The first row is a relocation's heading: the pointer column, its mark and its path.
        for (x in listOf(1, 2, 4)) {
            for (event in listOf(MouseEvent.press(MouseButton.LEFT, x, top), MouseEvent.release(MouseButton.LEFT, x, top))) {
                ui.press(event)
                assertEquals(REVIEW_DETAILS, ui.focused(), "$event")
                assertEquals(start, ui.screen(), "$event")
            }
        }
        ui.press(MouseEvent.scrollDown(3, top))
        assertEquals(1, ui.app.selectedIndex())
        assertEquals(REVIEW_DETAILS, ui.focused())
    }

    @Test
    fun aRelocationRowNamesTheOneTimeChoiceAsItsDecisionThroughResults(@TempDir temporary: Path) {
        val ui = HeadlessTui(session(temporary, conflicts = listOf("both")))
        ui.press(KeyCode.TAB)
        ui.press(KeyCode.ENTER)
        ui.press('a')
        assertEquals(0, ui.app.selectedIndex())
        val expected = compact("Decision: keep target, delete source (your choice, this run only)")
        val review = WorkspaceViewTest.rightPane(ui.screen(120, 30), 120)
        assertTrue(compact(review).contains(expected), review)
        ui.press('y')
        ui.app.session.awaitExecution()
        ui.screen(120, 30)
        // Applying forgets the choice; the reviewed snapshot still names it.
        assertTrue(assertInstanceOf(ConfigurationEvaluation.Loaded::class.java, ui.app.session.evaluation()).draft.isEmpty())
        ui.press(KeyCode.HOME)
        val results = ui.screen(120, 30)
        assertTrue(results.contains("[2: Results]"), results)
        assertTrue(compact(WorkspaceViewTest.rightPane(results, 120)).contains(expected), results)
    }

    @Test
    fun aRelocationRowNamesTheSavedRuleAsItsDecision(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        Files.createDirectories(root.resolve("home/both"))
        Files.createDirectories(root.resolve("local/both"))
        val config = Files.writeString(root.resolve("config.json"), "{\"lighten\": {\"target-root\": \"${root.resolve("local")}\"," +
            " \"relocations\": [{\"source-path\": \"${root.resolve("home/both")}\", \"target-path\": \"${root.resolve("local/both")}\"," +
            " \"when-source-and-target-directories-exist\": \"adopt\", \"when-adopting-target\": \"discard-source\"}]}}\n")
        val ui = HeadlessTui(LightenSession(config))
        ui.press('a')
        val review = WorkspaceViewTest.rightPane(ui.screen(120, 30), 120)
        assertTrue(compact(review).contains(compact("Decision: keep target, delete source (your configuration)")), review)
    }

    @Test
    fun reviewsConfirmsAppliesAndReturnsToRefreshedStatus(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val source = root.resolve("home/cache")
        val target = root.resolve("local/cache")
        val config = Files.writeString(root.resolve("config.json"), ("""
                {"lighten": {
                  "target-root": "%s",
                  "relocations": [
                    {"source-path": "%s", "target-path": "%s"}
                  ]
                }}
                """.trimIndent() + "\n").format(root, source, target))
        val ui = HeadlessTui(LightenSession(config))

        ui.press('2')
        assertEquals(Screen.APPLY, ui.app.activeScreen)
        ui.press(KeyCode.ENTER)
        assertTrue(ui.app.session.applyModel() is ApplyModel.Confirmation)
        assertFalse(Files.exists(source))
        ui.press('n')
        assertEquals(Screen.WORKSPACE, ui.app.activeScreen)
        ui.press('a')
        ui.press('y')
        ui.app.session.awaitExecution()
        assertTrue(Files.isSymbolicLink(source))
        assertTrue(ui.app.session.applyModel() is ApplyModel.Result)
        ui.press(KeyCode.ENTER)
        assertEquals(Screen.WORKSPACE, ui.app.activeScreen)
        assertEquals(1, (ui.app.session.evaluation() as ConfigurationEvaluation.Loaded).items.count { it.badge() == PlanBadge.IN_SYNC })
        ui.press('2')
        assertTrue(ui.app.session.applyModel() is ApplyModel.Result)
        ui.press('r')
        assertTrue(ui.app.session.isPlanReady())
        assertFalse((ui.app.session.evaluation() as ConfigurationEvaluation.Loaded).plan.actions().any(ReconciliationAction::mutatesFilesystem))
        ui.press('a')
        val unchanged = ui.app.session.applyModel()
        ui.press('y')
        assertSame(unchanged, ui.app.session.applyModel())
        assertFalse(ui.app.session.isApplying())
        ui.press(KeyCode.ENTER)
        assertEquals(Screen.WORKSPACE, ui.app.activeScreen)
    }

    @Test
    fun runningApplyConsumesQuitRefreshAndRepeatedConfirmation(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val config = Files.writeString(root.resolve("config.json"), ("""
                {"lighten": {
                  "target-root": "%s",
                  "relocations": [
                    {"source-path": "%s", "target-path": "%s"}
                  ]
                }}
                """.trimIndent() + "\n").format(root, root.resolve("source"), root.resolve("target")))
        val ui = HeadlessTui(LightenSession(config))
        ui.press('a')
        val tasks = mutableListOf<Runnable>()
        ui.app.session.confirmApply(Executor { tasks.add(it) })
        val running = ui.app.session.applyModel()
        for (key in charArrayOf('r', 'y', 'a', '1', '2', 'q')) {
            assertEquals(EventResult.HANDLED, ui.app.keyHandler.handle(KeyEvent.ofChar(key, KEY_BINDINGS)))
            assertEquals(Screen.APPLY, ui.app.activeScreen)
            assertSame(running, ui.app.session.applyModel())
        }
        tasks.first().run()
        assertTrue(Files.isSymbolicLink(root.resolve("source")))
    }

    @Test
    fun handlesNavigationKeys(@TempDir temporary: Path) {
        val session = session(temporary, inSync = listOf("source1", "source2", "source3"))
        val ui = HeadlessTui(session)

        assertEquals(0, ui.app.selectedIndex())

        ui.press(KeyCode.DOWN)
        assertEquals(1, ui.app.selectedIndex())

        ui.press(KeyCode.DOWN)
        assertEquals(2, ui.app.selectedIndex())

        // Cannot move past the end
        ui.press(KeyCode.DOWN)
        assertEquals(2, ui.app.selectedIndex())

        ui.press(KeyCode.UP)
        assertEquals(1, ui.app.selectedIndex())

        ui.press(KeyCode.END)
        assertEquals(2, ui.app.selectedIndex())

        ui.press(KeyCode.HOME)
        assertEquals(0, ui.app.selectedIndex())

        // TamboUI's list pages too
        ui.press(KeyCode.PAGE_DOWN)
        assertEquals(2, ui.app.selectedIndex())
        ui.press(KeyCode.PAGE_UP)
        assertEquals(0, ui.app.selectedIndex())

        // Cannot move before the first row
        ui.press(KeyCode.UP)
        assertEquals(0, ui.app.selectedIndex())

        // The former vim keys are not navigation
        for (letter in "jJGlL") ui.press(letter)
        assertEquals(0, ui.app.selectedIndex())
        assertEquals(WORKSPACE_LIST, ui.focused())
    }

    @Test
    fun handlesConvergedFilterAndToggle(@TempDir temporary: Path) {
        val session = session(temporary, inSync = listOf("source2", "source3"), conflicts = listOf("source1"))
        val ui = HeadlessTui(session)

        // While a relocation needs a choice, in-sync rows start hidden.
        assertFalse(ui.app.showInSync)
        assertEquals(0, ui.app.selectedIndex())
        // The count is in the list's title, where the selection cannot land
        val screen = ui.screen(80, 24)
        assertTrue(screen.contains("┏Relocations · c: show 2 in sync"), screen)

        // Only the row that needs a choice is listed, so moving down stays on it.
        ui.press(KeyCode.DOWN)
        assertEquals(0, ui.app.selectedIndex())

        ui.press('c')
        assertTrue(ui.app.showInSync)
        assertTrue(ui.screen(80, 24).contains("┏Relocations · c: hide 2 in sync"))

        // Now all three rows can be selected.
        ui.press(KeyCode.DOWN)
        assertEquals(1, ui.app.selectedIndex())
        ui.press(KeyCode.DOWN)
        assertEquals(2, ui.app.selectedIndex())

        // `c` hides them again, and the selection goes back to the first row.
        ui.press('c')
        assertFalse(ui.app.showInSync)
        assertEquals(0, ui.app.selectedIndex())
    }

    @Test
    fun allInSyncDefaultsToShowInSync(@TempDir temporary: Path) {
        val session = session(temporary, inSync = listOf("source1"))
        val ui = HeadlessTui(session)
        assertTrue(ui.app.showInSync)
        assertEquals(0, ui.app.selectedIndex())
    }

    @Test
    fun inSyncToggleActsOnlyWhileItsHintIsShown(@TempDir temporary: Path) {
        // All in sync: every row is shown and there is nothing to hide.
        val all = HeadlessTui(session(temporary.resolve("all").createDirectories(), inSync = listOf("source1")))
        assertFalse(all.screen(80, 24).contains("c: "), all.screen(80, 24))
        all.press('c')
        assertTrue(all.app.showInSync)
        // `c` did not become the user's setting, which would outlast a check again.
        all.press('r')
        assertTrue(all.app.showInSync)

        // Nothing in sync: there is nothing to show.
        val none = HeadlessTui(session(temporary.resolve("none").createDirectories(), conflicts = listOf("source1")))
        assertFalse(none.screen(80, 24).contains("c: "), none.screen(80, 24))
        none.press('c')
        assertFalse(none.app.showInSync)

        // Some in sync: the hint is shown and `c` toggles.
        val some = HeadlessTui(
            session(temporary.resolve("some").createDirectories(), inSync = listOf("source2"), conflicts = listOf("source1")),
        )
        assertTrue(some.screen(80, 24).contains("c: show 1 in sync"))
        some.press('c')
        assertTrue(some.app.showInSync)
    }

    @Test
    fun reviewAndApplyActsOnlyWhileItsHintIsShown(@TempDir temporary: Path) {
        // Nothing to apply: the help line lists only `2`, and `a` stays on the Workspace.
        val nothing = HeadlessTui(session(temporary.resolve("nothing").createDirectories(), inSync = listOf("source1")))
        assertFalse(nothing.screen(80, 24).contains("Review & apply"), nothing.screen(80, 24))
        nothing.press('a')
        assertEquals(Screen.WORKSPACE, nothing.app.activeScreen)
        assertSame(ApplyModel.Idle, nothing.app.session.applyModel())
        nothing.press('2')
        assertEquals(Screen.APPLY, nothing.app.activeScreen)

        // Changes to apply: `a` is listed and opens Review.
        val changes = HeadlessTui(LightenSession(twoMissingSources(temporary.resolve("changes").createDirectories())))
        assertTrue(changes.screen(80, 24).contains("Review & apply"), changes.screen(80, 24))
        changes.press('a')
        assertEquals(Screen.APPLY, changes.app.activeScreen)
        assertInstanceOf(ApplyModel.Confirmation::class.java, changes.app.session.applyModel())
    }

    @Test
    fun switchesScreensViaKeys() {
        val ui = HeadlessTui(LightenSession(Path.of("/nonexistent/config.json")))
        assertEquals(Screen.WORKSPACE, ui.app.activeScreen)

        ui.press('2')
        assertEquals(Screen.WORKSPACE, ui.app.activeScreen)

        ui.press('2')
        assertEquals(Screen.WORKSPACE, ui.app.activeScreen)

        ui.press('1')
        assertEquals(Screen.WORKSPACE, ui.app.activeScreen)

        ui.press('p')
        assertEquals(Screen.WORKSPACE, ui.app.activeScreen)

        ui.press('s')
        assertEquals(Screen.WORKSPACE, ui.app.activeScreen)
    }

    @Test
    fun resolvesConflictOnPlanScreenWithSpaceOrEnter(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val source = root.resolve("home/cache")
        val target = Files.createDirectories(root.resolve("local/cache"))
        Files.writeString(target.resolve("file.txt"), "target content")

        val config = Files.createTempFile(root, "lighten", ".json")
        Files.writeString(config, ("""
                {"lighten": {
                  "target-root": "%s",
                  "relocations": [
                    {"source-path": "%s", "target-path": "%s"}
                  ]
                }}
                """.trimIndent() + "\n").format(root, source, target))

        val ui = HeadlessTui(LightenSession(config))
        assertEquals(Screen.WORKSPACE, ui.app.activeScreen)
        assertTrue(ui.app.session.hasConflicts())
        assertEquals(WORKSPACE_LIST, ui.focused())

        ui.press(KeyCode.TAB)
        assertEquals(WORKSPACE_DETAILS, ui.focused())
        assertEquals(0, ui.app.detailSelectedIndex)

        ui.press('2')
        assertEquals(Screen.WORKSPACE, ui.app.activeScreen)
        assertEquals(WORKSPACE_DETAILS, ui.focused())

        // Space picks the focused choice, so the plan is ready to review.
        ui.press(' ')
        assertFalse(ui.app.session.hasConflicts())
        assertTrue(ui.app.session.isPlanReady())

        ui.press('2')
        assertEquals(Screen.APPLY, ui.app.activeScreen)
        assertEquals(REVIEW_LIST, ui.focused())
        assertTrue(ui.app.session.applyModel() is ApplyModel.Confirmation)
        assertFalse(Files.exists(source))
    }

    @Test
    fun handlesMasterDetailFocusSwitchingAndDetailCursorMovement(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val source1 = Files.createDirectories(root.resolve("home/cache1"))
        Files.writeString(source1.resolve("file1.txt"), "source content 1")
        val target1 = Files.createDirectories(root.resolve("local/cache1"))
        Files.writeString(target1.resolve("file1.txt"), "target content 1")

        val source2 = Files.createDirectories(root.resolve("home/cache2"))
        Files.writeString(source2.resolve("file2.txt"), "source content 2")
        val target2 = Files.createDirectories(root.resolve("local/cache2"))
        Files.writeString(target2.resolve("file2.txt"), "target content 2")

        val config = Files.createTempFile(root, "lighten", ".json")
        Files.writeString(config, ("""
                {"lighten": {
                  "target-root": "%s",
                  "relocations": [
                    {"source-path": "%s", "target-path": "%s"},
                    {"source-path": "%s", "target-path": "%s"}
                  ]
                }}
                """.trimIndent() + "\n").format(root, source1, target1, source2, target2))

        val ui = HeadlessTui(LightenSession(config))
        assertEquals(WORKSPACE_LIST, ui.focused())
        assertEquals(0, ui.app.selectedIndex())

        // → moves focus to Details.
        ui.press(KeyCode.RIGHT)
        assertEquals(WORKSPACE_DETAILS, ui.focused())
        assertEquals(0, ui.app.detailSelectedIndex)

        // ↓ and ↑ move through the choices.
        ui.press(KeyCode.DOWN)
        assertEquals(1, ui.app.detailSelectedIndex)

        ui.press(KeyCode.DOWN)
        assertEquals(2, ui.app.detailSelectedIndex)

        ui.press(KeyCode.UP)
        assertEquals(1, ui.app.detailSelectedIndex)

        // ← returns to the list.
        ui.press(KeyCode.LEFT)
        assertEquals(WORKSPACE_LIST, ui.focused())

        ui.press(KeyCode.DOWN)
        assertEquals(1, ui.app.selectedIndex())

        ui.press(KeyCode.RIGHT)
        assertEquals(WORKSPACE_DETAILS, ui.focused())

        // Enter picks a choice for the second relocation and keeps focus on Details.
        ui.press(KeyCode.ENTER)
        assertEquals(WORKSPACE_DETAILS, ui.focused())

        ui.press(KeyCode.LEFT)
        assertEquals(WORKSPACE_LIST, ui.focused())

        // Tab moves focus to Details
        ui.press(KeyCode.TAB)
        assertEquals(WORKSPACE_DETAILS, ui.focused())

        // Shift-Tab moves it back to the list
        ui.press(KeyEvent.ofKey(KeyCode.TAB, KeyModifiers.SHIFT, KEY_BINDINGS))
        assertEquals(WORKSPACE_LIST, ui.focused())
    }

    @Test
    fun canInspectDetailsWhenNoResolutionsAvailable(@TempDir temporary: Path) {
        val session = session(temporary, inSync = listOf("source1"))
        val ui = HeadlessTui(session)
        assertEquals(WORKSPACE_LIST, ui.focused())

        // Details take focus even when there are no choices.
        ui.press(KeyCode.TAB)
        assertEquals(WORKSPACE_DETAILS, ui.focused())

        ui.press(KeyCode.RIGHT)
        assertEquals(WORKSPACE_DETAILS, ui.focused())
    }

    @Test
    fun handlesDecisionSelectionAndPreservesSelectionOnResolvedItem(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val source1 = Files.createDirectories(root.resolve("home/cache1"))
        Files.writeString(source1.resolve("file1.txt"), "source content 1")
        val target1 = Files.createDirectories(root.resolve("local/cache1"))
        Files.writeString(target1.resolve("file1.txt"), "target content 1")

        val source2 = Files.createDirectories(root.resolve("home/cache2"))
        Files.writeString(source2.resolve("file2.txt"), "source content 2")
        val target2 = Files.createDirectories(root.resolve("local/cache2"))
        Files.writeString(target2.resolve("file2.txt"), "target content 2")

        val config = Files.createTempFile(root, "lighten", ".json")
        Files.writeString(config, ("""
                {"lighten": {
                  "target-root": "%s",
                  "relocations": [
                    {"source-path": "%s", "target-path": "%s"},
                    {"source-path": "%s", "target-path": "%s"}
                  ]
                }}
                """.trimIndent() + "\n").format(root, source1, target1, source2, target2))

        val ui = HeadlessTui(LightenSession(config))
        assertEquals(0, ui.app.selectedIndex())
        assertEquals(WORKSPACE_LIST, ui.focused())

        ui.press(KeyCode.RIGHT)
        assertEquals(WORKSPACE_DETAILS, ui.focused())
        assertEquals(0, ui.app.detailSelectedIndex) // 0 is ADOPT_AND_DISCARD_SOURCE

        // Choice 2 is LEAVE_UNCHANGED, Leave both as they are.
        ui.press(KeyCode.DOWN)
        ui.press(KeyCode.DOWN)
        assertEquals(2, ui.app.detailSelectedIndex)

        ui.press(' ')
        assertEquals(WORKSPACE_DETAILS, ui.focused())

        // The relocation stays selected and shows as Left as is.
        assertInstanceOf(ConfigurationEvaluation.Loaded::class.java, ui.app.session.evaluation()).let { configured ->
            val visible = WorkspaceView.visibleItems(configured, ui.app.showInSync)
            assertTrue(visible.size >= 2)
            val currentItem = visible[ui.app.selectedIndex()]
            assertEquals(source1, currentItem.relocation.sourcePath)
            assertEquals(PlanBadge.SKIPPED, currentItem.badge())
            assertEquals(2, ui.app.detailSelectedIndex)
        }

        // Back on the list: left as is sorts below the relocation that still needs a choice, now first.
        ui.press(KeyCode.LEFT)
        assertEquals(WORKSPACE_LIST, ui.focused())
        assertEquals(1, ui.app.selectedIndex())

        ui.press(KeyCode.UP)
        assertEquals(0, ui.app.selectedIndex())

        ui.press(KeyCode.RIGHT)
        assertEquals(WORKSPACE_DETAILS, ui.focused())

        // Choice 3 is DISCARD_BOTH, Delete both, start empty.
        ui.press(KeyCode.DOWN)
        ui.press(KeyCode.DOWN)
        ui.press(KeyCode.DOWN)
        assertEquals(3, ui.app.detailSelectedIndex)
        ui.press(KeyCode.ENTER)

        // That relocation stays selected and shows as Delete.
        assertInstanceOf(ConfigurationEvaluation.Loaded::class.java, ui.app.session.evaluation()).let { configured ->
            val visible = WorkspaceView.visibleItems(configured, ui.app.showInSync)
            val currentItem = visible[ui.app.selectedIndex()]
            assertEquals(source2, currentItem.relocation.sourcePath)
            assertEquals(PlanBadge.DISCARD, currentItem.badge())
        }
    }

    private companion object {
        /** A plan of two relocations, `/home/first` with two actions and [source] with one, and its relocation plans. */
        fun twoRelocations(source: Path, target: Path): Triple<ReconciliationPlan, RelocationPlan, RelocationPlan> {
            val first = Relocation(Path.of("/home/first"), Path.of("/local/first"))
            val second = Relocation(source, target)
            val firstPlan = RelocationPlan(first, RelocationOutcome.CONVERGED,
                listOf(ReconciliationAction.CreateDirectory(first.targetPath),
                    ReconciliationAction.CreateSymlink(first.sourcePath, first.targetPath)), listOf())
            val secondPlan = RelocationPlan(second, RelocationOutcome.CONVERGED,
                listOf(ReconciliationAction.CreateDirectory(second.targetPath)), listOf())
            return Triple(ReconciliationPlan(listOf(firstPlan, secondPlan), listOf()), firstPlan, secondPlan)
        }

        /** `text` without spaces, so a line Details wrapped still matches. */
        fun compact(text: String): String = text.replace(" ", "")

        /** A configuration of two relocations whose sources are missing: each plans a target folder and a link. */
        fun twoMissingSources(temporary: Path): Path {
            val root = temporary.toRealPath()
            val relocations = listOf("one", "two").joinToString(",\n") { name ->
                "{\"source-path\": \"${root.resolve("home/$name")}\", \"target-path\": \"${root.resolve("local/$name")}\"}"
            }
            Files.createDirectories(root.resolve("home"))
            return Files.writeString(root.resolve("config.json"),
                "{\"lighten\": {\"target-root\": \"${root.resolve("local")}\", \"relocations\": [\n$relocations\n]}}\n")
        }

        fun steps(plan: ReconciliationPlan, status: (Int) -> ApplyModel.StepStatus): List<ApplyModel.Step> =
            plan.relocations.flatMap { relocation -> relocation.actions.map { relocation to it } }
                .mapIndexed { i, (relocation, action) -> ApplyModel.Step(relocation, action, status(i), status(i).toString()) }

        /** A real session whose `inSync` sources already link to their targets and whose `conflicts` have both directories. */
        fun session(temporary: Path, inSync: List<String> = listOf(), conflicts: List<String> = listOf()): LightenSession {
            val root = temporary.toRealPath()
            for (name in inSync) {
                Files.createDirectories(root.resolve("home"))
                Files.createSymbolicLink(root.resolve("home/$name"), Files.createDirectories(root.resolve("local/$name")))
            }
            for (name in conflicts) {
                Files.createDirectories(root.resolve("home/$name"))
                Files.createDirectories(root.resolve("local/$name"))
            }
            val relocations = (inSync + conflicts).joinToString(",\n") { name ->
                "{\"source-path\": \"${root.resolve("home/$name")}\", \"target-path\": \"${root.resolve("local/$name")}\"}"
            }
            val config = Files.writeString(root.resolve("config.json"),
                "{\"lighten\": {\"target-root\": \"${root.resolve("local")}\", \"relocations\": [\n$relocations\n]}}\n")
            return LightenSession(config)
        }

        fun type(ui: HeadlessTui, value: String) {
            for (character in value.toCharArray()) ui.press(character)
        }
    }
}
