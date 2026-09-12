package io.github.bigswlittlesw.homelight.cli;

import io.github.bigswlittlesw.homelight.config.ConfigurationLoader;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlan;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "plan", description = "Show the filesystem actions required to converge configured relocations.")
final class PlanCommand implements Callable<Integer> {
    @Option(names = "--config", description = "Configuration file.")
    private Path config = ConfigurationLoader.DEFAULT_PATH;

    @Option(names = "--json", description = "Emit JSON.")
    private boolean json;

    @Option(names = "--no-color", description = "Disable terminal color.")
    private boolean noColor;

    @Option(names = "--source-path", description = "Override the source path for the first relocation.")
    private Path sourcePath;

    @Option(names = "--target-path", description = "Override the target path for the first relocation.")
    private Path targetPath;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {
        if ((sourcePath == null) != (targetPath == null)) {
            throw new ParameterException(spec.commandLine(), "--source-path and --target-path must be provided together");
        }
        var overrides = sourcePath == null ? Map.<String, String>of() : Map.of(
                "homelight.relocations[0].source-path", sourcePath.toString(),
                "homelight.relocations[0].target-path", targetPath.toString());
        return render(config, json, noColor, System.console() != null, overrides, spec.commandLine().getOut());
    }

    static int render(Path config, boolean json, Map<String, String> overrides, PrintWriter output) {
        return render(config, json, false, overrides, output);
    }

    static int render(Path config, boolean json, boolean noColor, Map<String, String> overrides, PrintWriter output) {
        return render(config, json, noColor, false, overrides, output);
    }

    private static int render(Path config, boolean json, boolean noColor, boolean interactive,
            Map<String, String> overrides, PrintWriter output) {
        if (isMissingDefaultConfig(config)) {
            if (json) {
                new PlanRenderer().render(new ReconciliationPlan(java.util.List.of(), java.util.List.of()), true, output);
            } else {
                output.println("No configuration available for planning.");
            }
            return 0;
        }
        var plan = new ReconciliationPlanning().plan(config, overrides);
        new PlanRenderer().render(plan, json, noColor, interactive, output);
        return 0;
    }

    private static boolean isMissingDefaultConfig(Path config) {
        return config.toAbsolutePath().normalize().equals(ConfigurationLoader.DEFAULT_PATH.toAbsolutePath().normalize())
                && !Files.isRegularFile(config);
    }
}
