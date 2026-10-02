package io.github.bigswlittlesw.homelight.tui

import dev.tamboui.toolkit.event.EventResult
import dev.tamboui.tui.event.KeyCode
import dev.tamboui.tui.event.KeyEvent
import io.github.bigswlittlesw.homelight.application.ApplyModel
import io.github.bigswlittlesw.homelight.application.HomeLightSession
import io.github.bigswlittlesw.homelight.application.PlanBadge
import io.github.bigswlittlesw.homelight.application.PlanModel
import io.github.bigswlittlesw.homelight.application.PlanRelocationItem
import io.github.bigswlittlesw.homelight.application.PlanSummary
import io.github.bigswlittlesw.homelight.config.ConfigurationLoader
import io.github.bigswlittlesw.homelight.config.Relocation
import io.github.bigswlittlesw.homelight.domain.RelocationSourceState
import io.github.bigswlittlesw.homelight.fs.PathObservation
import io.github.bigswlittlesw.homelight.fs.PathState
import io.github.bigswlittlesw.homelight.fs.SymlinkTargetAvailability
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationConflict
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlan
import io.github.bigswlittlesw.homelight.reconcile.RelocationOutcome
import io.github.bigswlittlesw.homelight.reconcile.RelocationPlan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

class HomeLightAppTest {

    @Test
    fun createsRootRelativeRowsWithDefaultPolicies(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val config = root.resolve("new/config.json")
        val app = HomeLightApp(HomeLightSession(config))
        app.handleKeyEvent(KeyEvent.ofChar('i', KEY_BINDINGS))
        app.handleKeyEvent(KeyEvent.ofChar('\u0015', KEY_BINDINGS))
        type(app, root.resolve("home").toString())
        app.handleKeyEvent(KeyEvent.ofKey(KeyCode.TAB, KEY_BINDINGS))
        app.handleKeyEvent(KeyEvent.ofChar('\u0015', KEY_BINDINGS))
        type(app, root.resolve("local").toString())
        app.handleKeyEvent(KeyEvent.ofKey(KeyCode.ENTER, KEY_BINDINGS))
        app.handleKeyEvent(KeyEvent.ofChar('a', KEY_BINDINGS))
        type(app, ".cache/tool")
        app.handleKeyEvent(KeyEvent.ofKey(KeyCode.ESCAPE, KEY_BINDINGS))
        app.handleKeyEvent(KeyEvent.ofChar('v', KEY_BINDINGS))
        val validation = WorkspaceViewTest.render(app.render(), 120, 30)
        assertTrue(validation.contains("Validation: valid"), validation)
        app.handleKeyEvent(KeyEvent.ofChar('s', KEY_BINDINGS))

        val relocation = ConfigurationLoader().load(config).relocations.first()
        assertEquals(root.resolve("home/.cache/tool"), relocation.sourcePath)
        assertEquals(root.resolve("local/.cache/tool"), relocation.targetPath)
        assertNull(relocation.whenSourceAndTargetDirectoriesExist)
        assertFalse(Files.exists(root.resolve("home/.cache/tool")), "saving must not relocate")
    }

    @Test
    fun setupTableKeepsRowsWhileLocationsAreEditedAndConfirmsDraftDiscard(@TempDir temporary: Path) {
        val app = HomeLightApp(HomeLightSession(temporary.resolve("config.json")))
        app.handleKeyEvent(KeyEvent.ofChar('i', KEY_BINDINGS))
        app.handleKeyEvent(KeyEvent.ofKey(KeyCode.ENTER, KEY_BINDINGS))
        app.handleKeyEvent(KeyEvent.ofChar('a', KEY_BINDINGS))
        type(app, ".cache/tool")
        app.handleKeyEvent(KeyEvent.ofKey(KeyCode.ESCAPE, KEY_BINDINGS))

        var table = WorkspaceViewTest.render(app.render(), 80, 24)
        assertTrue(table.contains("Source (relative)"), table)
        assertTrue(table.contains(".cache/tool"), table)

        app.handleKeyEvent(KeyEvent.ofChar('d', KEY_BINDINGS))
        table = WorkspaceViewTest.render(app.render(), 80, 24)
        assertTrue(table.contains("No relocations yet"), table)
        app.handleKeyEvent(KeyEvent.ofChar('a', KEY_BINDINGS))
        type(app, ".cache/tool")
        app.handleKeyEvent(KeyEvent.ofKey(KeyCode.ESCAPE, KEY_BINDINGS))

        app.handleKeyEvent(KeyEvent.ofChar('e', KEY_BINDINGS))
        app.handleKeyEvent(KeyEvent.ofKey(KeyCode.TAB, KEY_BINDINGS))
        type(app, "/target")
        app.handleKeyEvent(KeyEvent.ofKey(KeyCode.ENTER, KEY_BINDINGS))
        table = WorkspaceViewTest.render(app.render(), 80, 24)
        assertTrue(table.contains(".cache/tool"), table)
        assertTrue(table.contains("Validation: not run"), table)

        app.handleKeyEvent(KeyEvent.ofChar('q', KEY_BINDINGS))
        assertTrue(WorkspaceViewTest.render(app.render(), 80, 24).contains("Discard setup draft?"))
        app.handleKeyEvent(KeyEvent.ofKey(KeyCode.ESCAPE, KEY_BINDINGS))
        table = WorkspaceViewTest.render(app.render(), 80, 24)
        assertTrue(table.contains(".cache/tool"), table)
        assertFalse(Files.exists(temporary.resolve("config.json")))

        app.handleKeyEvent(KeyEvent.ofChar('q', KEY_BINDINGS))
        app.handleKeyEvent(KeyEvent.ofKey(KeyCode.ENTER, KEY_BINDINGS))
        app.handleKeyEvent(KeyEvent.ofChar('i', KEY_BINDINGS))
        val fresh = WorkspaceViewTest.render(app.render(), 80, 24)
        assertTrue(fresh.contains("Target root: "), fresh)
        assertTrue(fresh.contains("Validation: not run"), fresh)
        assertFalse(fresh.contains(".cache/tool"), fresh)
    }

