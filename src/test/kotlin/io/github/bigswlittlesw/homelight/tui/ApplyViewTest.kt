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
import java.util.Optional

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
            assertFalse(text.contains("actions completed"), text)
            assertFalse(text.contains("Confirm apply"), text)
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
            assertTrue(text.contains("destructive"), text)
            assertTrue(text.contains("y: Confirm destructive plan"), text)
            assertTrue(text.contains("n/Esc/1: Cancel review"), text)
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
        val text = render(ApplyModel.Result(plan, steps, Optional.empty(), listOf("Review changed source"), true), 1, 80, 24)
        assertTrue(text.contains("Plan stale"), text)
        assertTrue(text.contains("✔"), text)
        assertTrue(text.contains("✖"), text)
        assertTrue(text.contains("Source changed"), text)
        assertTrue(text.contains("r: Re-plan"), text)
        assertTrue(text.contains("Enter: Workspace"), text)
    }

    @Test
    fun runningViewShowsActiveAndPendingStepsAndQuitOptions() {
        val plan = plan()
        val relocation = plan.relocations.first()
        val steps = listOf(
            ApplyModel.Step(relocation, relocation.actions.first(), ApplyModel.StepStatus.RUNNING, "Running"),
            ApplyModel.Step(relocation, relocation.actions.last(), ApplyModel.StepStatus.PENDING, "Not started"))
        val text = render(ApplyModel.Running(plan, steps), 0, 80, 24)
        assertTrue(text.contains("⠋"), text)
        assertTrue(text.contains("○"), text)
        assertTrue(text.contains("q: Quit options"), text)
        val nextFrame = render(ApplyModel.Running(plan, steps), 0, 80, 24, 1)
        assertTrue(nextFrame.contains("⠙"), nextFrame)
        assertTrue(nextFrame.contains("○"), nextFrame)
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
        val result = ApplyModel.Result(plan, steps, Optional.empty(), listOf("Diagnostic: $cause"), false)
        for (selected in 0 until actions.size) {
            val viewport = DetailViewport()
            for (size in listOf(intArrayOf(80, 24), intArrayOf(120, 30), intArrayOf(200, 50), intArrayOf(120, 30), intArrayOf(80, 24))) {
                viewport.reset()
                val evidence = StringBuilder()
                repeat(180) {
                    val screen = WorkspaceViewTest.render(ApplyView.render(Path.of("/config.yaml"), result, selected,
                        0, PaneFocus.DETAIL, viewport), size[0], size[1])
                    assertTrue(screen.contains("Action details"), screen)
                    assertTrue(screen.contains("r: Re-plan"), screen)
                    assertFalse(screen.contains("before any mutation"), screen)
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
        assertTrue(archiveLines.stream().map(DetailViewport.Line::text).anyMatch("Target: /local/cache"::equals), archiveLines.toString())

        val link = ReconciliationAction.CreateSymlink(relocation.sourcePath, relocation.targetPath)
        val linkLines = ApplyView.details(ApplyModel.Step(relocationPlan, link, ApplyModel.StepStatus.PENDING, "Not started"))
        assertFalse(linkLines.stream().map(DetailViewport.Line::text).anyMatch("Target: /local/cache"::equals), linkLines.toString())
    }

    private companion object {
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
                ApplyView.render(Path.of("/config.yaml"), model, selected, spinnerFrame)
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
