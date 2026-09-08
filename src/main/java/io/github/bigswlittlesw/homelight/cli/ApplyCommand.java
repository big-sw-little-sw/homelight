package io.github.bigswlittlesw.homelight.cli;

import io.github.bigswlittlesw.homelight.config.ConfigurationLoader;
import io.github.bigswlittlesw.homelight.fs.PathInspector;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationExecutor;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlanner;
import io.github.bigswlittlesw.homelight.reconcile.RelocationState;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "apply", description = "Apply a fully resolved reconciliation plan.")
final class ApplyCommand implements Callable<Integer> {
    @Option(names = "--config", description = "Configuration file.")
    private Path config = ConfigurationLoader.DEFAULT_PATH;

    @Option(names = "--yes", required = true, description = "Confirm non-interactive application.")
    private boolean yes;

    @Option(names = "--json", description = "Emit JSON.")
    private boolean json;

    @Option(names = "--no-color", description = "Disable terminal color.")
    private boolean noColor;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {
        return render(config, json, noColor, spec.commandLine().getOut());
    }

    static int render(Path config, PrintWriter output) {
        return render(config, false, false, output);
    }

    static int render(Path config, boolean json, boolean noColor, PrintWriter output) {
        if (isMissingDefaultConfig(config)) {
            output.println(json ? "{\"succeeded\":true,\"relocations\":[]}" : "No configuration available to apply.");
            return 0;
        }
        var configuration = new ConfigurationLoader().load(config);
        var inspector = new PathInspector();
        var states = configuration.relocations().stream()
                .map(relocation -> new RelocationState(relocation,
                        inspector.inspect(relocation.sourcePath()), inspector.inspect(relocation.targetPath())))
                .toList();
        var plan = new ReconciliationPlanner().plan(states);
        if (plan.hasBlockedActions() || plan.hasConflicts()) {
            new PlanRenderer().render(plan, json, noColor, output);
            return 1;
        }
        var result = new ReconciliationExecutor().execute(plan);
        new ApplyRenderer().render(result, json, noColor, output);
        return result.succeeded() ? 0 : 1;
    }

    private static boolean isMissingDefaultConfig(Path config) {
        return config.toAbsolutePath().normalize().equals(ConfigurationLoader.DEFAULT_PATH.toAbsolutePath().normalize())
                && !Files.isRegularFile(config);
    }
}
