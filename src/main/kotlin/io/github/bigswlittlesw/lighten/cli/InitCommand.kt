package io.github.bigswlittlesw.homelight.cli

import io.github.bigswlittlesw.homelight.tui.launchConfiguration
import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.ParentCommand
import picocli.CommandLine.Spec
import java.util.concurrent.Callable

/** Opens Configuration on the configuration file, or on a new one when there is none. */
@Command(name = "init", aliases = ["config"], description = ["Create or change the configuration file."])
internal class InitCommand : Callable<Int> {
    @ParentCommand private lateinit var parent: HomeLightCommand
    @Spec private lateinit var spec: CommandSpec

    override fun call(): Int =
        launchConfiguration(parent.config, parent.debugStepDelayMillis, spec.commandLine().err)
}
