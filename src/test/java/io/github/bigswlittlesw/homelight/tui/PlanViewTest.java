package io.github.bigswlittlesw.homelight.tui;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.element.RenderContext;
import io.github.bigswlittlesw.homelight.application.DecisionChoice;
import io.github.bigswlittlesw.homelight.application.PlanBadge;
import io.github.bigswlittlesw.homelight.application.PlanModel;
import io.github.bigswlittlesw.homelight.application.PlanRelocationItem;
import io.github.bigswlittlesw.homelight.application.PlanSummary;
import io.github.bigswlittlesw.homelight.config.Relocation;
import io.github.bigswlittlesw.homelight.domain.RelocationSourceState;
import io.github.bigswlittlesw.homelight.fs.PathObservation;
import io.github.bigswlittlesw.homelight.fs.PathState;
import io.github.bigswlittlesw.homelight.fs.SymlinkTargetAvailability;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationConflict;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlan;
import io.github.bigswlittlesw.homelight.reconcile.RelocationOutcome;
import io.github.bigswlittlesw.homelight.reconcile.RelocationPlan;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanViewTest {

    @Test
    void rendersUnconfiguredView() {
        var model = new PlanModel.Unconfigured(Path.of("/tmp/.homelight.yaml"));
        var text = renderToString(model, 0, 80, 24);

        assertTrue(text.contains("⌂ HOMELIGHT"));
        assertTrue(text.contains("[1: Status]"));
        assertTrue(text.contains("[2: Plan]"));
        assertTrue(text.contains("[3: Apply]"));
        assertTrue(text.contains("Config:"));
        assertTrue(text.contains("HomeLight Not Configured"));
        assertTrue(text.contains("No configuration file found at:"));
        assertTrue(text.contains("q: Quit"));
    }

    @Test
    void rendersInvalidView() {
        var model = new PlanModel.Invalid(Path.of("/tmp/bad-config.yaml"), "Configuration syntax error");
        var text = renderToString(model, 0, 80, 24);

        assertTrue(text.contains("⌂ HOMELIGHT"));
        assertTrue(text.contains("[2: Plan (Error)]"));
        assertTrue(text.contains("Configuration Error"));
        assertTrue(text.contains("Configuration syntax error"));
    }

    @Test
    void rendersHeaderTabsAndMetadata() {
        var items = List.of(new PlanRelocationItem(
                new Relocation(Path.of("/home/user/.m2"), Path.of("/local/home/user/.m2")),
                new PathObservation(PathState.DIRECTORY, Optional.empty(), SymlinkTargetAvailability.NOT_A_SYMLINK, false),
                new PathObservation(PathState.DIRECTORY, Optional.empty(), SymlinkTargetAvailability.NOT_A_SYMLINK, false),
                new RelocationPlan(new Relocation(Path.of("/home/user/.m2"), Path.of("/local/home/user/.m2")),
                        RelocationOutcome.CONVERGED, List.of(new ReconciliationAction.NoOp(Path.of("/home/user/.m2"))), List.of(), Optional.empty()),
                RelocationSourceState.DIRECTORY,
                List.of()
        ));
        var summary = PlanSummary.from(items);
        var model = new PlanModel.Configured(
                Path.of("/home/user/.config/homelight/homelight.yaml"),
                Path.of("/local/home/user"),
                new ReconciliationPlan(List.of(), List.of()),
                items,
                summary
        );

        var text = renderToString(model, 0, 100, 30);
        assertTrue(text.contains("⌂ HOMELIGHT"));
        assertTrue(text.contains("[1: Status]"));
        assertTrue(text.contains("[2: Plan]"));
        assertTrue(text.contains("[3: Apply]"));
        assertTrue(text.contains("Config:"));
        assertTrue(text.contains("Target Root:"));
    }

    @Test
    void rendersConfiguredViewWithVariousOperationsAndDestructiveWarnings() {
        var source1 = Path.of("/home/user/.m2");
        var target1 = Path.of("/local/home/user/.m2");
        var rel1 = new Relocation(source1, target1);
        var plan1 = new RelocationPlan(rel1, RelocationOutcome.CONVERGED, List.of(
                new ReconciliationAction.StageDirectoryForPublication(source1, target1),
                new ReconciliationAction.ReplaceDirectoryWithSymlink(source1, target1)
        ), List.of(), Optional.empty());
        var item1 = new PlanRelocationItem(rel1,
                new PathObservation(PathState.DIRECTORY, Optional.empty(), SymlinkTargetAvailability.NOT_A_SYMLINK, false),
                new PathObservation(PathState.ABSENT, Optional.empty(), SymlinkTargetAvailability.NOT_A_SYMLINK, false),
                plan1, RelocationSourceState.DIRECTORY, List.of());

        var source2 = Path.of("/home/user/.conflict");
        var target2 = Path.of("/local/home/user/.conflict");
        var rel2 = new Relocation(source2, target2);
        var conflict = new ReconciliationConflict(source2, "target directory requires an adopt-target decision",
                List.of(ReconciliationConflict.Resolution.RESOLVE_EXISTING_CONTENT));
        var plan2 = new RelocationPlan(rel2, RelocationOutcome.UNRESOLVED, List.of(), List.of(), Optional.of(conflict));
        var item2 = new PlanRelocationItem(rel2,
                new PathObservation(PathState.ABSENT, Optional.empty(), SymlinkTargetAvailability.NOT_A_SYMLINK, false),
                new PathObservation(PathState.DIRECTORY, Optional.empty(), SymlinkTargetAvailability.NOT_A_SYMLINK, false),
                plan2, RelocationSourceState.ABSENT, List.of(DecisionChoice.ADOPT_TARGET));

        var source3 = Path.of("/home/user/.converged");
        var target3 = Path.of("/local/home/user/.converged");
        var rel3 = new Relocation(source3, target3);
        var plan3 = new RelocationPlan(rel3, RelocationOutcome.CONVERGED, List.of(new ReconciliationAction.NoOp(source3)), List.of(), Optional.empty());
        var item3 = new PlanRelocationItem(rel3,
                new PathObservation(PathState.SYMLINK, Optional.of(target3), SymlinkTargetAvailability.EXISTS, false),
                new PathObservation(PathState.DIRECTORY, Optional.empty(), SymlinkTargetAvailability.NOT_A_SYMLINK, false),
                plan3, RelocationSourceState.CORRECT_SYMLINK, List.of());

        var items = List.of(item1, item2, item3);
        var summary = PlanSummary.from(items);
        var model = new PlanModel.Configured(
                Path.of("/config.yaml"),
                Path.of("/local"),
                new ReconciliationPlan(List.of(plan1, plan2, plan3), List.of()),
                items,
                summary
        );

        // Render selected index 0 (item2: conflict because it's priority 1)
        var text0 = renderToString(model, 0, 120, 30);
        assertTrue(text0.contains("3 relocations"));
        assertTrue(text0.contains("1 conflict"));
        assertTrue(text0.contains("1 stage"));
        assertTrue(text0.contains("1 converged"));
        assertTrue(text0.contains("[Conflict]"));
        assertTrue(text0.contains("Available Resolutions:"));
        assertTrue(text0.contains("Adopt target and create source link"));

        // Render selected index 1 (item1: stage item with destructive action)
        var text1 = renderToString(model, 1, 200, 30);
        assertTrue(text1.contains("[Stage]"));
        assertTrue(text1.contains("Actions (dry-run):"));
        assertTrue(text1.contains("DESTRUCTIVE"));
        assertTrue(text1.contains("Safety Warning: This relocation includes destructive actions."));
    }

    private static String renderToString(PlanModel model, int selectedIndex, int width, int height) {
        return renderToString(model, selectedIndex, true, width, height);
    }

    private static String renderToString(PlanModel model, int selectedIndex, boolean showConverged, int width, int height) {
        try {
            var method = Class.forName("dev.tamboui.tui.RenderThread").getDeclaredMethod("markAsRenderThread");
            method.setAccessible(true);
            method.invoke(null);
            try {
                var element = PlanView.render(model, selectedIndex, showConverged);
                var buffer = Buffer.empty(Rect.of(width, height));
                var frame = Frame.forTesting(buffer);
                element.render(frame, Rect.of(width, height), RenderContext.empty());
                var sb = new StringBuilder();
                for (int y = 0; y < buffer.height(); y++) {
                    for (int x = 0; x < buffer.width(); x++) {
                        var cell = buffer.get(x, y);
                        sb.append(cell != null && cell.symbol() != null ? cell.symbol() : " ");
                    }
                    sb.append("\n");
                }
                return sb.toString();
            } finally {
                var clearMethod = Class.forName("dev.tamboui.tui.RenderThread").getDeclaredMethod("clearRenderThread");
                clearMethod.setAccessible(true);
                clearMethod.invoke(null);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
