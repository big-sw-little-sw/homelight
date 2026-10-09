package io.github.bigswlittlesw.lighten.cli

import io.github.bigswlittlesw.lighten.application.ApplyModel
import io.github.bigswlittlesw.lighten.fs.PathText
import io.github.bigswlittlesw.lighten.reconcile.ReconciliationExecutor
import kotlinx.serialization.Serializable
import java.io.PrintWriter
import java.util.Locale

internal fun renderApplyJson(result: ApplyModel.Result, output: PrintWriter) {
    val execution = result.execution
    if (execution != null) {
        renderApplyJson(execution, output)
        return
    }
    // There is no execution result. Report the known step results, without inventing one or implying nothing changed.
    val relocations = result.plan.relocations.map { relocation ->
        ReconciliationExecutor.RelocationExecution(
            relocation,
            result.steps.filter { step -> step.relocation === relocation }.map { step ->
                ReconciliationExecutor.ActionExecution(step.action, executionStatus(step.status), step.message)
            },
        )
    }
    output.println(encodeApplyJson(false, relocations, result.diagnostics, result.stale))
}

internal fun renderApplyJson(result: ReconciliationExecutor.ExecutionResult, output: PrintWriter) {
    output.println(encodeApplyJson(result.succeeded(), result.relocations, listOf(), false))
}

private fun encodeApplyJson(
    succeeded: Boolean, relocations: List<ReconciliationExecutor.RelocationExecution>,
    diagnostics: List<PathText>, stale: Boolean,
): String {
    val relocationsJson = relocations.map { relocation ->
        val configuredRelocation = relocation.relocation.relocation
        RelocationResultJson(
            configuredRelocation.sourcePath.toString(), configuredRelocation.targetPath.toString(),
            relocation.outcome().value,
            relocation.actions.map { action ->
                actionJson(action.action)
                    .copy(status = action.status.name.lowercase(Locale.ROOT), message = action.message)
            },
        )
    }
    val result = if (diagnostics.isEmpty()) ApplyJson(JSON_SCHEMA, succeeded, relocationsJson)
    else ApplyJson(JSON_SCHEMA, succeeded, relocationsJson, stale, diagnostics.map(PathText::toString))
    return encodeJson(ApplyJson.serializer(), result)
}

/** `stale` and `diagnostics` appear only together, when there are diagnostics. */
@Serializable
private data class ApplyJson(
    val schema: Int,
    val succeeded: Boolean,
    val relocations: List<RelocationResultJson>,
    val stale: Boolean? = null,
    val diagnostics: List<String>? = null,
)

@Serializable
private data class RelocationResultJson(
    val source: String, val target: String, val outcome: String, val actions: List<ActionJson>,
)

private fun executionStatus(status: ApplyModel.StepStatus): ReconciliationExecutor.ActionStatus = when (status) {
    ApplyModel.StepStatus.COMPLETED -> ReconciliationExecutor.ActionStatus.COMPLETED
    ApplyModel.StepStatus.FAILED, ApplyModel.StepStatus.RUNNING -> ReconciliationExecutor.ActionStatus.FAILED
    ApplyModel.StepStatus.PENDING -> ReconciliationExecutor.ActionStatus.PENDING
}
