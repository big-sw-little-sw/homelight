package io.github.bigswlittlesw.homelight.cli

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonGenerator
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationDiagnostic
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlan
import io.github.bigswlittlesw.homelight.reconcile.RelocationPlan
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Locale

internal class PlanRenderer {
    private val jsonFactory = JsonFactory()

    fun renderJson(plan: ReconciliationPlan, output: PrintWriter) {
        output.println(toJson(plan))
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

    private fun toJson(plan: ReconciliationPlan): String {
        val json = StringWriter()
        try {
            jsonFactory.createGenerator(json).use { generator ->
                generator.writeStartObject()
                generator.writeBooleanField("blocked", plan.hasBlockedActions())
                generator.writeBooleanField("conflicts", plan.hasConflicts())
                generator.writeArrayFieldStart("diagnostics")
                for (diagnostic in plan.diagnostics) {
                    writeDiagnostic(generator, diagnostic)
                }
                generator.writeEndArray()
                generator.writeArrayFieldStart("relocations")
                for (relocation in plan.relocations) {
                    generator.writeStartObject()
                    generator.writeStringField("source", relocation.relocation.sourcePath.toString())
                    generator.writeStringField("target", relocation.relocation.targetPath.toString())
                    generator.writeStringField("outcome", relocation.outcome.value)
                    generator.writeArrayFieldStart("diagnostics")
                    for (diagnostic in relocation.diagnostics) {
                        writeDiagnostic(generator, diagnostic)
                    }
                    generator.writeEndArray()
                    relocation.conflict?.let { conflict ->
                        generator.writeObjectFieldStart("conflict")
                        generator.writeStringField("path", conflict.path.toString())
                        generator.writeStringField("reason", conflict.reason)
                        generator.writeArrayFieldStart("resolutions")
                        for (resolution in conflict.resolutions) {
                            generator.writeString(resolution.name.lowercase(Locale.getDefault()).replace('_', '-'))
                        }
                        generator.writeEndArray()
                        generator.writeEndObject()
                    }
                    generator.writeArrayFieldStart("actions")
                    for (action in relocation.actions) {
                        writeAction(generator, action)
                    }
                    generator.writeEndArray()
                    generator.writeEndObject()
                }
                generator.writeEndArray()
                generator.writeArrayFieldStart("actions")
                for (action in plan.actions()) {
                    writeAction(generator, action)
                }
                generator.writeEndArray()
                generator.writeEndObject()
            }
        } catch (exception: IOException) {
            throw IllegalStateException("Unable to render reconciliation plan as JSON", exception)
        }
        return json.toString()
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

private fun writeDiagnostic(generator: JsonGenerator, diagnostic: ReconciliationDiagnostic) {
    generator.writeStartObject()
    generator.writeStringField("severity", diagnostic.severity.name.lowercase(Locale.getDefault()))
    generator.writeStringField("source", diagnostic.source.toString())
    generator.writeStringField("code", diagnostic.code)
    generator.writeStringField("message", diagnostic.message)
    generator.writeEndObject()
}

private fun writeAction(generator: JsonGenerator, action: ReconciliationAction) {
    generator.writeStartObject()
    writeActionFields(generator, action)
    generator.writeEndObject()
}
