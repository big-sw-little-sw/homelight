package io.github.bigswlittlesw.homelight.tui

import dev.tamboui.buffer.Buffer
import dev.tamboui.layout.Rect
import dev.tamboui.terminal.Frame
import dev.tamboui.toolkit.element.RenderContext
import io.github.bigswlittlesw.homelight.application.ApplyModel
import io.github.bigswlittlesw.homelight.config.Relocation
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlan
import io.github.bigswlittlesw.homelight.reconcile.RelocationOutcome
import io.github.bigswlittlesw.homelight.reconcile.RelocationPlan
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class ApplyViewTest {

    @Test
    fun unchangedPlanNeedsNeitherConfirmationNorAProgressCounter() {
        val relocation = Relocation(Path.of("/home/cache"), Path.of("/local/cache"))
        for (action in listOf(ReconciliationAction.NoOp(relocation.sourcePath),
            ReconciliationAction.LeaveUnchanged(relocation.sourcePath))) {
            val plan = ReconciliationPlan(listOf(RelocationPlan(relocation,
                if (action is ReconciliationAction.NoOp) RelocationOutcome.CONVERGED else RelocationOutcome.UNCHANGED,
                listOf(action), listOf())), listOf())
            val text = render(ApplyModel.Confirmation(plan), 0, 80, 24)
            assertTrue(text.contains("No changes to apply"), text)
            assertTrue(text.contains("1/Enter/n/Esc: Workspace"), text)
            assertFalse(text.contains("changes done"), text)
            assertFalse(text.contains("y: Apply"), text)
            assertFalse(text.contains("Not started"), text)
            assertFalse(text.contains("○"), text)
        }
    }

    @Test
    fun mixedPlanCountsOnlyFilesystemChanges() {
        val changing = plan().relocations.first()
        val relocation = Relocation(Path.of("/home/unchanged"), Path.of("/local/unchanged"))
        val unchanged = RelocationPlan(relocation, RelocationOutcome.CONVERGED,
            listOf(ReconciliationAction.NoOp(relocation.sourcePath)), listOf())
        val plan = ReconciliationPlan(listOf(unchanged, changing), listOf())
        val text = render(ApplyModel.Confirmation(plan), 0, 80, 24)
        assertTrue(text.contains("2 planned changes"), text)
        assertFalse(text.contains("0/3"), text)
    }
    @Test
    fun confirmationDisplaysDestructiveActionsAndDistinctConfirmAndCancelKeys() {
        val plan = plan()
        for (width in intArrayOf(80, 120)) {
            val text = render(ApplyModel.Confirmation(plan), 1, width, 24)
            assertTrue(text.contains("[2: Review]"), text)
            assertTrue(text.contains("delete or replace data"), text)
            assertTrue(text.contains("y: Apply"), text)
            assertTrue(text.contains("n/Esc/1: Cancel"), text)
            assertTrue(text.contains("/home/cache"), text)
            assertTrue(text.contains("/local/cache"), text)
            assertTrue(text.contains("2 planned changes"), text)
        }
    }

    @Test
    fun partialFailureRetainsActionStatesAndRecoveryInstructions() {
        val plan = plan()
        val relocation = plan.relocations.first()
        val steps = listOf(
            ApplyModel.Step(relocation, relocation.actions.first(), ApplyModel.StepStatus.COMPLETED, "completed"),
            ApplyModel.Step(relocation, relocation.actions.last(), ApplyModel.StepStatus.FAILED, "Source changed"))
        val text = render(ApplyModel.Result.of(plan, steps, null, listOf("Review changed source"), true), 1, 80, 24)
        assertTrue(text.contains("the disk changed while applying"), text)
        assertTrue(text.contains("✔"), text)
        assertTrue(text.contains("✖"), text)
        assertTrue(text.contains("Source changed"), text)
        assertTrue(text.contains("r: Check again"), text)
        assertTrue(text.contains("Enter: Workspace"), text)
    }

    @Test
    fun runningViewMarksEachRelocationAndCountsChanges() {
        val changing = plan().relocations.first()
        val relocation = Relocation(Path.of("/home/npm"), Path.of("/local/npm"))
        val inSync = RelocationPlan(relocation, RelocationOutcome.CONVERGED, listOf(ReconciliationAction.NoOp(relocation.sourcePath)), listOf())
        val plan = ReconciliationPlan(listOf(changing, inSync), listOf())
        val steps = listOf(
            ApplyModel.Step(changing, changing.actions.first(), ApplyModel.StepStatus.COMPLETED, "Copied"),
            ApplyModel.Step(changing, changing.actions.last(), ApplyModel.StepStatus.RUNNING, "Running"),
            ApplyModel.Step(inSync, inSync.actions.first(), ApplyModel.StepStatus.PENDING, "Not started"))
        for (size in listOf(intArrayOf(80, 24), intArrayOf(120, 30))) {
            val text = render(ApplyModel.Running.of(plan, steps), 0, size[0], size[1])
            // Only what is happening: neither destination can be reached while applying.
            assertTrue(text.contains("⌂ HOMELIGHT  [Applying]"), text)
            assertFalse(text.contains("Workspace"), text)
            assertTrue(text.contains("Applying. Leave HomeLight running until it finishes."), text)
            assertTrue(text.contains("1 of 2 changes done · 1 running · 0 failed"), text)
            assertTrue(text.contains("━"), text)
            // The relocation and its running step both spin; the done step is checked.
            assertTrue(text.contains("  ⠙ /home/cache"), text)
            assertTrue(text.contains("❯ ✔ Copy to target and check"), text)
            assertTrue(text.contains("  ⠙ Replace source with a link ⚠ "), text)
            assertTrue(text.contains("  ─ /home/npm (in sync)"), text)
            assertFalse(text.contains("Already in sync"), text)
            assertTrue(text.contains("q: Quit"), text)
        }
        val nextFrame = render(ApplyModel.Running.of(plan, steps), 0, 80, 24, 1)
        assertTrue(nextFrame.contains("⠹ Replace source with a link"), nextFrame)
    }

    @Test
    fun relocationMarksFollowTheirStepsAndResultsCountWhatDidNotRun() {
        val first = plan().relocations.first()
        val relocation = Relocation(Path.of("/home/other"), Path.of("/local/other"))
        val second = RelocationPlan(relocation, RelocationOutcome.CONVERGED,
            listOf(ReconciliationAction.CreateSymlink(relocation.sourcePath, relocation.targetPath)), listOf())
        val plan = ReconciliationPlan(listOf(first, second), listOf())
        val steps = listOf(
            ApplyModel.Step(first, first.actions.first(), ApplyModel.StepStatus.COMPLETED, "Copied"),
            ApplyModel.Step(first, first.actions.last(), ApplyModel.StepStatus.FAILED, "Source changed"),
            ApplyModel.Step(second, second.actions.first(), ApplyModel.StepStatus.PENDING, "Not run"))
        val text = render(ApplyModel.Result.of(plan, steps, null, listOf(), true), 1, 120, 30)
        assertTrue(text.contains("  ✖ /home/cache"), text)
        assertTrue(text.contains("  ○ /home/other"), text)
        assertTrue(text.contains("1 of 3 changes done · 1 failed · 1 not run"), text)
        assertTrue(text.contains("[1: Workspace]  [2: Results]"), text)

        val done = steps.map { it.copy(status = ApplyModel.StepStatus.COMPLETED) }
        val finished = render(ApplyModel.Result.of(plan, done, null, listOf(), false), 0, 120, 30)
        assertTrue(finished.contains("  ✔ /home/cache"), finished)
        assertTrue(finished.contains("  ✔ /home/other"), finished)
    }

    @Test
    fun runningViewStopsOfferingQuitOnceHomeLightWillExit() {
        val plan = plan()
        val relocation = plan.relocations.first()
        val steps = listOf(
            ApplyModel.Step(relocation, relocation.actions.first(), ApplyModel.StepStatus.RUNNING, "Running"),
            ApplyModel.Step(relocation, relocation.actions.last(), ApplyModel.StepStatus.PENDING, "Not started"))
        val text = WorkspaceViewTest.render(
            ApplyView.render(Path.of("/config.json"), ApplyModel.Running.of(plan, steps), list(0), quitting = true), 80, 24,
        )
        assertTrue(text.contains("○ Replace source with a link"), text)
        assertFalse(text.contains("q: Quit"), text)
    }

    @Test
    fun longFailureAndEveryAffectedDestinationRemainScrollableAcrossResize() {
        val source = Path.of("/home/" + "long-source-segment/".repeat(8) + "source-suffix")
        val target = Path.of("/storage/" + "long-target-segment/".repeat(8) + "target-suffix")
        val archive = Path.of("/archive/" + "archive-segment/".repeat(8) + "archive-suffix")
        val relocation = Relocation(source, target)
        val actions = listOf<ReconciliationAction>(ReconciliationAction.ArchiveDirectory(source, archive),
            ReconciliationAction.MigrateDirectoryForPublication(source, target),
            ReconciliationAction.ReplaceDirectoryWithSymlink(source, target))
        val relocationPlan = RelocationPlan(relocation, RelocationOutcome.CONVERGED, actions, listOf())
        val plan = ReconciliationPlan(listOf(relocationPlan), listOf())
        val cause = "Failure at /staging/" + "segment/".repeat(40) + "failure-cause-suffix"
        val steps = actions.map { action -> ApplyModel.Step(relocationPlan, action, ApplyModel.StepStatus.FAILED, cause) }
        val result = ApplyModel.Result.of(plan, steps, null, listOf("Diagnostic: $cause"), false)
        for (selected in 0 until actions.size) {
            val viewport = DetailViewport()
            for (size in listOf(intArrayOf(80, 24), intArrayOf(120, 30), intArrayOf(200, 50), intArrayOf(120, 30), intArrayOf(80, 24))) {
                viewport.reset()
                val evidence = StringBuilder()
                repeat(180) {
                    val screen = WorkspaceViewTest.render(ApplyView.render(Path.of("/config.json"), result, list(selected),
                        0, REVIEW_DETAILS, viewport = viewport), size[0], size[1])
                    assertTrue(screen.contains("Action details"), screen)
                    assertTrue(screen.contains("r: Check again"), screen)
                    assertFalse(screen.contains("no longer matches the reviewed plan"), screen)
                    evidence.append(WorkspaceViewTest.rightPane(screen, size[0]))
                    viewport.scroll(1)
                }
                assertTrue(evidence.toString().contains(source.toString()))
                assertTrue(evidence.toString().contains(target.toString()))
                assertTrue(evidence.toString().replace(" ", "").contains(cause.replace(" ", "")))
                assertTrue(evidence.toString().replace(" ", "").contains(ApplyView.affectedPath(actions[selected]).replace(" ", "")))
                assertTrue(evidence.toString().replace(" ", "").contains(ApplyView.destination(actions[selected]).replace(" ", "")))
            }
        }
    }

    @Test
    fun detailsUseExactDestinationPathsWhenDecidingWhetherToRepeatTheTarget() {
        val relocation = Relocation(Path.of("/home/cache"), Path.of("/local/cache"))
        val relocationPlan = RelocationPlan(relocation, RelocationOutcome.CONVERGED, listOf(), listOf())

        val archive = ReconciliationAction.ArchiveDirectory(relocation.sourcePath, Path.of("/archive/local/cache"))
        val archiveLines = ApplyView.details(ApplyModel.Step(relocationPlan, archive, ApplyModel.StepStatus.PENDING, "Not started"))
        assertTrue(archiveLines.map(DetailViewport.Line::text).any { it == "Target: /local/cache" }, archiveLines.toString())

        val link = ReconciliationAction.CreateSymlink(relocation.sourcePath, relocation.targetPath)
        val linkLines = ApplyView.details(ApplyModel.Step(relocationPlan, link, ApplyModel.StepStatus.PENDING, "Not started"))
        assertFalse(linkLines.map(DetailViewport.Line::text).any { it == "Target: /local/cache" }, linkLines.toString())
    }

    private companion object {
        fun list(selected: Int) = ApplyView.list().selected(selected)

        fun plan(): ReconciliationPlan {
            val relocation = Relocation(Path.of("/home/cache"), Path.of("/local/cache"))
            return ReconciliationPlan(listOf(RelocationPlan(relocation, RelocationOutcome.CONVERGED,
                listOf(ReconciliationAction.MigrateDirectoryForPublication(relocation.sourcePath, relocation.targetPath),
                    ReconciliationAction.ReplaceDirectoryWithSymlink(relocation.sourcePath, relocation.targetPath)),
                listOf())), listOf())
        }

        fun render(model: ApplyModel, selected: Int, width: Int, height: Int): String =
            render(model, selected, width, height, 0)

        fun render(model: ApplyModel, selected: Int, width: Int, height: Int, spinnerFrame: Int): String {
            val marker = Class.forName("dev.tamboui.tui.RenderThread").getDeclaredMethod("markAsRenderThread")
            val clear = Class.forName("dev.tamboui.tui.RenderThread").getDeclaredMethod("clearRenderThread")
            marker.isAccessible = true
            clear.isAccessible = true
            marker.invoke(null)
            try {
                val buffer = Buffer.empty(Rect.of(width, height))
                ApplyView.render(Path.of("/config.json"), model, list(selected), spinnerFrame)
                    .render(Frame.forTesting(buffer), Rect.of(width, height), RenderContext.empty())
                val text = StringBuilder()
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        text.append(buffer.get(x, y).symbol())
                    }
                    text.append('\n')
                }
                return text.toString()
            } finally {
                clear.invoke(null)
            }
        }
    }
}
