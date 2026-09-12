package io.github.bigswlittlesw.homelight.tui;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import io.github.bigswlittlesw.homelight.application.RelocationStatusItem;
import io.github.bigswlittlesw.homelight.application.StatusModel;
import io.github.bigswlittlesw.homelight.application.StatusSummary;
import io.github.bigswlittlesw.homelight.application.StatusWorkflow;
import io.github.bigswlittlesw.homelight.config.Relocation;
import io.github.bigswlittlesw.homelight.domain.RelocationSourceState;
import io.github.bigswlittlesw.homelight.fs.PathObservation;
import io.github.bigswlittlesw.homelight.fs.PathState;
import io.github.bigswlittlesw.homelight.fs.SymlinkTargetAvailability;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlan;
import io.github.bigswlittlesw.homelight.reconcile.RelocationOutcome;
import io.github.bigswlittlesw.homelight.reconcile.RelocationPlan;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HomeLightAppTest {

    @Test
    void handlesNavigationKeys() {
        var rel1 = new Relocation(Path.of("/source1"), Path.of("/target1"));
        var rel2 = new Relocation(Path.of("/source2"), Path.of("/target2"));
        var rel3 = new Relocation(Path.of("/source3"), Path.of("/target3"));

        var plan1 = new RelocationPlan(rel1, RelocationOutcome.CONVERGED, List.of(new ReconciliationAction.NoOp(rel1.sourcePath())), List.of(), Optional.empty());
        var plan2 = new RelocationPlan(rel2, RelocationOutcome.CONVERGED, List.of(new ReconciliationAction.NoOp(rel2.sourcePath())), List.of(), Optional.empty());
        var plan3 = new RelocationPlan(rel3, RelocationOutcome.CONVERGED, List.of(new ReconciliationAction.NoOp(rel3.sourcePath())), List.of(), Optional.empty());

        var obs = new PathObservation(PathState.DIRECTORY, Optional.empty(), SymlinkTargetAvailability.NOT_A_SYMLINK, false);
        var item1 = new RelocationStatusItem(rel1, obs, obs, plan1, RelocationSourceState.DIRECTORY);
        var item2 = new RelocationStatusItem(rel2, obs, obs, plan2, RelocationSourceState.DIRECTORY);
        var item3 = new RelocationStatusItem(rel3, obs, obs, plan3, RelocationSourceState.DIRECTORY);

        var items = List.of(item1, item2, item3);
        var summary = StatusSummary.from(items);
        var configured = new StatusModel.Configured(Path.of("/config.yaml"), Path.of("/target"),
                new ReconciliationPlan(List.of(plan1, plan2, plan3), List.of()), items, summary);

        var mockWorkflow = new StatusWorkflow() {
            @Override
            public StatusModel loadStatus(Path configPath) {
                return configured;
            }
        };

        var app = new HomeLightApp(Path.of("/config.yaml"), mockWorkflow);

        assertEquals(0, app.selectedIndex());

        // Move down with 'j'
        app.handleKeyEvent(KeyEvent.ofChar('j'));
        assertEquals(1, app.selectedIndex());

        // Move down with DOWN key
        app.handleKeyEvent(KeyEvent.ofKey(KeyCode.DOWN));
        assertEquals(2, app.selectedIndex());

        // Cannot move past the end
        app.handleKeyEvent(KeyEvent.ofKey(KeyCode.DOWN));
        assertEquals(2, app.selectedIndex());

        // Move up with 'k'
        app.handleKeyEvent(KeyEvent.ofChar('k'));
        assertEquals(1, app.selectedIndex());

        // Jump to end with 'G'
        app.handleKeyEvent(KeyEvent.ofChar('G'));
        assertEquals(2, app.selectedIndex());

        // Jump to start with 'g'
        app.handleKeyEvent(KeyEvent.ofChar('g'));
        assertEquals(0, app.selectedIndex());

        // Cannot move before 0
        app.handleKeyEvent(KeyEvent.ofKey(KeyCode.UP));
        assertEquals(0, app.selectedIndex());
    }

    @Test
    void rendersAppElement() {
        var unconfigured = new StatusModel.Unconfigured(Path.of("/tmp/.homelight.yaml"));
        var mockWorkflow = new StatusWorkflow() {
            @Override
            public StatusModel loadStatus(Path configPath) {
                return unconfigured;
            }
        };

        var app = new HomeLightApp(Path.of("/tmp/.homelight.yaml"), mockWorkflow);
        var element = app.render();
        assertTrue(element.isFocusable());
    }
}
