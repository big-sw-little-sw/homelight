package io.github.bigswlittlesw.homelight.cli

import io.github.bigswlittlesw.homelight.reconcile.ReconciliationDiagnostic
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlan
import kotlinx.serialization.Serializable
import java.io.PrintWriter
import java.util.Locale

internal fun renderPlanJson(plan: ReconciliationPlan, output: PrintWriter) {
    output.println(encodeJson(PlanJson.serializer(), planJson(plan)))
}

@Serializable
private data class PlanJson(
    val blocked: Boolean,
    val conflicts: Boolean,
    val diagnostics: List<DiagnosticJson>,
    val relocations: List<RelocationPlanJson>,
    val actions: List<ActionJson>,
)

@Serializable
private data class RelocationPlanJson(
    val source: String,
    val target: String,
    val outcome: String,
    val diagnostics: List<DiagnosticJson>,
    val conflict: ConflictJson? = null,
    val actions: List<ActionJson>,
)

@Serializable
private data class ConflictJson(val path: String, val reason: String, val resolutions: List<String>)

@Serializable
private data class DiagnosticJson(val severity: String, val source: String, val code: String, val message: String)

private fun planJson(plan: ReconciliationPlan) = PlanJson(
    plan.hasBlockedActions(), plan.hasConflicts(), plan.diagnostics.map(::diagnosticJson),
    plan.relocations.map { relocation ->
        RelocationPlanJson(
            relocation.relocation.sourcePath.toString(), relocation.relocation.targetPath.toString(),
            relocation.outcome.value, relocation.diagnostics.map(::diagnosticJson),
            relocation.conflict?.let { conflict ->
                ConflictJson(
                    conflict.path.toString(), conflict.reason,
                    conflict.resolutions.map { it.name.lowercase(Locale.ROOT).replace('_', '-') },
                )
            },
            relocation.actions.map(::actionJson),
        )
    },
    plan.actions().map(::actionJson),
)

private fun diagnosticJson(diagnostic: ReconciliationDiagnostic) = DiagnosticJson(
    diagnostic.severity.name.lowercase(Locale.ROOT), diagnostic.source.toString(), diagnostic.code,
    diagnostic.message,
)
