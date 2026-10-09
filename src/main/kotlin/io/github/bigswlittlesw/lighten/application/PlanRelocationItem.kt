package io.github.bigswlittlesw.lighten.application

import io.github.bigswlittlesw.lighten.config.Relocation
import io.github.bigswlittlesw.lighten.fs.PathObservation
import io.github.bigswlittlesw.lighten.fs.PathState
import io.github.bigswlittlesw.lighten.fs.RelocationSourceState
import io.github.bigswlittlesw.lighten.reconcile.ReconciliationAction
import io.github.bigswlittlesw.lighten.reconcile.ReconciliationDiagnostic
import io.github.bigswlittlesw.lighten.reconcile.RelocationOutcome
import io.github.bigswlittlesw.lighten.reconcile.RelocationPlan

/**
 * One relocation in the plan: its configuration, what is on disk, the planned steps, and what decides it.
 *
 * `decision` is null when no rule governs the case observed now, or when the relocation takes no choice (a source
 * configured twice). `choiceAvoidsFolder` is true when the plan is blocked only by a folder in the way and one of
 * the offered choices plans without that block.
 */
data class PlanRelocationItem(
    val relocation: Relocation,
    val sourceObservation: PathObservation,
    val targetObservation: PathObservation,
    val plan: RelocationPlan,
    val sourceState: RelocationSourceState,
    val decision: RelocationDecision?,
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
        if (sourceState == RelocationSourceState.BROKEN_SYMLINK || plan.diagnostics.isNotEmpty()) {
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

    /**
     * A source link that points somewhere else is blocked, not a warning: the blocked reason says so. A broken link
     * is a warning only when the plan repairs it; when it is blocked, the reason says so too.
     */
    fun hasWarnings(): Boolean =
        (sourceState == RelocationSourceState.BROKEN_SYMLINK && !isBlocked())
                || plan.diagnostics.any { it.severity == ReconciliationDiagnostic.Severity.WARNING }

    fun hasConflict(): Boolean = plan.conflict != null || plan.outcome == RelocationOutcome.UNRESOLVED

    fun isBlocked(): Boolean = badge() == PlanBadge.BLOCKED || badge() == PlanBadge.INACCESSIBLE
}
