package io.github.bigswlittlesw.homelight.application;

import io.github.bigswlittlesw.homelight.config.ConfigurationLoader;
import io.github.bigswlittlesw.homelight.config.Relocation;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationExecutor;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlan;
import io.github.bigswlittlesw.homelight.reconcile.RelocationPlan;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/// Presentation-neutral session holding active workflow state, configuration overrides, and evaluated plans.
public class HomeLightSession {
    private final Path configPath;
    private final StatusWorkflow statusWorkflow;
    private final PlanWorkflow planWorkflow;
    private final ConfigurationLoader configurationLoader;
    private final long debugStepDelayMillis;
    private final Map<String, String> overrides = new HashMap<>();

    private Screen activeScreen;
    private StatusModel statusModel;
    private PlanModel planModel;
    private ApplyModel applyModel = new ApplyModel.Idle();
    private CompletableFuture<Void> execution = CompletableFuture.completedFuture(null);

    public HomeLightSession(Path configPath, Screen initialScreen) {
        this(configPath, initialScreen, 0);
    }

    public HomeLightSession(Path configPath, Screen initialScreen, long debugStepDelayMillis) {
        this(configPath, initialScreen, new StatusWorkflow(), new PlanWorkflow(), new ConfigurationLoader(), debugStepDelayMillis);
    }

    public HomeLightSession(Path configPath, Screen initialScreen, StatusWorkflow statusWorkflow,
            PlanWorkflow planWorkflow, ConfigurationLoader configurationLoader) {
        this(configPath, initialScreen, statusWorkflow, planWorkflow, configurationLoader, 0);
    }

    private HomeLightSession(Path configPath, Screen initialScreen, StatusWorkflow statusWorkflow,
            PlanWorkflow planWorkflow, ConfigurationLoader configurationLoader, long debugStepDelayMillis) {
        if (debugStepDelayMillis < 0 || debugStepDelayMillis > 60_000) {
            throw new IllegalArgumentException("debug step delay must be between 0 and 60000 milliseconds");
        }
        this.debugStepDelayMillis = debugStepDelayMillis;
        this.configPath = Objects.requireNonNull(configPath, "configPath");
        this.activeScreen = Objects.requireNonNull(initialScreen, "initialScreen");
        this.statusWorkflow = Objects.requireNonNull(statusWorkflow, "statusWorkflow");
        this.planWorkflow = Objects.requireNonNull(planWorkflow, "planWorkflow");
        this.configurationLoader = Objects.requireNonNull(configurationLoader, "configurationLoader");
        reload();
    }

    public synchronized Screen activeScreen() {
        return activeScreen;
    }

    public synchronized void setActiveScreen(Screen activeScreen) {
        if (isApplying()) {
            return;
        }
        if (activeScreen != Screen.APPLY && applyModel instanceof ApplyModel.Confirmation) {
            applyModel = new ApplyModel.Idle();
        }
        this.activeScreen = Objects.requireNonNull(activeScreen, "activeScreen");
    }

    public Path configPath() {
        return configPath;
    }

    public synchronized StatusModel statusModel() {
        return statusModel;
    }

    public synchronized PlanModel planModel() {
        return planModel;
    }

    public synchronized Map<String, String> overrides() {
        return Map.copyOf(overrides);
    }

    public synchronized void refresh() {
        if (isApplying()) {
            return;
        }
        applyModel = new ApplyModel.Idle();
        if (activeScreen == Screen.APPLY) {
            activeScreen = Screen.PLAN;
        }
        reload();
    }

    public synchronized void resolveDecision(Relocation relocation, DecisionChoice choice) {
        if (isApplying() || applyModel instanceof ApplyModel.Result) {
            return;
        }
        applyModel = new ApplyModel.Idle();
        try {
            var configuration = configurationLoader.load(configPath, overrides);
            int index = -1;
            for (int i = 0; i < configuration.relocations().size(); i++) {
                if (configuration.relocations().get(i).sourcePath().equals(relocation.sourcePath())) {
                    index = i;
                    break;
                }
            }
            if (index >= 0) {
                for (var entry : choice.configurationOverrides().entrySet()) {
                    overrides.put("homelight.relocations[" + index + "]." + entry.getKey(), entry.getValue());
                }
                reloadModels();
            }
        } catch (Exception exception) {
            reloadModels();
        }
    }

