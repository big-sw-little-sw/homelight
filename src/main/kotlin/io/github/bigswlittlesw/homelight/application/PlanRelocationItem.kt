package io.github.bigswlittlesw.homelight.application

import io.github.bigswlittlesw.homelight.config.Relocation
import io.github.bigswlittlesw.homelight.config.WhenAdoptingTarget
import io.github.bigswlittlesw.homelight.config.WhenOnlyTargetExists
import io.github.bigswlittlesw.homelight.config.WhenSourceAndTargetDirectoriesExist
import io.github.bigswlittlesw.homelight.domain.RelocationSourceState
import io.github.bigswlittlesw.homelight.fs.PathObservation
import io.github.bigswlittlesw.homelight.fs.PathState
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationDiagnostic
import io.github.bigswlittlesw.homelight.reconcile.RelocationOutcome
import io.github.bigswlittlesw.homelight.reconcile.RelocationPlan

/** An evaluated relocation item combining configuration, observations, dry-run actions, and available decisions. */
data class PlanRelocationItem(
    val relocation: Relocation,
    val sourceObservation: PathObservation,
    val targetObservation: PathObservation,
    val plan: RelocationPlan,
    val sourceState: RelocationSourceState,
    val availableResolutions: List<DecisionChoice>,
) {
    fun badge(): PlanBadge {
        if (sourceObservation.state == PathState.INACCESSIBLE || targetObservation.state == PathState.INACCESSIBLE) {
            return PlanBadge.INACCESSIBLE
        }
        if (plan.actions.any { it is ReconciliationAction.Blocked }) {
            return PlanBadge.BLOCKED
        }
        if (plan.conflict != null || plan.outcome == RelocationOutcome.UNRESOLVED) {
            return PlanBadge.CONFLICT
        }
        if (plan.actions.any { it is ReconciliationAction.ArchiveDirectory }) {
            return PlanBadge.BACKUP
        }
        if (plan.actions.any { it is ReconciliationAction.MigrateDirectoryForPublication }) {
            return PlanBadge.MIGRATE
        }
        if (plan.actions.any { it is ReconciliationAction.ReplaceDirectoryWithSymlink }) {
            return PlanBadge.ADOPT
        }
        if (plan.actions.any { it is ReconciliationAction.DeleteDirectory }) {
            return PlanBadge.DISCARD
        }
        if (plan.actions.any { it is ReconciliationAction.CreateSymlink || it is ReconciliationAction.ReplaceSymlink }) {
            return PlanBadge.LINK
        }
        if (plan.outcome == RelocationOutcome.UNCHANGED || plan.actions.any { it is ReconciliationAction.LeaveUnchanged }) {
            return PlanBadge.SKIPPED
        }
        if (sourceState == RelocationSourceState.WRONG_SYMLINK
            || sourceState == RelocationSourceState.BROKEN_SYMLINK
            || plan.diagnostics.isNotEmpty()
        ) {
            return PlanBadge.WARNING
        }
        if (plan.outcome == RelocationOutcome.CONVERGED) {
            return PlanBadge.IN_SYNC
        }
        return PlanBadge.SKIPPED
    }

    fun hasDestructiveActions(): Boolean = plan.actions.any { it.destructive }

    fun hasWarnings(): Boolean =
        sourceState == RelocationSourceState.WRONG_SYMLINK || sourceState == RelocationSourceState.BROKEN_SYMLINK
                || plan.diagnostics.any { it.severity == ReconciliationDiagnostic.Severity.WARNING }

    fun hasConflict(): Boolean = plan.conflict != null || plan.outcome == RelocationOutcome.UNRESOLVED

    fun isBlocked(): Boolean = badge() == PlanBadge.BLOCKED || badge() == PlanBadge.INACCESSIBLE

    /** The decision the saved policy already makes, or null while it still prompts or has none. */
    fun selectedResolution(): DecisionChoice? {
        val whenBoth = relocation.whenSourceAndTargetDirectoriesExist
        if (whenBoth != null) {
            return when (whenBoth) {
                WhenSourceAndTargetDirectoriesExist.ADOPT -> when (relocation.whenAdoptingTarget) {
                    WhenAdoptingTarget.DiscardSource -> DecisionChoice.ADOPT_AND_DISCARD_SOURCE
                    is WhenAdoptingTarget.ArchiveSource -> DecisionChoice.ADOPT_AND_ARCHIVE_SOURCE
                    is WhenAdoptingTarget.Prompt, null -> null
                }
                WhenSourceAndTargetDirectoriesExist.LEAVE_UNCHANGED -> DecisionChoice.LEAVE_UNCHANGED
                WhenSourceAndTargetDirectoriesExist.DISCARD -> DecisionChoice.DISCARD_BOTH
                WhenSourceAndTargetDirectoriesExist.PROMPT -> null
            }
        }
        return when (relocation.whenOnlyTargetExists) {
            WhenOnlyTargetExists.ADOPT_TARGET -> DecisionChoice.ADOPT_TARGET
            WhenOnlyTargetExists.PROMPT, null -> null
        }
    }
}
