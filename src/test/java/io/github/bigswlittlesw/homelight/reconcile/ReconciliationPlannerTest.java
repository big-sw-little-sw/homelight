package io.github.bigswlittlesw.homelight.reconcile;

import io.github.bigswlittlesw.homelight.config.Relocation;
import io.github.bigswlittlesw.homelight.config.WhenAdoptingTarget;
import io.github.bigswlittlesw.homelight.config.WhenOnlyTargetExists;
import io.github.bigswlittlesw.homelight.config.WhenSourceAndTargetDirectoriesExist;
import io.github.bigswlittlesw.homelight.fs.PathInspector;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconciliationPlannerTest {
    @Test
    void sourceDirectoryWithAbsentTargetRequiresStagedPublication() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var source = Files.createDirectories(root.resolve("home/cache"));

        var plan = plan(new Relocation(source, root.resolve("local/cache")));

        assertEquals(RelocationOutcome.CONVERGED, plan.relocations().getFirst().outcome());
        assertTrue(plan.actions().stream().anyMatch(ReconciliationAction.MigrateDirectoryForPublication.class::isInstance));
        assertTrue(plan.actions().stream().anyMatch(ReconciliationAction.ReplaceDirectoryWithSymlink.class::isInstance));
        assertFalse(plan.hasBlockedActions());
    }

    @Test
    void bothDirectoriesRequireADecisionByDefault() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var source = Files.createDirectories(root.resolve("home/cache"));
        var target = Files.createDirectories(root.resolve("local/cache"));

        var plan = plan(new Relocation(source, target));

        assertEquals(RelocationOutcome.UNRESOLVED, plan.relocations().getFirst().outcome());
        assertTrue(plan.hasConflicts());
    }

    @Test
    void leaveUnchangedIsSuccessfulButNotConverged() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var source = Files.createDirectories(root.resolve("home/cache"));
        var target = Files.createDirectories(root.resolve("local/cache"));

        var plan = plan(relocation(source, target, WhenSourceAndTargetDirectoriesExist.LEAVE_UNCHANGED,
                null, null, null));

        assertEquals(RelocationOutcome.UNCHANGED, plan.relocations().getFirst().outcome());
        assertEquals("leave-unchanged", plan.actions().getFirst().type());
    }

    @Test
    void onlyTargetRequiresAdoptTargetDecision() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var source = root.resolve("home/cache");
        var target = Files.createDirectories(root.resolve("local/cache"));

        var unresolved = plan(new Relocation(source, target));
        var adopted = plan(relocation(source, target, null, WhenOnlyTargetExists.ADOPT_TARGET, null, null));

        assertTrue(unresolved.hasConflicts());
        assertEquals(RelocationOutcome.CONVERGED, adopted.relocations().getFirst().outcome());
    }

    @Test
    void archiveSourceUsesADeterministicPath() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var source = Files.createDirectories(root.resolve("home/cache"));
        var target = Files.createDirectories(root.resolve("local/cache"));
        var archiveRoot = root.resolve("archive");
        var relocation = relocation(source, target, WhenSourceAndTargetDirectoriesExist.ADOPT,
                null, WhenAdoptingTarget.ARCHIVE_SOURCE, archiveRoot);

        var archive = (ReconciliationAction.ArchiveDirectory) plan(relocation).actions().stream()
                .filter(ReconciliationAction.ArchiveDirectory.class::isInstance).findFirst().orElseThrow();
        assertEquals(archiveRoot.resolve(sourceRelativeToRoot(source)), archive.target());

        Files.createDirectories(archive.target());
        assertTrue(plan(relocation).hasBlockedActions());

    }

    @Test
    void refusesFilesAndTargetSymlinks() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var fileSource = Files.writeString(root.resolve("source-file"), "value");
        var filePlan = plan(new Relocation(fileSource, root.resolve("target")));

        var directorySource = Files.createDirectories(root.resolve("home/cache"));
        var target = root.resolve("local/cache");
        Files.createDirectories(target.getParent());
        Files.createSymbolicLink(target, Files.createDirectories(root.resolve("other")));
        var symlinkPlan = plan(new Relocation(directorySource, target));

        assertTrue(filePlan.hasBlockedActions());
        assertTrue(symlinkPlan.hasBlockedActions());
    }

    @Test
    void repairsBrokenLinksOnlyWhenTheTargetIsARealDirectory() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var source = root.resolve("home/cache");
        Files.createDirectories(source.getParent());
        Files.createSymbolicLink(source, root.resolve("missing"));

        var unavailable = plan(new Relocation(source, root.resolve("local/cache")));
        var target = Files.createDirectories(root.resolve("local/cache"));
        var repair = plan(new Relocation(source, target));

        assertTrue(unavailable.hasBlockedActions());
        assertEquals(RelocationOutcome.CONVERGED, repair.relocations().getFirst().outcome());
        assertTrue(repair.actions().stream().anyMatch(ReconciliationAction.ReplaceSymlink.class::isInstance));
    }

    @Test
    void correctLinksAreTheOnlyNoOpState() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var target = Files.createDirectories(root.resolve("local/cache"));
        var source = root.resolve("home/cache");
        Files.createDirectories(source.getParent());
        Files.createSymbolicLink(source, target);

        var plan = plan(new Relocation(source, target));

        assertEquals(RelocationOutcome.CONVERGED, plan.relocations().getFirst().outcome());
        assertEquals("no-op", plan.actions().getFirst().type());
    }

    @Test
    void wrongLiveSourceLinksRequireARepairPlan() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var target = Files.createDirectories(root.resolve("local/cache"));
        var source = root.resolve("home/cache");
        Files.createDirectories(source.getParent());
        Files.createSymbolicLink(source, Files.createDirectories(root.resolve("other")));

        var plan = plan(new Relocation(source, target));

        assertEquals(RelocationOutcome.UNRESOLVED, plan.relocations().getFirst().outcome());
        assertTrue(plan.hasConflicts());
    }

    @Test
    void blocksInaccessibleSourcesAndOverlappingRelocations() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var relocation = new Relocation(root.resolve("home/cache"), root.resolve("local/cache"));
        var inaccessible = new RelocationState(relocation,
                new io.github.bigswlittlesw.homelight.fs.PathObservation(
                        io.github.bigswlittlesw.homelight.fs.PathState.INACCESSIBLE, Optional.empty(), false),
                new io.github.bigswlittlesw.homelight.fs.PathObservation(
                        io.github.bigswlittlesw.homelight.fs.PathState.ABSENT, Optional.empty(), false));
        var inaccessiblePlan = new ReconciliationPlanner().plan(List.of(inaccessible));

        var parent = new Relocation(root.resolve("home/parent"), root.resolve("local/parent"));
        var child = new Relocation(root.resolve("home/parent/child"), root.resolve("local/child"));
        var overlapPlan = new ReconciliationPlanner().plan(List.of(state(parent), state(child)));

        assertTrue(inaccessiblePlan.hasBlockedActions());
        assertTrue(overlapPlan.hasBlockedActions());
        assertEquals("INVALID_RELOCATION", overlapPlan.diagnostics().getFirst().code());
    }

    private static Relocation relocation(Path source, Path target, WhenSourceAndTargetDirectoriesExist directories,
            WhenOnlyTargetExists onlyTarget, WhenAdoptingTarget adoption, Path archiveRoot) {
        return new Relocation(source, target, Optional.ofNullable(directories), Optional.ofNullable(onlyTarget),
                Optional.ofNullable(adoption), Optional.ofNullable(archiveRoot));
    }

    private static Path sourceRelativeToRoot(Path source) {
        var absolute = source.toAbsolutePath();
        return absolute.getRoot().relativize(absolute);
    }

    private static ReconciliationPlan plan(Relocation relocation) {
        return new ReconciliationPlanner().plan(List.of(state(relocation)));
    }

    private static RelocationState state(Relocation relocation) {
        var inspector = new PathInspector();
        var archive = relocation.sourceArchiveRoot().map(root -> {
            var source = relocation.sourcePath().toAbsolutePath();
            var path = root.resolve(source.getRoot().relativize(source));
            return new RelocationState.ArchiveDestination(path, inspector.inspect(path));
        });
        return new RelocationState(relocation, inspector.inspect(relocation.sourcePath()),
                inspector.inspect(relocation.targetPath()), archive);
    }
}
