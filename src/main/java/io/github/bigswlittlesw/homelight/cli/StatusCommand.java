package io.github.bigswlittlesw.homelight.cli;

import io.github.bigswlittlesw.homelight.config.ConfigurationLoader;
import io.github.bigswlittlesw.homelight.fs.PathInspector;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "status", description = "Show the state of the configured relocation.")
final class StatusCommand implements Callable<Integer> {
    @Option(names = "--config", description = "Configuration file.")
    private Path config = ConfigurationLoader.DEFAULT_PATH;

    @Option(names = "--json", description = "Emit JSON.")
    private boolean json;

    @Override
    public Integer call() {
        return render(config, json, spec().commandLine().getOut());
    }

    static int render(Path config, boolean json, PrintWriter output) {
        var configuration = new ConfigurationLoader().load(config);
        var snapshots = configuration.relocations().stream()
                .map(relocation -> new StatusSnapshot(relocation.sourcePath(), relocation.targetPath(),
                        new PathInspector().inspect(relocation.sourcePath(), relocation.targetPath())))
                .toList();
        new StatusRenderer().render(snapshots, json, output);
        return 0;
    }

    private picocli.CommandLine.Model.CommandSpec spec() {
        return commandSpec;
    }

    @picocli.CommandLine.Spec
    private picocli.CommandLine.Model.CommandSpec commandSpec;
}
