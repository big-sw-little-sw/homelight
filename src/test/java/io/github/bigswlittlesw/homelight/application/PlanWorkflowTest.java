package io.github.bigswlittlesw.homelight.application;

import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction;
import io.github.bigswlittlesw.homelight.reconcile.RelocationOutcome;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanWorkflowTest {

    @Test
    void returnsUnconfiguredWhenDefaultConfigMissing() {
        var workflow = new PlanWorkflow();
        var model = workflow.loadPlan(Path.of(System.getProperty("user.home"), ".homelight.yaml"));

        if (!Files.exists(Path.of(System.getProperty("user.home"), ".homelight.yaml"))) {
            assertInstanceOf(PlanModel.Unconfigured.class, model);
        }
    }

    @Test
    void returnsInvalidWhenConfigFileDoesNotExist() {
        var workflow = new PlanWorkflow();
        var model = workflow.loadPlan(Path.of("/nonexistent/path/homelight.yaml"));

        assertInstanceOf(PlanModel.Invalid.class, model);
        assertTrue(((PlanModel.Invalid) model).message().contains("does not exist"));
    }

    @Test
    void plansStagedPublicationForExistingSource() throws Exception {
        var root = Files.createTempDirectory("homelight-plan-test").toRealPath();
        var source = Files.createDirectories(root.resolve("home/cache"));
        var target = root.resolve("local/cache");
        Files.writeString(source.resolve("file.txt"), "hello");

        var config = Files.createTempFile("homelight", ".yaml");
        Files.writeString(config, """
                homelight:
                  target-root: %s
                  relocations:
                    - source-path: %s
                      target-path: %s
                """.formatted(root, source, target));

        var workflow = new PlanWorkflow();
        var model = workflow.loadPlan(config);

        assertInstanceOf(PlanModel.Configured.class, model);
        var configured = (PlanModel.Configured) model;

        assertEquals(1, configured.items().size());
        var item = configured.items().getFirst();

        assertEquals(PlanBadge.MIGRATE, item.badge());
        assertEquals(RelocationOutcome.CONVERGED, item.plan().outcome());
        assertTrue(item.plan().actions().stream().anyMatch(ReconciliationAction.MigrateDirectoryForPublication.class::isInstance));
        assertTrue(item.plan().actions().stream().anyMatch(ReconciliationAction.ReplaceDirectoryWithSymlink.class::isInstance));
        assertTrue(item.hasDestructiveActions());
        assertFalse(item.hasConflict());

        // Filesystem is completely unmodified
        assertTrue(Files.isDirectory(source));
        assertFalse(Files.isSymbolicLink(source));
        assertFalse(Files.exists(target));
    }

    @Test
    void detectsConflictWhenOnlyTargetExistsAndProvidesTypedResolutions() throws Exception {
        var root = Files.createTempDirectory("homelight-plan-test").toRealPath();
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

        var workflow = new PlanWorkflow();
        var model = workflow.loadPlan(config);

        assertInstanceOf(PlanModel.Configured.class, model);
        var configured = (PlanModel.Configured) model;

        var item = configured.items().getFirst();
        assertEquals(PlanBadge.CONFLICT, item.badge());
        assertTrue(item.hasConflict());
        assertEquals(1, item.availableResolutions().size());
        assertEquals(DecisionChoice.ADOPT_TARGET, item.availableResolutions().getFirst());

        // Test resolving through HomeLightSession
        var session = new HomeLightSession(config, Screen.PLAN);
        assertTrue(session.hasConflicts());
        assertFalse(session.isPlanReady());

        session.resolveDecision(item.relocation(), DecisionChoice.ADOPT_TARGET);

        assertFalse(session.hasConflicts());
        assertTrue(session.isPlanReady());
        assertInstanceOf(PlanModel.Configured.class, session.planModel());
        var resolvedModel = (PlanModel.Configured) session.planModel();
        var resolvedItem = resolvedModel.items().getFirst();

        assertEquals(PlanBadge.LINK, resolvedItem.badge());
        assertEquals(RelocationOutcome.CONVERGED, resolvedItem.plan().outcome());
        assertTrue(resolvedItem.plan().actions().stream().anyMatch(ReconciliationAction.CreateSymlink.class::isInstance));

        // Filesystem still untouched
        assertFalse(Files.exists(source));
        assertTrue(Files.isDirectory(target));
    }

    @Test
    void detectsConflictWhenBothDirectoriesExistAndProvidesAllResolutions() throws Exception {
        var root = Files.createTempDirectory("homelight-plan-test").toRealPath();
        var source = Files.createDirectories(root.resolve("home/cache"));
        var target = Files.createDirectories(root.resolve("local/cache"));
        var archiveRoot = root.resolve("local/archive");

        var config = Files.createTempFile("homelight", ".yaml");
        Files.writeString(config, """
                homelight:
                  target-root: %s
                  relocations:
                    - source-path: %s
                      target-path: %s
                      source-archive-root: %s
                """.formatted(root, source, target, archiveRoot));

        var session = new HomeLightSession(config, Screen.PLAN);
        assertTrue(session.hasConflicts());

        var configured = (PlanModel.Configured) session.planModel();
        var item = configured.items().getFirst();
        assertEquals(PlanBadge.CONFLICT, item.badge());

        var resolutions = item.availableResolutions();
        assertEquals(4, resolutions.size());
        assertTrue(resolutions.contains(DecisionChoice.ADOPT_AND_DISCARD_SOURCE));
        assertTrue(resolutions.contains(DecisionChoice.ADOPT_AND_ARCHIVE_SOURCE));
        assertTrue(resolutions.contains(DecisionChoice.LEAVE_UNCHANGED));
        assertTrue(resolutions.contains(DecisionChoice.DISCARD_BOTH));

        // Resolve with ADOPT_AND_ARCHIVE_SOURCE
        session.resolveDecision(item.relocation(), DecisionChoice.ADOPT_AND_ARCHIVE_SOURCE);
        assertTrue(session.isPlanReady());

        var planModel = (PlanModel.Configured) session.planModel();
        var resolvedItem = planModel.items().getFirst();
        assertEquals(PlanBadge.BACKUP, resolvedItem.badge());
        assertTrue(resolvedItem.plan().actions().stream().anyMatch(ReconciliationAction.ArchiveDirectory.class::isInstance));
    }

    @Test
    void resolvesWithDiscardBothProducesDiscardBadgeAndDestructiveWarning() throws Exception {
        var root = Files.createTempDirectory("homelight-plan-test").toRealPath();
        var source = Files.createDirectories(root.resolve("home/cache"));
        var target = Files.createDirectories(root.resolve("local/cache"));

        var config = Files.createTempFile("homelight", ".yaml");
        Files.writeString(config, """
                homelight:
                  target-root: %s
                  relocations:
                    - source-path: %s
                      target-path: %s
                """.formatted(root, source, target));

        var session = new HomeLightSession(config, Screen.PLAN);
        var item = ((PlanModel.Configured) session.planModel()).items().getFirst();

        session.resolveDecision(item.relocation(), DecisionChoice.DISCARD_BOTH);
        var planModel = (PlanModel.Configured) session.planModel();
        var resolvedItem = planModel.items().getFirst();

        assertEquals(PlanBadge.DISCARD, resolvedItem.badge());
        assertTrue(resolvedItem.hasDestructiveActions());
        assertFalse(resolvedItem.plan().diagnostics().isEmpty());
    }

    @Test
    void handlesConvergedAndNoOpRelocation() throws Exception {
        var root = Files.createTempDirectory("homelight-plan-test").toRealPath();
        var target = Files.createDirectories(root.resolve("local/cache"));
        var source = root.resolve("home/cache");
        Files.createDirectories(source.getParent());
        Files.createSymbolicLink(source, target);

        var config = Files.createTempFile("homelight", ".yaml");
        Files.writeString(config, """
                homelight:
                  target-root: %s
                  relocations:
                    - source-path: %s
                      target-path: %s
                """.formatted(root, source, target));

        var workflow = new PlanWorkflow();
        var model = workflow.loadPlan(config);

        assertInstanceOf(PlanModel.Configured.class, model);
        var configured = (PlanModel.Configured) model;
        var item = configured.items().getFirst();

        assertEquals(PlanBadge.IN_SYNC, item.badge());
        assertEquals(RelocationOutcome.CONVERGED, item.plan().outcome());
        assertEquals(1, configured.summary().inSync());
        assertEquals(0, configured.summary().destructive());
    }
}