    @Test
    fun rejectsUnsafeRelativeRowsUntilCorrected(@TempDir temporary: Path) {
        for (invalid in listOf("", ".", "..")) {
            val config = temporary.resolve("config-" + (if (invalid.isEmpty()) "blank" else invalid.replace('.', 'd')) + ".json")
            val app = setupWithEmptyRow(config, temporary)
            type(app, invalid)
            app.handleKeyEvent(KeyEvent.ofKey(KeyCode.ESCAPE, KEY_BINDINGS))

            assertInvalidAndUnpublished(app, config, if (invalid.isEmpty()) "cannot be blank" else "nested below their root")
            app.handleKeyEvent(KeyEvent.ofKey(KeyCode.ENTER, KEY_BINDINGS))
            repeat(invalid.length) { app.handleKeyEvent(KeyEvent.ofKey(KeyCode.BACKSPACE, KEY_BINDINGS)) }
            type(app, "nested/cache")
            app.handleKeyEvent(KeyEvent.ofKey(KeyCode.ESCAPE, KEY_BINDINGS))
            app.handleKeyEvent(KeyEvent.ofChar('s', KEY_BINDINGS))
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
            val app = setupWithEmptyRow(config, temporary)
            type(app, "valid/source")
            app.handleKeyEvent(KeyEvent.ofKey(KeyCode.TAB, KEY_BINDINGS))
            repeat("valid/source".length) { app.handleKeyEvent(KeyEvent.ofKey(KeyCode.BACKSPACE, KEY_BINDINGS)) }
            type(app, invalid)
            app.handleKeyEvent(KeyEvent.ofKey(KeyCode.ESCAPE, KEY_BINDINGS))
            assertInvalidAndUnpublished(app, config, if (invalid.isEmpty()) "cannot be blank" else "nested below their root")
            app.handleKeyEvent(KeyEvent.ofKey(KeyCode.ENTER, KEY_BINDINGS))
            app.handleKeyEvent(KeyEvent.ofKey(KeyCode.TAB, KEY_BINDINGS))
            repeat(invalid.length) { app.handleKeyEvent(KeyEvent.ofKey(KeyCode.BACKSPACE, KEY_BINDINGS)) }
            type(app, "valid/target")
            app.handleKeyEvent(KeyEvent.ofKey(KeyCode.ESCAPE, KEY_BINDINGS))
            app.handleKeyEvent(KeyEvent.ofChar('s', KEY_BINDINGS))
            val relocation = ConfigurationLoader().load(config).relocations.first()
            assertEquals(temporary.resolve("home/valid/source"), relocation.sourcePath)
            assertEquals(temporary.resolve("local/valid/target"), relocation.targetPath)
        }
    }

