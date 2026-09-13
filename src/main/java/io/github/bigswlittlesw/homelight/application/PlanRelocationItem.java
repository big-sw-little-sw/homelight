package io.github.bigswlittlesw.homelight.application;

import io.github.bigswlittlesw.homelight.config.Relocation;
import io.github.bigswlittlesw.homelight.config.WhenAdoptingTarget;
import io.github.bigswlittlesw.homelight.domain.RelocationSourceState;
import io.github.bigswlittlesw.homelight.fs.PathObservation;
import io.github.bigswlittlesw.homelight.fs.PathState;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationDiagnostic;
import io.github.bigswlittlesw.homelight.reconcile.RelocationOutcome;
import io.github.bigswlittlesw.homelight.reconcile.RelocationPlan;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

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
        if (plan.actions().stream().anyMatch(ReconciliationAction.MigrateDirectoryForPublication.class::isInstance)) {
            return PlanBadge.MIGRATE;
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
            return PlanBadge.SKIPPED;
        }
        if (sourceState == RelocationSourceState.WRONG_SYMLINK
                || sourceState == RelocationSourceState.BROKEN_SYMLINK
                || plan.diagnostics().stream().anyMatch(d -> d.severity() == ReconciliationDiagnostic.Severity.WARNING || d.severity() == ReconciliationDiagnostic.Severity.ERROR)) {
            return PlanBadge.WARNING;
        }
        if (plan.outcome() == RelocationOutcome.CONVERGED) {
            return PlanBadge.IN_SYNC;
        }
        return PlanBadge.SKIPPED;
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

    public Optional<DecisionChoice> selectedResolution() {
        if (relocation.whenSourceAndTargetDirectoriesExist().isPresent()) {
            var whenBoth = relocation.whenSourceAndTargetDirectoriesExist().get();
            return switch (whenBoth) {
                case ADOPT -> {
                    var adopting = relocation.whenAdoptingTarget().orElse(WhenAdoptingTarget.PROMPT);
                    yield switch (adopting) {
                        case DISCARD_SOURCE -> Optional.of(DecisionChoice.ADOPT_AND_DISCARD_SOURCE);
                        case ARCHIVE_SOURCE -> Optional.of(DecisionChoice.ADOPT_AND_ARCHIVE_SOURCE);
                        case PROMPT -> Optional.empty();
                    };
                }
                case LEAVE_UNCHANGED -> Optional.of(DecisionChoice.LEAVE_UNCHANGED);
                case DISCARD -> Optional.of(DecisionChoice.DISCARD_BOTH);
                case PROMPT -> Optional.empty();
            };
        }
        if (relocation.whenOnlyTargetExists().isPresent()) {
            var whenOnly = relocation.whenOnlyTargetExists().get();
            return switch (whenOnly) {
                case ADOPT_TARGET -> Optional.of(DecisionChoice.ADOPT_TARGET);
                case PROMPT -> Optional.empty();
            };
        }
        return Optional.empty();
    }
}
