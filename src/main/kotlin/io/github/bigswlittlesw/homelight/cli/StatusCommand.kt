package io.github.bigswlittlesw.homelight.cli

import io.github.bigswlittlesw.homelight.application.ConfigurationEvaluation
import io.github.bigswlittlesw.homelight.config.ConfigurationLoader
import io.github.bigswlittlesw.homelight.tui.TuiLauncher
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand
import picocli.CommandLine.Spec
import java.nio.file.Path
import java.util.concurrent.Callable

@Command(name = "status", description = ["Show the state of the configured relocations."])
class StatusCommand : Callable<Int> {
    @field:ParentCommand
    private var parent: HomeLightCommand? = null

    @field:Option(names = ["--json"], description = ["Emit JSON."])
    private var json = false

    @field:Spec
    private lateinit var commandSpec: CommandLine.Model.CommandSpec

    private fun config(): Path = parent?.config() ?: ConfigurationLoader.DEFAULT_PATH

    override fun call(): Int {
        val configPath = config()
        if (json) {
            if (ConfigurationEvaluation.isUnconfiguredDefault(configPath)) {
                StatusRenderer().renderUnconfiguredJson(configPath, commandSpec.commandLine().out)
                return CommandLine.ExitCode.OK
            }
            val evaluation = ConfigurationEvaluation().loadRequired(configPath)
            val snapshots = evaluation.observations.stream()
                .map { state ->
                    StatusSnapshot(
                        state.relocation.sourcePath, state.relocation.targetPath,
                        state.source.sourceStateForTarget(state.relocation.targetPath),
                    )
                }
                .toList()
            StatusRenderer().renderJson(snapshots, commandSpec.commandLine().out)
            return CommandLine.ExitCode.OK
        }
        return TuiLauncher.launchStatus(configPath, parent!!.debugStepDelayMillis(), commandSpec.commandLine().err)
    }
}
