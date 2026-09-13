package io.github.bigswlittlesw.homelight.application;

import io.github.bigswlittlesw.homelight.config.Relocation;
import io.github.bigswlittlesw.homelight.domain.RelocationSourceState;
import io.github.bigswlittlesw.homelight.fs.PathObservation;
import io.github.bigswlittlesw.homelight.fs.PathState;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationDiagnostic;
import io.github.bigswlittlesw.homelight.reconcile.RelocationOutcome;
import io.github.bigswlittlesw.homelight.reconcile.RelocationPlan;

import java.util.Comparator;
import java.util.List;

/// An evaluated relocation item combining configuration, observations, dry-run actions, and available decisions.
public record PlanRelocationItem(
        Relocation relocation,
        PathObservation sourceObservation,
        PathObservation targetObservation,
        RelocationPlan plan,
        RelocationSourceState sourceState,
        List<DecisionChoice> availableResolutions
) {
    public PlanRelocationItem {
        availableResolutions = List.copyOf(availableResolutions);
    }

    public static final Comparator<PlanRelocationItem> BY_URGENCY_AND_PATH = Comparator
            .comparingInt((PlanRelocationItem item) -> item.badge().priority())
            .thenComparing(item -> item.relocation().sourcePath().toString());

    public PlanBadge badge() {
        if (sourceObservation.state() == PathState.INACCESSIBLE || targetObservation.state() == PathState.INACCESSIBLE) {
            return PlanBadge.INACCESSIBLE;
        }
        if (plan.actions().stream().anyMatch(ReconciliationAction.Blocked.class::isInstance)) {
            return PlanBadge.BLOCKED;
        }
        if (plan.conflict().isPresent() || plan.outcome() == RelocationOutcome.UNRESOLVED) {
            return PlanBadge.CONFLICT;
        }
        if (plan.actions().stream().anyMatch(ReconciliationAction.ArchiveDirectory.class::isInstance)) {
            return PlanBadge.BACKUP;
        }
        if (plan.actions().stream().anyMatch(ReconciliationAction.StageDirectoryForPublication.class::isInstance)) {
            return PlanBadge.STAGE;
        }
        if (plan.actions().stream().anyMatch(ReconciliationAction.ReplaceDirectoryWithSymlink.class::isInstance)) {
            return PlanBadge.ADOPT;
        }
        if (plan.actions().stream().anyMatch(ReconciliationAction.DeleteDirectory.class::isInstance)) {
            return PlanBadge.DISCARD;
        }
        if (plan.actions().stream().anyMatch(ReconciliationAction.CreateSymlink.class::isInstance)
                || plan.actions().stream().anyMatch(ReconciliationAction.ReplaceSymlink.class::isInstance)) {
            return PlanBadge.LINK;
        }
        if (plan.outcome() == RelocationOutcome.UNCHANGED
                || plan.actions().stream().anyMatch(ReconciliationAction.LeaveUnchanged.class::isInstance)) {
            return PlanBadge.UNCHANGED;
        }
        if (sourceState == RelocationSourceState.WRONG_SYMLINK
                || sourceState == RelocationSourceState.BROKEN_SYMLINK
                || plan.diagnostics().stream().anyMatch(d -> d.severity() == ReconciliationDiagnostic.Severity.WARNING || d.severity() == ReconciliationDiagnostic.Severity.ERROR)) {
            return PlanBadge.WARNING;
        }
        if (plan.outcome() == RelocationOutcome.CONVERGED) {
            return PlanBadge.CONVERGED;
        }
        return PlanBadge.UNCHANGED;
    }

    public boolean hasDestructiveActions() {
        return plan.actions().stream().anyMatch(ReconciliationAction::destructive);
    }

    public boolean hasConflict() {
        return plan.conflict().isPresent() || plan.outcome() == RelocationOutcome.UNRESOLVED;
    }

    public boolean isBlocked() {
        return badge() == PlanBadge.BLOCKED || badge() == PlanBadge.INACCESSIBLE;
    }
}
