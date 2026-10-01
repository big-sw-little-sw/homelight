package io.github.bigswlittlesw.homelight.reconcile

import io.github.bigswlittlesw.homelight.config.Relocation
import java.util.Objects
import java.util.Optional

/**
 * The planned outcome for one configured relocation.
 *
 * Not a `@JvmRecord data class`: the constructor copies its lists and replaces a null `conflict`,
 * which a Kotlin record cannot do. Accessors keep the record names; equality and `toString` match
 * the record this replaces.
 */
class RelocationPlan(
    relocation: Relocation,
    outcome: RelocationOutcome,
    actions: List<ReconciliationAction>,
    diagnostics: List<ReconciliationDiagnostic>,
    conflict: Optional<ReconciliationConflict>?,
) {
    @get:JvmName("relocation")
    val relocation: Relocation = relocation

    @get:JvmName("outcome")
    val outcome: RelocationOutcome = outcome

    @get:JvmName("actions")
    val actions: List<ReconciliationAction> = java.util.List.copyOf(actions)

    @get:JvmName("diagnostics")
    val diagnostics: List<ReconciliationDiagnostic> = java.util.List.copyOf(diagnostics)

    @get:JvmName("conflict")
    val conflict: Optional<ReconciliationConflict> = conflict ?: Optional.empty()

    override fun equals(other: Any?): Boolean = other is RelocationPlan
            && relocation == other.relocation
            && outcome == other.outcome
            && actions == other.actions
            && diagnostics == other.diagnostics
            && conflict == other.conflict

    override fun hashCode(): Int = Objects.hash(relocation, outcome, actions, diagnostics, conflict)

    override fun toString(): String = "RelocationPlan[relocation=$relocation, outcome=$outcome, actions=$actions, " +
            "diagnostics=$diagnostics, conflict=$conflict]"
}
