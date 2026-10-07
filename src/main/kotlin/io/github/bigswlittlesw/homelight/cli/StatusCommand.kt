package io.github.bigswlittlesw.homelight.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.nullableFlag
import com.github.ajalt.clikt.parameters.options.option
import io.github.bigswlittlesw.homelight.application.ConfigurationEvaluation
import io.github.bigswlittlesw.homelight.application.isUnconfiguredDefault
import io.github.bigswlittlesw.homelight.tui.launchTui
import java.io.PrintWriter

internal class StatusCommand(private val out: PrintWriter, private val err: PrintWriter) : ExitCodeCommand("status") {
    private val shared by SharedOptions()
    private val json by option("--json", help = "Emit JSON.").nullableFlag().once { it ?: false }

    override fun help(context: Context) = "Show the state of the configured relocations."

    override fun call(): Int {
        val settings = settings(shared)
        val configPath = settings.config
        if (!json) {
            return launchTui(configPath, settings.debugStepDelayMillis, err)
        }
        if (isUnconfiguredDefault(configPath)) {
            renderStatusJson(configPath, listOf(), out, configured = false)
            return 0
        }
        val snapshots = ConfigurationEvaluation().loadRequired(configPath).observations.map { state ->
            StatusSnapshot(
                state.relocation.sourcePath, state.relocation.targetPath,
                state.source.sourceStateForTarget(state.relocation.targetPath),
            )
        }
        renderStatusJson(configPath, snapshots, out)
        return 0
    }
}
