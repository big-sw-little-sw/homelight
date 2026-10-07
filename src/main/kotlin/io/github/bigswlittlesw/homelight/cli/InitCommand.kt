package io.github.bigswlittlesw.homelight.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import io.github.bigswlittlesw.homelight.tui.launchConfiguration
import java.io.PrintWriter

/** Opens Configuration on the configuration file, or on a new one when there is none. Also runs as `config`. */
internal class InitCommand(private val err: PrintWriter) : ExitCodeCommand("init") {
    private val shared by SharedOptions()

    // Clikt's help lists a command by its name only, so the alias is named here.
    override fun help(context: Context) = "Create or change the configuration file. Also: config."

    override fun call(): Int {
        val settings = settings(shared)
        return launchConfiguration(settings.config, settings.debugStepDelayMillis, err)
    }
}
