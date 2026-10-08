package io.github.bigswlittlesw.homelight.reconcile

import io.github.bigswlittlesw.homelight.config.Relocation

/** The planned outcome for one configured relocation. */
data class RelocationPlan(
    val relocation: Relocation,
    val outcome: RelocationOutcome,
    val actions: List<ReconciliationAction>,
    val diagnostics: List<ReconciliationDiagnostic>,
    val conflict: ReconciliationConflict? = null,
)
