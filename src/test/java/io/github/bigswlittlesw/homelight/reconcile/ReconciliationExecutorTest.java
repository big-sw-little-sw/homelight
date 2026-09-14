package io.github.bigswlittlesw.homelight.reconcile;

import io.github.bigswlittlesw.homelight.config.Relocation;
import io.github.bigswlittlesw.homelight.config.WhenAdoptingTarget;
import io.github.bigswlittlesw.homelight.config.WhenSourceAndTargetDirectoriesExist;
import io.github.bigswlittlesw.homelight.fs.PathInspector;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconciliationExecutorTest {
    @Test
    void rejectsUnresolvedPlans() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var source = Files.createDirectories(root.resolve("source"));
        var target = Files.createDirectories(root.resolve("target"));

        assertThrows(IllegalArgumentException.class, () -> new ReconciliationExecutor().execute(plan(new Relocation(source, target))));
    }

    @Test
    void createsAnAbsentSourceLinkAndTargetDirectory() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var source = root.resolve("source");
        var target = root.resolve("target");

        var result = new ReconciliationExecutor().execute(plan(new Relocation(source, target)));

        assertTrue(result.succeeded());
        assertTrue(Files.isSymbolicLink(source));
    }

    @Test
    void archivesTheSourceBeforeLinkingToAnAdoptedTarget() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var source = Files.createDirectories(root.resolve("home/cache"));
        Files.writeString(source.resolve("entry"), "source");
        var target = Files.createDirectories(root.resolve("local/cache"));
        var archiveRoot = root.resolve("archive");
        var relocation = new Relocation(source, target,
                java.util.Optional.of(WhenSourceAndTargetDirectoriesExist.ADOPT), java.util.Optional.empty(),
                java.util.Optional.of(WhenAdoptingTarget.ARCHIVE_SOURCE), java.util.Optional.of(archiveRoot));

        var result = new ReconciliationExecutor().execute(plan(relocation));

        assertTrue(result.succeeded());
        assertTrue(Files.isSymbolicLink(source));
        assertTrue(Files.exists(archiveRoot.resolve(source.toAbsolutePath().getRoot().relativize(source.toAbsolutePath()))
                .resolve("entry")));
    }

    @Test
    void reportsFailedRecoveryWhenPublicationSucceedsButSourceReplacementCannotStart() throws Exception {
        var root = Files.createTempDirectory("homelight").toRealPath();
        var sourceParent = Files.createDirectories(root.resolve("home"));
        var source = Files.createDirectories(sourceParent.resolve("cache"));
        Files.writeString(source.resolve("entry"), "source");
        var target = root.resolve("local/cache");
        var originalPermissions = Files.getPosixFilePermissions(sourceParent);
        var result = new ReconciliationExecutor.ExecutionResult(List.of());

        try {
            result = new ReconciliationExecutor().execute(plan(new Relocation(source, target)),
                    new ReconciliationExecutor.ProgressListener() {
                        @Override
                        public void finished(RelocationPlan relocation, ReconciliationExecutor.ActionExecution action) {
                            if (action.action() instanceof ReconciliationAction.MigrateDirectoryForPublication) {
                                try {
                                    Files.setPosixFilePermissions(sourceParent, Set.of(
                                            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
                                } catch (java.io.IOException exception) {
                                    throw new AssertionError(exception);
                                }
                            }
                        }
                    });
        } finally {
            Files.setPosixFilePermissions(sourceParent, originalPermissions);
        }

        assertEquals(ReconciliationExecutor.ExecutionOutcome.FAILED_RECOVERY, result.relocations().getFirst().outcome());
        assertTrue(Files.isDirectory(source));
        assertTrue(Files.isDirectory(target));
        try (var stagingEntries = Files.list(target.getParent().resolve(".homelight-staging"))) {
            assertTrue(stagingEntries.findAny().isEmpty());
        }
        assertTrue(plan(new Relocation(source, target)).hasConflicts());
    }

    @Test
    void reportsAnUnresolvedResultWhenPlannedStateIsStale() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var source = root.resolve("source");
        var target = root.resolve("target");
        var relocation = new Relocation(source, target);
        var plan = plan(relocation);
        Files.createDirectories(target);

        var result = new ReconciliationExecutor().execute(plan);

        assertEquals(ReconciliationExecutor.ExecutionOutcome.UNRESOLVED, result.relocations().getFirst().outcome());
        assertTrue(Files.isDirectory(target));
        assertTrue(Files.notExists(source));
    }

    private static ReconciliationPlan plan(Relocation relocation) {
        var inspector = new PathInspector();
        var archive = relocation.sourceArchiveRoot().map(root -> {
            var source = relocation.sourcePath().toAbsolutePath();
            var path = root.resolve(source.getRoot().relativize(source));
            return new RelocationState.ArchiveDestination(path, inspector.inspect(path));
        });
        return new ReconciliationPlanner().plan(List.of(new RelocationState(relocation,
                inspector.inspect(relocation.sourcePath()), inspector.inspect(relocation.targetPath()), archive)));
    }

    @Test
    void reportsTypedDriftAtAnActionBoundaryAfterSuccessfulPreflight() throws Exception {
        var root = Files.createTempDirectory("homelight").toRealPath();
        var source = root.resolve("home/cache");
        var target = root.resolve("local/cache");
        var plan = plan(new Relocation(source, target));
        var executor = new ReconciliationExecutor();
        assertTrue(executor.preflight(plan).isEmpty());

        var result = executor.execute(plan, new ReconciliationExecutor.ProgressListener() {
            @Override
            public void finished(RelocationPlan relocation, ReconciliationExecutor.ActionExecution action) {
                if (action.action() instanceof ReconciliationAction.EnsureDirectory && action.action().path().equals(target.getParent())) {
                    try {
                        Files.createDirectory(target);
                    } catch (java.io.IOException exception) {
                        throw new AssertionError(exception);
                    }
                }
            }
        });

        var actions = result.relocations().getFirst().actions();
        assertEquals(ReconciliationExecutor.ActionStatus.COMPLETED, actions.get(0).status());
        assertEquals(ReconciliationExecutor.ActionStatus.FAILED, actions.get(1).status());
        assertTrue(actions.get(1).stateDrift());
        assertEquals(ReconciliationExecutor.ActionStatus.PENDING, actions.get(2).status());
        assertTrue(Files.notExists(source));
    }
}
