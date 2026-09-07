package io.github.bigswlittlesw.homelight.cli;

import io.github.bigswlittlesw.homelight.config.ConfigurationLoader;
import io.github.bigswlittlesw.homelight.fs.PathInspector;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.concurrent.Callable;

@Command(name = "status", description = "Show the state of the configured relocation.")
final class StatusCommand implements Callable<Integer> {
    @Option(names = "--config", description = "Configuration file.")
    private Path config = ConfigurationLoader.DEFAULT_PATH;

    @Option(names = "--json", description = "Emit JSON.")
    private boolean json;

    @Option(names = "--source-path", description = "Override the path to relocate.")
    private String sourcePath;

    @Option(names = "--target-path", description = "Override the relocation target path.")
    private String targetPath;

    @Override
    public Integer call() {
        return render(config, json, sourcePath, targetPath, spec().commandLine().getOut());
    }

    static int render(Path config, boolean json, String sourcePath, String targetPath, PrintWriter output) {
        var overrides = new HashMap<String, String>();
        if (sourcePath != null) {
            overrides.put("homelight.source-path", sourcePath);
        }
        if (targetPath != null) {
            overrides.put("homelight.target-path", targetPath);
        }
        var configuration = new ConfigurationLoader().load(config, overrides);
        var snapshot = new StatusSnapshot(configuration.sourcePath(), configuration.targetPath(),
                new PathInspector().inspect(configuration.sourcePath(), configuration.targetPath()));
        new StatusRenderer().render(snapshot, json, output);
        return 0;
    }

    private picocli.CommandLine.Model.CommandSpec spec() {
        return commandSpec;
    }

    @picocli.CommandLine.Spec
    private picocli.CommandLine.Model.CommandSpec commandSpec;
}
