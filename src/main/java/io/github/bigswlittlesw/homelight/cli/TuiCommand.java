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

    @Option(names = "--source-path", description = "Override the path to relocate.")
    private String sourcePath;

    @Option(names = "--target-path", description = "Override the relocation target path.")
    private String targetPath;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {
        PrintWriter output = spec.commandLine().getOut();
        output.println("HomeLight TUI");
        return StatusCommand.render(config, json, sourcePath, targetPath, output);
    }
}
