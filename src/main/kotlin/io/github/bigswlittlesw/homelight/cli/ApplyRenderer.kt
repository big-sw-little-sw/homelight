package io.github.bigswlittlesw.homelight.cli

import com.fasterxml.jackson.core.JsonFactory
import io.github.bigswlittlesw.homelight.application.ApplyModel
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationExecutor
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Locale

internal class ApplyRenderer {
    fun renderJson(result: ApplyModel.Result, output: PrintWriter) {
        if (result.execution.isPresent) {
            renderJson(result.execution.orElseThrow(), output)
            return
        }
        // Preserve known action evidence without inventing an execution or implying zero mutation.
        val relocations = result.plan.relocations.stream().map { relocation ->
            ReconciliationExecutor.RelocationExecution(relocation, result.steps.stream()
                .filter { step -> step.relocation === relocation }
                .map { step ->
                    ReconciliationExecutor.ActionExecution(step.action, when (step.status) {
                        ApplyModel.StepStatus.COMPLETED -> ReconciliationExecutor.ActionStatus.COMPLETED
                        ApplyModel.StepStatus.FAILED, ApplyModel.StepStatus.RUNNING -> ReconciliationExecutor.ActionStatus.FAILED
                        ApplyModel.StepStatus.PENDING -> ReconciliationExecutor.ActionStatus.PENDING
                    }, step.message)
                }.toList())
        }.toList()
        output.println(toJson(false, relocations, result.diagnostics, result.stale))
    }

    fun renderJson(result: ReconciliationExecutor.ExecutionResult, output: PrintWriter) {
        output.println(toJson(result))
    }

    private val jsonFactory = JsonFactory()

    private fun toJson(result: ReconciliationExecutor.ExecutionResult): String =
        toJson(result.succeeded(), result.relocations, listOf(), false)

    private fun toJson(
        succeeded: Boolean, relocations: List<ReconciliationExecutor.RelocationExecution>,
        diagnostics: List<String>, stale: Boolean,
    ): String {
        val json = StringWriter()
        try {
            jsonFactory.createGenerator(json).use { generator ->
                generator.writeStartObject()
                generator.writeBooleanField("succeeded", succeeded)
                generator.writeArrayFieldStart("relocations")
                for (relocation in relocations) {
                    val configuredRelocation = relocation.relocation.relocation
                    generator.writeStartObject()
                    generator.writeStringField("source", configuredRelocation.sourcePath.toString())
                    generator.writeStringField("target", configuredRelocation.targetPath.toString())
                    generator.writeStringField("outcome", relocation.outcome().value)
                    generator.writeArrayFieldStart("actions")
                    for (action in relocation.actions) {
                        generator.writeStartObject()
                        ActionJson.writeFields(generator, action.action)
                        generator.writeStringField("status", action.status.name.lowercase(Locale.getDefault()))
                        generator.writeStringField("message", action.message)
                        generator.writeEndObject()
                    }
                    generator.writeEndArray()
                    generator.writeEndObject()
                }
                generator.writeEndArray()
                if (!diagnostics.isEmpty()) {
                    generator.writeBooleanField("stale", stale)
                    generator.writeArrayFieldStart("diagnostics")
                    for (diagnostic in diagnostics) {
                        generator.writeString(diagnostic)
                    }
                    generator.writeEndArray()
                }
                generator.writeEndObject()
            }
        } catch (exception: IOException) {
            throw IllegalStateException("Unable to render apply result as JSON", exception)
        }
        return json.toString()
    }
}
