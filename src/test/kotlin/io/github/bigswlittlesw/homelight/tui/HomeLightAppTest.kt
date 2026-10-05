package io.github.bigswlittlesw.homelight.tui

import dev.tamboui.toolkit.event.EventResult
import dev.tamboui.tui.event.KeyCode
import dev.tamboui.tui.event.KeyEvent
import dev.tamboui.tui.event.KeyModifiers
import io.github.bigswlittlesw.homelight.application.ApplyModel
import io.github.bigswlittlesw.homelight.application.HomeLightSession
import io.github.bigswlittlesw.homelight.application.PlanBadge
import io.github.bigswlittlesw.homelight.application.ConfigurationEvaluation
import io.github.bigswlittlesw.homelight.config.ConfigurationLoader
import io.github.bigswlittlesw.homelight.config.Relocation
import io.github.bigswlittlesw.homelight.config.WhenSourceAndTargetDirectoriesExist
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlan
import io.github.bigswlittlesw.homelight.reconcile.RelocationOutcome
import io.github.bigswlittlesw.homelight.reconcile.RelocationPlan
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

class HomeLightAppTest {

    @Test
    fun createsRootRelativeRowsWithDefaultPolicies(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val config = root.resolve("new/config.json")
        val ui = HeadlessTui(HomeLightSession(config))
        ui.press('i')
        ui.press('\u0015')
        type(ui, root.resolve("home").toString())
        ui.press(KeyCode.DOWN)
        ui.press('\u0015')
        type(ui, root.resolve("local").toString())
        ui.press(KeyCode.ENTER)
        ui.press('a')
        type(ui, ".cache/tool")
        ui.press(KeyCode.ESCAPE)
        ui.press('v')
        val validation = ui.screen(120, 30)
        assertTrue(validation.contains("Validation: valid"), validation)
        ui.press('s')

        val relocation = ConfigurationLoader().load(config).relocations.first()
        assertEquals(root.resolve("home/.cache/tool"), relocation.sourcePath)
        assertEquals(root.resolve("local/.cache/tool"), relocation.targetPath)
        assertEquals(WhenSourceAndTargetDirectoriesExist.PROMPT, relocation.whenSourceAndTargetDirectoriesExist)
        assertFalse(Files.exists(root.resolve("home/.cache/tool")), "saving must not relocate")
    }

    @Test
    fun setupTableKeepsRowsWhileLocationsAreEditedAndConfirmsDraftDiscard(@TempDir temporary: Path) {
        val ui = HeadlessTui(HomeLightSession(temporary.resolve("config.json")))
        ui.press('i')
        ui.press(KeyCode.ENTER)
        ui.press('a')
        type(ui, ".cache/tool")
        ui.press(KeyCode.ESCAPE)

        var table = ui.screen(80, 24)
        assertTrue(table.contains("Source (relative)"), table)
        assertTrue(table.contains(".cache/tool"), table)

        ui.press('d')
        table = ui.screen(80, 24)
        assertTrue(table.contains("No relocations yet"), table)
        ui.press('a')
        type(ui, ".cache/tool")
        ui.press(KeyCode.ESCAPE)

        ui.press('e')
        ui.press(KeyCode.DOWN)
        type(ui, "/target")
        ui.press(KeyCode.ENTER)
        table = ui.screen(80, 24)
        assertTrue(table.contains(".cache/tool"), table)
        assertTrue(table.contains("Validation: not run"), table)

        ui.press('q')
        val dialog = ui.screen(80, 24)
        assertTrue(dialog.contains("╔Discard this configuration?"), dialog)
        assertTrue(dialog.contains("y: Discard · n/Esc: Keep editing"), dialog)
        assertEquals(DIALOG, ui.focused())
        ui.press(KeyCode.ESCAPE)
        table = ui.screen(80, 24)
        assertTrue(table.contains(".cache/tool"), table)
        assertFalse(Files.exists(temporary.resolve("config.json")))

        ui.press('q')
        // Only `y` discards; every other key, Enter included, leaves the dialog open.
        for (key in listOf(KeyCode.ENTER, KeyCode.TAB, KeyCode.DOWN)) ui.press(key)
        ui.press('d')
        assertEquals(dialog, ui.screen(80, 24))
        ui.press('n')
        assertTrue(ui.screen(80, 24).contains(".cache/tool"))
        ui.press('q')
        ui.press('y')
        assertEquals(WORKSPACE_DETAILS, ui.focused(), "the workspace without a configuration has only its details pane")
        ui.press('i')
        val fresh = ui.screen(80, 24)
        assertTrue(fresh.contains("Target root: "), fresh)
        assertTrue(fresh.contains("Validation: not run"), fresh)
        assertFalse(fresh.contains(".cache/tool"), fresh)
    }

