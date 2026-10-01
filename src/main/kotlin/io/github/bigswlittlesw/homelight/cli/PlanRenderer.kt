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

    fun render(plan: ReconciliationPlan, json: Boolean, output: PrintWriter) {
        render(plan, json, false, output)
    }

    fun render(plan: ReconciliationPlan, json: Boolean, noColor: Boolean, output: PrintWriter) {
        if (json) {
            output.println(toJson(plan))
            return
        }
        renderForConfirmation(plan, noColor, output)
    }

    fun renderJson(plan: ReconciliationPlan, output: PrintWriter) {
        output.println(toJson(plan))
    }

    fun renderForConfirmation(plan: ReconciliationPlan, noColor: Boolean, output: PrintWriter) {
        if (plan.actions().isEmpty() && plan.diagnostics.isEmpty() && !plan.hasConflicts()) {
            output.println(TerminalStyle(noColor).success("Plan is already up to date."))
            return
        }
        val style = TerminalStyle(noColor)
        val ready = plan.relocations.stream().filter { relocation -> relocation.conflict.isEmpty }.count()
        val heading = heading(plan, ready)
        output.println(style.heading(heading))
        output.println()
        for (diagnostic in plan.diagnostics) {
            renderDiagnostic(diagnostic, style, output)
        }
        for (relocation in plan.relocations) {
            for (diagnostic in relocation.diagnostics) {
                renderDiagnostic(diagnostic, style, output)
            }
            output.println("  " + relocation.relocation.sourcePath + " → " + relocation.relocation.targetPath)
            output.println("    Plan outcome: " + relocation.outcome.value())
            relocation.conflict.ifPresentOrElse(
                { conflict -> output.println("    " + style.error("! " + conflict.reason)) },
                { output.println("    " + intent(relocation)) },
            )
        }
        if (plan.hasBlockedActions()) {
            output.println()
            output.println(style.error("Apply is unavailable: " + firstBlockedReason(plan) + "."))
        } else if (plan.hasConflicts()) {
            output.println()
            output.println(style.error("No changes will be made until the required decisions are resolved."))
        } else {
            output.println()
            output.println("No changes have been made. Confirm to apply this plan.")
        }
    }

    private fun renderDiagnostic(diagnostic: ReconciliationDiagnostic, style: TerminalStyle, output: PrintWriter) {
        val label = if (diagnostic.severity == ReconciliationDiagnostic.Severity.ERROR)
            style.error("! " + diagnostic.message)
        else
            style.warning("! " + diagnostic.message)
        output.println("  " + label + " (" + diagnostic.source + ")")
    }

    private fun intent(relocation: RelocationPlan): String {
        val actions = relocation.actions
        if (actions.stream().anyMatch { it is ReconciliationAction.Blocked }) {
            val blocked = actions.stream()
                .filter { it is ReconciliationAction.Blocked }.findFirst().orElseThrow() as ReconciliationAction.Blocked
            return "Blocked: " + blocked.reason
        }
        if (actions.stream().anyMatch { it is ReconciliationAction.MigrateDirectoryForPublication }) {
            return "Migrate, verify, and atomically publish the source directory"
        }
        if (actions.stream().anyMatch { it is ReconciliationAction.NoOp }) {
            return "Already configured"
        }
        if (actions.stream().anyMatch { it is ReconciliationAction.ReplaceDirectoryWithSymlink }) {
            return "Adopt the target and replace the source with a link"
        }
        if (actions.stream().anyMatch { it is ReconciliationAction.DeleteDirectory }) {
            return "Discard configured: delete both source and target contents, then create an empty target and source link"
        }
        if (actions.stream().anyMatch { it is ReconciliationAction.ReplaceSymlink }) {
            return "Repair the source link"
        }
        if (actions.stream().anyMatch { it is ReconciliationAction.LeaveUnchanged }) {
            return "Leave source and target unmanaged"
        }
        return "Create a destination directory and link"
    }

    fun toJson(plan: ReconciliationPlan): String {
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
                    generator.writeStringField("outcome", relocation.outcome.value())
                    generator.writeArrayFieldStart("diagnostics")
                    for (diagnostic in relocation.diagnostics) {
                        writeDiagnostic(generator, diagnostic)
                    }
                    generator.writeEndArray()
                    relocation.conflict.ifPresent { conflict ->
                        try {
                            generator.writeObjectFieldStart("conflict")
                            generator.writeStringField("path", conflict.path.toString())
                            generator.writeStringField("reason", conflict.reason)
                            generator.writeArrayFieldStart("resolutions")
                            for (resolution in conflict.resolutions) {
                                generator.writeString(resolution.name.lowercase(Locale.getDefault()).replace('_', '-'))
                            }
                            generator.writeEndArray()
                            generator.writeEndObject()
                        } catch (exception: IOException) {
                            throw IllegalStateException(exception)
                        }
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
        ActionJson.writeFields(generator, action)
        generator.writeEndObject()
    }

    companion object {
        private fun heading(plan: ReconciliationPlan, ready: Long): String {
            if (plan.hasBlockedActions()) {
                return "Plan cannot be applied"
            }
            if (plan.hasConflicts()) {
                return "Plan has unresolved decisions"
            }
            return "Plan: " + ready + plural(ready.toInt(), "relocation") + " ready"
        }

        private fun firstBlockedReason(plan: ReconciliationPlan): String = plan.actions().stream()
            .filter { it is ReconciliationAction.Blocked }
            .map { (it as ReconciliationAction.Blocked).reason }
            .findFirst()
            .orElseThrow()

        private fun plural(count: Int, noun: String): String = if (count == 1) " $noun" else " " + noun + "s"
    }
}