    @Test
    fun rejectsRelativeArchiveRootUntilCorrected(@TempDir temporary: Path) {
        val config = temporary.resolve("archive.json")
        val app = setupWithEmptyRow(config, temporary)
        type(app, "nested/cache")
        repeat(5) { app.handleKeyEvent(KeyEvent.ofKey(KeyCode.DOWN, KEY_BINDINGS)) }
        type(app, "archive")
        app.handleKeyEvent(KeyEvent.ofKey(KeyCode.ESCAPE, KEY_BINDINGS))

        assertInvalidAndUnpublished(app, config, "Archive root must be an absolute path")
        app.handleKeyEvent(KeyEvent.ofKey(KeyCode.ENTER, KEY_BINDINGS))
        repeat(5) { app.handleKeyEvent(KeyEvent.ofKey(KeyCode.DOWN, KEY_BINDINGS)) }
        repeat("archive".length) { app.handleKeyEvent(KeyEvent.ofKey(KeyCode.BACKSPACE, KEY_BINDINGS)) }
        type(app, temporary.resolve("archive").toString())
        app.handleKeyEvent(KeyEvent.ofKey(KeyCode.ESCAPE, KEY_BINDINGS))
        app.handleKeyEvent(KeyEvent.ofChar('s', KEY_BINDINGS))

        val relocation = ConfigurationLoader().load(config).relocations.first()
        assertEquals(temporary.resolve("archive"), relocation.archiveRoot)
        assertFalse(Files.exists(temporary.resolve("home/nested/cache")), "saving must not relocate")
    }

    @Test
    fun followsExecutionAcrossRelocationsWithoutOverridingManualInspectionBetweenActions() {
        val first = Relocation(Path.of("/home/first"), Path.of("/local/first"))
        val second = Relocation(Path.of("/home/second"), Path.of("/local/second"))
        val firstPlan = RelocationPlan(first, RelocationOutcome.CONVERGED,
            listOf(ReconciliationAction.CreateDirectory(first.targetPath),
                ReconciliationAction.CreateSymlink(first.sourcePath, first.targetPath)), listOf())
        val secondPlan = RelocationPlan(second, RelocationOutcome.CONVERGED,
            listOf(ReconciliationAction.CreateDirectory(second.targetPath)), listOf())
        val plan = ReconciliationPlan(listOf(firstPlan, secondPlan), listOf())
        val progress = AtomicReference<ApplyModel>(ApplyModel.Confirmation(plan))
        val session = object : HomeLightSession(Path.of("/nonexistent/config.json")) {
            override fun applyModel(): ApplyModel {
                return progress.get()
            }
        }
        val app = HomeLightApp(session)
        app.switchScreen(Screen.APPLY)
        app.render()

        for (active in 0 until 3) {
            val steps = mutableListOf<ApplyModel.Step>()
            for (relocation in plan.relocations) {
                for (action in relocation.actions) {
                    val status = if (steps.size < active) ApplyModel.StepStatus.COMPLETED
                    else if (steps.size == active) ApplyModel.StepStatus.RUNNING else ApplyModel.StepStatus.PENDING
                    steps.add(ApplyModel.Step(relocation, action, status, status.toString()))
                }
            }
            progress.set(ApplyModel.Running.of(plan, steps))
            app.render()
            assertEquals(active, app.selectedIndex())
            app.handleKeyEvent(KeyEvent.ofChar(if (active == 0) 'j' else 'k', KEY_BINDINGS))
            val inspected: Int = app.selectedIndex()
            app.render()
            assertEquals(inspected, app.selectedIndex())
        }

        val result = ApplyModel.Result.of(plan, listOf(
            ApplyModel.Step(firstPlan, firstPlan.actions.first(), ApplyModel.StepStatus.COMPLETED, "completed"),
            ApplyModel.Step(firstPlan, firstPlan.actions.last(), ApplyModel.StepStatus.FAILED, "source changed"),
            ApplyModel.Step(secondPlan, secondPlan.actions.first(), ApplyModel.StepStatus.PENDING, "not run")),
            null, listOf(), true)
        progress.set(result)
        app.render()
        assertEquals(1, app.selectedIndex())
        app.handleKeyEvent(KeyEvent.ofChar('k', KEY_BINDINGS))
        app.render()
        assertEquals(0, app.selectedIndex())

        val completed = result.steps.map { step -> ApplyModel.Step(step.relocation, step.action,
            ApplyModel.StepStatus.COMPLETED, "completed") }
        progress.set(ApplyModel.Result.of(plan, completed, null, listOf(), false))
        app.render()
        assertEquals(2, app.selectedIndex())
    }

