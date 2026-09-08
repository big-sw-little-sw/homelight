package io.github.bigswlittlesw.homelight.reconcile;

import io.github.bigswlittlesw.homelight.config.Relocation;
import io.github.bigswlittlesw.homelight.fs.PathInspector;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ReconciliationPlannerTest {
    @Test
    void createsDestinationAndLinkWhenBothPathsAreAbsent() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var relocation = new Relocation(root.resolve("home/cache"), root.resolve("local/cache"));

        var plan = plan(relocation);

        assertEquals(List.of(
                new ReconciliationAction.CreateDirectory(relocation.targetPath()),
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
    void blocksCorrectSourceLinkWhenTargetIsASymlink() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var realTarget = Files.createDirectories(root.resolve("real-local/cache"));
        var target = root.resolve("local/cache");
        Files.createDirectories(target.getParent());
        Files.createSymbolicLink(target, realTarget);
        var source = root.resolve("home/cache");
        Files.createDirectories(source.getParent());
        Files.createSymbolicLink(source, target);

        var plan = plan(new Relocation(source, target));

        var blocked = assertInstanceOf(ReconciliationAction.Blocked.class, plan.actions().getFirst());
        assertEquals(target, blocked.path());
    }

    @Test
    void replacesWrongLinksWhenDestinationIsAvailable() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var target = Files.createDirectories(root.resolve("local/cache"));
        var source = root.resolve("home/cache");
        Files.createDirectories(source.getParent());
        Files.createSymbolicLink(source, root.resolve("other"));

        var plan = plan(new Relocation(source, target));

        assertEquals(List.of(new ReconciliationAction.ReplaceSymlink(source, target)), plan.actions());
    }

    @Test
    void blocksWhenBothSourceAndDestinationContainDirectories() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var source = Files.createDirectories(root.resolve("home/cache"));
        Files.writeString(source.resolve("source-entry"), "source");
        var target = Files.createDirectories(root.resolve("local/cache"));
        Files.writeString(target.resolve("target-entry"), "target");

        var plan = plan(new Relocation(source, target));

        var blocked = assertInstanceOf(ReconciliationAction.Blocked.class, plan.actions().getFirst());
        assertEquals(source, blocked.path());
        assertEquals(true, plan.hasBlockedActions());
    }

    private static ReconciliationPlan plan(Relocation relocation) {
        var inspector = new PathInspector();
        var state = new RelocationState(relocation,
                inspector.inspect(relocation.sourcePath()),
                inspector.inspect(relocation.targetPath()));
        return new ReconciliationPlanner().plan(List.of(state));
    }
}
