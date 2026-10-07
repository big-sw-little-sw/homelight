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

/**
 * An evaluated relocation item combining configuration, observations, dry-run actions, and available decisions.
 *
 * `choiceAvoidsFolder` is true when the plan is blocked only by a folder in the way and one of
 * `availableResolutions` plans without that block.
 */
data class PlanRelocationItem(
    val relocation: Relocation,
    val sourceObservation: PathObservation,
    val targetObservation: PathObservation,
    val plan: RelocationPlan,
    val sourceState: RelocationSourceState,
    val availableResolutions: List<DecisionChoice>,
    val choiceAvoidsFolder: Boolean = false,
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

    /**
     * Whether applying deletes data that is not kept anywhere else. A Move does not: it replaces the source with a
     * link only after the copy at the target is checked. Replacing a link deletes no data either.
     */
    fun deletesData(): Boolean {
        val copiedFirst = plan.actions.any { it is ReconciliationAction.MigrateDirectoryForPublication }
        return plan.actions.any { action ->
            action is ReconciliationAction.DeleteDirectory
                || (action is ReconciliationAction.ReplaceDirectoryWithSymlink && !copiedFirst)
        }
    }

    fun hasWarnings(): Boolean =
        sourceState == RelocationSourceState.WRONG_SYMLINK || sourceState == RelocationSourceState.BROKEN_SYMLINK
                || plan.diagnostics.any { it.severity == ReconciliationDiagnostic.Severity.WARNING }

    fun hasConflict(): Boolean = plan.conflict != null || plan.outcome == RelocationOutcome.UNRESOLVED

    fun isBlocked(): Boolean = badge() == PlanBadge.BLOCKED || badge() == PlanBadge.INACCESSIBLE

    /** The decision the saved rules already make, or null while they ask each time. */
    fun selectedResolution(): DecisionChoice? = when (relocation.whenSourceAndTargetDirectoriesExist) {
        WhenSourceAndTargetDirectoriesExist.ADOPT -> when (relocation.whenAdoptingTarget) {
            WhenAdoptingTarget.DISCARD_SOURCE -> DecisionChoice.ADOPT_AND_DISCARD_SOURCE
            WhenAdoptingTarget.ARCHIVE_SOURCE -> DecisionChoice.ADOPT_AND_ARCHIVE_SOURCE
            WhenAdoptingTarget.PROMPT -> null
        }
        WhenSourceAndTargetDirectoriesExist.LEAVE_UNCHANGED -> DecisionChoice.LEAVE_UNCHANGED
        WhenSourceAndTargetDirectoriesExist.DISCARD -> DecisionChoice.DISCARD_BOTH
        WhenSourceAndTargetDirectoriesExist.PROMPT -> when (relocation.whenOnlyTargetExists) {
            WhenOnlyTargetExists.ADOPT_TARGET -> DecisionChoice.ADOPT_TARGET
            WhenOnlyTargetExists.PROMPT -> null
        }
    }
}
