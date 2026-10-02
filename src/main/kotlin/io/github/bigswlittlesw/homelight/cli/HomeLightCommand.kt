package io.github.bigswlittlesw.homelight.cli

import io.github.bigswlittlesw.homelight.application.DEBUG_STEP_DELAY_MILLIS
import io.github.bigswlittlesw.homelight.config.ConfigurationLoader
import io.github.bigswlittlesw.homelight.tui.launchTui
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Option
import picocli.CommandLine.Spec
import java.nio.file.Path
import java.util.concurrent.Callable
import kotlin.system.exitProcess

/** Root command and CLI entry point for HomeLight. */
@Command(
    name = "homelight",
    subcommands = [StatusCommand::class, PlanCommand::class, ApplyCommand::class, InitCommand::class],
    mixinStandardHelpOptions = true,
    versionProvider = HomeLightVersionProvider::class,
    description = ["Relocates selected bulky home directories to machine-local storage."],
)
class HomeLightCommand : Callable<Int> {
    @field:Option(
        names = ["--config", "-c"], description = ["Path to configuration file."],
        scope = CommandLine.ScopeType.INHERIT,
    )
    var config: Path = ConfigurationLoader.DEFAULT_PATH
        private set

    // picocli calls the public setter `setDebugStepDelayMillis(long)`, the name the native-image metadata lists.
    @set:Option(
        names = ["--debug-step-delay-ms"], hidden = true, scope = CommandLine.ScopeType.INHERIT,
        description = ["Hold each TUI action in its running state for visual testing (0–60000 ms)."],
    )
    var debugStepDelayMillis: Long = 0
        set(milliseconds) {
            require(milliseconds in DEBUG_STEP_DELAY_MILLIS) {
                "--debug-step-delay-ms must be between ${DEBUG_STEP_DELAY_MILLIS.first} and ${DEBUG_STEP_DELAY_MILLIS.last}"
            }
            field = milliseconds
        }

    @field:Spec
    private lateinit var spec: CommandSpec

    override fun call(): Int = launchTui(config, debugStepDelayMillis, spec.commandLine().err)

    companion object {
        // The application and native-image entry point.
        @JvmStatic
        fun main(vararg args: String) {
            exitProcess(createCommandLine().execute(*args))
        }

        fun createCommandLine(): CommandLine = CommandLine(HomeLightCommand())
    }
}