    @Test
    fun followsTheFirstRunningStepWhenRelocationsRunConcurrently() {
        val first = Relocation(Path.of("/home/first"), Path.of("/local/first"))
        val second = Relocation(Path.of("/opt/second"), Path.of("/srv/second"))
        val firstPlan = RelocationPlan(first, RelocationOutcome.CONVERGED,
            listOf(ReconciliationAction.CreateDirectory(first.targetPath),
                ReconciliationAction.CreateSymlink(first.sourcePath, first.targetPath)), listOf())
        val secondPlan = RelocationPlan(second, RelocationOutcome.CONVERGED,
            listOf(ReconciliationAction.CreateDirectory(second.targetPath)), listOf())
        val plan = ReconciliationPlan(listOf(firstPlan, secondPlan), listOf())
        val progress = AtomicReference<ApplyModel>(ApplyModel.Confirmation(plan))
        val session = object : HomeLightSession(Path.of("/nonexistent/config.json")) {
            override fun applyModel(): ApplyModel = progress.get()
        }
        val app = HomeLightApp(session)
        app.switchScreen(Screen.APPLY)
        app.render()
        fun running(vararg statuses: ApplyModel.StepStatus) {
            val steps = plan.relocations.flatMap { relocation -> relocation.actions.map { relocation to it } }
                .zip(statuses) { (relocation, action), status -> ApplyModel.Step(relocation, action, status, status.toString()) }
            progress.set(ApplyModel.Running.of(plan, steps))
            app.render()
        }
        val (pending, runningStep, completed) =
            listOf(ApplyModel.StepStatus.PENDING, ApplyModel.StepStatus.RUNNING, ApplyModel.StepStatus.COMPLETED)

        running(runningStep, pending, runningStep)
        assertEquals(0, app.selectedIndex())
        // Repeated frames with two running steps keep following the first, rather than alternating.
        app.render()
        assertEquals(0, app.selectedIndex())
        app.handleKeyEvent(KeyEvent.ofChar('j'))
        app.handleKeyEvent(KeyEvent.ofChar('j'))
        app.render()
        assertEquals(2, app.selectedIndex())

        running(completed, runningStep, runningStep)
        assertEquals(1, app.selectedIndex())
        running(completed, completed, runningStep)
        assertEquals(2, app.selectedIndex())
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
        val app = HomeLightApp(HomeLightSession(config))

        app.handleKeyEvent(KeyEvent.ofChar('2', KEY_BINDINGS))
        assertEquals(Screen.APPLY, app.activeScreen)
        app.handleKeyEvent(KeyEvent.ofKey(KeyCode.ENTER, KEY_BINDINGS))
        assertTrue(app.session.applyModel() is ApplyModel.Confirmation)
        assertFalse(Files.exists(source))
        app.handleKeyEvent(KeyEvent.ofChar('n', KEY_BINDINGS))
        assertEquals(Screen.WORKSPACE, app.activeScreen)
        app.handleKeyEvent(KeyEvent.ofChar('a', KEY_BINDINGS))
        app.handleKeyEvent(KeyEvent.ofChar('y', KEY_BINDINGS))
        app.session.awaitExecution()
        assertTrue(Files.isSymbolicLink(source))
        assertTrue(app.session.applyModel() is ApplyModel.Result)
        app.handleKeyEvent(KeyEvent.ofKey(KeyCode.ENTER, KEY_BINDINGS))
        assertEquals(Screen.WORKSPACE, app.activeScreen)
        assertEquals(1, (app.planModel() as PlanModel.Configured).summary.inSync)
        app.handleKeyEvent(KeyEvent.ofChar('2', KEY_BINDINGS))
        assertTrue(app.session.applyModel() is ApplyModel.Result)
        app.handleKeyEvent(KeyEvent.ofChar('r', KEY_BINDINGS))
        assertTrue(app.session.isPlanReady())
        assertFalse((app.planModel() as PlanModel.Configured).plan.actions().any(ReconciliationAction::mutatesFilesystem))
        app.handleKeyEvent(KeyEvent.ofChar('a', KEY_BINDINGS))
        val unchanged = app.session.applyModel()
        app.handleKeyEvent(KeyEvent.ofChar('y', KEY_BINDINGS))
        assertSame(unchanged, app.session.applyModel())
        assertFalse(app.session.isApplying())
        app.handleKeyEvent(KeyEvent.ofKey(KeyCode.ENTER, KEY_BINDINGS))
        assertEquals(Screen.WORKSPACE, app.activeScreen)
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
        val app = HomeLightApp(HomeLightSession(config))
        app.handleKeyEvent(KeyEvent.ofChar('a', KEY_BINDINGS))
        val tasks = mutableListOf<Runnable>()
        app.session.confirmApply(Executor { tasks.add(it) })
        val running = app.session.applyModel()
        for (key in charArrayOf('r', 'y', 'a', '1', '2', 'q')) {
            assertEquals(EventResult.HANDLED, app.handleKeyEvent(KeyEvent.ofChar(key, KEY_BINDINGS)))
            assertEquals(Screen.APPLY, app.activeScreen)
            assertSame(running, app.session.applyModel())
        }
        tasks.first().run()
        assertTrue(Files.isSymbolicLink(root.resolve("source")))
    }