    public synchronized boolean isPlanReady() {
        return !(applyModel instanceof ApplyModel.Running) && !(applyModel instanceof ApplyModel.Result)
                && planModel instanceof PlanModel.Configured configured
                && !configured.plan().hasBlockedActions()
                && !configured.plan().hasConflicts();
    }

    public synchronized boolean hasConflicts() {
        return planModel instanceof PlanModel.Configured configured
                && configured.plan().hasConflicts();
    }

    public synchronized ApplyModel applyModel() {
        return applyModel;
    }

    public synchronized boolean isApplying() {
        return applyModel instanceof ApplyModel.Running;
    }

    /// Captures the current plan without reloading it or touching the filesystem.
    public synchronized boolean requestApply() {
        if (!isPlanReady() || !(planModel instanceof PlanModel.Configured configured)) {
            return false;
        }
        applyModel = new ApplyModel.Confirmation(configured.plan());
        activeScreen = Screen.APPLY;
        return true;
    }

    public synchronized void cancelApply() {
        if (applyModel instanceof ApplyModel.Confirmation) {
            applyModel = new ApplyModel.Idle();
            activeScreen = Screen.PLAN;
        }
    }

    public CompletableFuture<Void> confirmApply() {
        return confirmApply(task -> Thread.ofPlatform().name("homelight-apply").start(task));
    }

    /// The worker never accesses terminal state. Repeated confirmation cannot schedule another execution.
    public synchronized CompletableFuture<Void> confirmApply(Executor worker) {
        if (!(applyModel instanceof ApplyModel.Confirmation confirmation)) {
            return execution;
        }
        var plan = confirmation.plan();
        applyModel = new ApplyModel.Running(plan, pendingSteps(plan));
        try {
            execution = CompletableFuture.runAsync(() -> executeReviewed(plan), worker);
        } catch (RuntimeException exception) {
            finishWithoutExecution(plan, List.of(message(exception)), false);
        }
        return execution;
    }

    /// Terminal shutdown waits for an active mutation sequence rather than interrupting it mid-action.
    public void awaitExecution() {
        CompletableFuture<Void> pending;
        synchronized (this) {
            pending = execution;
        }
        pending.join();
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
                statusModel = statusWorkflow.loadStatus(configPath);
                applyModel = new ApplyModel.Result(plan, steps, Optional.of(result), List.of(), stale);
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
        if (applyModel instanceof ApplyModel.Running running) {
            var steps = running.steps().stream().map(step -> step.relocation() == relocation && step.action() == action
                    ? new ApplyModel.Step(relocation, action, status, message) : step).toList();
            applyModel = new ApplyModel.Running(running.plan(), steps);
        }
    }

    private synchronized void finishWithoutExecution(ReconciliationPlan plan, List<String> diagnostics, boolean stale) {
        var steps = applyModel instanceof ApplyModel.Running running ? running.steps() : pendingSteps(plan);
        steps = steps.stream().map(step -> step.status() == ApplyModel.StepStatus.RUNNING
                ? new ApplyModel.Step(step.relocation(), step.action(), ApplyModel.StepStatus.FAILED, diagnostics.getFirst())
                : step).toList();
        statusModel = statusWorkflow.loadStatus(configPath);
        applyModel = new ApplyModel.Result(plan, steps, Optional.empty(), diagnostics, stale);
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

    private static String message(RuntimeException exception) {
        return exception.getMessage() == null ? exception.toString() : exception.getMessage();
    }

    private void reload() {
        reloadModels();
    }

    private void reloadModels() {
        this.statusModel = statusWorkflow.loadStatus(configPath);
        this.planModel = planWorkflow.loadPlan(configPath, overrides);
    }
}
