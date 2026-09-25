package io.github.bigswlittlesw.homelight.cli;

import io.github.bigswlittlesw.homelight.tui.TuiLauncher;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

/// Opens manual creation for a missing configuration; it never edits an existing file.
@Command(name = "init", description = "Create a new configuration through the interactive setup.")
final class InitCommand implements Callable<Integer> {
    @ParentCommand private HomeLightCommand parent;
    @Spec private picocli.CommandLine.Model.CommandSpec spec;

    @Override public Integer call() {
        return TuiLauncher.launchInit(parent.config(), parent.debugStepDelayMillis(), spec.commandLine().getErr());
    }
}
