package io.github.bigswlittlesw.homelight.reconcile;

import io.github.bigswlittlesw.homelight.config.Relocation;
import io.github.bigswlittlesw.homelight.config.ExistingContentPolicy;
import io.github.bigswlittlesw.homelight.fs.PathInspector;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconciliationExecutorTest {
    @Test
    void rejectsPlansWithConflictsBeforeMutatingTheFilesystem() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var source = Files.createDirectories(root.resolve("home/cache"));
        var target = Files.createDirectories(root.resolve("local/cache"));
        var plan = plan(new Relocation(source, target));

        assertThrows(IllegalArgumentException.class, () -> new ReconciliationExecutor().execute(plan));

        assertTrue(Files.isDirectory(source));
        assertTrue(Files.isDirectory(target));
    }

    @Test
    void stopsWhenTheFilesystemChangesAfterPlanning() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var source = root.resolve("home/cache");
        var target = root.resolve("local/cache");
        var plan = plan(new Relocation(source, target));
        Files.createDirectories(target);

        var result = new ReconciliationExecutor().execute(plan);

        assertFalse(result.succeeded());
        var action = result.relocations().getFirst().actions().stream()
                .filter(execution -> execution.status() == ReconciliationExecutor.ActionStatus.FAILED)
                .findFirst()
                .orElseThrow();
        assertEquals(ReconciliationExecutor.ActionStatus.FAILED, action.status());
        assertTrue(action.message().contains("expected absent"));
        assertTrue(Files.notExists(source));
    }

    @Test
    void reportsRemainingActionsAsPendingAfterAFailure() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var firstSource = root.resolve("home/first");
        var firstTarget = root.resolve("local/first");
        var secondSource = root.resolve("home/second");
        var secondTarget = root.resolve("local/second");
        var plan = plan(new Relocation(firstSource, firstTarget), new Relocation(secondSource, secondTarget));
        Files.createDirectories(firstTarget);

        var result = new ReconciliationExecutor().execute(plan);

        var secondActions = result.relocations().get(1).actions();
        assertTrue(secondActions.stream().allMatch(action -> action.status() == ReconciliationExecutor.ActionStatus.PENDING));
        assertTrue(Files.notExists(secondSource));
        assertTrue(Files.notExists(secondTarget));
    }

    @Test
    void copiesDirectoryContentsWithoutRemovingTheSourceAndPreservesNestedLinks() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var source = Files.createDirectories(root.resolve("home/cache"));
        Files.writeString(source.resolve("entry"), "value");
        var external = Files.writeString(root.resolve("external"), "external-value");
        Files.createSymbolicLink(source.resolve("nested-link"), external);
        var target = root.resolve("local/cache");
        var plan = plan(new Relocation(source, target, java.util.Optional.of(ExistingContentPolicy.MOVE)));

        var result = new ReconciliationExecutor().execute(plan);

        assertTrue(result.succeeded());
        assertEquals("value", Files.readString(target.resolve("entry")));
        assertTrue(Files.isSymbolicLink(target.resolve("nested-link")));
        assertEquals(external, target.resolve("nested-link").getParent()
                .resolve(Files.readSymbolicLink(target.resolve("nested-link"))).normalize());
        assertTrue(Files.isDirectory(source));
        assertEquals("value", Files.readString(source.resolve("entry")));
    }

    @Test
    void doesNotCopyOverATargetCreatedAfterPlanning() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var source = Files.createDirectories(root.resolve("home/cache"));
        Files.writeString(source.resolve("entry"), "value");
        var target = root.resolve("local/cache");
        var plan = plan(new Relocation(source, target, java.util.Optional.of(ExistingContentPolicy.MOVE)));

        Files.createDirectories(target);

        var result = new ReconciliationExecutor().execute(plan);

        assertTrue(!result.succeeded());
        assertTrue(Files.isDirectory(source));
        assertTrue(Files.isDirectory(target));
    }

    @Test
    void discardsConfiguredExistingContentBeforeCreatingAFreshTargetAndLink() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var source = Files.createDirectories(root.resolve("home/cache"));
        Files.writeString(source.resolve("source-entry"), "source");
        var target = Files.createDirectories(root.resolve("local/cache"));
        Files.writeString(target.resolve("target-entry"), "target");
        var relocation = new Relocation(source, target, java.util.Optional.of(ExistingContentPolicy.DISCARD));

        var result = new ReconciliationExecutor().execute(plan(relocation));

        assertTrue(result.succeeded());
        assertTrue(Files.isSymbolicLink(source));
        assertTrue(Files.isDirectory(target));
        assertTrue(Files.notExists(target.resolve("source-entry")));
        assertTrue(Files.notExists(target.resolve("target-entry")));
    }

    @Test
    void copiesExistingContentToAnAbsentTargetWithoutReplacingTheSource() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var source = Files.createDirectories(root.resolve("home/cache"));
        Files.writeString(source.resolve("entry"), "value");
        var target = root.resolve("local/cache");
        var relocation = new Relocation(source, target, java.util.Optional.of(ExistingContentPolicy.MOVE));

        var result = new ReconciliationExecutor().execute(plan(relocation));

        assertTrue(result.succeeded());
        assertEquals("value", Files.readString(target.resolve("entry")));
        assertTrue(Files.isDirectory(source));
    }

    @Test
    void refusesToCopyOverATargetCreatedAfterPlanning() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var source = Files.createDirectories(root.resolve("home/cache"));
        Files.writeString(source.resolve("source-entry"), "source");
        var target = root.resolve("local/cache");
        var relocation = new Relocation(source, target, java.util.Optional.of(ExistingContentPolicy.MOVE));
        var plan = plan(relocation);
        Files.createDirectories(target);
        Files.writeString(target.resolve("late-entry"), "late");

        var result = new ReconciliationExecutor().execute(plan);

        assertTrue(!result.succeeded());
        assertTrue(Files.isDirectory(source));
        assertEquals("late", Files.readString(target.resolve("late-entry")));
    }

    @Test
    void adoptsAnAcceptedTargetByReplacingTheSourceWithALink() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var source = Files.createDirectories(root.resolve("home/cache"));
        Files.writeString(source.resolve("old-entry"), "old");
        var target = Files.createDirectories(root.resolve("local/cache"));
        Files.writeString(target.resolve("accepted-entry"), "accepted");
        var relocation = new Relocation(source, target, java.util.Optional.of(ExistingContentPolicy.ADOPT));

        var result = new ReconciliationExecutor().execute(plan(relocation));

        assertTrue(result.succeeded());
        assertTrue(Files.isSymbolicLink(source));
        assertEquals("accepted", Files.readString(target.resolve("accepted-entry")));
        assertTrue(Files.notExists(target.resolve("old-entry")));
    }

    private static ReconciliationPlan plan(Relocation... relocations) {
        var inspector = new PathInspector();
        var states = java.util.Arrays.stream(relocations)
                .map(relocation -> new RelocationState(relocation,
                        inspector.inspect(relocation.sourcePath()), inspector.inspect(relocation.targetPath())))
                .toList();
        return new ReconciliationPlanner().plan(states);
    }
}