    @Test
    fun rejectsUnsafeRelativeRowsUntilCorrected(@TempDir temporary: Path) {
        for (invalid in listOf("", ".", "..")) {
            val config = temporary.resolve("config-" + (if (invalid.isEmpty()) "blank" else invalid.replace('.', 'd')) + ".json")
            val ui = setupWithEmptyRow(config, temporary)
            type(ui, invalid)
            ui.press(KeyCode.ESCAPE)

            assertInvalidAndUnpublished(ui, config, if (invalid.isEmpty()) "cannot be blank" else "nested below their root")
            ui.press(KeyCode.ENTER)
            repeat(invalid.length) { ui.press(KeyCode.BACKSPACE) }
            type(ui, "nested/cache")
            ui.press(KeyCode.ESCAPE)
            ui.press('s')
            assertTrue(Files.exists(config), invalid)
            val relocation = ConfigurationLoader().load(config).relocations.first()
            assertEquals(temporary.resolve("home/nested/cache"), relocation.sourcePath)
            assertEquals(temporary.resolve("local/nested/cache"), relocation.targetPath)
        }
    }

    @Test
    fun rejectsInvalidTargetsWithValidSourcesUntilCorrected(@TempDir temporary: Path) {
        var index = 0
        for (invalid in listOf("", ".", "../escape")) {
            val config = temporary.resolve("invalid-target-" + index++ + ".json")
            val ui = setupWithEmptyRow(config, temporary)
            type(ui, "valid/source")
            ui.press(KeyCode.DOWN)
            repeat("valid/source".length) { ui.press(KeyCode.BACKSPACE) }
            type(ui, invalid)
            ui.press(KeyCode.ESCAPE)
            assertInvalidAndUnpublished(ui, config, if (invalid.isEmpty()) "cannot be blank" else "nested below their root")
            ui.press(KeyCode.ENTER)
            ui.press(KeyCode.DOWN)
            repeat(invalid.length) { ui.press(KeyCode.BACKSPACE) }
            type(ui, "valid/target")
            ui.press(KeyCode.ESCAPE)
            ui.press('s')
            val relocation = ConfigurationLoader().load(config).relocations.first()
            assertEquals(temporary.resolve("home/valid/source"), relocation.sourcePath)
            assertEquals(temporary.resolve("local/valid/target"), relocation.targetPath)
        }
    }

    @Test
    fun rejectsRelativeArchiveRootUntilCorrected(@TempDir temporary: Path) {
        val config = temporary.resolve("archive.json")
        val ui = setupWithEmptyRow(config, temporary)
        type(ui, "nested/cache")
        repeat(5) { ui.press(KeyCode.DOWN) }
        type(ui, "archive")
        ui.press(KeyCode.ESCAPE)

        assertInvalidAndUnpublished(ui, config, "Archive root must be an absolute path")
        ui.press(KeyCode.ENTER)
        repeat(5) { ui.press(KeyCode.DOWN) }
        repeat("archive".length) { ui.press(KeyCode.BACKSPACE) }
        type(ui, temporary.resolve("archive").toString())
        ui.press(KeyCode.ESCAPE)
        ui.press('s')

        val relocation = ConfigurationLoader().load(config).relocations.first()
        assertEquals(temporary.resolve("archive"), relocation.archiveRoot)
        assertFalse(Files.exists(temporary.resolve("home/nested/cache")), "saving must not relocate")
    }

    @Test
    fun followsExecutionAcrossRelocationsWithoutOverridingManualInspectionBetweenActions() {
        val (plan, firstPlan, secondPlan) = twoRelocations(Path.of("/home/second"), Path.of("/local/second"))
        val follower = ProgressFollower()
        assertNull(follower.jump(ApplyModel.Confirmation(plan)))
        for (active in 0 until 3) {
            fun running(): ApplyModel = ApplyModel.Running.of(plan, steps(plan) { i ->
                if (i < active) ApplyModel.StepStatus.COMPLETED
                else if (i == active) ApplyModel.StepStatus.RUNNING else ApplyModel.StepStatus.PENDING
            })
            assertEquals(active, follower.jump(running()))
            // Until the running step changes, the user's own selection stands.
            assertNull(follower.jump(running()))
        }

        val result = ApplyModel.Result.of(plan, listOf(
            ApplyModel.Step(firstPlan, firstPlan.actions.first(), ApplyModel.StepStatus.COMPLETED, "completed"),
            ApplyModel.Step(firstPlan, firstPlan.actions.last(), ApplyModel.StepStatus.FAILED, "source changed"),
            ApplyModel.Step(secondPlan, secondPlan.actions.first(), ApplyModel.StepStatus.PENDING, "not run")),
            null, listOf(), true)
        assertEquals(1, follower.jump(result))
        assertNull(follower.jump(result))

        val completed = result.steps.map { step -> ApplyModel.Step(step.relocation, step.action,
            ApplyModel.StepStatus.COMPLETED, "completed") }
        assertEquals(2, follower.jump(ApplyModel.Result.of(plan, completed, null, listOf(), false)))
    }

