package io.github.bigswlittlesw.homelight.application;

import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationExecutor.ExecutionResult;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlan;
import io.github.bigswlittlesw.homelight.reconcile.RelocationPlan;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/// Immutable snapshots of one reviewed plan's confirmation and execution lifecycle.
public sealed interface ApplyModel {
    record Idle() implements ApplyModel { }

    record Confirmation(ReconciliationPlan plan) implements ApplyModel {
        public Confirmation {
            Objects.requireNonNull(plan, "plan");
        }
    }

    record Running(ReconciliationPlan plan, List<Step> steps) implements ApplyModel {
        public Running {
            Objects.requireNonNull(plan, "plan");
            steps = List.copyOf(steps);
        }
    }

    /// A result remains retained until explicit re-planning. Preflight failures have no execution.
    record Result(ReconciliationPlan plan, List<Step> steps, Optional<ExecutionResult> execution,
            List<String> diagnostics, boolean stale) implements ApplyModel {
        public Result {
            Objects.requireNonNull(plan, "plan");
            steps = List.copyOf(steps);
            execution = Objects.requireNonNull(execution, "execution");
            diagnostics = List.copyOf(diagnostics);
        }

        public boolean succeeded() {
            return execution.map(ExecutionResult::succeeded).orElse(false);
        }
    }

    record Step(RelocationPlan relocation, ReconciliationAction action, StepStatus status, String message) {
        public Step {
            Objects.requireNonNull(relocation, "relocation");
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(message, "message");
        }
    }

    enum StepStatus { PENDING, RUNNING, COMPLETED, FAILED }
}
