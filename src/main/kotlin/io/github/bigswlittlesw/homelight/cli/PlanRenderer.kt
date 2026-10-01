package io.github.bigswlittlesw.homelight.cli

import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationDiagnostic
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlan
import io.github.bigswlittlesw.homelight.reconcile.RelocationPlan
import kotlinx.serialization.Serializable
import java.io.PrintWriter
import java.util.Locale

internal class PlanRenderer {
    fun renderJson(plan: ReconciliationPlan, output: PrintWriter) {
        output.println(encodeJson(PlanJson.serializer(), planJson(plan)))
    }

    fun renderForConfirmation(plan: ReconciliationPlan, noColor: Boolean, output: PrintWriter) {
        val style = TerminalStyle(noColor)
        if (plan.actions().isEmpty() && plan.diagnostics.isEmpty() && !plan.hasConflicts()) {
            output.println(style.success("Plan is already up to date."))
            return
        }
        output.println(style.heading(heading(plan, plan.relocations.count { it.conflict == null })))
        output.println()
        for (diagnostic in plan.diagnostics) {
            renderDiagnostic(diagnostic, style, output)
        }
        for (relocation in plan.relocations) {
            for (diagnostic in relocation.diagnostics) {
                renderDiagnostic(diagnostic, style, output)
            }
            output.println("  " + relocation.relocation.sourcePath + " → " + relocation.relocation.targetPath)
            output.println("    Plan outcome: " + relocation.outcome.value)
            val conflict = relocation.conflict
            output.println("    " + if (conflict != null) style.error("! " + conflict.reason) else intent(relocation))
        }
        output.println()
        output.println(
            when {
                plan.hasBlockedActions() -> style.error("Apply is unavailable: " + firstBlockedReason(plan) + ".")
                plan.hasConflicts() -> style.error("No changes will be made until the required decisions are resolved.")
                else -> "No changes have been made. Confirm to apply this plan."
            },
        )
    }
}

private fun renderDiagnostic(diagnostic: ReconciliationDiagnostic, style: TerminalStyle, output: PrintWriter) {
    val label = if (diagnostic.severity == ReconciliationDiagnostic.Severity.ERROR) style.error("! " + diagnostic.message)
    else style.warning("! " + diagnostic.message)
    output.println("  " + label + " (" + diagnostic.source + ")")
}

private fun intent(relocation: RelocationPlan): String {
    val actions = relocation.actions
    actions.filterIsInstance<ReconciliationAction.Blocked>().firstOrNull()?.let { return "Blocked: " + it.reason }
    return when {
        actions.any { it is ReconciliationAction.MigrateDirectoryForPublication } ->
            "Migrate, verify, and atomically publish the source directory"
        actions.any { it is ReconciliationAction.NoOp } -> "Already configured"
        actions.any { it is ReconciliationAction.ReplaceDirectoryWithSymlink } ->
            "Adopt the target and replace the source with a link"
        actions.any { it is ReconciliationAction.DeleteDirectory } ->
            "Discard configured: delete both source and target contents, then create an empty target and source link"
        actions.any { it is ReconciliationAction.ReplaceSymlink } -> "Repair the source link"
        actions.any { it is ReconciliationAction.LeaveUnchanged } -> "Leave source and target unmanaged"
        else -> "Create a destination directory and link"
    }
}

private fun heading(plan: ReconciliationPlan, ready: Int): String = when {
    plan.hasBlockedActions() -> "Plan cannot be applied"
    plan.hasConflicts() -> "Plan has unresolved decisions"
    else -> "Plan: $ready " + (if (ready == 1) "relocation" else "relocations") + " ready"
}

private fun firstBlockedReason(plan: ReconciliationPlan): String =
    plan.actions().filterIsInstance<ReconciliationAction.Blocked>().first().reason

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
                    conflict.resolutions.map { it.name.lowercase(Locale.getDefault()).replace('_', '-') },
                )
            },
            relocation.actions.map(::actionJson),
        )
    },
    plan.actions().map(::actionJson),
)

private fun diagnosticJson(diagnostic: ReconciliationDiagnostic) = DiagnosticJson(
    diagnostic.severity.name.lowercase(Locale.getDefault()), diagnostic.source.toString(), diagnostic.code,
    diagnostic.message,
)
