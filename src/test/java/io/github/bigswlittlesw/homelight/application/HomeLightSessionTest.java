package io.github.bigswlittlesw.homelight.application;

import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class HomeLightSessionTest {
    @TempDir
    Path directory;

    @Test
    void rejectedWorkerRetainsResultAndRefreshesStatusWithoutMutation() throws Exception {
        var root = directory.toRealPath();
        var session = new HomeLightSession(configuration(root, "", "cache"));
        assertTrue(session.requestApply());
        Files.createDirectories(root.resolve("local/cache"));
        var completion = session.confirmApply(task -> {
            throw new java.util.concurrent.RejectedExecutionException("worker unavailable");
        });
        completion.join();
        session.awaitExecution();
        var result = assertInstanceOf(ApplyModel.Result.class, session.applyModel());
        assertEquals("worker unavailable", result.diagnostics().getFirst());
        assertTrue(result.execution().isEmpty());
        assertFalse(session.isApplying());
        assertSame(completion, session.confirmApply(task -> fail("Must not restart")));
        assertFalse(Files.exists(root.resolve("home")));
        assertEquals(1, assertInstanceOf(PlanModel.Configured.class, session.planModel()).items().size());
    }

    @Test
    void invalidConfigurationAtStatusRefreshDoesNotEraseExecutionResult() throws Exception {
        var root = directory.toRealPath();
        var config = configuration(root, "", "cache");
        var session = new HomeLightSession(config);
        assertTrue(session.requestApply());
        Files.writeString(config, "homelight: [invalid");
        session.confirmApply(Runnable::run).join();
        assertTrue(assertInstanceOf(ApplyModel.Result.class, session.applyModel()).succeeded());
        assertInstanceOf(PlanModel.Invalid.class, session.planModel());
        assertTrue(Files.isSymbolicLink(root.resolve("home/cache")));
    }

    @Test
    void visualDelayHoldsEachActionBeforeCompletion() throws Exception {
        var root = directory.toRealPath();
        var session = new HomeLightSession(configuration(root, "", "cache"), 20);
        var actionCount = assertInstanceOf(PlanModel.Configured.class, session.planModel()).plan().actions().size();
        session.requestApply();
        long started = System.nanoTime();
        session.confirmApply().get(10, TimeUnit.SECONDS);
        assertTrue(System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(20L * actionCount));
        assertTrue(assertInstanceOf(ApplyModel.Result.class, session.applyModel()).succeeded());
    }

    @Test
    void confirmsTheExactReviewedPlanAndRetainsTheResultUntilReplanning() throws Exception {
        var root = directory.toRealPath();
        var source = Files.createDirectories(root.resolve("home/cache"));
        Files.writeString(source.resolve("entry"), "keep this content");
        var config = configuration(root, "", "cache");
        var session = new HomeLightSession(config);
        var reviewed = assertInstanceOf(PlanModel.Configured.class, session.planModel()).plan();

        assertTrue(session.requestApply());
        assertSame(reviewed, assertInstanceOf(ApplyModel.Confirmation.class, session.applyModel()).plan());
        assertFalse(Files.exists(root.resolve("local/cache")));

        // A later config read must not silently substitute a different plan at confirmation.
        Files.writeString(config, Files.readString(config).replace("home/cache", "home/other"));
        session.confirmApply().get(10, TimeUnit.SECONDS);

        var result = assertInstanceOf(ApplyModel.Result.class, session.applyModel());
        assertSame(reviewed, result.plan());
        assertSame(reviewed.relocations().getFirst(), result.execution().orElseThrow().relocations().getFirst().relocation());
        assertTrue(result.succeeded());
        assertTrue(Files.isSymbolicLink(source));
        assertEquals("keep this content", Files.readString(root.resolve("local/cache/entry")));
        assertFalse(Files.exists(root.resolve("home/other")));
        assertFalse(session.requestApply());
        session.confirmApply(Runnable::run);
        assertSame(result, session.applyModel());
        session.cancelApply();
        assertSame(result, session.applyModel());

        configuration(root, "", "cache");
        session.refresh();
        assertInstanceOf(ApplyModel.Idle.class, session.applyModel());
        assertEquals(1, assertInstanceOf(PlanModel.Configured.class, session.planModel()).summary().inSync());
        var repeated = assertInstanceOf(PlanModel.Configured.class, session.planModel()).plan();
        assertNotSame(reviewed, repeated);
        assertFalse(repeated.actions().stream().anyMatch(ReconciliationAction::mutatesFilesystem));
        assertTrue(session.requestApply());
        session.confirmApply(Runnable::run).join();
        assertTrue(assertInstanceOf(ApplyModel.Result.class, session.applyModel()).succeeded());
    }

    @Test
    void cancellationAndLeavingConfirmationDoNotMutate() throws Exception {
        var root = directory.toRealPath();
        var session = new HomeLightSession(configuration(root, "", "cache"));
        session.requestApply();
        session.cancelApply();
        session.confirmApply(Runnable::run);
        assertInstanceOf(ApplyModel.Idle.class, session.applyModel());
        assertTrue(session.requestApply());
        session.cancelApply();
        session.confirmApply(Runnable::run);
        assertFalse(Files.exists(root.resolve("home/cache")));
        assertFalse(Files.exists(root.resolve("local/cache")));
    }

    @Test
    void unresolvedAndBlockedPlansCannotBeConfirmed() throws Exception {
        var root = directory.toRealPath();
        Files.createDirectories(root.resolve("local/cache"));
        var session = new HomeLightSession(configuration(root, "", "cache"));
        assertFalse(session.requestApply());
        session.confirmApply(Runnable::run);
        assertFalse(Files.exists(root.resolve("home/cache")));

        Files.createDirectories(root.resolve("home"));
        Files.writeString(root.resolve("home/cache"), "unsupported source file");
        session.refresh();
        assertFalse(session.requestApply());
        session.confirmApply(Runnable::run);
        assertEquals("unsupported source file", Files.readString(root.resolve("home/cache")));
    }

    @Test
    void preflightsEveryRelocationBeforeAnyMutationAndRequiresExplicitReplanning() throws Exception {
        var root = directory.toRealPath();
        var session = new HomeLightSession(configuration(root, "", "first", "second"));
        session.requestApply();
        Files.createDirectories(root.resolve("local/second"));
        session.confirmApply(Runnable::run).join();

        var stale = assertInstanceOf(ApplyModel.Result.class, session.applyModel());
        assertTrue(stale.stale());
        assertTrue(stale.execution().isEmpty());
        assertTrue(stale.diagnostics().getFirst().contains("local/second"));
        assertTrue(stale.steps().stream().allMatch(step -> step.status() == ApplyModel.StepStatus.PENDING));
        assertFalse(Files.exists(root.resolve("local/first")));
        assertFalse(Files.exists(root.resolve("home")));
        assertFalse(session.requestApply());

        session.refresh();
        assertTrue(session.hasConflicts());
        var second = assertInstanceOf(PlanModel.Configured.class, session.planModel()).items().stream()
                .filter(item -> item.relocation().sourcePath().endsWith("second")).findFirst().orElseThrow();
        session.resolveDecision(second.relocation(), DecisionChoice.ADOPT_TARGET);
        assertTrue(session.requestApply());
        session.confirmApply(Runnable::run).join();
        assertTrue(assertInstanceOf(ApplyModel.Result.class, session.applyModel()).succeeded());
        assertEquals(2, assertInstanceOf(PlanModel.Configured.class, session.planModel()).summary().inSync());
    }

    @Test
    void preflightRetainsArchiveDestinationObservation() throws Exception {
        var root = directory.toRealPath();
        var source = Files.createDirectories(root.resolve("home/cache"));
        Files.writeString(source.resolve("entry"), "source");
        Files.createDirectories(root.resolve("local/cache"));
        var config = configuration(root, "", "cache");
        Files.writeString(config, Files.readString(config) + """
                      when-source-and-target-directories-exist: adopt
                      when-adopting-target: archive-source
                      source-archive-root: %s
                """.formatted(root.resolve("archive")));
        var session = new HomeLightSession(config);
        assertTrue(session.requestApply());
        var archive = root.resolve("archive").resolve(source.getRoot().relativize(source));
        Files.createDirectories(archive);
        Files.writeString(archive.resolve("entry"), "external archive");
        session.confirmApply(Runnable::run).join();

        assertTrue(assertInstanceOf(ApplyModel.Result.class, session.applyModel()).stale());
        assertEquals("source", Files.readString(source.resolve("entry")));
        assertEquals("external archive", Files.readString(archive.resolve("entry")));
    }

    @Test
    void runningSnapshotIsImmutableAndRepeatedIntentsCannotStartOrReplaceExecution() throws Exception {
        var root = directory.toRealPath();
        var session = new HomeLightSession(configuration(root, "", "cache"));
        var tasks = new ArrayList<Runnable>();
        session.requestApply();
        var execution = session.confirmApply(tasks::add);
        var running = assertInstanceOf(ApplyModel.Running.class, session.applyModel());
        assertEquals(1, tasks.size());
        assertSame(execution, session.confirmApply(tasks::add));
        session.refresh();
        session.cancelApply();
        session.cancelApply();
        assertFalse(session.requestApply());
        assertSame(running, session.applyModel());
        assertEquals(1, tasks.size());

        tasks.getFirst().run();
        execution.join();
        assertTrue(running.steps().stream().allMatch(step -> step.status() == ApplyModel.StepStatus.PENDING));
        var result = assertInstanceOf(ApplyModel.Result.class, session.applyModel());
        assertTrue(result.steps().stream().allMatch(step -> step.status() == ApplyModel.StepStatus.COMPLETED));
        assertEquals(1, assertInstanceOf(PlanModel.Configured.class, session.planModel()).summary().inSync());
    }

    @Test
    void partialFailureRetainsCompletedFailedAndPendingActionsAndRetryUsesANewPlan() throws Exception {
        var root = directory.toRealPath();
        Files.createDirectories(root.resolve("home/second"));
        var staging = Files.createDirectories(root.resolve("local")).resolve("staging-file");
        var config = configuration(root, "  staging-root: " + staging + "\n", "first", "second", "third");
        var session = new HomeLightSession(config);
        assertTrue(session.requestApply(), session.planModel().toString());
        Files.writeString(staging, "not a directory");
        session.confirmApply(Runnable::run).join();

        var result = assertInstanceOf(ApplyModel.Result.class, session.applyModel());
        assertFalse(result.succeeded());
        assertTrue(result.steps().stream().anyMatch(step -> step.status() == ApplyModel.StepStatus.COMPLETED));
        var failed = result.steps().stream().filter(step -> step.status() == ApplyModel.StepStatus.FAILED).findFirst().orElseThrow();
        assertTrue(failed.relocation().relocation().sourcePath().endsWith("second"));
        assertFalse(failed.message().isBlank());
        assertTrue(result.steps().stream().anyMatch(step -> step.status() == ApplyModel.StepStatus.PENDING));
        assertTrue(Files.isSymbolicLink(root.resolve("home/first")));
        assertFalse(Files.exists(root.resolve("home/third")));
        assertFalse(session.requestApply());

        Files.delete(staging);
        session.refresh();
        assertTrue(session.requestApply());
        session.confirmApply(Runnable::run).join();
        var retried = assertInstanceOf(ApplyModel.Result.class, session.applyModel());
        assertNotSame(result.plan(), retried.plan());
        assertTrue(retried.succeeded());
        assertEquals(3, assertInstanceOf(PlanModel.Configured.class, session.planModel()).summary().inSync());
    }

    private Path configuration(Path root, String globals, String... names) throws Exception {
        var yaml = new StringBuilder("homelight:\n  target-root: " + root.resolve("local") + "\n" + globals + "  relocations:\n");
        for (var name : names) {
            yaml.append("    - source-path: ").append(root.resolve("home").resolve(name)).append('\n');
            yaml.append("      target-path: ").append(root.resolve("local").resolve(name)).append('\n');
        }
        return Files.writeString(root.resolve("config.yaml"), yaml);
    }
}
