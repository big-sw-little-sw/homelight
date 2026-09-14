package io.github.bigswlittlesw.homelight.cli;

import io.github.bigswlittlesw.homelight.config.ConfigurationLoader;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationExecutor;
import io.github.bigswlittlesw.homelight.tui.TuiLauncher;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "apply", description = "Review and apply a fully resolved reconciliation plan.")
final class ApplyCommand implements Callable<Integer> {
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
        if (config.toAbsolutePath().normalize().equals(ConfigurationLoader.DEFAULT_PATH.toAbsolutePath().normalize())
                && !Files.isRegularFile(config)) {
            new ApplyRenderer().renderJson(new ReconciliationExecutor.ExecutionResult(List.of()), output);
            return 0;
        }
        var plan = new ReconciliationPlanning().plan(config, Map.of());
        if (plan.hasBlockedActions() || plan.hasConflicts()) {
            new PlanRenderer().renderJson(plan, output);
            return 1;
        }
        var result = new ReconciliationExecutor().execute(plan);
        new ApplyRenderer().renderJson(result, output);
        return result.succeeded() ? 0 : 1;
    }
}
