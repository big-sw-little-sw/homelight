package io.github.bigswlittlesw.homelight.reconcile

/**
 * The complete, filesystem-independent result of reconciliation planning.
 *
 * `expectedStates` is the review snapshot that whole-plan preflight compares against. A hand-assembled
 * plan leaves it empty and so cannot pass preflight.
 */
data class ReconciliationPlan(
    val relocations: List<RelocationPlan>,
    val diagnostics: List<ReconciliationDiagnostic>,
    val expectedStates: List<RelocationState> = listOf(),
) {
    fun actions(): List<ReconciliationAction> = relocations.flatMap { relocation -> relocation.actions }

    fun hasBlockedActions(): Boolean = actions().any { it is ReconciliationAction.Blocked }

    fun hasChanges(): Boolean = actions().any { it.mutatesFilesystem }

    fun hasConflicts(): Boolean = relocations.any { relocation -> relocation.conflict != null }
}