    @Test
    fun handlesNavigationKeys() {
        val rel1 = Relocation(Path.of("/source1"), Path.of("/target1"))
        val rel2 = Relocation(Path.of("/source2"), Path.of("/target2"))
        val rel3 = Relocation(Path.of("/source3"), Path.of("/target3"))

        val plan1 = RelocationPlan(rel1, RelocationOutcome.CONVERGED, listOf(ReconciliationAction.NoOp(rel1.sourcePath)), listOf())
        val plan2 = RelocationPlan(rel2, RelocationOutcome.CONVERGED, listOf(ReconciliationAction.NoOp(rel2.sourcePath)), listOf())
        val plan3 = RelocationPlan(rel3, RelocationOutcome.CONVERGED, listOf(ReconciliationAction.NoOp(rel3.sourcePath)), listOf())

        val obs = PathObservation(PathState.DIRECTORY, null, SymlinkTargetAvailability.NOT_A_SYMLINK, false)
        val item1 = PlanRelocationItem(rel1, obs, obs, plan1, RelocationSourceState.DIRECTORY, listOf())
        val item2 = PlanRelocationItem(rel2, obs, obs, plan2, RelocationSourceState.DIRECTORY, listOf())
        val item3 = PlanRelocationItem(rel3, obs, obs, plan3, RelocationSourceState.DIRECTORY, listOf())

        val items = listOf(item1, item2, item3)
        val summary = PlanSummary.from(items)
        val configured = PlanModel.Configured.of(Path.of("/config.json"), Path.of("/target"),
            ReconciliationPlan(listOf(plan1, plan2, plan3), listOf()), items, summary)

        val session = object : HomeLightSession(Path.of("/nonexistent/config.json")) {
            override fun planModel(): PlanModel {
                return configured
            }
        }

        val app = HomeLightApp(session)

        assertEquals(0, app.selectedIndex())

        // Move down with 'j'
        app.handleKeyEvent(KeyEvent.ofChar('j', KEY_BINDINGS))
        assertEquals(1, app.selectedIndex())

        // Move down with DOWN key
        app.handleKeyEvent(KeyEvent.ofKey(KeyCode.DOWN, KEY_BINDINGS))
        assertEquals(2, app.selectedIndex())

        // Cannot move past the end
        app.handleKeyEvent(KeyEvent.ofKey(KeyCode.DOWN, KEY_BINDINGS))
        assertEquals(2, app.selectedIndex())

        // Move up with 'k'
        app.handleKeyEvent(KeyEvent.ofChar('k', KEY_BINDINGS))
        assertEquals(1, app.selectedIndex())

        // Jump to end with 'G'
        app.handleKeyEvent(KeyEvent.ofChar('G', KEY_BINDINGS))
        assertEquals(2, app.selectedIndex())

        // Jump to start with 'g'
        app.handleKeyEvent(KeyEvent.ofChar('g', KEY_BINDINGS))
        assertEquals(0, app.selectedIndex())

        // Cannot move before 0
        app.handleKeyEvent(KeyEvent.ofKey(KeyCode.UP, KEY_BINDINGS))
        assertEquals(0, app.selectedIndex())
    }

    @Test
    fun handlesConvergedFilterAndToggle() {
        val rel1 = Relocation(Path.of("/source1"), Path.of("/target1"))
        val rel2 = Relocation(Path.of("/source2"), Path.of("/target2"))
        val rel3 = Relocation(Path.of("/source3"), Path.of("/target3"))

        // plan1 is CONFLICT, plan2 and plan3 are CONVERGED
        val conflict = ReconciliationConflict(
            rel1.sourcePath, "conflict", listOf(ReconciliationConflict.Resolution.RESOLVE_EXISTING_CONTENT))
        val plan1 = RelocationPlan(rel1, RelocationOutcome.UNRESOLVED, listOf(), listOf(), conflict)
        val plan2 = RelocationPlan(rel2, RelocationOutcome.CONVERGED, listOf(ReconciliationAction.NoOp(rel2.sourcePath)), listOf())
        val plan3 = RelocationPlan(rel3, RelocationOutcome.CONVERGED, listOf(ReconciliationAction.NoOp(rel3.sourcePath)), listOf())

        val obs = PathObservation(PathState.DIRECTORY, null, SymlinkTargetAvailability.NOT_A_SYMLINK, false)
        val item1 = PlanRelocationItem(rel1, obs, obs, plan1, RelocationSourceState.DIRECTORY, listOf())
        val item2 = PlanRelocationItem(rel2, obs, obs, plan2, RelocationSourceState.DIRECTORY, listOf())
        val item3 = PlanRelocationItem(rel3, obs, obs, plan3, RelocationSourceState.DIRECTORY, listOf())

        val items = listOf(item1, item2, item3)
        val summary = PlanSummary.from(items)
        val configured = PlanModel.Configured.of(Path.of("/config.json"), Path.of("/target"),
            ReconciliationPlan(listOf(plan1, plan2, plan3), listOf()), items, summary)

        val session = object : HomeLightSession(Path.of("/nonexistent/config.json")) {
            override fun planModel(): PlanModel {
                return configured
            }
        }

        val app = HomeLightApp(session)

        // When there is an unresolved item, showInSync defaults to false
        assertFalse(app.showInSync)
        assertEquals(0, app.selectedIndex())

        // Moving down stays at 0 because only 1 active item is visible
        app.handleKeyEvent(KeyEvent.ofChar('j', KEY_BINDINGS))
        assertEquals(0, app.selectedIndex())

        // Press 'c' to toggle showInSync to true
        app.handleKeyEvent(KeyEvent.ofChar('c', KEY_BINDINGS))
        assertTrue(app.showInSync)

        // Now all 3 items are navigable
        app.handleKeyEvent(KeyEvent.ofChar('j', KEY_BINDINGS))
        assertEquals(1, app.selectedIndex())
        app.handleKeyEvent(KeyEvent.ofChar('j', KEY_BINDINGS))
        assertEquals(2, app.selectedIndex())

        // Press SPACE to toggle showInSync back to false
        app.handleKeyEvent(KeyEvent.ofChar('c', KEY_BINDINGS))
        assertFalse(app.showInSync)
        assertEquals(0, app.selectedIndex())
    }

