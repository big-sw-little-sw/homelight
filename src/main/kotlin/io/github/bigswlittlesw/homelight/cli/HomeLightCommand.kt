package io.github.bigswlittlesw.homelight.cli

import io.github.bigswlittlesw.homelight.config.ConfigurationLoader
import io.github.bigswlittlesw.homelight.tui.TuiLauncher
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Option
import picocli.CommandLine.Spec
import java.nio.file.Path
import java.util.concurrent.Callable

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
    private var config: Path = ConfigurationLoader.DEFAULT_PATH

    private var debugStepDelayMillis: Long = 0

    // @JvmName keeps the unmangled JVM name, as listed in the picocli native-image metadata.
    @JvmName("setDebugStepDelayMillis")
    @Option(
        names = ["--debug-step-delay-ms"], hidden = true, scope = CommandLine.ScopeType.INHERIT,
        description = ["Hold each TUI action in its running state for visual testing (0–60000 ms)."],
    )
    internal fun setDebugStepDelayMillis(milliseconds: Long) {
        if (milliseconds < 0 || milliseconds > 60_000) {
            throw IllegalArgumentException("--debug-step-delay-ms must be between 0 and 60000")
        }
        debugStepDelayMillis = milliseconds
    }

    fun debugStepDelayMillis(): Long = debugStepDelayMillis

    @field:Spec
    private lateinit var spec: CommandSpec

    fun config(): Path = config

    override fun call(): Int = TuiLauncher.launchStatus(config, debugStepDelayMillis, spec.commandLine().err)

    companion object {
        @JvmStatic
        fun main(vararg args: String) {
            val exitCode = execute(*args)
            System.exit(exitCode)
        }

        @JvmStatic
        fun execute(vararg args: String): Int = createCommandLine().execute(*args)

        @JvmStatic
        fun createCommandLine(): CommandLine = CommandLine(HomeLightCommand())
    }
}
