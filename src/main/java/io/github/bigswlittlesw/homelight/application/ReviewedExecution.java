package io.github.bigswlittlesw.homelight.application;

import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationExecutor;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlan;
import io.github.bigswlittlesw.homelight.reconcile.RelocationPlan;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/// One captured plan's preflight, execution, immutable progress, and retained result.
/// Capturing performs no I/O; starting never reloads or substitutes the plan.
public final class ReviewedExecution {
    private final ReconciliationPlan plan;
    private final long debugStepDelayMillis;
    private ApplyModel snapshot;
    private CompletableFuture<Void> completion;

    public ReviewedExecution(ReconciliationPlan plan) {
        this(plan, 0);
    }

    public ReviewedExecution(ReconciliationPlan plan, long debugStepDelayMillis) {
        this.plan = Objects.requireNonNull(plan, "plan");
        if (plan.hasBlockedActions() || plan.hasConflicts()) {
            throw new IllegalArgumentException("Review requires a resolved, unblocked plan");
        }
        if (debugStepDelayMillis < 0 || debugStepDelayMillis > 60_000) {
            throw new IllegalArgumentException("debug step delay must be between 0 and 60000 milliseconds");
        }
        this.debugStepDelayMillis = debugStepDelayMillis;
        snapshot = new ApplyModel.Confirmation(plan);
    }

    public synchronized ApplyModel snapshot() {
        return snapshot;
    }

    /// Schedules at most once, returning the same completion on every subsequent call.
    /// The terminal snapshot is published before completion settles, including scheduling rejection.
    public synchronized CompletableFuture<Void> start(Executor worker) {
        if (completion != null) {
            return completion;
        }
        completion = new CompletableFuture<>();
        snapshot = new ApplyModel.Running(plan, pendingSteps(plan));
        try {
            worker.execute(() -> {
                try {
                    executeReviewed(plan);
                    completion.complete(null);
                } catch (Error error) {
                    finishWithoutExecution(plan, List.of(message(error)), false);
                    completion.completeExceptionally(error);
                }
            });
        } catch (RuntimeException exception) {
            finishWithoutExecution(plan, List.of(message(exception)), false);
            completion.complete(null);
        }
        return completion;
    }

    /// Waits for started work without cancelling or interrupting its mutation sequence.
    public void awaitExecution() {
        CompletableFuture<Void> pending;
        synchronized (this) {
            pending = completion;
        }
        if (pending != null) {
            pending.join();
        }
    }

    private void executeReviewed(ReconciliationPlan plan) {
        try {
            var executor = new ReconciliationExecutor();
            var drift = executor.preflight(plan);
            if (!drift.isEmpty()) {
                finishWithoutExecution(plan, drift.stream().map(diagnostic -> diagnostic.message()).toList(), true);
                return;
            }
            var result = executor.execute(plan, new ReconciliationExecutor.ProgressListener() {
                @Override
                public void started(RelocationPlan relocation, ReconciliationAction action) {
                    updateStep(relocation, action, ApplyModel.StepStatus.RUNNING, "Running");
                    if (action.mutatesFilesystem()) {
                        pauseForVisualTesting();
                    }
                }

                @Override
                public void finished(RelocationPlan relocation, ReconciliationExecutor.ActionExecution action) {
                    updateStep(relocation, action.action(), stepStatus(action.status()), action.message());
                }
            });
            var steps = result.relocations().stream().flatMap(relocation -> relocation.actions().stream()
                    .map(action -> new ApplyModel.Step(relocation.relocation(), action.action(),
                            stepStatus(action.status()), action.message()))).toList();
            boolean stale = result.relocations().stream().flatMap(relocation -> relocation.actions().stream())
                    .anyMatch(ReconciliationExecutor.ActionExecution::stateDrift);
            synchronized (this) {
                snapshot = new ApplyModel.Result(plan, steps, Optional.of(result), List.of(), stale);
            }
        } catch (RuntimeException exception) {
            finishWithoutExecution(plan, List.of(message(exception)), false);
        }
    }

    private void pauseForVisualTesting() {
        if (debugStepDelayMillis == 0) {
            return;
        }
        try {
            // Delay only the execution worker after publishing RUNNING, so the TUI keeps animating.
            Thread.sleep(debugStepDelayMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during visual-test delay", exception);
        }
    }

    private synchronized void updateStep(RelocationPlan relocation, ReconciliationAction action,
            ApplyModel.StepStatus status, String message) {
        if (snapshot instanceof ApplyModel.Running running) {
            var steps = running.steps().stream().map(step -> step.relocation() == relocation && step.action() == action
                    ? new ApplyModel.Step(relocation, action, status, message) : step).toList();
            snapshot = new ApplyModel.Running(running.plan(), steps);
        }
    }

    private synchronized void finishWithoutExecution(ReconciliationPlan plan, List<String> diagnostics, boolean stale) {
        var steps = snapshot instanceof ApplyModel.Running running ? running.steps() : pendingSteps(plan);
        steps = steps.stream().map(step -> step.status() == ApplyModel.StepStatus.RUNNING
                ? new ApplyModel.Step(step.relocation(), step.action(), ApplyModel.StepStatus.FAILED, diagnostics.getFirst())
                : step).toList();
        snapshot = new ApplyModel.Result(plan, steps, Optional.empty(), diagnostics, stale);
    }

    private static List<ApplyModel.Step> pendingSteps(ReconciliationPlan plan) {
        return plan.relocations().stream().flatMap(relocation -> relocation.actions().stream()
                .map(action -> new ApplyModel.Step(relocation, action, ApplyModel.StepStatus.PENDING, "Not started"))).toList();
    }

    private static ApplyModel.StepStatus stepStatus(ReconciliationExecutor.ActionStatus status) {
        return switch (status) {
            case COMPLETED -> ApplyModel.StepStatus.COMPLETED;
            case FAILED -> ApplyModel.StepStatus.FAILED;
            case PENDING -> ApplyModel.StepStatus.PENDING;
        };
    }

    private static String message(Throwable exception) {
        return exception.getMessage() == null ? exception.toString() : exception.getMessage();
    }
}
