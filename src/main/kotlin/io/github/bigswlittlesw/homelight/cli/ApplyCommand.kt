package io.github.bigswlittlesw.homelight.cli

import io.github.bigswlittlesw.homelight.application.ApplyModel
import io.github.bigswlittlesw.homelight.application.ConfigurationEvaluation
import io.github.bigswlittlesw.homelight.application.ReviewedExecution
import io.github.bigswlittlesw.homelight.application.isUnconfiguredDefault
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationExecutor
import io.github.bigswlittlesw.homelight.tui.launchTui
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand
import picocli.CommandLine.Spec
import java.io.PrintWriter
import java.util.concurrent.Callable
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor

// picocli creates the command through the no-arg constructor that the all-default primary
// constructor generates.
@Command(name = "apply", description = ["Review and apply a fully resolved reconciliation plan."])
internal class ApplyCommand(private val worker: Executor = Executor { it.run() }) : Callable<Int> {
    @field:ParentCommand
    private lateinit var parent: HomeLightCommand

    @field:Option(names = ["--yes"], description = ["Confirm a resolved plan in JSON automation mode."])
    private var yes = false

    @field:Option(names = ["--json"], description = ["Emit JSON."])
    private var json = false

    @field:Spec
    private lateinit var spec: CommandLine.Model.CommandSpec

    override fun call(): Int {
        val config = parent.config
        if (!json) {
            return launchTui(config, parent.debugStepDelayMillis, spec.commandLine().err)
        }
        if (!yes) {
            spec.commandLine().err.println("JSON apply requires --yes.")
            return CommandLine.ExitCode.USAGE
        }
        val output = spec.commandLine().out
        if (isUnconfiguredDefault(config)) {
            ApplyRenderer().renderJson(ReconciliationExecutor.ExecutionResult(listOf()), output)
            return 0
        }
        val plan = ConfigurationEvaluation().loadRequired(config).plan
        if (plan.hasBlockedActions() || plan.hasConflicts()) {
            PlanRenderer().renderJson(plan, output)
            return 1
        }
        val execution = ReviewedExecution(plan)
        execution.start(worker)
        return renderCompletion(execution, output)
    }
}

internal fun renderCompletion(execution: ReviewedExecution, output: PrintWriter): Int {
    try {
        execution.awaitExecution()
    } catch (exception: CompletionException) {
        // Completion failure must not hide evidence already published by the worker.
        val snapshot = execution.snapshot()
        if (snapshot is ApplyModel.Result && !snapshot.succeeded()) {
            ApplyRenderer().renderJson(snapshot, output)
            return 1
        }
        throw exception
    }
    val result = execution.snapshot() as ApplyModel.Result
    ApplyRenderer().renderJson(result, output)
    return if (result.succeeded()) 0 else 1
}
