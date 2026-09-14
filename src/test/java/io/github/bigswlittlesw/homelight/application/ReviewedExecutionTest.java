package io.github.bigswlittlesw.homelight.application;

import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ReviewedExecutionTest {
    @TempDir
    Path directory;

    @Test
    void capturesWithoutIoAndStartsExactlyOnceWithImmutableSnapshots() throws Exception {
        var plan = plan("cache");
        var review = new ReviewedExecution(plan);
        review.awaitExecution();
        assertSame(plan, assertInstanceOf(ApplyModel.Confirmation.class, review.snapshot()).plan());
        assertFalse(Files.exists(directory.resolve("local")));
        var tasks = new ArrayList<Runnable>();
        var completion = review.start(tasks::add);
        var running = assertInstanceOf(ApplyModel.Running.class, review.snapshot());
        assertSame(completion, review.start(tasks::add));
        assertEquals(1, tasks.size());
        assertFalse(completion.isDone());
        assertThrows(UnsupportedOperationException.class, () -> running.steps().clear());

        // Execution owns the captured plan, with no configuration dependency after capture.
        Files.delete(directory.resolve("config.yaml"));
        var atCompletion = new AtomicReference<ApplyModel>();
        var observed = completion.thenRun(() -> atCompletion.set(review.snapshot()));
        tasks.getFirst().run();
        observed.join();
        review.awaitExecution();

        var result = assertInstanceOf(ApplyModel.Result.class, review.snapshot());
        assertSame(result, atCompletion.get());
        assertTrue(result.succeeded());
        assertSame(plan, result.plan());
        assertSame(plan.relocations().getFirst(), result.steps().getFirst().relocation());
        assertSame(plan.actions().getFirst(), result.steps().getFirst().action());
        assertTrue(running.steps().stream().allMatch(step -> step.status() == ApplyModel.StepStatus.PENDING));
        assertSame(completion, review.start(task -> fail("Must not reschedule a retained result")));
        assertSame(result, review.snapshot());
        assertTrue(Files.isSymbolicLink(directory.resolve("home/cache")));
    }

    @Test
    void rejectionRetainsDiagnosticsAndSettlesWithoutRetryOrMutation() throws Exception {
        var review = new ReviewedExecution(plan("cache"));
        var completion = review.start(task -> { throw new RejectedExecutionException("worker unavailable"); });
        assertTrue(completion.isDone());
        completion.join();
        review.awaitExecution();
        var result = assertInstanceOf(ApplyModel.Result.class, review.snapshot());
        assertFalse(result.succeeded());
        assertFalse(result.stale());
        assertTrue(result.execution().isEmpty());
        assertEquals(java.util.List.of("worker unavailable"), result.diagnostics());
        assertTrue(result.steps().stream().allMatch(step -> step.status() == ApplyModel.StepStatus.PENDING));
        assertSame(completion, review.start(task -> fail("Rejected review cannot restart")));
        assertFalse(Files.exists(directory.resolve("local")));
    }

    @Test
    void wholePlanDriftRejectsBeforeAnyAction() throws Exception {
        var review = new ReviewedExecution(plan("first", "second"));
        Files.createDirectories(directory.resolve("local/second"));
        review.start(Runnable::run).join();
        var result = assertInstanceOf(ApplyModel.Result.class, review.snapshot());
        assertTrue(result.stale());
        assertTrue(result.execution().isEmpty());
        assertFalse(result.diagnostics().isEmpty());
        assertTrue(result.steps().stream().allMatch(step -> step.status() == ApplyModel.StepStatus.PENDING));
        assertFalse(Files.exists(directory.resolve("local/first")));
        assertFalse(Files.exists(directory.resolve("home")));
    }

    @Test
    void unresolvedAndBlockedPlansAreRejectedAtCapture() throws Exception {
        Files.createDirectories(directory.resolve("local/cache"));
        assertThrows(IllegalArgumentException.class, () -> new ReviewedExecution(plan("cache")));
        Files.createDirectories(directory.resolve("home"));
        Files.writeString(directory.resolve("home/cache"), "unsupported source file");
        assertThrows(IllegalArgumentException.class, () -> new ReviewedExecution(plan("cache")));
        assertEquals("unsupported source file", Files.readString(directory.resolve("home/cache")));
    }

    @Test
    void successfulRepeatUsesANewNoChangePlan() throws Exception {
        new ReviewedExecution(plan("cache")).start(Runnable::run).join();
        var repeated = plan("cache");
        assertFalse(repeated.hasChanges());
        var review = new ReviewedExecution(repeated);
        review.start(Runnable::run).join();
        assertTrue(assertInstanceOf(ApplyModel.Result.class, review.snapshot()).succeeded());
    }

    @Test
    void workerFailureRetainsFailedAndNotStartedEvidence() throws Exception {
        var review = new ReviewedExecution(plan("cache"), 1);
        var tasks = new ArrayList<Runnable>();
        var completion = review.start(tasks::add);
        // Exercise the existing debug-delay failure path deterministically, without a timing race.
        Thread.currentThread().interrupt();
        try {
            tasks.getFirst().run();
        } finally {
            Thread.interrupted();
        }
        completion.join();
        var result = assertInstanceOf(ApplyModel.Result.class, review.snapshot());
        assertFalse(result.execution().orElseThrow().succeeded());
        assertEquals("Interrupted during visual-test delay", result.steps().getFirst().message());
        assertEquals(ApplyModel.StepStatus.FAILED, result.steps().getFirst().status());
        assertTrue(result.steps().stream().skip(1).allMatch(step -> step.status() == ApplyModel.StepStatus.PENDING));
        assertFalse(result.stale());
        assertFalse(Files.exists(directory.resolve("local")));
    }

    private ReconciliationPlan plan(String... names) throws Exception {
        var root = directory.toRealPath();
        var yaml = new StringBuilder("homelight:\n  target-root: " + root.resolve("local") + "\n  relocations:\n");
        for (var name : names) {
            yaml.append("    - source-path: ").append(root.resolve("home").resolve(name)).append('\n');
            yaml.append("      target-path: ").append(root.resolve("local").resolve(name)).append('\n');
        }
        var config = Files.writeString(root.resolve("config.yaml"), yaml);
        return assertInstanceOf(ConfigurationEvaluation.Loaded.class, new ConfigurationEvaluation().load(config)).plan();
    }
}
