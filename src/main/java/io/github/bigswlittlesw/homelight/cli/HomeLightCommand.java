package io.github.bigswlittlesw.homelight.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

/// Root command and CLI entry point for HomeLight.
@Command(
    name = "homelight",
    subcommands = {StatusCommand.class, TuiCommand.class},
    mixinStandardHelpOptions = true,
    versionProvider = HomeLightVersionProvider.class,
    description = "Relocates selected bulky home directories to machine-local storage."
)
public final class HomeLightCommand implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().usage(spec.commandLine().getOut());
        return CommandLine.ExitCode.OK;
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
