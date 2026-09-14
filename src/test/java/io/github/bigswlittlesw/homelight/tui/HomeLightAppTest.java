package io.github.bigswlittlesw.homelight.tui;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import io.github.bigswlittlesw.homelight.application.DecisionChoice;
import io.github.bigswlittlesw.homelight.application.ApplyModel;
import io.github.bigswlittlesw.homelight.application.HomeLightSession;
import io.github.bigswlittlesw.homelight.application.PlanBadge;
import io.github.bigswlittlesw.homelight.application.PlanModel;
import io.github.bigswlittlesw.homelight.application.PlanRelocationItem;
import io.github.bigswlittlesw.homelight.application.PlanSummary;
import io.github.bigswlittlesw.homelight.application.PlanWorkflow;
import io.github.bigswlittlesw.homelight.application.RelocationStatusItem;
import io.github.bigswlittlesw.homelight.application.Screen;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HomeLightAppTest {

    @Test
    void followsExecutionAcrossRelocationsWithoutOverridingManualInspectionBetweenActions() {
        var first = new Relocation(Path.of("/home/first"), Path.of("/local/first"));
        var second = new Relocation(Path.of("/home/second"), Path.of("/local/second"));
        var firstPlan = new RelocationPlan(first, RelocationOutcome.CONVERGED,
                List.of(new ReconciliationAction.CreateDirectory(first.targetPath()),
                        new ReconciliationAction.CreateSymlink(first.sourcePath(), first.targetPath())), List.of(), Optional.empty());
        var secondPlan = new RelocationPlan(second, RelocationOutcome.CONVERGED,
                List.of(new ReconciliationAction.CreateDirectory(second.targetPath())), List.of(), Optional.empty());
        var plan = new ReconciliationPlan(List.of(firstPlan, secondPlan), List.of());
        var progress = new java.util.concurrent.atomic.AtomicReference<ApplyModel>(new ApplyModel.Confirmation(plan));
        var session = new HomeLightSession(Path.of("/nonexistent/config.yaml"), Screen.APPLY) {
            @Override
            public ApplyModel applyModel() {
                return progress.get();
            }
        };
        var app = new HomeLightApp(session);
        app.render();

        for (int active = 0; active < 3; active++) {
            var steps = new java.util.ArrayList<ApplyModel.Step>();
            for (var relocation : plan.relocations()) {
                for (var action : relocation.actions()) {
                    var status = steps.size() < active ? ApplyModel.StepStatus.COMPLETED
                            : steps.size() == active ? ApplyModel.StepStatus.RUNNING : ApplyModel.StepStatus.PENDING;
                    steps.add(new ApplyModel.Step(relocation, action, status, status.toString()));
                }
            }
            progress.set(new ApplyModel.Running(plan, steps));
            app.render();
            assertEquals(active, app.selectedIndex());
            app.handleKeyEvent(KeyEvent.ofChar(active == 0 ? 'j' : 'k'));
            int inspected = app.selectedIndex();
            app.render();
            assertEquals(inspected, app.selectedIndex());
        }

        var result = new ApplyModel.Result(plan, List.of(
                new ApplyModel.Step(firstPlan, firstPlan.actions().getFirst(), ApplyModel.StepStatus.COMPLETED, "completed"),
                new ApplyModel.Step(firstPlan, firstPlan.actions().getLast(), ApplyModel.StepStatus.FAILED, "source changed"),
                new ApplyModel.Step(secondPlan, secondPlan.actions().getFirst(), ApplyModel.StepStatus.PENDING, "not run")),
                Optional.empty(), List.of(), true);
        progress.set(result);
        app.render();
        assertEquals(1, app.selectedIndex());
        app.handleKeyEvent(KeyEvent.ofChar('k'));
        app.render();
        assertEquals(0, app.selectedIndex());

        var completed = result.steps().stream().map(step -> new ApplyModel.Step(step.relocation(), step.action(),
                ApplyModel.StepStatus.COMPLETED, "completed")).toList();
        progress.set(new ApplyModel.Result(plan, completed, Optional.empty(), List.of(), false));
        app.render();
        assertEquals(2, app.selectedIndex());
    }

    @Test
    void reviewsConfirmsAppliesAndReturnsToRefreshedStatus(@org.junit.jupiter.api.io.TempDir Path temporary) throws Exception {
        var root = temporary.toRealPath();
        var source = root.resolve("home/cache");
        var target = root.resolve("local/cache");
        var config = Files.writeString(root.resolve("config.yaml"), """
                homelight:
                  target-root: %s
                  relocations:
                    - source-path: %s
                      target-path: %s
                """.formatted(root, source, target));
        var app = new HomeLightApp(config, Screen.PLAN);

        app.handleKeyEvent(KeyEvent.ofKey(KeyCode.ENTER));
        assertEquals(Screen.APPLY, app.session().activeScreen());
        app.handleKeyEvent(KeyEvent.ofKey(KeyCode.ENTER));
        assertTrue(app.session().applyModel() instanceof io.github.bigswlittlesw.homelight.application.ApplyModel.Confirmation);
        assertFalse(Files.exists(source));
        app.handleKeyEvent(KeyEvent.ofChar('n'));
        assertEquals(Screen.PLAN, app.session().activeScreen());
        app.handleKeyEvent(KeyEvent.ofChar('a'));
        app.handleKeyEvent(KeyEvent.ofChar('y'));
        app.session().awaitExecution();
        assertTrue(Files.isSymbolicLink(source));
        assertTrue(app.session().applyModel() instanceof io.github.bigswlittlesw.homelight.application.ApplyModel.Result);
        app.handleKeyEvent(KeyEvent.ofKey(KeyCode.ENTER));
        assertEquals(Screen.STATUS, app.session().activeScreen());
        assertEquals(1, ((StatusModel.Configured) app.statusModel()).summary().inSync());
        app.handleKeyEvent(KeyEvent.ofChar('p'));
        assertTrue(app.session().isPlanReady());
        assertFalse(((PlanModel.Configured) app.planModel()).plan().actions().stream().anyMatch(ReconciliationAction::mutatesFilesystem));
        app.handleKeyEvent(KeyEvent.ofChar('a'));
        var unchanged = app.session().applyModel();
        app.handleKeyEvent(KeyEvent.ofChar('y'));
        org.junit.jupiter.api.Assertions.assertSame(unchanged, app.session().applyModel());
        assertFalse(app.session().isApplying());
        app.handleKeyEvent(KeyEvent.ofKey(KeyCode.ENTER));
        assertEquals(Screen.STATUS, app.session().activeScreen());
    }

    @Test
    void runningApplyConsumesQuitRefreshAndRepeatedConfirmation(@org.junit.jupiter.api.io.TempDir Path temporary) throws Exception {
        var root = temporary.toRealPath();
        var config = Files.writeString(root.resolve("config.yaml"), """
                homelight:
                  target-root: %s
                  relocations:
                    - source-path: %s
                      target-path: %s
                """.formatted(root, root.resolve("source"), root.resolve("target")));
        var app = new HomeLightApp(config, Screen.PLAN);
        app.handleKeyEvent(KeyEvent.ofChar('a'));
        var tasks = new java.util.ArrayList<Runnable>();
        app.session().confirmApply(tasks::add);
        var running = app.session().applyModel();
        for (char key : new char[] {'q', 'r', 'y', 'a', '1', '2'}) {
            assertEquals(dev.tamboui.toolkit.event.EventResult.HANDLED, app.handleKeyEvent(KeyEvent.ofChar(key)));
            assertEquals(Screen.APPLY, app.session().activeScreen());
            org.junit.jupiter.api.Assertions.assertSame(running, app.session().applyModel());
        }
        tasks.getFirst().run();
        assertTrue(Files.isSymbolicLink(root.resolve("source")));
    }

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
    void handlesConvergedFilterAndToggle() {
        var rel1 = new Relocation(Path.of("/source1"), Path.of("/target1"));
        var rel2 = new Relocation(Path.of("/source2"), Path.of("/target2"));
        var rel3 = new Relocation(Path.of("/source3"), Path.of("/target3"));

        // plan1 is CONFLICT, plan2 and plan3 are CONVERGED
        var conflict = new io.github.bigswlittlesw.homelight.reconcile.ReconciliationConflict(
                rel1.sourcePath(), "conflict", List.of(io.github.bigswlittlesw.homelight.reconcile.ReconciliationConflict.Resolution.RESOLVE_EXISTING_CONTENT));
        var plan1 = new RelocationPlan(rel1, RelocationOutcome.UNRESOLVED, List.of(), List.of(), Optional.of(conflict));
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

        // When there is an unresolved item, showInSync defaults to false
        assertFalse(app.showInSync());
        assertEquals(0, app.selectedIndex());

        // Moving down stays at 0 because only 1 active item is visible
        app.handleKeyEvent(KeyEvent.ofChar('j'));
        assertEquals(0, app.selectedIndex());

        // Press 'c' to toggle showInSync to true
        app.handleKeyEvent(KeyEvent.ofChar('c'));
        assertTrue(app.showInSync());

        // Now all 3 items are navigable
        app.handleKeyEvent(KeyEvent.ofChar('j'));
        assertEquals(1, app.selectedIndex());
        app.handleKeyEvent(KeyEvent.ofChar('j'));
        assertEquals(2, app.selectedIndex());

        // Press SPACE to toggle showInSync back to false
        app.handleKeyEvent(KeyEvent.ofChar(' '));
        assertFalse(app.showInSync());
        assertEquals(0, app.selectedIndex());
    }

    @Test
    void allInSyncDefaultsToShowInSync() {
        var rel1 = new Relocation(Path.of("/source1"), Path.of("/target1"));
        var plan1 = new RelocationPlan(rel1, RelocationOutcome.CONVERGED, List.of(new ReconciliationAction.NoOp(rel1.sourcePath())), List.of(), Optional.empty());
        var obs = new PathObservation(PathState.DIRECTORY, Optional.empty(), SymlinkTargetAvailability.NOT_A_SYMLINK, false);
        var item1 = new RelocationStatusItem(rel1, obs, obs, plan1, RelocationSourceState.DIRECTORY);

        var items = List.of(item1);
        var summary = StatusSummary.from(items);
        var configured = new StatusModel.Configured(Path.of("/config.yaml"), Path.of("/target"),
                new ReconciliationPlan(List.of(plan1), List.of()), items, summary);

        var mockWorkflow = new StatusWorkflow() {
            @Override
            public StatusModel loadStatus(Path configPath) {
                return configured;
            }
        };

        var app = new HomeLightApp(Path.of("/config.yaml"), mockWorkflow);
        assertTrue(app.showInSync());
        assertEquals(0, app.selectedIndex());
    }

    @Test
    void switchesScreensViaKeys() {
        var app = new HomeLightApp(Path.of("/nonexistent/config.yaml"));
        assertEquals(Screen.STATUS, app.session().activeScreen());

        app.handleKeyEvent(KeyEvent.ofChar('2'));
        assertEquals(Screen.PLAN, app.session().activeScreen());

        app.handleKeyEvent(KeyEvent.ofChar('3'));
        assertEquals(Screen.PLAN, app.session().activeScreen());

        app.handleKeyEvent(KeyEvent.ofChar('1'));
        assertEquals(Screen.STATUS, app.session().activeScreen());

        app.handleKeyEvent(KeyEvent.ofChar('p'));
        assertEquals(Screen.PLAN, app.session().activeScreen());

        app.handleKeyEvent(KeyEvent.ofChar('s'));
        assertEquals(Screen.STATUS, app.session().activeScreen());
    }

    @Test
    void resolvesConflictOnPlanScreenWithSpaceOrEnter() throws Exception {
        var root = Files.createTempDirectory("homelight-app-test").toRealPath();
        var source = root.resolve("home/cache");
        var target = Files.createDirectories(root.resolve("local/cache"));
        Files.writeString(target.resolve("file.txt"), "target content");

        var config = Files.createTempFile("homelight", ".yaml");
        Files.writeString(config, """
                homelight:
                  target-root: %s
                  relocations:
                    - source-path: %s
                      target-path: %s
                """.formatted(root, source, target));

        var app = new HomeLightApp(config, Screen.PLAN);
        assertEquals(Screen.PLAN, app.session().activeScreen());
        assertTrue(app.session().hasConflicts());
        assertEquals(PaneFocus.MASTER, app.paneFocus());

        // Press TAB to focus detail pane
        app.handleKeyEvent(KeyEvent.ofKey(KeyCode.TAB));
        assertEquals(PaneFocus.DETAIL, app.paneFocus());
        assertEquals(0, app.detailSelectedIndex());

        app.handleKeyEvent(KeyEvent.ofChar('3'));
        assertEquals(Screen.PLAN, app.session().activeScreen());
        assertEquals(PaneFocus.DETAIL, app.paneFocus());

        // Press SPACE in detail pane to resolve highlighted decision
        app.handleKeyEvent(KeyEvent.ofChar(' '));
        assertFalse(app.session().hasConflicts());
        assertTrue(app.session().isPlanReady());

        app.handleKeyEvent(KeyEvent.ofChar('3'));
        assertEquals(Screen.APPLY, app.session().activeScreen());
        assertEquals(PaneFocus.MASTER, app.paneFocus());
        assertTrue(app.session().applyModel() instanceof ApplyModel.Confirmation);
        assertFalse(Files.exists(source));
    }

    @Test
    void handlesMasterDetailFocusSwitchingAndDetailCursorMovement() throws Exception {
        var root = Files.createTempDirectory("homelight-app-focus-test").toRealPath();
        var source1 = Files.createDirectories(root.resolve("home/cache1"));
        Files.writeString(source1.resolve("file1.txt"), "source content 1");
        var target1 = Files.createDirectories(root.resolve("local/cache1"));
        Files.writeString(target1.resolve("file1.txt"), "target content 1");

        var source2 = Files.createDirectories(root.resolve("home/cache2"));
        Files.writeString(source2.resolve("file2.txt"), "source content 2");
        var target2 = Files.createDirectories(root.resolve("local/cache2"));
        Files.writeString(target2.resolve("file2.txt"), "target content 2");

        var config = Files.createTempFile("homelight", ".yaml");
        Files.writeString(config, """
                homelight:
                  target-root: %s
                  relocations:
                    - source-path: %s
                      target-path: %s
                    - source-path: %s
                      target-path: %s
                """.formatted(root, source1, target1, source2, target2));

        var app = new HomeLightApp(config, Screen.PLAN);
        assertEquals(PaneFocus.MASTER, app.paneFocus());
        assertEquals(0, app.selectedIndex());

        // Press 'l' (vim right) to move focus to DETAIL pane
        app.handleKeyEvent(KeyEvent.ofChar('l'));
        assertEquals(PaneFocus.DETAIL, app.paneFocus());
        assertEquals(0, app.detailSelectedIndex());

        // Press 'j' to move down resolution options
        app.handleKeyEvent(KeyEvent.ofChar('j'));
        assertEquals(1, app.detailSelectedIndex());

        // Press 'j' again
        app.handleKeyEvent(KeyEvent.ofChar('j'));
        assertEquals(2, app.detailSelectedIndex());

        // Press 'k' to move back up
        app.handleKeyEvent(KeyEvent.ofChar('k'));
        assertEquals(1, app.detailSelectedIndex());

        // Press 'h' (vim left) to return to MASTER pane
        app.handleKeyEvent(KeyEvent.ofChar('h'));
        assertEquals(PaneFocus.MASTER, app.paneFocus());

        // In MASTER pane, move down to second relocation
        app.handleKeyEvent(KeyEvent.ofChar('j'));
        assertEquals(1, app.selectedIndex());

        // Focus DETAIL pane with RIGHT arrow
        app.handleKeyEvent(KeyEvent.ofKey(KeyCode.RIGHT));
        assertEquals(PaneFocus.DETAIL, app.paneFocus());

        // Select resolution (Enter) on second item
        app.handleKeyEvent(KeyEvent.ofKey(KeyCode.ENTER));
        assertEquals(PaneFocus.DETAIL, app.paneFocus());

        // Return to master with LEFT arrow
        app.handleKeyEvent(KeyEvent.ofKey(KeyCode.LEFT));
        assertEquals(PaneFocus.MASTER, app.paneFocus());

        // Enter detail pane using raw TAB character '\t'
        app.handleKeyEvent(KeyEvent.ofChar('\t'));
        assertEquals(PaneFocus.DETAIL, app.paneFocus());

        // Return to master using TAB in detail pane
        app.handleKeyEvent(KeyEvent.ofChar('\t'));
        assertEquals(PaneFocus.MASTER, app.paneFocus());
    }

    @Test
    void cannotEnterDetailPaneWhenNoResolutionsAvailable() {
        var rel1 = new Relocation(Path.of("/source1"), Path.of("/target1"));
        var plan1 = new RelocationPlan(rel1, RelocationOutcome.CONVERGED, List.of(new ReconciliationAction.NoOp(rel1.sourcePath())), List.of(), Optional.empty());
        var obs = new PathObservation(PathState.DIRECTORY, Optional.empty(), SymlinkTargetAvailability.NOT_A_SYMLINK, false);
        var item1 = new PlanRelocationItem(rel1, obs, obs, plan1, RelocationSourceState.DIRECTORY, List.of());

        var items = List.of(item1);
        var summary = PlanSummary.from(items);
        var configured = new PlanModel.Configured(Path.of("/config.yaml"), Path.of("/target"),
                new ReconciliationPlan(List.of(plan1), List.of()), items, summary);

        var mockWorkflow = new PlanWorkflow() {
            @Override
            public PlanModel loadPlan(Path configPath, java.util.Map<String, String> overrides) {
                return configured;
            }
        };

        var app = new HomeLightApp(Path.of("/config.yaml"), mockWorkflow);
        assertEquals(PaneFocus.MASTER, app.paneFocus());

        // Pressing Tab or Right arrow remains in MASTER pane because item has no resolutions
        app.handleKeyEvent(KeyEvent.ofKey(KeyCode.TAB));
        assertEquals(PaneFocus.MASTER, app.paneFocus());

        app.handleKeyEvent(KeyEvent.ofKey(KeyCode.RIGHT));
        assertEquals(PaneFocus.MASTER, app.paneFocus());
    }

    @Test
    void handlesDecisionSelectionAndPreservesSelectionOnResolvedItem() throws Exception {
        var root = Files.createTempDirectory("homelight-app-select-test").toRealPath();
        var source1 = Files.createDirectories(root.resolve("home/cache1"));
        Files.writeString(source1.resolve("file1.txt"), "source content 1");
        var target1 = Files.createDirectories(root.resolve("local/cache1"));
        Files.writeString(target1.resolve("file1.txt"), "target content 1");

        var source2 = Files.createDirectories(root.resolve("home/cache2"));
        Files.writeString(source2.resolve("file2.txt"), "source content 2");
        var target2 = Files.createDirectories(root.resolve("local/cache2"));
        Files.writeString(target2.resolve("file2.txt"), "target content 2");

        var config = Files.createTempFile("homelight", ".yaml");
        Files.writeString(config, """
                homelight:
                  target-root: %s
                  relocations:
                    - source-path: %s
                      target-path: %s
                    - source-path: %s
                      target-path: %s
                """.formatted(root, source1, target1, source2, target2));

        var app = new HomeLightApp(config, Screen.PLAN);
        assertEquals(0, app.selectedIndex());
        assertEquals(PaneFocus.MASTER, app.paneFocus());

        // Focus detail pane with 'l'
        app.handleKeyEvent(KeyEvent.ofChar('l'));
        assertEquals(PaneFocus.DETAIL, app.paneFocus());
        assertEquals(0, app.detailSelectedIndex()); // 0 is ADOPT_AND_DISCARD_SOURCE

        // Move to choice 1: LEAVE_UNCHANGED (Skipped)
        app.handleKeyEvent(KeyEvent.ofChar('j'));
        assertEquals(1, app.detailSelectedIndex());

        // Select it (Space)
        app.handleKeyEvent(KeyEvent.ofChar(' '));
        assertEquals(PaneFocus.DETAIL, app.paneFocus());

        // The item must stay selected and visible as SKIPPED
        if (app.planModel() instanceof PlanModel.Configured configured) {
            var visible = PlanView.visibleItems(configured, app.showInSync());
            assertTrue(visible.size() >= 2);
            var currentItem = visible.get(app.selectedIndex());
            assertEquals(source1, currentItem.relocation().sourcePath());
            assertEquals(PlanBadge.SKIPPED, currentItem.badge());
            assertEquals(1, app.detailSelectedIndex());
        }

        // Return to master list with 'h'
        app.handleKeyEvent(KeyEvent.ofChar('h'));
        assertEquals(PaneFocus.MASTER, app.paneFocus());
        assertEquals(1, app.selectedIndex());

        // Move up to unresolved conflict item (source2 is at index 0)
        app.handleKeyEvent(KeyEvent.ofChar('k'));
        assertEquals(0, app.selectedIndex());

        // Move into detail pane with 'l'
        app.handleKeyEvent(KeyEvent.ofChar('l'));
        assertEquals(PaneFocus.DETAIL, app.paneFocus());

        // Select choice 2: DISCARD_BOTH (index 2)
        app.handleKeyEvent(KeyEvent.ofChar('j'));
        app.handleKeyEvent(KeyEvent.ofChar('j'));
        assertEquals(2, app.detailSelectedIndex());
        app.handleKeyEvent(KeyEvent.ofKey(KeyCode.ENTER));

        // The second item must stay selected and have DISCARD badge
        if (app.planModel() instanceof PlanModel.Configured configured) {
            var visible = PlanView.visibleItems(configured, app.showInSync());
            var currentItem = visible.get(app.selectedIndex());
            assertEquals(source2, currentItem.relocation().sourcePath());
            assertEquals(PlanBadge.DISCARD, currentItem.badge());
        }
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
