package io.github.bigswlittlesw.homelight.cli

import io.github.bigswlittlesw.homelight.tui.TuiLauncher
import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.ParentCommand
import picocli.CommandLine.Spec
import java.util.concurrent.Callable

/** Opens manual creation for a missing configuration; it never edits an existing file. */
@Command(name = "init", description = ["Create a new configuration through the interactive setup."])
internal class InitCommand : Callable<Int> {
    @field:ParentCommand private lateinit var parent: HomeLightCommand
    @field:Spec private lateinit var spec: CommandSpec

    override fun call(): Int =
        TuiLauncher.launchInit(parent.config, parent.debugStepDelayMillis, spec.commandLine().err)
}
