package io.github.bigswlittlesw.lighten.cli

import io.github.bigswlittlesw.lighten.application.ConfigurationEvaluation
import io.github.bigswlittlesw.lighten.application.DecisionChoice
import io.github.bigswlittlesw.lighten.reconcile.ReconciliationDiagnostic
import io.github.bigswlittlesw.lighten.reconcile.ReconciliationPlan
import kotlinx.serialization.Serializable
import java.io.PrintWriter
import java.nio.file.Path
import java.util.Locale

/** `loaded` is null when there is no configuration at the default path, which plans nothing. */
internal fun renderPlanJson(loaded: ConfigurationEvaluation.Loaded?, output: PrintWriter) {
    val json = if (loaded == null) planJson(ReconciliationPlan(listOf(), listOf())) { listOf() }
        else planJson(loaded.plan, loaded::choicesFor)
    output.println(encodeJson(PlanJson.serializer(), json))
}

@Serializable
private data class PlanJson(
    val schema: Int,
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

/**
 * A conflict's `resolutions` are the one-time choices the Workspace offers for it, so scripts and people see the same
 * names; empty when no choice resolves it, such as a source link to somewhere else.
 *
 * They are [DecisionChoice] names in kebab case, not configuration values, on purpose: one choice can set two
 * settings, so `adopt-and-discard-source` is `when-source-and-target-directories-exist: adopt` plus
 * `when-adopting-target: discard-source`, and `discard-both` is `discard`.
 */
private fun planJson(plan: ReconciliationPlan, offered: (Path) -> List<DecisionChoice>) = PlanJson(
    JSON_SCHEMA, plan.hasBlockedActions(), plan.hasConflicts(), plan.diagnostics.map(::diagnosticJson),
    plan.relocations.map { relocation ->
        RelocationPlanJson(
            relocation.relocation.sourcePath.toString(), relocation.relocation.targetPath.toString(),
            relocation.outcome.value, relocation.diagnostics.map(::diagnosticJson),
            relocation.conflict?.let { conflict ->
                ConflictJson(
                    conflict.path.toString(), conflict.reason,
                    offered(relocation.relocation.sourcePath).map { it.name.lowercase(Locale.ROOT).replace('_', '-') },
                )
            },
            relocation.actions.map(::actionJson),
        )
    },
    plan.actions().map(::actionJson),
)

private fun diagnosticJson(diagnostic: ReconciliationDiagnostic) = DiagnosticJson(
    diagnostic.severity.name.lowercase(Locale.ROOT), diagnostic.source.toString(), diagnostic.code,
    diagnostic.message.toString(),
)
