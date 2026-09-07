package io.github.bigswlittlesw.homelight.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "tui", description = "Show the baseline interactive status view.")
final class TuiCommand implements Callable<Integer> {
    @Option(names = "--config", description = "Configuration file.")
    private Path config = io.github.bigswlittlesw.homelight.config.ConfigurationLoader.DEFAULT_PATH;

    @Option(names = "--json", description = "Emit JSON.")
    private boolean json;

    @Option(names = "--local-root", description = "Override the local storage root.")
    private String localRoot;

    @Option(names = "--relocation-path", description = "Override the home path to relocate.")
    private String relocationPath;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {
        PrintWriter output = spec.commandLine().getOut();
        output.println("HomeLight TUI");
        return StatusCommand.render(config, json, localRoot, relocationPath, output);
    }
}
