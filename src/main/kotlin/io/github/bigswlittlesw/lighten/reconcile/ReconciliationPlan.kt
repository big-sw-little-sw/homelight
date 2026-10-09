package io.github.bigswlittlesw.lighten.reconcile

/**
 * The plan for every relocation, as plain data.
 *
 * `expectedStates` holds the observations made for review, which [ReconciliationExecutor.preflight] compares with the
 * disk. A plan built by hand leaves it empty, so it cannot pass preflight.
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
