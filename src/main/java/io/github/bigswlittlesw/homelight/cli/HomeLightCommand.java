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
    subcommands = {StatusCommand.class, PlanCommand.class, ApplyCommand.class, InitCommand.class},
    mixinStandardHelpOptions = true,
    versionProvider = HomeLightVersionProvider.class,
    description = "Relocates selected bulky home directories to machine-local storage."
)
public final class HomeLightCommand implements Callable<Integer> {

    @Option(names = {"--config", "-c"}, description = "Path to configuration file.", scope = CommandLine.ScopeType.INHERIT)
    private Path config = ConfigurationLoader.DEFAULT_PATH;

    private long debugStepDelayMillis;

    @Option(names = "--debug-step-delay-ms", hidden = true, scope = CommandLine.ScopeType.INHERIT,
            description = "Hold each TUI action in its running state for visual testing (0–60000 ms).")
    void setDebugStepDelayMillis(long milliseconds) {
        if (milliseconds < 0 || milliseconds > 60_000) {
            throw new IllegalArgumentException("--debug-step-delay-ms must be between 0 and 60000");
        }
        debugStepDelayMillis = milliseconds;
    }

    public long debugStepDelayMillis() {
        return debugStepDelayMillis;
    }

    @Spec
    private CommandSpec spec;

    public Path config() {
        return config;
    }

    @Override
    public Integer call() {
        return TuiLauncher.launchStatus(config, debugStepDelayMillis, spec.commandLine().getErr());
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
