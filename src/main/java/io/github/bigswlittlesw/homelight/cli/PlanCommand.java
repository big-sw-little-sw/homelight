package io.github.bigswlittlesw.homelight.cli;

import io.github.bigswlittlesw.homelight.config.ConfigurationLoader;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlan;
import io.github.bigswlittlesw.homelight.tui.TuiLauncher;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

        if (json) {
            if (isMissingDefaultConfig(config)) {
                new PlanRenderer().renderJson(new ReconciliationPlan(List.of(), List.of()), spec.commandLine().getOut());
                return 0;
            }
            var plan = new ReconciliationPlanning().plan(config, overrides);
            new PlanRenderer().renderJson(plan, spec.commandLine().getOut());
            return 0;
        }

        return TuiLauncher.launchPlan(config, spec.commandLine().getErr());
    }

    private static boolean isMissingDefaultConfig(Path config) {
        return config.toAbsolutePath().normalize().equals(ConfigurationLoader.DEFAULT_PATH.toAbsolutePath().normalize())
                && !Files.isRegularFile(config);
    }
}