    @Test
    fun allInSyncDefaultsToShowInSync() {
        val rel1 = Relocation(Path.of("/source1"), Path.of("/target1"))
        val plan1 = RelocationPlan(rel1, RelocationOutcome.CONVERGED, listOf(ReconciliationAction.NoOp(rel1.sourcePath)), listOf())
        val obs = PathObservation(PathState.DIRECTORY, null, SymlinkTargetAvailability.NOT_A_SYMLINK, false)
        val item1 = PlanRelocationItem(rel1, obs, obs, plan1, RelocationSourceState.DIRECTORY, listOf())

        val items = listOf(item1)
        val summary = PlanSummary.from(items)
        val configured = PlanModel.Configured.of(Path.of("/config.json"), Path.of("/target"),
            ReconciliationPlan(listOf(plan1), listOf()), items, summary)

        val session = object : HomeLightSession(Path.of("/nonexistent/config.json")) {
            override fun planModel(): PlanModel {
                return configured
            }
        }

        val app = HomeLightApp(session)
        assertTrue(app.showInSync)
        assertEquals(0, app.selectedIndex())
    }

    @Test
    fun switchesScreensViaKeys() {
        val app = HomeLightApp(HomeLightSession(Path.of("/nonexistent/config.json")))
        assertEquals(Screen.WORKSPACE, app.activeScreen)

        app.handleKeyEvent(KeyEvent.ofChar('2', KEY_BINDINGS))
        assertEquals(Screen.WORKSPACE, app.activeScreen)

        app.handleKeyEvent(KeyEvent.ofChar('2', KEY_BINDINGS))
        assertEquals(Screen.WORKSPACE, app.activeScreen)

        app.handleKeyEvent(KeyEvent.ofChar('1', KEY_BINDINGS))
        assertEquals(Screen.WORKSPACE, app.activeScreen)

        app.handleKeyEvent(KeyEvent.ofChar('p', KEY_BINDINGS))
        assertEquals(Screen.WORKSPACE, app.activeScreen)

        app.handleKeyEvent(KeyEvent.ofChar('s', KEY_BINDINGS))
        assertEquals(Screen.WORKSPACE, app.activeScreen)
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

        val app = HomeLightApp(HomeLightSession(config))
        assertEquals(Screen.WORKSPACE, app.activeScreen)
        assertTrue(app.session.hasConflicts())
        assertEquals(PaneFocus.MASTER, app.paneFocus())

        // Press TAB to focus detail pane
        app.handleKeyEvent(KeyEvent.ofKey(KeyCode.TAB, KEY_BINDINGS))
        assertEquals(PaneFocus.DETAIL, app.paneFocus())
        assertEquals(0, app.detailSelectedIndex)

        app.handleKeyEvent(KeyEvent.ofChar('2', KEY_BINDINGS))
        assertEquals(Screen.WORKSPACE, app.activeScreen)
        assertEquals(PaneFocus.DETAIL, app.paneFocus())

        // Press SPACE in detail pane to resolve highlighted decision
        app.handleKeyEvent(KeyEvent.ofChar(' ', KEY_BINDINGS))
        assertFalse(app.session.hasConflicts())
        assertTrue(app.session.isPlanReady())

        app.handleKeyEvent(KeyEvent.ofChar('2', KEY_BINDINGS))
        assertEquals(Screen.APPLY, app.activeScreen)
        assertEquals(PaneFocus.MASTER, app.paneFocus())
        assertTrue(app.session.applyModel() is ApplyModel.Confirmation)
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

        val app = HomeLightApp(HomeLightSession(config))
        assertEquals(PaneFocus.MASTER, app.paneFocus())
        assertEquals(0, app.selectedIndex())

        // Press 'l' (vim right) to move focus to DETAIL pane
        app.handleKeyEvent(KeyEvent.ofChar('l', KEY_BINDINGS))
        assertEquals(PaneFocus.DETAIL, app.paneFocus())
        assertEquals(0, app.detailSelectedIndex)

        // Press 'j' to move down resolution options
        app.handleKeyEvent(KeyEvent.ofChar('j', KEY_BINDINGS))
        assertEquals(1, app.detailSelectedIndex)

        // Press 'j' again
        app.handleKeyEvent(KeyEvent.ofChar('j', KEY_BINDINGS))
        assertEquals(2, app.detailSelectedIndex)

        // Press 'k' to move back up
        app.handleKeyEvent(KeyEvent.ofChar('k', KEY_BINDINGS))
        assertEquals(1, app.detailSelectedIndex)

        // Press 'h' (vim left) to return to MASTER pane
        app.handleKeyEvent(KeyEvent.ofChar('h', KEY_BINDINGS))
        assertEquals(PaneFocus.MASTER, app.paneFocus())

        // In MASTER pane, move down to second relocation
        app.handleKeyEvent(KeyEvent.ofChar('j', KEY_BINDINGS))
        assertEquals(1, app.selectedIndex())

        // Focus DETAIL pane with RIGHT arrow
        app.handleKeyEvent(KeyEvent.ofKey(KeyCode.RIGHT, KEY_BINDINGS))
        assertEquals(PaneFocus.DETAIL, app.paneFocus())

        // Select resolution (Enter) on second item
        app.handleKeyEvent(KeyEvent.ofKey(KeyCode.ENTER, KEY_BINDINGS))
        assertEquals(PaneFocus.DETAIL, app.paneFocus())

        // Return to master with LEFT arrow
        app.handleKeyEvent(KeyEvent.ofKey(KeyCode.LEFT, KEY_BINDINGS))
        assertEquals(PaneFocus.MASTER, app.paneFocus())

        // Enter detail pane using raw TAB character '\t'
        app.handleKeyEvent(KeyEvent.ofChar('\t', KEY_BINDINGS))
        assertEquals(PaneFocus.DETAIL, app.paneFocus())

        // Return to master using TAB in detail pane
        app.handleKeyEvent(KeyEvent.ofChar('\t', KEY_BINDINGS))
        assertEquals(PaneFocus.MASTER, app.paneFocus())
    }

