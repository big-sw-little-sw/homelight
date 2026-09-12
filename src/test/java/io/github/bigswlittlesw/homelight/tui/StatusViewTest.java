package io.github.bigswlittlesw.homelight.tui;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.element.RenderContext;
import io.github.bigswlittlesw.homelight.application.RelocationStatusItem;
import io.github.bigswlittlesw.homelight.application.StatusModel;
import io.github.bigswlittlesw.homelight.application.StatusSummary;
import io.github.bigswlittlesw.homelight.config.Relocation;
import io.github.bigswlittlesw.homelight.config.WhenOnlyTargetExists;
import io.github.bigswlittlesw.homelight.config.WhenSourceAndTargetDirectoriesExist;
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

class StatusViewTest {

    @Test
    void rendersUnconfiguredView() {
        var model = new StatusModel.Unconfigured(Path.of("/tmp/.homelight.yaml"));
        var text = renderToString(model, 0, 80, 24);

        assertTrue(text.contains("HomeLight · Status"));
        assertTrue(text.contains("HomeLight Not Configured"));
        assertTrue(text.contains("No paths are currently managed."));
        assertTrue(text.contains("./homelight init"));
        assertTrue(text.contains("q: Quit"));
    }

    @Test
    void rendersInvalidView() {
        var model = new StatusModel.Invalid(Path.of("/tmp/bad-config.yaml"), "Yaml syntax error on line 4");
        var text = renderToString(model, 0, 80, 24);

        assertTrue(text.contains("HomeLight · Status (Configuration Error)"));
        assertTrue(text.contains("Failed to load configuration"));
        assertTrue(text.contains("Yaml syntax error on line 4"));
    }

    @Test
    void rendersConfiguredViewWithVariousStates() {
        var source1 = Path.of("/home/user/.m2");
        var target1 = Path.of("/local/home/user/.m2");
        var rel1 = new Relocation(source1, target1);
        var plan1 = new RelocationPlan(rel1, RelocationOutcome.CONVERGED, List.of(new ReconciliationAction.NoOp(source1)), List.of(), Optional.empty());
        var item1 = new RelocationStatusItem(rel1,
                new PathObservation(PathState.SYMLINK, Optional.of(target1), SymlinkTargetAvailability.EXISTS, false),
                new PathObservation(PathState.DIRECTORY, Optional.empty(), SymlinkTargetAvailability.NOT_A_SYMLINK, false),
                plan1, RelocationSourceState.CORRECT_SYMLINK);

        var source2 = Path.of("/home/user/.cache/uv");
        var target2 = Path.of("/local/home/user/.cache/uv");
        var rel2 = new Relocation(source2, target2, Optional.empty(), Optional.of(WhenOnlyTargetExists.ADOPT_TARGET), Optional.empty(), Optional.empty());
        var plan2 = new RelocationPlan(rel2, RelocationOutcome.CONVERGED,
                List.of(new ReconciliationAction.EnsureDirectory(source2.getParent()), new ReconciliationAction.CreateSymlink(source2, target2)),
                List.of(), Optional.empty());
        var item2 = new RelocationStatusItem(rel2,
                new PathObservation(PathState.ABSENT, Optional.empty(), SymlinkTargetAvailability.NOT_A_SYMLINK, false),
                new PathObservation(PathState.DIRECTORY, Optional.empty(), SymlinkTargetAvailability.NOT_A_SYMLINK, false),
                plan2, RelocationSourceState.ABSENT);

        var source3 = Path.of("/home/user/.gradle");
        var target3 = Path.of("/local/home/user/.gradle");
        var rel3 = new Relocation(source3, target3, Optional.of(WhenSourceAndTargetDirectoriesExist.PROMPT), Optional.empty(), Optional.empty(), Optional.empty());
        var conflict = new ReconciliationConflict(source3, "source and target directories require a decision",
                List.of(ReconciliationConflict.Resolution.RESOLVE_EXISTING_CONTENT));
        var plan3 = new RelocationPlan(rel3, RelocationOutcome.UNRESOLVED, List.of(), List.of(), Optional.of(conflict));
        var item3 = new RelocationStatusItem(rel3,
                new PathObservation(PathState.DIRECTORY, Optional.empty(), SymlinkTargetAvailability.NOT_A_SYMLINK, false),
                new PathObservation(PathState.DIRECTORY, Optional.empty(), SymlinkTargetAvailability.NOT_A_SYMLINK, false),
                plan3, RelocationSourceState.DIRECTORY);

        var source4 = Path.of("/home/user/.blocked");
        var target4 = Path.of("/local/home/user/.blocked");
        var rel4 = new Relocation(source4, target4);
        var plan4 = new RelocationPlan(rel4, RelocationOutcome.UNRESOLVED,
                List.of(new ReconciliationAction.Blocked(source4, "source is a file; relocations require directories")),
                List.of(), Optional.empty());
        var item4 = new RelocationStatusItem(rel4,
                new PathObservation(PathState.FILE, Optional.empty(), SymlinkTargetAvailability.NOT_A_SYMLINK, false),
                new PathObservation(PathState.DIRECTORY, Optional.empty(), SymlinkTargetAvailability.NOT_A_SYMLINK, false),
                plan4, RelocationSourceState.FILE);

        var items = List.of(item1, item2, item3, item4);
        var summary = StatusSummary.from(items);
        var model = new StatusModel.Configured(Path.of("/home/user/.config/homelight/homelight.yaml"), Path.of("/local/home/user"),
                new ReconciliationPlan(List.of(plan1, plan2, plan3, plan4), List.of()), items, summary);

        // Test with selectedIndex = 0 (first item)
        var text0 = renderToString(model, 0, 100, 30);

        assertTrue(text0.contains("4 relocations"));
        assertTrue(text0.contains("1 converged"));
        assertTrue(text0.contains("1 pending"));
        assertTrue(text0.contains("1 conflict"));
        assertTrue(text0.contains("1 blocked"));
        assertTrue(text0.contains("[Converged]"));
        assertTrue(text0.contains("[Pending]"));
        assertTrue(text0.contains("[Conflict]"));
        assertTrue(text0.contains("[Blocked]"));
        assertTrue(text0.contains("Details"));
        assertTrue(text0.contains("Source:"));
        assertTrue(text0.contains("Target:"));
        assertTrue(text0.contains("Outcome: CONVERGED"));

        // Test with selectedIndex = 2 (conflict item)
        var text2 = renderToString(model, 2, 120, 30);
        assertTrue(text2.contains("Conflict:"));
        assertTrue(text2.contains("source and target directories require a decision"));
    }

    @Test
    void rendersNarrowTerminal() {
        var model = new StatusModel.Unconfigured(Path.of("/tmp/.homelight.yaml"));
        var text = renderToString(model, 0, 50, 15);

        assertTrue(text.contains("HomeLight · Status"));
        assertTrue(text.contains("HomeLight Not Configured"));
    }

    private static String renderToString(StatusModel model, int selectedIndex, int width, int height) {
        try {
            var method = Class.forName("dev.tamboui.tui.RenderThread").getDeclaredMethod("markAsRenderThread");
            method.setAccessible(true);
            method.invoke(null);
            try {
                var element = StatusView.render(model, selectedIndex);
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
