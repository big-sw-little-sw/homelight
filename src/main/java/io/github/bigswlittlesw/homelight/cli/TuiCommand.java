package io.github.bigswlittlesw.homelight.cli;

import io.github.bigswlittlesw.homelight.config.ConfigurationLoader;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "tui", description = "Show the baseline interactive status view.")
final class TuiCommand implements Callable<Integer> {
    @Option(names = "--config", description = "Configuration file.")
    private Path config = ConfigurationLoader.DEFAULT_PATH;

    @Option(names = "--json", description = "Emit JSON.")
    private boolean json;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {
        PrintWriter output = spec.commandLine().getOut();
        output.println("HomeLight TUI");
        int status = StatusCommand.render(config, json, output);
        if (!json) {
            output.println("Plan");
            PlanCommand.render(config, false, java.util.Map.of(), output);
        }
        return status;
    }
}
