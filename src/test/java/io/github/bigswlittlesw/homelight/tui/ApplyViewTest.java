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
            assertTrue(text.contains("1/Enter/n/Esc: Workspace"), text);
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
        assertTrue(text.contains("2 planned changes"), text);
        assertFalse(text.contains("0/3"), text);
    }
    @Test
    void confirmationDisplaysDestructiveActionsAndDistinctConfirmAndCancelKeys() throws Exception {
        var plan = plan();
        for (int width : new int[] {80, 120}) {
            var text = render(new ApplyModel.Confirmation(plan), 1, width, 24);
            assertTrue(text.contains("[2: Review]"), text);
            assertTrue(text.contains("destructive"), text);
            assertTrue(text.contains("y: Confirm destructive plan"), text);
            assertTrue(text.contains("n/Esc/1: Cancel review"), text);
            assertTrue(text.contains("/home/cache"), text);
            assertTrue(text.contains("/local/cache"), text);
            assertTrue(text.contains("2 planned changes"), text);
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
        assertTrue(text.contains("Enter: Workspace"), text);
    }

    @Test
    void runningViewShowsActiveAndPendingStepsAndQuitOptions() throws Exception {
        var plan = plan();
        var relocation = plan.relocations().getFirst();
        var steps = List.of(
                new ApplyModel.Step(relocation, relocation.actions().getFirst(), ApplyModel.StepStatus.RUNNING, "Running"),
                new ApplyModel.Step(relocation, relocation.actions().getLast(), ApplyModel.StepStatus.PENDING, "Not started"));
        var text = render(new ApplyModel.Running(plan, steps), 0, 80, 24);
        assertTrue(text.contains("⠋"), text);
        assertTrue(text.contains("○"), text);
        assertTrue(text.contains("q: Quit options"), text);
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

    @Test
    void longFailureAndEveryAffectedDestinationRemainScrollableAcrossResize() throws Exception {
        var source = Path.of("/home/" + "long-source-segment/".repeat(8) + "source-suffix");
        var target = Path.of("/storage/" + "long-target-segment/".repeat(8) + "target-suffix");
        var archive = Path.of("/archive/" + "archive-segment/".repeat(8) + "archive-suffix");
        var relocation = new Relocation(source, target);
        var actions = List.<ReconciliationAction>of(new ReconciliationAction.ArchiveDirectory(source, archive),
                new ReconciliationAction.MigrateDirectoryForPublication(source, target),
                new ReconciliationAction.ReplaceDirectoryWithSymlink(source, target));
        var relocationPlan = new RelocationPlan(relocation, RelocationOutcome.CONVERGED, actions, List.of(), Optional.empty());
        var plan = new ReconciliationPlan(List.of(relocationPlan), List.of());
        var cause = "Failure at /staging/" + "segment/".repeat(40) + "failure-cause-suffix";
        var steps = actions.stream().map(action -> new ApplyModel.Step(relocationPlan, action, ApplyModel.StepStatus.FAILED, cause)).toList();
        var result = new ApplyModel.Result(plan, steps, Optional.empty(), List.of("Diagnostic: " + cause), false);
        for (int selected = 0; selected < actions.size(); selected++) {
            var viewport = new DetailViewport();
            for (var size : List.of(new int[]{80, 24}, new int[]{120, 30}, new int[]{200, 50}, new int[]{120, 30}, new int[]{80, 24})) {
                viewport.reset();
                var evidence = new StringBuilder();
                for (int line = 0; line < 180; line++) {
                    var screen = WorkspaceViewTest.render(ApplyView.render(Path.of("/config.yaml"), result, selected,
                            0, PaneFocus.DETAIL, viewport), size[0], size[1]);
                    assertTrue(screen.contains("Action details"), screen);
                    assertTrue(screen.contains("r: Re-plan"), screen);
                    assertFalse(screen.contains("before any mutation"), screen);
                    evidence.append(WorkspaceViewTest.rightPane(screen, size[0]));
                    viewport.scroll(1);
                }
                assertTrue(evidence.toString().contains(source.toString()));
                assertTrue(evidence.toString().contains(target.toString()));
                assertTrue(evidence.toString().replace(" ", "").contains(cause.replace(" ", "")));
                assertTrue(evidence.toString().replace(" ", "").contains(ApplyView.affectedPath(actions.get(selected)).replace(" ", "")));
                assertTrue(evidence.toString().replace(" ", "").contains(ApplyView.destination(actions.get(selected)).replace(" ", "")));
            }
        }
    }

    @Test
    void detailsUseExactDestinationPathsWhenDecidingWhetherToRepeatTheTarget() {
        var relocation = new Relocation(Path.of("/home/cache"), Path.of("/local/cache"));
        var relocationPlan = new RelocationPlan(relocation, RelocationOutcome.CONVERGED, List.of(), List.of(), Optional.empty());

        var archive = new ReconciliationAction.ArchiveDirectory(relocation.sourcePath(), Path.of("/archive/local/cache"));
        var archiveLines = ApplyView.details(new ApplyModel.Step(relocationPlan, archive, ApplyModel.StepStatus.PENDING, "Not started"));
        assertTrue(archiveLines.stream().map(DetailViewport.Line::text).anyMatch("Target: /local/cache"::equals), archiveLines.toString());

        var link = new ReconciliationAction.CreateSymlink(relocation.sourcePath(), relocation.targetPath());
        var linkLines = ApplyView.details(new ApplyModel.Step(relocationPlan, link, ApplyModel.StepStatus.PENDING, "Not started"));
        assertFalse(linkLines.stream().map(DetailViewport.Line::text).anyMatch("Target: /local/cache"::equals), linkLines.toString());
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