    @Test
    fun canInspectDetailsWhenNoResolutionsAvailable() {
        val rel1 = Relocation(Path.of("/source1"), Path.of("/target1"))
        val plan1 = RelocationPlan(rel1, RelocationOutcome.CONVERGED, listOf(ReconciliationAction.NoOp(rel1.sourcePath)), listOf())
        val obs = PathObservation(PathState.DIRECTORY, null, SymlinkTargetAvailability.NOT_A_SYMLINK, false)
        val item1 = PlanRelocationItem(rel1, obs, obs, plan1, RelocationSourceState.DIRECTORY, listOf())

        val items = listOf(item1)
        val summary = PlanSummary.from(items)
        val configured = PlanModel.Configured.of(Path.of("/config.json"), Path.of("/target"),
            ReconciliationPlan(listOf(plan1), listOf()), items, summary)

        val session = object : HomeLightSession(Path.of("/nonexistent/config.json")) {
            override fun planModel(): PlanModel {
                return configured
            }
        }

        val app = HomeLightApp(session)
        assertEquals(PaneFocus.MASTER, app.paneFocus())

        // Read-only details remain accessible without choices.
        app.handleKeyEvent(KeyEvent.ofKey(KeyCode.TAB, KEY_BINDINGS))
        assertEquals(PaneFocus.DETAIL, app.paneFocus())

        app.handleKeyEvent(KeyEvent.ofKey(KeyCode.RIGHT, KEY_BINDINGS))
        assertEquals(PaneFocus.DETAIL, app.paneFocus())
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

        val app = HomeLightApp(HomeLightSession(config))
        assertEquals(0, app.selectedIndex())
        assertEquals(PaneFocus.MASTER, app.paneFocus())

        // Focus detail pane with 'l'
        app.handleKeyEvent(KeyEvent.ofChar('l', KEY_BINDINGS))
        assertEquals(PaneFocus.DETAIL, app.paneFocus())
        assertEquals(0, app.detailSelectedIndex) // 0 is ADOPT_AND_DISCARD_SOURCE

        // Move past ADOPT_AND_ARCHIVE_SOURCE to choice 2: LEAVE_UNCHANGED (Unchanged)
        app.handleKeyEvent(KeyEvent.ofChar('j', KEY_BINDINGS))
        app.handleKeyEvent(KeyEvent.ofChar('j', KEY_BINDINGS))
        assertEquals(2, app.detailSelectedIndex)

        // Select it (Space)
        app.handleKeyEvent(KeyEvent.ofChar(' ', KEY_BINDINGS))
        assertEquals(PaneFocus.DETAIL, app.paneFocus())

        // The item must stay selected and visible as SKIPPED
        (app.planModel() as? PlanModel.Configured)?.let { configured ->
            val visible = WorkspaceView.visibleItems(configured, app.showInSync)
            assertTrue(visible.size >= 2)
            val currentItem = visible[app.selectedIndex()]
            assertEquals(source1, currentItem.relocation.sourcePath)
            assertEquals(PlanBadge.SKIPPED, currentItem.badge())
            assertEquals(2, app.detailSelectedIndex)
        }

        // Return to master list with 'h'
        app.handleKeyEvent(KeyEvent.ofChar('h', KEY_BINDINGS))
        assertEquals(PaneFocus.MASTER, app.paneFocus())
        assertEquals(1, app.selectedIndex())

        // Move up to unresolved conflict item (source2 is at index 0)
        app.handleKeyEvent(KeyEvent.ofChar('k', KEY_BINDINGS))
        assertEquals(0, app.selectedIndex())

        // Move into detail pane with 'l'
        app.handleKeyEvent(KeyEvent.ofChar('l', KEY_BINDINGS))
        assertEquals(PaneFocus.DETAIL, app.paneFocus())

        // Select choice 3: DISCARD_BOTH (index 3)
        app.handleKeyEvent(KeyEvent.ofChar('j', KEY_BINDINGS))
        app.handleKeyEvent(KeyEvent.ofChar('j', KEY_BINDINGS))
        app.handleKeyEvent(KeyEvent.ofChar('j', KEY_BINDINGS))
        assertEquals(3, app.detailSelectedIndex)
        app.handleKeyEvent(KeyEvent.ofKey(KeyCode.ENTER, KEY_BINDINGS))

        // The second item must stay selected and have DISCARD badge
        (app.planModel() as? PlanModel.Configured)?.let { configured ->
            val visible = WorkspaceView.visibleItems(configured, app.showInSync)
            val currentItem = visible[app.selectedIndex()]
            assertEquals(source2, currentItem.relocation.sourcePath)
            assertEquals(PlanBadge.DISCARD, currentItem.badge())
        }
    }