    @Test
    fun followsTheFirstRunningStepWhenRelocationsRunConcurrently() {
        val (plan) = twoRelocations(Path.of("/opt/second"), Path.of("/srv/second"))
        val follower = ProgressFollower()
        val (pending, running, completed) =
            listOf(ApplyModel.StepStatus.PENDING, ApplyModel.StepStatus.RUNNING, ApplyModel.StepStatus.COMPLETED)
        fun model(vararg statuses: ApplyModel.StepStatus) = ApplyModel.Running.of(plan, steps(plan) { statuses[it] })

        assertEquals(0, follower.jump(model(running, pending, running)))
        // Repeated frames with two running steps keep following the first, rather than alternating.
        assertNull(follower.jump(model(running, pending, running)))
        assertEquals(1, follower.jump(model(completed, running, running)))
        assertEquals(2, follower.jump(model(completed, completed, running)))
    }

    @Test
    fun reviewSelectionMovesWithTheListAndKeepsActionsSelectable(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val names = listOf("one", "two")
        val relocations = names.joinToString(",\n") { name ->
            "{\"source-path\": \"${root.resolve("home/$name")}\", \"target-path\": \"${root.resolve("local/$name")}\"}"
        }
        Files.createDirectories(root.resolve("home"))
        val config = Files.writeString(root.resolve("config.json"),
            "{\"homelight\": {\"target-root\": \"${root.resolve("local")}\", \"relocations\": [\n$relocations\n]}}\n")
        val ui = HeadlessTui(HomeLightSession(config))
        ui.press('a')
        val steps = ApplyView.steps(ui.app.session.applyModel())
        assertEquals(REVIEW_LIST, ui.focused())
        assertEquals(0, ui.app.selectedIndex())
        // Each relocation's line rides on its first action, so every row the list selects is an action.
        for (i in 1 until steps.size) {
            ui.press(KeyCode.DOWN)
            assertEquals(i, ui.app.selectedIndex())
        }
        ui.press(KeyCode.DOWN)
        assertEquals(steps.size - 1, ui.app.selectedIndex())
        ui.press(KeyCode.HOME)
        assertEquals(0, ui.app.selectedIndex())
        ui.press(KeyCode.END)
        assertEquals(steps.size - 1, ui.app.selectedIndex())
        val screen = ui.screen(120, 30)
        assertTrue(screen.contains("❯ ○ Link source to target"), screen)
        // A path ends its row, shortened in the middle when it does not fit.
        for (name in names) assertTrue(Regex("home/$name +│").containsMatchIn(screen), screen)
    }

