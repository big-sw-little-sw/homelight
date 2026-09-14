package io.github.bigswlittlesw.homelight.application;

import io.github.bigswlittlesw.homelight.config.Relocation;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/// Presentation-neutral session holding active workflow state, typed draft decisions, and evaluated plans.
public class HomeLightSession {
    private final Path configPath;
    private final ConfigurationEvaluation evaluator;
    private final long debugStepDelayMillis;
    private ConfigurationEvaluation.Evaluation evaluation;
    private List<ConfigurationEvaluation.DiscardedChoice> discardedChoices = List.of();

    private PlanModel planModel;
    private ReviewedExecution reviewedExecution;
    private CompletableFuture<Void> execution = CompletableFuture.completedFuture(null);

    public HomeLightSession(Path configPath) {
        this(configPath, 0);
    }

    public HomeLightSession(Path configPath, long debugStepDelayMillis) {
        this(configPath, new ConfigurationEvaluation(), debugStepDelayMillis);
    }

    public HomeLightSession(Path configPath, ConfigurationEvaluation evaluator,
            long debugStepDelayMillis) {
        if (debugStepDelayMillis < 0 || debugStepDelayMillis > 60_000) {
            throw new IllegalArgumentException("debug step delay must be between 0 and 60000 milliseconds");
        }
        this.debugStepDelayMillis = debugStepDelayMillis;
        this.configPath = Objects.requireNonNull(configPath, "configPath");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        reload();
    }

    public Path configPath() {
        return configPath;
    }

    public synchronized PlanModel planModel() {
        return planModel;
    }

    public synchronized ConfigurationEvaluation.Evaluation evaluation() {
        return evaluation;
    }

    /// Choices discarded by the most recent explicit replan, retained for presentation by the caller.
    public synchronized List<ConfigurationEvaluation.DiscardedChoice> discardedChoices() {
        return discardedChoices;
    }

    public synchronized void refresh() {
        if (isApplying()) {
            return;
        }
        reviewedExecution = null;
        reload();
    }

    public synchronized void resolveDecision(Relocation relocation, DecisionChoice choice) {
        choose(relocation.sourcePath(), choice);
    }

    public synchronized void choose(Path sourcePath, DecisionChoice choice) {
        if (isApplying() || applyModel() instanceof ApplyModel.Result) {
            throw new IllegalStateException("Replan before editing a running or retained result");
        }
        if (!(evaluation instanceof ConfigurationEvaluation.Loaded loaded)) {
            throw new IllegalStateException("No loaded configuration");
        }
        evaluation = evaluator.choose(loaded, sourcePath, choice);
        reviewedExecution = null;
        reloadModels();
    }

    public synchronized boolean isPlanReady() {
        return !(applyModel() instanceof ApplyModel.Running) && !(applyModel() instanceof ApplyModel.Result)
                && planModel instanceof PlanModel.Configured configured
                && !configured.plan().hasBlockedActions()
                && !configured.plan().hasConflicts();
    }

    public synchronized boolean hasConflicts() {
        return planModel instanceof PlanModel.Configured configured
                && configured.plan().hasConflicts();
    }

    public synchronized ApplyModel applyModel() {
        return reviewedExecution == null ? new ApplyModel.Idle() : reviewedExecution.snapshot();
    }

    public synchronized boolean isApplying() {
        return applyModel() instanceof ApplyModel.Running;
    }

    /// Includes post-execution refresh and exceptional settlement, not just result publication.
    public synchronized boolean executionSettled() {
        return execution.isDone();
    }

    /// Captures the current plan without reloading it or touching the filesystem.
    public synchronized boolean requestApply() {
        if (!isPlanReady() || !(planModel instanceof PlanModel.Configured configured)) {
            return false;
        }
        reviewedExecution = new ReviewedExecution(configured.plan(), debugStepDelayMillis);
        return true;
    }

    public synchronized void cancelApply() {
        if (applyModel() instanceof ApplyModel.Confirmation) {
            reviewedExecution = null;
        }
    }

    public CompletableFuture<Void> confirmApply() {
        return confirmApply(task -> Thread.ofPlatform().name("homelight-apply").start(task));
    }

    /// The worker never accesses terminal state. Repeated confirmation cannot schedule another execution.
    public synchronized CompletableFuture<Void> confirmApply(Executor worker) {
        if (!(applyModel() instanceof ApplyModel.Confirmation)) {
            return execution;
        }
        execution = reviewedExecution.start(worker).thenRun(this::refreshObservationsAfterExecution);
        return execution;
    }

    private synchronized void refreshObservationsAfterExecution() {
        var current = evaluator.load(configPath);
        planModel = PlanWorkflow.from(current);
    }

    /// Terminal shutdown waits for an active mutation sequence rather than interrupting it mid-action.
    public void awaitExecution() {
        CompletableFuture<Void> pending;
        synchronized (this) {
            pending = execution;
        }
        pending.join();
    }

    private void reload() {
        if (evaluation == null) {
            evaluation = evaluator.load(configPath);
        } else {
            var replanned = evaluator.replan(evaluation);
            evaluation = replanned.evaluation();
            discardedChoices = replanned.discardedChoices();
        }
        reloadModels();
    }

    private void reloadModels() {
        this.planModel = PlanWorkflow.from(evaluation);
    }
}
