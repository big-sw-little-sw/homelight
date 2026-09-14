package io.github.bigswlittlesw.homelight.tui;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.element.RenderContext;
import io.github.bigswlittlesw.homelight.application.ApplyModel;
import io.github.bigswlittlesw.homelight.config.Relocation;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlan;
import io.github.bigswlittlesw.homelight.reconcile.RelocationOutcome;
import io.github.bigswlittlesw.homelight.reconcile.RelocationPlan;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ApplyViewTest {

    @Test
    void unchangedPlanNeedsNeitherConfirmationNorAProgressCounter() throws Exception {
        var relocation = new Relocation(Path.of("/home/cache"), Path.of("/local/cache"));
        for (var action : List.of(new ReconciliationAction.NoOp(relocation.sourcePath()),
                new ReconciliationAction.LeaveUnchanged(relocation.sourcePath()))) {
            var plan = new ReconciliationPlan(List.of(new RelocationPlan(relocation,
                    action instanceof ReconciliationAction.NoOp ? RelocationOutcome.CONVERGED : RelocationOutcome.UNCHANGED,
                    List.of(action), List.of(), Optional.empty())), List.of());
            var text = render(new ApplyModel.Confirmation(plan), 0, 80, 24);
            assertTrue(text.contains("No changes to apply"), text);
            assertTrue(text.contains("Enter: Status"), text);
            assertFalse(text.contains("actions completed"), text);
            assertFalse(text.contains("Confirm apply"), text);
            assertFalse(text.contains("Not started"), text);
            assertFalse(text.contains("○"), text);
        }
    }

    @Test
    void mixedPlanCountsOnlyFilesystemChanges() throws Exception {
        var changing = plan().relocations().getFirst();
        var relocation = new Relocation(Path.of("/home/unchanged"), Path.of("/local/unchanged"));
        var unchanged = new RelocationPlan(relocation, RelocationOutcome.CONVERGED,
                List.of(new ReconciliationAction.NoOp(relocation.sourcePath())), List.of(), Optional.empty());
        var plan = new ReconciliationPlan(List.of(unchanged, changing), List.of());
        var text = render(new ApplyModel.Confirmation(plan), 0, 80, 24);
        assertTrue(text.contains("0/2 actions completed"), text);
        assertFalse(text.contains("0/3"), text);
    }
    @Test
    void confirmationDisplaysDestructiveActionsAndDistinctConfirmAndCancelKeys() throws Exception {
        var plan = plan();
        for (int width : new int[] {80, 120}) {
            var text = render(new ApplyModel.Confirmation(plan), 1, width, 24);
            assertTrue(text.contains("[3: Apply]"), text);
            assertTrue(text.contains("destructive"), text);
            assertTrue(text.contains("y: Confirm destructive plan"), text);
            assertTrue(text.contains("n/Esc: Cancel"), text);
            assertTrue(text.contains("/home/cache"), text);
            assertTrue(text.contains("/local/cache"), text);
            assertTrue(text.contains("0/2 actions completed"), text);
        }
    }

    @Test
    void partialFailureRetainsActionStatesAndRecoveryInstructions() throws Exception {
        var plan = plan();
        var relocation = plan.relocations().getFirst();
        var steps = List.of(
                new ApplyModel.Step(relocation, relocation.actions().getFirst(), ApplyModel.StepStatus.COMPLETED, "completed"),
                new ApplyModel.Step(relocation, relocation.actions().getLast(), ApplyModel.StepStatus.FAILED, "Source changed"));
        var text = render(new ApplyModel.Result(plan, steps, Optional.empty(), List.of("Review changed source"), true), 1, 80, 24);
        assertTrue(text.contains("Plan stale"), text);
        assertTrue(text.contains("✔"), text);
        assertTrue(text.contains("✖"), text);
        assertTrue(text.contains("Source changed"), text);
        assertTrue(text.contains("r: Re-plan"), text);
        assertTrue(text.contains("Enter: Status"), text);
    }

    @Test
    void runningViewShowsActiveAndPendingStepsAndDisablesLeaving() throws Exception {
        var plan = plan();
        var relocation = plan.relocations().getFirst();
        var steps = List.of(
                new ApplyModel.Step(relocation, relocation.actions().getFirst(), ApplyModel.StepStatus.RUNNING, "Running"),
                new ApplyModel.Step(relocation, relocation.actions().getLast(), ApplyModel.StepStatus.PENDING, "Not started"));
        var text = render(new ApplyModel.Running(plan, steps), 0, 80, 24);
        assertTrue(text.contains("⠋"), text);
        assertTrue(text.contains("○"), text);
        assertTrue(text.contains("leaving is disabled"), text);
        var nextFrame = render(new ApplyModel.Running(plan, steps), 0, 80, 24, 1);
        assertTrue(nextFrame.contains("⠙"), nextFrame);
        assertTrue(nextFrame.contains("○"), nextFrame);
    }

    private static ReconciliationPlan plan() {
        var relocation = new Relocation(Path.of("/home/cache"), Path.of("/local/cache"));
        return new ReconciliationPlan(List.of(new RelocationPlan(relocation, RelocationOutcome.CONVERGED,
                List.of(new ReconciliationAction.MigrateDirectoryForPublication(relocation.sourcePath(), relocation.targetPath()),
                        new ReconciliationAction.ReplaceDirectoryWithSymlink(relocation.sourcePath(), relocation.targetPath())),
                List.of(), Optional.empty())), List.of());
    }

    private static String render(ApplyModel model, int selected, int width, int height) throws Exception {
        return render(model, selected, width, height, 0);
    }

    private static String render(ApplyModel model, int selected, int width, int height, int spinnerFrame) throws Exception {
        var marker = Class.forName("dev.tamboui.tui.RenderThread").getDeclaredMethod("markAsRenderThread");
        var clear = Class.forName("dev.tamboui.tui.RenderThread").getDeclaredMethod("clearRenderThread");
        marker.setAccessible(true);
        clear.setAccessible(true);
        marker.invoke(null);
        try {
            var buffer = Buffer.empty(Rect.of(width, height));
            ApplyView.render(Path.of("/config.yaml"), model, selected, spinnerFrame)
                    .render(Frame.forTesting(buffer), Rect.of(width, height), RenderContext.empty());
            var text = new StringBuilder();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    text.append(buffer.get(x, y).symbol());
                }
                text.append('\n');
            }
            return text.toString();
        } finally {
            clear.invoke(null);
        }
    }
}
