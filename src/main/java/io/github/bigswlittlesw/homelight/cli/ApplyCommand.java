package io.github.bigswlittlesw.homelight.cli;

import io.github.bigswlittlesw.homelight.config.ConfigurationLoader;
import io.github.bigswlittlesw.homelight.application.ConfigurationEvaluation;
import io.github.bigswlittlesw.homelight.application.ApplyModel;
import io.github.bigswlittlesw.homelight.application.ReviewedExecution;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationExecutor;
import io.github.bigswlittlesw.homelight.tui.TuiLauncher;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

@Command(name = "apply", description = "Review and apply a fully resolved reconciliation plan.")
final class ApplyCommand implements Callable<Integer> {
    private final Executor worker;

    ApplyCommand() {
        this(Runnable::run);
    }

    ApplyCommand(Executor worker) {
        this.worker = worker;
    }

    @ParentCommand
    private HomeLightCommand parent;

    @Option(names = "--yes", description = "Confirm a resolved plan in JSON automation mode.")
    private boolean yes;

    @Option(names = "--json", description = "Emit JSON.")
    private boolean json;

    @Spec
    private CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() {
        var config = parent != null ? parent.config() : ConfigurationLoader.DEFAULT_PATH;
        if (!json) {
            return TuiLauncher.launchPlan(config, parent.debugStepDelayMillis(), spec.commandLine().getErr());
        }
        if (!yes) {
            spec.commandLine().getErr().println("JSON apply requires --yes.");
            return CommandLine.ExitCode.USAGE;
        }
        var output = spec.commandLine().getOut();
        if (ConfigurationEvaluation.isUnconfiguredDefault(config)) {
            new ApplyRenderer().renderJson(new ReconciliationExecutor.ExecutionResult(List.of()), output);
            return 0;
        }
        var plan = new ConfigurationEvaluation().loadRequired(config).plan();
        if (plan.hasBlockedActions() || plan.hasConflicts()) {
            new PlanRenderer().renderJson(plan, output);
            return 1;
        }
        var execution = new ReviewedExecution(plan);
        execution.start(worker);
        return renderCompletion(execution, output);
    }

    static int renderCompletion(ReviewedExecution execution, PrintWriter output) {
        try {
            execution.awaitExecution();
        } catch (CompletionException exception) {
            // Completion failure must not hide evidence already published by the worker.
            if (execution.snapshot() instanceof ApplyModel.Result result && !result.succeeded()) {
                new ApplyRenderer().renderJson(result, output);
                return 1;
            }
            throw exception;
        }
        var result = (ApplyModel.Result) execution.snapshot();
        new ApplyRenderer().renderJson(result, output);
        return result.succeeded() ? 0 : 1;
    }
}
