package io.github.bigswlittlesw.homelight.cli;

import io.github.bigswlittlesw.homelight.config.ConfigurationLoader;
import io.github.bigswlittlesw.homelight.fs.PathInspector;
import io.github.bigswlittlesw.homelight.tui.TuiLauncher;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "status", description = "Show the state of the configured relocations.")
public final class StatusCommand implements Callable<Integer> {
    @ParentCommand
    private HomeLightCommand parent;

    @Option(names = "--json", description = "Emit JSON.")
    private boolean json;

    @Spec
    private CommandLine.Model.CommandSpec commandSpec;

    private Path config() {
        return parent != null ? parent.config() : ConfigurationLoader.DEFAULT_PATH;
    }

    @Override
    public Integer call() {
        var configPath = config();
        if (json) {
            if (isMissingDefaultConfig(configPath)) {
                new StatusRenderer().renderUnconfiguredJson(configPath, commandSpec.commandLine().getOut());
                return CommandLine.ExitCode.OK;
            }
            var configuration = new ConfigurationLoader().load(configPath);
            var snapshots = configuration.relocations().stream()
                    .map(relocation -> new StatusSnapshot(relocation.sourcePath(), relocation.targetPath(),
                            new PathInspector().inspectRelocationSource(relocation.sourcePath(), relocation.targetPath())))
                    .toList();
            new StatusRenderer().renderJson(snapshots, commandSpec.commandLine().getOut());
            return CommandLine.ExitCode.OK;
        }
        return TuiLauncher.launchStatus(configPath, parent.debugStepDelayMillis(), commandSpec.commandLine().getErr());
    }

    private static boolean isMissingDefaultConfig(Path config) {
        return config.toAbsolutePath().normalize().equals(ConfigurationLoader.DEFAULT_PATH.toAbsolutePath().normalize())
                && !Files.isRegularFile(config);
    }
}
