package io.github.bigswlittlesw.homelight.reconcile

import java.util.Objects

/**
 * The complete, filesystem-independent result of reconciliation planning.
 *
 * Not a `@JvmRecord data class`: the constructor copies its lists, which a Kotlin record cannot do.
 * Accessors keep the record names; equality and `toString` match the record this replaces.
 */
class ReconciliationPlan(
    relocations: List<RelocationPlan>, diagnostics: List<ReconciliationDiagnostic>,
    expectedStates: List<RelocationState>,
) {
    @get:JvmName("relocations")
    val relocations: List<RelocationPlan> = java.util.List.copyOf(relocations)

    @get:JvmName("diagnostics")
    val diagnostics: List<ReconciliationDiagnostic> = java.util.List.copyOf(diagnostics)

    @get:JvmName("expectedStates")
    val expectedStates: List<RelocationState> = java.util.List.copyOf(expectedStates)

    /** Hand-assembled plans have no review snapshot and cannot pass whole-plan preflight. */
    constructor(relocations: List<RelocationPlan>, diagnostics: List<ReconciliationDiagnostic>) :
            this(relocations, diagnostics, java.util.List.of())

    fun actions(): List<ReconciliationAction> =
        relocations.stream().flatMap { relocation -> relocation.actions.stream() }.toList()

    fun hasBlockedActions(): Boolean = actions().stream().anyMatch { it is ReconciliationAction.Blocked }

    fun hasChanges(): Boolean = actions().stream().anyMatch(ReconciliationAction::mutatesFilesystem)

    fun hasConflicts(): Boolean = relocations.stream().anyMatch { relocation -> relocation.conflict.isPresent }

    override fun equals(other: Any?): Boolean = other is ReconciliationPlan
            && relocations == other.relocations
            && diagnostics == other.diagnostics
            && expectedStates == other.expectedStates

    override fun hashCode(): Int = Objects.hash(relocations, diagnostics, expectedStates)

    override fun toString(): String =
        "ReconciliationPlan[relocations=$relocations, diagnostics=$diagnostics, expectedStates=$expectedStates]"
}
