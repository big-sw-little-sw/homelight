package io.github.bigswlittlesw.homelight.cli;

import io.github.bigswlittlesw.homelight.config.ConfigurationLoader;
import io.github.bigswlittlesw.homelight.application.ConfigurationEvaluation;
import io.github.bigswlittlesw.homelight.tui.TuiLauncher;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.Map;

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
            if (ConfigurationEvaluation.isUnconfiguredDefault(configPath)) {
                new StatusRenderer().renderUnconfiguredJson(configPath, commandSpec.commandLine().getOut());
                return CommandLine.ExitCode.OK;
            }
            var evaluation = new ConfigurationEvaluation().loadRequired(configPath, Map.of());
            var snapshots = evaluation.observations().stream()
                    .map(state -> new StatusSnapshot(state.relocation().sourcePath(), state.relocation().targetPath(),
                            state.source().sourceStateForTarget(state.relocation().targetPath())))
                    .toList();
            new StatusRenderer().renderJson(snapshots, commandSpec.commandLine().getOut());
            return CommandLine.ExitCode.OK;
        }
        return TuiLauncher.launchStatus(configPath, parent.debugStepDelayMillis(), commandSpec.commandLine().getErr());
    }

}
