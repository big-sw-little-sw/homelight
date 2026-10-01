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
import java.util.Objects
import java.util.Optional

/**
 * An evaluated relocation item combining configuration, observations, dry-run actions, and available decisions.
 *
 * Not a `@JvmRecord data class`: the constructor copies `availableResolutions`, which a Kotlin record
 * cannot do. Accessors keep the record names; equality and `toString` match the record this replaces.
 */
class PlanRelocationItem(
    relocation: Relocation,
    sourceObservation: PathObservation,
    targetObservation: PathObservation,
    plan: RelocationPlan,
    sourceState: RelocationSourceState,
    availableResolutions: List<DecisionChoice>,
) {
    @get:JvmName("relocation")
    val relocation: Relocation = relocation

    @get:JvmName("sourceObservation")
    val sourceObservation: PathObservation = sourceObservation

    @get:JvmName("targetObservation")
    val targetObservation: PathObservation = targetObservation

    @get:JvmName("plan")
    val plan: RelocationPlan = plan

    @get:JvmName("sourceState")
    val sourceState: RelocationSourceState = sourceState

    @get:JvmName("availableResolutions")
    val availableResolutions: List<DecisionChoice> = java.util.List.copyOf(availableResolutions)

    fun badge(): PlanBadge {
        if (sourceObservation.state == PathState.INACCESSIBLE || targetObservation.state == PathState.INACCESSIBLE) {
            return PlanBadge.INACCESSIBLE
        }
        if (plan.actions.stream().anyMatch { it is ReconciliationAction.Blocked }) {
            return PlanBadge.BLOCKED
        }
        if (plan.conflict != null || plan.outcome == RelocationOutcome.UNRESOLVED) {
            return PlanBadge.CONFLICT
        }
        if (plan.actions.stream().anyMatch { it is ReconciliationAction.ArchiveDirectory }) {
            return PlanBadge.BACKUP
        }
        if (plan.actions.stream().anyMatch { it is ReconciliationAction.MigrateDirectoryForPublication }) {
            return PlanBadge.MIGRATE
        }
        if (plan.actions.stream().anyMatch { it is ReconciliationAction.ReplaceDirectoryWithSymlink }) {
            return PlanBadge.ADOPT
        }
        if (plan.actions.stream().anyMatch { it is ReconciliationAction.DeleteDirectory }) {
            return PlanBadge.DISCARD
        }
        if (plan.actions.stream().anyMatch { it is ReconciliationAction.CreateSymlink }
            || plan.actions.stream().anyMatch { it is ReconciliationAction.ReplaceSymlink }
        ) {
            return PlanBadge.LINK
        }
        if (plan.outcome == RelocationOutcome.UNCHANGED
            || plan.actions.stream().anyMatch { it is ReconciliationAction.LeaveUnchanged }
        ) {
            return PlanBadge.SKIPPED
        }
        if (sourceState == RelocationSourceState.WRONG_SYMLINK
            || sourceState == RelocationSourceState.BROKEN_SYMLINK
            || plan.diagnostics.stream().anyMatch { d ->
                d.severity == ReconciliationDiagnostic.Severity.WARNING || d.severity == ReconciliationDiagnostic.Severity.ERROR
            }
        ) {
            return PlanBadge.WARNING
        }
        if (plan.outcome == RelocationOutcome.CONVERGED) {
            return PlanBadge.IN_SYNC
        }
        return PlanBadge.SKIPPED
    }

    fun hasDestructiveActions(): Boolean = plan.actions.stream().anyMatch(ReconciliationAction::destructive)

    fun hasWarnings(): Boolean =
        sourceState == RelocationSourceState.WRONG_SYMLINK || sourceState == RelocationSourceState.BROKEN_SYMLINK
                || plan.diagnostics.stream()
            .anyMatch { diagnostic -> diagnostic.severity == ReconciliationDiagnostic.Severity.WARNING }

    fun hasConflict(): Boolean = plan.conflict != null || plan.outcome == RelocationOutcome.UNRESOLVED

    fun isBlocked(): Boolean = badge() == PlanBadge.BLOCKED || badge() == PlanBadge.INACCESSIBLE

    fun selectedResolution(): Optional<DecisionChoice> {
        val whenBoth = relocation.whenSourceAndTargetDirectoriesExist
        if (whenBoth != null) {
            return when (whenBoth) {
                WhenSourceAndTargetDirectoriesExist.ADOPT -> {
                    val adopting = relocation.whenAdoptingTarget ?: WhenAdoptingTarget.PROMPT
                    when (adopting) {
                        WhenAdoptingTarget.DISCARD_SOURCE -> Optional.of(DecisionChoice.ADOPT_AND_DISCARD_SOURCE)
                        WhenAdoptingTarget.ARCHIVE_SOURCE -> Optional.of(DecisionChoice.ADOPT_AND_ARCHIVE_SOURCE)
                        WhenAdoptingTarget.PROMPT -> Optional.empty()
                    }
                }
                WhenSourceAndTargetDirectoriesExist.LEAVE_UNCHANGED -> Optional.of(DecisionChoice.LEAVE_UNCHANGED)
                WhenSourceAndTargetDirectoriesExist.DISCARD -> Optional.of(DecisionChoice.DISCARD_BOTH)
                WhenSourceAndTargetDirectoriesExist.PROMPT -> Optional.empty()
            }
        }
        val whenOnly = relocation.whenOnlyTargetExists
        if (whenOnly != null) {
            return when (whenOnly) {
                WhenOnlyTargetExists.ADOPT_TARGET -> Optional.of(DecisionChoice.ADOPT_TARGET)
                WhenOnlyTargetExists.PROMPT -> Optional.empty()
            }
        }
        return Optional.empty()
    }

    override fun equals(other: Any?): Boolean = other is PlanRelocationItem
            && relocation == other.relocation
            && sourceObservation == other.sourceObservation
            && targetObservation == other.targetObservation
            && plan == other.plan
            && sourceState == other.sourceState
            && availableResolutions == other.availableResolutions

    override fun hashCode(): Int = Objects.hash(
        relocation, sourceObservation, targetObservation, plan, sourceState, availableResolutions,
    )

    override fun toString(): String = "PlanRelocationItem[relocation=$relocation, " +
            "sourceObservation=$sourceObservation, targetObservation=$targetObservation, plan=$plan, " +
            "sourceState=$sourceState, availableResolutions=$availableResolutions]"

    companion object {
        @JvmField
        val BY_URGENCY_AND_PATH: Comparator<PlanRelocationItem> = Comparator
            .comparingInt { item: PlanRelocationItem -> item.badge().priority() }
            .thenComparing { item: PlanRelocationItem -> item.relocation.sourcePath.toString() }
    }
}
