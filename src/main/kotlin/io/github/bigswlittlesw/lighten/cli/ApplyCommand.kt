package io.github.bigswlittlesw.lighten.cli

import io.github.bigswlittlesw.lighten.application.ApplyModel
import io.github.bigswlittlesw.lighten.application.ConfigurationEvaluation
import io.github.bigswlittlesw.lighten.application.ReviewedExecution
import io.github.bigswlittlesw.lighten.application.isUnconfiguredDefault
import io.github.bigswlittlesw.lighten.reconcile.ReconciliationExecutor
import io.github.bigswlittlesw.lighten.tui.launchTui
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
    @ParentCommand
    private lateinit var parent: LightenCommand

    @Option(names = ["--yes"], description = ["Confirm a resolved plan in JSON automation mode."])
    private var yes = false

    @Option(names = ["--json"], description = ["Emit JSON."])
    private var json = false

    @Spec
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
            renderApplyJson(ReconciliationExecutor.ExecutionResult(listOf()), output)
            return CommandLine.ExitCode.OK
        }
        val loaded = ConfigurationEvaluation().loadRequired(config)
        val plan = loaded.plan
        if (plan.hasBlockedActions() || plan.hasConflicts()) {
            renderPlanJson(loaded, output)
            return 1
        }
        val execution = ReviewedExecution(plan)
        execution.start(worker)
        return renderCompletion(execution, output)
    }
}

/** A bug that stopped execution propagates, after the JSON evidence, so the CLI reports it as an internal error. */
internal fun renderCompletion(execution: ReviewedExecution, output: PrintWriter): Int {
    try {
        execution.awaitExecution()
    } catch (exception: CompletionException) {
        // A failed completion must not hide the results that the worker already published.
        val snapshot = execution.snapshot()
        if (snapshot is ApplyModel.Result && !snapshot.succeeded()) {
            renderApplyJson(snapshot, output)
        }
        throw exception
    }
    // ReviewedExecution publishes its final Result snapshot before its completion finishes, so the cast is safe.
    val result = execution.snapshot() as ApplyModel.Result
    renderApplyJson(result, output)
    return if (result.succeeded()) 0 else 1
}