    @Test
    fun rendersAppElement() {
        val unconfigured = PlanModel.Unconfigured(Path.of("/tmp/.homelight.json"))
        val session = object : HomeLightSession(Path.of("/nonexistent/config.json")) {
            override fun planModel(): PlanModel {
                return unconfigured
            }
        }

        val app = HomeLightApp(session)
        val element = app.render()
        assertTrue(element.isFocusable)
    }

    private companion object {
        fun type(app: HomeLightApp, value: String) {
            for (character in value.toCharArray()) app.handleKeyEvent(KeyEvent.ofChar(character, KEY_BINDINGS))
        }

        fun setupWithEmptyRow(config: Path, temporary: Path): HomeLightApp {
            val app = HomeLightApp(HomeLightSession(config))
            app.handleKeyEvent(KeyEvent.ofChar('i', KEY_BINDINGS))
            app.handleKeyEvent(KeyEvent.ofChar('\u0015', KEY_BINDINGS))
            type(app, temporary.resolve("home").toString())
            app.handleKeyEvent(KeyEvent.ofKey(KeyCode.TAB, KEY_BINDINGS))
            type(app, temporary.resolve("local").toString())
            app.handleKeyEvent(KeyEvent.ofKey(KeyCode.ENTER, KEY_BINDINGS))
            app.handleKeyEvent(KeyEvent.ofChar('a', KEY_BINDINGS))
            return app
        }

        fun assertInvalidAndUnpublished(app: HomeLightApp, config: Path, message: String) {
            app.handleKeyEvent(KeyEvent.ofChar('v', KEY_BINDINGS))
            val validation = WorkspaceViewTest.render(app.render(), 120, 30)
            assertTrue(validation.contains(message), validation)
            app.handleKeyEvent(KeyEvent.ofChar('s', KEY_BINDINGS))
            val save = WorkspaceViewTest.render(app.render(), 120, 30)
            assertTrue(save.contains(message), save)
            assertFalse(Files.exists(config))
        }
    }
}
