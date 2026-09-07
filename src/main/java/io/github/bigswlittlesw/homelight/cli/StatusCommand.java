package io.github.bigswlittlesw.homelight.cli;

import io.github.bigswlittlesw.homelight.config.ConfigurationLoader;
import io.github.bigswlittlesw.homelight.fs.PathInspector;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.concurrent.Callable;

@Command(name = "status", description = "Show the state of the configured relocation.")
final class StatusCommand implements Callable<Integer> {
    @Option(names = "--config", description = "Configuration file.")
    private Path config = ConfigurationLoader.DEFAULT_PATH;

    @Option(names = "--json", description = "Emit JSON.")
    private boolean json;

    @Option(names = "--local-root", description = "Override the local storage root.")
    private String localRoot;

    @Option(names = "--relocation-path", description = "Override the home path to relocate.")
    private String relocationPath;

    @Override
    public Integer call() {
        return render(config, json, localRoot, relocationPath, spec().commandLine().getOut());
    }

    static int render(Path config, boolean json, String localRoot, String relocationPath, PrintWriter output) {
        var overrides = new HashMap<String, String>();
        if (localRoot != null) {
            overrides.put("homelight.local-root", localRoot);
        }
        if (relocationPath != null) {
            overrides.put("homelight.relocation-path", relocationPath);
        }
        var configuration = new ConfigurationLoader().load(config, overrides);
        var target = localTargetFor(configuration.localRoot(), configuration.relocationPath());
        var snapshot = new StatusSnapshot(configuration.relocationPath(), target,
                new PathInspector().inspect(configuration.relocationPath(), target));
        new StatusRenderer().render(snapshot, json, output);
        return 0;
    }

    private picocli.CommandLine.Model.CommandSpec spec() {
        return commandSpec;
    }

    @picocli.CommandLine.Spec
    private picocli.CommandLine.Model.CommandSpec commandSpec;

    static Path localTargetFor(Path localRoot, Path relocationPath) {
        var home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        var absoluteRelocation = relocationPath.toAbsolutePath().normalize();
        if (absoluteRelocation.startsWith(home)) {
            return localRoot.resolve(home.relativize(absoluteRelocation)).normalize();
        }
        return localRoot.resolve(absoluteRelocation.getFileName()).normalize();
    }
}
