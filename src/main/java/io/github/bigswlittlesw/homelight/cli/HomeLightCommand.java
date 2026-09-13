package io.github.bigswlittlesw.homelight.cli;

import io.github.bigswlittlesw.homelight.config.ConfigurationLoader;
import io.github.bigswlittlesw.homelight.tui.TuiLauncher;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/// Root command and CLI entry point for HomeLight.
@Command(
    name = "homelight",
    subcommands = {StatusCommand.class, PlanCommand.class, ApplyCommand.class},
    mixinStandardHelpOptions = true,
    versionProvider = HomeLightVersionProvider.class,
    description = "Relocates selected bulky home directories to machine-local storage."
)
public final class HomeLightCommand implements Callable<Integer> {

    @Option(names = {"--config", "-c"}, description = "Path to configuration file.", scope = CommandLine.ScopeType.INHERIT)
    private Path config = ConfigurationLoader.DEFAULT_PATH;

    @Spec
    private CommandSpec spec;

    public Path config() {
        return config;
    }

    @Override
    public Integer call() {
        return TuiLauncher.launchStatus(config, spec.commandLine().getErr());
    }

    public static void main(String... args) {
        int exitCode = execute(args);
        System.exit(exitCode);
    }

    public static int execute(String... args) {
        return createCommandLine().execute(args);
    }

    public static CommandLine createCommandLine() {
        return new CommandLine(new HomeLightCommand());
    }
}
