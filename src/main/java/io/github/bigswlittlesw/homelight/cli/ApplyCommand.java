package io.github.bigswlittlesw.homelight.cli;

import io.github.bigswlittlesw.homelight.config.ConfigurationLoader;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationExecutor;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.io.PrintWriter;
import java.io.Console;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "apply", description = "Apply a fully resolved reconciliation plan.")
final class ApplyCommand implements Callable<Integer> {
    @Option(names = "--config", description = "Configuration file.")
    private Path config = ConfigurationLoader.DEFAULT_PATH;

    @Option(names = "--yes", description = "Apply without an interactive confirmation prompt.")
    private boolean yes;

    @Option(names = "--json", description = "Emit JSON.")
    private boolean json;

    @Option(names = "--no-color", description = "Disable terminal color.")
    private boolean noColor;

    @Option(names = "--verbose", description = "Show planned internal steps.")
    private boolean verbose;

    @Option(names = "--debug-step-delay-ms", hidden = true, paramLabel = "MILLISECONDS",
            description = "Pause each action for visual testing.")
    private long debugStepDelayMillis;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {
        if (debugStepDelayMillis < 0 || debugStepDelayMillis > 60_000) {
            throw new ParameterException(spec.commandLine(), "--debug-step-delay-ms must be between 0 and 60000");
        }
        var console = System.console();
        Confirmation confirmation = console == null ? null : () -> confirmed(console);
        return render(config, json, noColor, verbose, debugStepDelayMillis, yes, confirmation, spec.commandLine().getOut());
    }

    static int render(Path config, PrintWriter output) {
        return render(config, false, false, output);
    }

    static int render(Path config, boolean json, boolean noColor, PrintWriter output) {
        return render(config, json, noColor, false, 0, true, null, output);
    }

    static int renderGuided(Path config, Confirmation confirmation, PrintWriter output) {
        return render(config, false, false, false, 0, false, confirmation, output);
    }

    private static int render(Path config, boolean json, boolean noColor, boolean verbose,
            long debugStepDelayMillis, boolean yes, Confirmation confirmation, PrintWriter output) {
        if (json && !yes) {
            output.println("JSON apply requires --yes.");
            return CommandLine.ExitCode.USAGE;
        }
        if (!yes && confirmation == null) {
            output.println("Non-interactive apply requires --yes.");
            return CommandLine.ExitCode.USAGE;
        }
        if (isMissingDefaultConfig(config)) {
            new ApplyRenderer().renderUnconfigured(json, output);
            return 0;
        }
        var plan = new ReconciliationPlanning().plan(config, Map.of());
        if (plan.hasBlockedActions() || plan.hasConflicts()) {
            new PlanRenderer().render(plan, json, noColor, output);
            return 1;
        }
        var hasChanges = plan.actions().stream().anyMatch(action -> !(action instanceof ReconciliationAction.NoOp));
        if (!yes && hasChanges) {
            new PlanRenderer().renderForConfirmation(plan, noColor, output);
            if (!confirmation.confirm()) {
                output.println("Cancelled. No changes made.");
                return CommandLine.ExitCode.OK;
            }
        }
        var progress = json || System.console() == null || !hasChanges ? null
                : new ApplyProgress(output, plan, noColor, verbose, debugStepDelayMillis);
        var result = progress == null
                ? new ReconciliationExecutor().execute(plan)
                : new ReconciliationExecutor().execute(plan, progress);
        if (progress != null) {
            progress.complete();
        }
        if (verbose && !json && progress == null) {
            ApplyProgress.renderResult(plan, result, noColor, output);
        }
        new ApplyRenderer().render(result, json, noColor, verbose || progress != null, output);
        return result.succeeded() ? 0 : 1;
    }

    private static boolean isMissingDefaultConfig(Path config) {
        return config.toAbsolutePath().normalize().equals(ConfigurationLoader.DEFAULT_PATH.toAbsolutePath().normalize())
                && !Files.isRegularFile(config);
    }

    private static boolean confirmed(Console console) {
        var answer = console.readLine("Apply this plan? [y/N] ");
        return answer != null && answer.trim().equalsIgnoreCase("y");
    }

    @FunctionalInterface
    interface Confirmation {
        boolean confirm();
    }
}