    @Test
    fun reviewsConfirmsAppliesAndReturnsToRefreshedStatus(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val source = root.resolve("home/cache")
        val target = root.resolve("local/cache")
        val config = Files.writeString(root.resolve("config.json"), ("""
                {"homelight": {
                  "target-root": "%s",
                  "relocations": [
                    {"source-path": "%s", "target-path": "%s"}
                  ]
                }}
                """.trimIndent() + "\n").format(root, source, target))
        val ui = HeadlessTui(HomeLightSession(config))

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
                {"homelight": {
                  "target-root": "%s",
                  "relocations": [
                    {"source-path": "%s", "target-path": "%s"}
                  ]
                }}
                """.trimIndent() + "\n").format(root, root.resolve("source"), root.resolve("target")))
        val ui = HeadlessTui(HomeLightSession(config))
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

        // Move down
        ui.press(KeyCode.DOWN)
        assertEquals(1, ui.app.selectedIndex())

        // Move down with DOWN key
        ui.press(KeyCode.DOWN)
        assertEquals(2, ui.app.selectedIndex())

        // Cannot move past the end
        ui.press(KeyCode.DOWN)
        assertEquals(2, ui.app.selectedIndex())

        // Move up
        ui.press(KeyCode.UP)
        assertEquals(1, ui.app.selectedIndex())

        // Jump to end
        ui.press(KeyCode.END)
        assertEquals(2, ui.app.selectedIndex())

        // Jump to start
        ui.press(KeyCode.HOME)
        assertEquals(0, ui.app.selectedIndex())

        // TamboUI's list pages too
        ui.press(KeyCode.PAGE_DOWN)
        assertEquals(2, ui.app.selectedIndex())
        ui.press(KeyCode.PAGE_UP)
        assertEquals(0, ui.app.selectedIndex())

        // Cannot move before 0
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

        // When there is an unresolved item, showInSync defaults to false
        assertFalse(ui.app.showInSync)
        assertEquals(0, ui.app.selectedIndex())
        // The count is in the list's title, where the selection cannot land
        val screen = ui.screen(80, 24)
        assertTrue(screen.contains("┌Relocations · c: show 2 in sync"), screen)

        // Moving down stays at 0 because only 1 active item is visible
        ui.press(KeyCode.DOWN)
        assertEquals(0, ui.app.selectedIndex())

        // Press 'c' to toggle showInSync to true
        ui.press('c')
        assertTrue(ui.app.showInSync)
        assertTrue(ui.screen(80, 24).contains("┌Relocations · c: hide 2 in sync"))

        // Now all 3 items are navigable
        ui.press(KeyCode.DOWN)
        assertEquals(1, ui.app.selectedIndex())
        ui.press(KeyCode.DOWN)
        assertEquals(2, ui.app.selectedIndex())

        // Press SPACE to toggle showInSync back to false
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
    fun switchesScreensViaKeys() {
        val ui = HeadlessTui(HomeLightSession(Path.of("/nonexistent/config.json")))
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

        val config = Files.createTempFile(root, "homelight", ".json")
        Files.writeString(config, ("""
                {"homelight": {
                  "target-root": "%s",
                  "relocations": [
                    {"source-path": "%s", "target-path": "%s"}
                  ]
                }}
                """.trimIndent() + "\n").format(root, source, target))

        val ui = HeadlessTui(HomeLightSession(config))
        assertEquals(Screen.WORKSPACE, ui.app.activeScreen)
        assertTrue(ui.app.session.hasConflicts())
        assertEquals(WORKSPACE_LIST, ui.focused())

        // Press TAB to focus detail pane
        ui.press(KeyCode.TAB)
        assertEquals(WORKSPACE_DETAILS, ui.focused())
        assertEquals(0, ui.app.detailSelectedIndex)

        ui.press('2')
        assertEquals(Screen.WORKSPACE, ui.app.activeScreen)
        assertEquals(WORKSPACE_DETAILS, ui.focused())

        // Press SPACE in detail pane to resolve highlighted decision
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

        val config = Files.createTempFile(root, "homelight", ".json")
        Files.writeString(config, ("""
                {"homelight": {
                  "target-root": "%s",
                  "relocations": [
                    {"source-path": "%s", "target-path": "%s"},
                    {"source-path": "%s", "target-path": "%s"}
                  ]
                }}
                """.trimIndent() + "\n").format(root, source1, target1, source2, target2))

        val ui = HeadlessTui(HomeLightSession(config))
        assertEquals(WORKSPACE_LIST, ui.focused())
        assertEquals(0, ui.app.selectedIndex())

        // Right moves focus to the DETAIL pane
        ui.press(KeyCode.RIGHT)
        assertEquals(WORKSPACE_DETAILS, ui.focused())
        assertEquals(0, ui.app.detailSelectedIndex)

        // Down moves through the resolution options
        ui.press(KeyCode.DOWN)
        assertEquals(1, ui.app.detailSelectedIndex)

        // Down again
        ui.press(KeyCode.DOWN)
        assertEquals(2, ui.app.detailSelectedIndex)

        // Up moves back
        ui.press(KeyCode.UP)
        assertEquals(1, ui.app.detailSelectedIndex)

        // Left returns to the MASTER pane
        ui.press(KeyCode.LEFT)
        assertEquals(WORKSPACE_LIST, ui.focused())

        // In MASTER pane, move down to second relocation
        ui.press(KeyCode.DOWN)
        assertEquals(1, ui.app.selectedIndex())

        // Focus DETAIL pane with RIGHT arrow
        ui.press(KeyCode.RIGHT)
        assertEquals(WORKSPACE_DETAILS, ui.focused())

        // Select resolution (Enter) on second item
        ui.press(KeyCode.ENTER)
        assertEquals(WORKSPACE_DETAILS, ui.focused())

        // Return to master with LEFT arrow
        ui.press(KeyCode.LEFT)
        assertEquals(WORKSPACE_LIST, ui.focused())

        // Tab moves focus to the details pane
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

        // Read-only details remain accessible without choices.
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

        val config = Files.createTempFile(root, "homelight", ".json")
        Files.writeString(config, ("""
                {"homelight": {
                  "target-root": "%s",
                  "relocations": [
                    {"source-path": "%s", "target-path": "%s"},
                    {"source-path": "%s", "target-path": "%s"}
                  ]
                }}
                """.trimIndent() + "\n").format(root, source1, target1, source2, target2))

        val ui = HeadlessTui(HomeLightSession(config))
        assertEquals(0, ui.app.selectedIndex())
        assertEquals(WORKSPACE_LIST, ui.focused())

        // Focus the detail pane with Right
        ui.press(KeyCode.RIGHT)
        assertEquals(WORKSPACE_DETAILS, ui.focused())
        assertEquals(0, ui.app.detailSelectedIndex) // 0 is ADOPT_AND_DISCARD_SOURCE

        // Move past ADOPT_AND_ARCHIVE_SOURCE to choice 2: LEAVE_UNCHANGED (Unchanged)
        ui.press(KeyCode.DOWN)
        ui.press(KeyCode.DOWN)
        assertEquals(2, ui.app.detailSelectedIndex)

        // Select it (Space)
        ui.press(' ')
        assertEquals(WORKSPACE_DETAILS, ui.focused())

        // The item must stay selected and visible as SKIPPED
        assertInstanceOf(ConfigurationEvaluation.Loaded::class.java, ui.app.session.evaluation()).let { configured ->
            val visible = WorkspaceView.visibleItems(configured, ui.app.showInSync)
            assertTrue(visible.size >= 2)
            val currentItem = visible[ui.app.selectedIndex()]
            assertEquals(source1, currentItem.relocation.sourcePath)
            assertEquals(PlanBadge.SKIPPED, currentItem.badge())
            assertEquals(2, ui.app.detailSelectedIndex)
        }

        // Return to the master list with Left
        ui.press(KeyCode.LEFT)
        assertEquals(WORKSPACE_LIST, ui.focused())
        assertEquals(1, ui.app.selectedIndex())

        // Move up to unresolved conflict item (source2 is at index 0)
        ui.press(KeyCode.UP)
        assertEquals(0, ui.app.selectedIndex())

        // Move into the detail pane with Right
        ui.press(KeyCode.RIGHT)
        assertEquals(WORKSPACE_DETAILS, ui.focused())

        // Select choice 3: DISCARD_BOTH (index 3)
        ui.press(KeyCode.DOWN)
        ui.press(KeyCode.DOWN)
        ui.press(KeyCode.DOWN)
        assertEquals(3, ui.app.detailSelectedIndex)
        ui.press(KeyCode.ENTER)

        // The second item must stay selected and have DISCARD badge
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

        fun steps(plan: ReconciliationPlan, status: (Int) -> ApplyModel.StepStatus): List<ApplyModel.Step> =
            plan.relocations.flatMap { relocation -> relocation.actions.map { relocation to it } }
                .mapIndexed { i, (relocation, action) -> ApplyModel.Step(relocation, action, status(i), status(i).toString()) }

        /** A real session whose `inSync` sources already link to their targets and whose `conflicts` have both directories. */
        fun session(temporary: Path, inSync: List<String> = listOf(), conflicts: List<String> = listOf()): HomeLightSession {
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
                "{\"homelight\": {\"target-root\": \"${root.resolve("local")}\", \"relocations\": [\n$relocations\n]}}\n")
            return HomeLightSession(config)
        }

        fun type(ui: HeadlessTui, value: String) {
            for (character in value.toCharArray()) ui.press(character)
        }

        fun setupWithEmptyRow(config: Path, temporary: Path): HeadlessTui {
            val ui = HeadlessTui(HomeLightSession(config))
            ui.press('i')
            ui.press('\u0015')
            type(ui, temporary.resolve("home").toString())
            ui.press(KeyCode.DOWN)
            type(ui, temporary.resolve("local").toString())
            ui.press(KeyCode.ENTER)
            ui.press('a')
            return ui
        }

        fun assertInvalidAndUnpublished(ui: HeadlessTui, config: Path, message: String) {
            ui.press('v')
            val validation = ui.screen(120, 30)
            assertTrue(validation.contains(message), validation)
            ui.press('s')
            val save = ui.screen(120, 30)
            assertTrue(save.contains(message), save)
            assertFalse(Files.exists(config))
        }
    }
}
