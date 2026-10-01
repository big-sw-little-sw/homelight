package io.github.bigswlittlesw.homelight.cli

import io.github.bigswlittlesw.homelight.application.ConfigurationEvaluation
import io.github.bigswlittlesw.homelight.application.isUnconfiguredDefault
import io.github.bigswlittlesw.homelight.tui.TuiLauncher
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand
import picocli.CommandLine.Spec
import java.util.concurrent.Callable

@Command(name = "status", description = ["Show the state of the configured relocations."])
internal class StatusCommand : Callable<Int> {
    @field:ParentCommand
    private lateinit var parent: HomeLightCommand

    @field:Option(names = ["--json"], description = ["Emit JSON."])
    private var json = false

    @field:Spec
    private lateinit var commandSpec: CommandLine.Model.CommandSpec

    override fun call(): Int {
        val configPath = parent.config
        if (!json) {
            return TuiLauncher.launchStatus(configPath, parent.debugStepDelayMillis, commandSpec.commandLine().err)
        }
        val output = commandSpec.commandLine().out
        if (isUnconfiguredDefault(configPath)) {
            StatusRenderer().renderUnconfiguredJson(configPath, output)
            return CommandLine.ExitCode.OK
        }
        val snapshots = ConfigurationEvaluation().loadRequired(configPath).observations.map { state ->
            StatusSnapshot(
                state.relocation.sourcePath, state.relocation.targetPath,
                state.source.sourceStateForTarget(state.relocation.targetPath),
            )
        }
        StatusRenderer().renderJson(snapshots, output)
        return CommandLine.ExitCode.OK
    }
}
