package io.github.bigswlittlesw.homelight.cli

import io.github.bigswlittlesw.homelight.application.DEBUG_STEP_DELAY_MILLIS
import io.github.bigswlittlesw.homelight.config.ConfigurationException
import io.github.bigswlittlesw.homelight.config.ConfigurationLoader
import io.github.bigswlittlesw.homelight.tui.launchTui
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Option
import picocli.CommandLine.ParameterException
import picocli.CommandLine.ParseResult
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
    @Option(
        names = ["--config", "-c"], description = ["Path to configuration file."],
        scope = CommandLine.ScopeType.INHERIT,
    )
    var config: Path = ConfigurationLoader.DEFAULT_PATH
        private set

    @Option(
        names = ["--debug-step-delay-ms"], hidden = true, scope = CommandLine.ScopeType.INHERIT,
        description = ["Hold each TUI action in its running state for visual testing (0–60000 ms)."],
    )
    var debugStepDelayMillis: Long = 0
        private set

    @Spec
    private lateinit var spec: CommandSpec

    override fun call(): Int = launchTui(config, debugStepDelayMillis, spec.commandLine().err)

    companion object {
        // The application and native-image entry point.
        @JvmStatic
        fun main(vararg args: String) {
            exitProcess(createCommandLine().execute(*args))
        }

        fun createCommandLine(): CommandLine = CommandLine(HomeLightCommand())
            .setExecutionStrategy(::executeValidated)
            .setExecutionExceptionHandler(::handleExecutionException)
    }
}

// Every command reads the inherited delay from the root, so one check covers them all, JSON mode included.
// picocli handles a ParameterException thrown here as a usage error: message, usage help, exit code 2.
private fun executeValidated(parseResult: ParseResult): Int {
    val root = parseResult.commandSpec().commandLine()
    if (root.getCommand<HomeLightCommand>().debugStepDelayMillis !in DEBUG_STEP_DELAY_MILLIS) {
        throw ParameterException(root,
            "--debug-step-delay-ms must be between ${DEBUG_STEP_DELAY_MILLIS.first} and ${DEBUG_STEP_DELAY_MILLIS.last}")
    }
    return CommandLine.RunLast().execute(parseResult)
}

/**
 * Prints a [ConfigurationException] as its message alone: it is the user's error, not a bug.
 * Rethrowing keeps picocli's default for everything else: a stack trace and exit code 1.
 */
private fun handleExecutionException(exception: Exception, commandLine: CommandLine, parseResult: ParseResult): Int {
    if (exception !is ConfigurationException) throw exception
    commandLine.err.println(exception.message)
    return CommandLine.ExitCode.SOFTWARE
}
