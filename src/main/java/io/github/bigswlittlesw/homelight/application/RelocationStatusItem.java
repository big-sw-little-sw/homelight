package io.github.bigswlittlesw.homelight.application;

import io.github.bigswlittlesw.homelight.config.Relocation;
import io.github.bigswlittlesw.homelight.domain.RelocationSourceState;
import io.github.bigswlittlesw.homelight.fs.PathObservation;
import io.github.bigswlittlesw.homelight.fs.PathState;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationDiagnostic;
import io.github.bigswlittlesw.homelight.reconcile.RelocationOutcome;
import io.github.bigswlittlesw.homelight.reconcile.RelocationPlan;

import java.util.List;

/// An evaluated relocation item combining configuration, observations, and plan.
public record RelocationStatusItem(
        Relocation relocation,
        PathObservation sourceObservation,
        PathObservation targetObservation,
        RelocationPlan plan,
        RelocationSourceState sourceState
) {
    public StatusBadge badge() {
        if (sourceObservation.state() == PathState.INACCESSIBLE || targetObservation.state() == PathState.INACCESSIBLE) {
            return StatusBadge.INACCESSIBLE;
        }
        if (plan.actions().stream().anyMatch(ReconciliationAction.Blocked.class::isInstance)) {
            return StatusBadge.BLOCKED;
        }
        if (plan.conflict().isPresent() || plan.outcome() == RelocationOutcome.UNRESOLVED) {
            return StatusBadge.CONFLICT;
        }
        if (sourceState == RelocationSourceState.WRONG_SYMLINK
                || sourceState == RelocationSourceState.BROKEN_SYMLINK
                || plan.diagnostics().stream().anyMatch(d -> d.severity() == ReconciliationDiagnostic.Severity.WARNING || d.severity() == ReconciliationDiagnostic.Severity.ERROR)) {
            return StatusBadge.WARNING;
        }
        if (plan.outcome() == RelocationOutcome.CONVERGED) {
            if (plan.actions().stream().anyMatch(ReconciliationAction::mutatesFilesystem)) {
                return StatusBadge.PENDING;
            }
            return StatusBadge.CONVERGED;
        }
        return StatusBadge.UNCHANGED;
    }

    public enum StatusBadge {
        CONVERGED("Converged"),
        PENDING("Pending"),
        CONFLICT("Conflict"),
        BLOCKED("Blocked"),
        WARNING("Warning"),
        INACCESSIBLE("Inaccessible"),
        UNCHANGED("Unchanged");

        private final String label;

        StatusBadge(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
