package io.github.bigswlittlesw.homelight.reconcile;

import io.github.bigswlittlesw.homelight.config.Relocation;
import io.github.bigswlittlesw.homelight.fs.PathState;
import io.github.bigswlittlesw.homelight.fs.PathInspector;
import io.github.bigswlittlesw.homelight.fs.PathObservation;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconciliationPlannerTest {
    @Test
    void createsDestinationAndLinkWhenBothPathsAreAbsent() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var relocation = new Relocation(root.resolve("home/cache"), root.resolve("local/cache"));

        var plan = plan(relocation);

        assertEquals(List.of(
                new ReconciliationAction.EnsureDirectory(relocation.targetPath().getParent()),
                new ReconciliationAction.CreateDirectory(relocation.targetPath()),
                new ReconciliationAction.EnsureDirectory(relocation.sourcePath().getParent()),
                new ReconciliationAction.CreateSymlink(relocation.sourcePath(), relocation.targetPath())),
                plan.actions());
    }

    @Test
    void movesAnExistingSourceIntoAnAbsentDestination() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var source = Files.createDirectories(root.resolve("home/cache"));
        Files.writeString(source.resolve("entry"), "value");
        var relocation = new Relocation(source, root.resolve("local/cache"));

        var plan = plan(relocation);

        assertEquals(List.of(
                new ReconciliationAction.EnsureDirectory(relocation.targetPath().getParent()),
                new ReconciliationAction.Move(relocation.sourcePath(), relocation.targetPath()),
                new ReconciliationAction.CreateSymlink(relocation.sourcePath(), relocation.targetPath())),
                plan.actions());
    }

    @Test
    void correctLinksAreNoOps() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var target = Files.createDirectories(root.resolve("local/cache"));
        var source = root.resolve("home/cache");
        Files.createDirectories(source.getParent());
        Files.createSymbolicLink(source, target);

        var plan = plan(new Relocation(source, target));

        assertEquals(List.of(new ReconciliationAction.NoOp(source)), plan.actions());
    }

    @Test
    void conflictsWhenCorrectSourceLinkTargetsASymlink() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var realTarget = Files.createDirectories(root.resolve("real-local/cache"));
        var target = root.resolve("local/cache");
        Files.createDirectories(target.getParent());
        Files.createSymbolicLink(target, realTarget);
        var source = root.resolve("home/cache");
        Files.createDirectories(source.getParent());
        Files.createSymbolicLink(source, target);

        var plan = plan(new Relocation(source, target));

        var conflict = plan.relocations().getFirst().conflict().orElseThrow();
        assertEquals(target, conflict.path());
    }

    @Test
    void conflictsWhenExistingSourceWouldMoveToASymlinkTarget() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var source = Files.createDirectories(root.resolve("home/cache"));
        var target = root.resolve("local/cache");
        Files.createDirectories(target.getParent());
        Files.createSymbolicLink(target, Files.createDirectories(root.resolve("other")));

        var plan = plan(new Relocation(source, target));

        assertEquals(target, plan.relocations().getFirst().conflict().orElseThrow().path());
    }

    @Test
    void replacesWrongLinksWhenDestinationIsAvailable() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var target = Files.createDirectories(root.resolve("local/cache"));
        var source = root.resolve("home/cache");
        Files.createDirectories(source.getParent());
        Files.createSymbolicLink(source, root.resolve("other"));

        var plan = plan(new Relocation(source, target));

        assertEquals(List.of(new ReconciliationAction.ReplaceSymlink(source, target,
                root.resolve("other"), PathState.DIRECTORY)), plan.actions());
    }

    @Test
    void conflictsWhenBothSourceAndDestinationContainDirectories() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var source = Files.createDirectories(root.resolve("home/cache"));
        Files.writeString(source.resolve("source-entry"), "source");
        var target = Files.createDirectories(root.resolve("local/cache"));
        Files.writeString(target.resolve("target-entry"), "target");

        var plan = plan(new Relocation(source, target));

        var conflict = plan.relocations().getFirst().conflict().orElseThrow();
        assertEquals(source, conflict.path());
        assertTrue(plan.hasConflicts());
    }

    @Test
    void blocksFileSourcesUntilFileRelocationIsSupported() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var source = Files.createDirectories(root.resolve("home")).resolve("cache");
        Files.writeString(source, "value");
        var relocation = new Relocation(source, root.resolve("local/cache"));

        var plan = plan(relocation);

        var blocked = assertInstanceOf(ReconciliationAction.Blocked.class, plan.actions().getFirst());
        assertEquals(source, blocked.path());
        assertTrue(blocked.reason().contains("require directories"));
    }

    @Test
    void conflictsBeforeAdoptingAnExistingTarget() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var source = root.resolve("home/cache");
        var target = Files.createDirectories(root.resolve("local/cache"));

        var plan = plan(new Relocation(source, target));

        var conflict = plan.relocations().getFirst().conflict().orElseThrow();
        assertEquals(target, conflict.path());
        assertTrue(conflict.reason().contains("ownership is unknown"));
    }

    @Test
    void warnsWhenRepairingABrokenSourceLink() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var source = root.resolve("home/cache");
        Files.createDirectories(source.getParent());
        Files.createSymbolicLink(source, root.resolve("missing"));
        var target = Files.createDirectories(root.resolve("local/cache"));

        var plan = plan(new Relocation(source, target));

        assertEquals("BROKEN_SOURCE_LINK_REPAIRED", plan.relocations().getFirst().diagnostics().getFirst().code());
        assertEquals(List.of(new ReconciliationAction.ReplaceSymlink(source, target,
                root.resolve("missing"), PathState.DIRECTORY)), plan.actions());
    }

    @Test
    void blocksInaccessibleSources() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var relocation = new Relocation(root.resolve("home/cache"), root.resolve("local/cache"));
        var state = new RelocationState(relocation,
                new PathObservation(PathState.INACCESSIBLE, java.util.Optional.empty(), false),
                new PathObservation(PathState.ABSENT, java.util.Optional.empty(), false));

        var plan = new ReconciliationPlanner().plan(List.of(state));

        assertTrue(plan.hasBlockedActions());
    }

    @Test
    void blocksAllRelocationsWhenTheirPathsOverlap() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var parent = new Relocation(root.resolve("home/cache"), root.resolve("local/cache"));
        var child = new Relocation(root.resolve("home/cache/tool"), root.resolve("local/tool"));
        var inspector = new PathInspector();
        var states = List.of(parent, child).stream()
                .map(relocation -> new RelocationState(relocation,
                        inspector.inspect(relocation.sourcePath()), inspector.inspect(relocation.targetPath())))
                .toList();

        var plan = new ReconciliationPlanner().plan(states);

        assertEquals(2, plan.actions().size());
        assertTrue(plan.hasBlockedActions());
        assertEquals("OVERLAPPING_RELOCATION", plan.diagnostics().getFirst().code());
    }

    private static ReconciliationPlan plan(Relocation relocation) {
        var inspector = new PathInspector();
        var state = new RelocationState(relocation,
                inspector.inspect(relocation.sourcePath()),
                inspector.inspect(relocation.targetPath()));
        return new ReconciliationPlanner().plan(List.of(state));
    }
}
