package io.github.bigswlittlesw.homelight.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.nullableFlag
import com.github.ajalt.clikt.parameters.options.option
import io.github.bigswlittlesw.homelight.application.ApplyModel
import io.github.bigswlittlesw.homelight.application.ConfigurationEvaluation
import io.github.bigswlittlesw.homelight.application.ReviewedExecution
import io.github.bigswlittlesw.homelight.application.isUnconfiguredDefault
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationExecutor
import io.github.bigswlittlesw.homelight.tui.launchTui
import java.io.PrintWriter
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor

internal class ApplyCommand(
    private val out: PrintWriter, private val err: PrintWriter, private val worker: Executor,
) : ExitCodeCommand("apply") {
    private val shared by SharedOptions()
    private val yes by option("--yes", help = "Confirm a resolved plan in JSON automation mode.")
        .nullableFlag().once { it ?: false }
    private val json by option("--json", help = "Emit JSON.").nullableFlag().once { it ?: false }

    override fun help(context: Context) = "Review and apply a fully resolved reconciliation plan."

    override fun call(): Int {
        val settings = settings(shared)
        val config = settings.config
        if (!json) {
            return launchTui(config, settings.debugStepDelayMillis, err)
        }
        if (!yes) {
            err.println("JSON apply requires --yes.")
            return USAGE_EXIT_CODE
        }
        if (isUnconfiguredDefault(config)) {
            renderApplyJson(ReconciliationExecutor.ExecutionResult(listOf()), out)
            return 0
        }
        val plan = ConfigurationEvaluation().loadRequired(config).plan
        if (plan.hasBlockedActions() || plan.hasConflicts()) {
            renderPlanJson(plan, out)
            return 1
        }
        val execution = ReviewedExecution(plan)
        execution.start(worker)
        return renderCompletion(execution, out)
    }
}

/** A bug that stopped execution propagates, after the JSON evidence, so the CLI reports it as an internal error. */
internal fun renderCompletion(execution: ReviewedExecution, output: PrintWriter): Int {
    try {
        execution.awaitExecution()
    } catch (exception: CompletionException) {
        // Completion failure must not hide evidence already published by the worker.
        val snapshot = execution.snapshot()
        if (snapshot is ApplyModel.Result && !snapshot.succeeded()) {
            renderApplyJson(snapshot, output)
        }
        throw exception
    }
    // ReviewedExecution publishes its terminal Result snapshot before completion settles.
    val result = execution.snapshot() as ApplyModel.Result
    renderApplyJson(result, output)
    return if (result.succeeded()) 0 else 1
}
