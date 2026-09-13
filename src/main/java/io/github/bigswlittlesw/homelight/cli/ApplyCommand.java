package io.github.bigswlittlesw.homelight.cli;

import io.github.bigswlittlesw.homelight.config.ConfigurationLoader;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationExecutor;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlan;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "apply", description = "Apply a fully resolved reconciliation plan.")
final class ApplyCommand implements Callable<Integer> {
    @ParentCommand
    private HomeLightCommand parent;

    @Option(names = "--yes", description = "Apply without an interactive confirmation prompt.")
    private boolean yes;

    @Option(names = "--json", description = "Emit JSON.")
    private boolean json;

    @Option(names = "--no-color", description = "Disable terminal color.")
    private boolean noColor;

    @Option(names = "--debug-step-delay-ms", hidden = true, paramLabel = "MILLISECONDS",
            description = "Pause each action for visual testing.")
    private long debugStepDelayMillis;

    @Spec
    private CommandSpec spec;

    private Path config() {
        return parent != null ? parent.config() : ConfigurationLoader.DEFAULT_PATH;
    }

    @Override
    public Integer call() {
        if (debugStepDelayMillis < 0 || debugStepDelayMillis > 60_000) {
            throw new ParameterException(spec.commandLine(), "--debug-step-delay-ms must be between 0 and 60000");
        }
        return render(config(), json, noColor, debugStepDelayMillis, yes, System.console() != null, null,
                spec.commandLine().getOut());
    }

    static int render(Path config, PrintWriter output) {
        return render(config, false, false, output);
    }

    static int render(Path config, boolean json, boolean noColor, PrintWriter output) {
        return render(config, json, noColor, 0, true, false, null, output);
    }

    static int renderGuided(Path config, Confirmation confirmation, PrintWriter output) {
        return render(config, false, false, 0, false, false, confirmation, output);
    }

    private static int render(Path config, boolean json, boolean noColor,
            long debugStepDelayMillis, boolean yes, boolean interactive, Confirmation confirmation, PrintWriter output) {
        if (json && !yes) {
            output.println("JSON apply requires --yes.");
            return CommandLine.ExitCode.USAGE;
        }
        if (!yes && !interactive && confirmation == null) {
            output.println("Non-interactive apply requires --yes.");
            return CommandLine.ExitCode.USAGE;
        }
        if (isMissingDefaultConfig(config)) {
            new ApplyRenderer().renderUnconfigured(json, output);
            return 0;
        }
        var planning = new ReconciliationPlanning();
        var plan = planning.plan(config, Map.of());
        if (plan.hasConflicts() && !plan.hasBlockedActions() && !json && !yes && interactive
                && ConflictResolutionPrompt.supports(plan)) {
            var overrides = ConflictResolutionPrompt.resolve(plan, noColor);
            if (overrides.isEmpty()) {
                output.println("Cancelled. No changes made.");
                return CommandLine.ExitCode.OK;
            }
            plan = planning.plan(config, overrides.orElseThrow());
        }
        if (plan.hasBlockedActions() || plan.hasConflicts()) {
            new PlanRenderer().render(plan, json, noColor, output);
            return 1;
        }
        var hasChanges = plan.actions().stream().anyMatch(ReconciliationAction::mutatesFilesystem);
        var progress = json || !interactive || !hasChanges ? null
                : new ApplyProgress(output, plan, noColor, !yes, debugStepDelayMillis);
        if (!yes && hasChanges) {
            var confirmed = interactive ? progress.awaitConfirmation() : confirmed(plan, noColor, confirmation, output);
            if (!confirmed) {
                output.println("Cancelled. No changes made.");
                return CommandLine.ExitCode.OK;
            }
        }
        var result = progress == null
                ? new ReconciliationExecutor().execute(plan)
                : new ReconciliationExecutor().execute(plan, progress);
        if (progress != null) {
            progress.complete(result);
        }
        if (!json && progress == null) {
            ApplyProgress.renderResult(plan, result, noColor, output);
        }
        new ApplyRenderer().render(result, json, noColor, !json, output);
        return result.succeeded() ? 0 : 1;
    }

    private static boolean confirmed(ReconciliationPlan plan, boolean noColor, Confirmation confirmation, PrintWriter output) {
        new PlanRenderer().renderForConfirmation(plan, noColor, output);
        return confirmation.confirm();
    }

    private static boolean isMissingDefaultConfig(Path config) {
        return config.toAbsolutePath().normalize().equals(ConfigurationLoader.DEFAULT_PATH.toAbsolutePath().normalize())
                && !Files.isRegularFile(config);
    }

    @FunctionalInterface
    interface Confirmation {
        boolean confirm();
    }
}
