package io.github.bigswlittlesw.lighten.cli

import io.github.bigswlittlesw.lighten.application.DEBUG_STEP_DELAY_MILLIS
import io.github.bigswlittlesw.lighten.application.guideUrl
import io.github.bigswlittlesw.lighten.application.internalErrorMessage
import io.github.bigswlittlesw.lighten.config.ConfigurationException
import io.github.bigswlittlesw.lighten.config.ConfigurationLoader
import io.github.bigswlittlesw.lighten.config.InvalidConfigurationException
import io.github.bigswlittlesw.lighten.tui.launchTui
import io.github.bigswlittlesw.lighten.tui.unreadableCli
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Model.OptionSpec
import picocli.CommandLine.Option
import picocli.CommandLine.ParameterException
import picocli.CommandLine.ParseResult
import picocli.CommandLine.Spec
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.CompletionException
import kotlin.system.exitProcess

/** Root command and CLI entry point for Lighten. */
@Command(
    name = "lighten",
    subcommands = [StatusCommand::class, PlanCommand::class, ApplyCommand::class, InitCommand::class, GuideCommand::class],
    mixinStandardHelpOptions = true,
    versionProvider = LightenVersionProvider::class,
    description = ["Relocates selected bulky home directories to machine-local storage."],
)
class LightenCommand : Callable<Int> {
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

        fun createCommandLine(): CommandLine = CommandLine(LightenCommand())
            .setExecutionStrategy(::executeValidated)
            .setExecutionExceptionHandler(::handleExecutionException)
            // Set here, not in @Command: the address depends on the version. It has a line of its own, so picocli's
            // wrapping at 80 columns never splits it.
            .also { it.commandSpec.usageMessage().footer("", "User guide: run lighten guide, or read it online:", guideUrl()) }
            .also { root -> root.subcommands.values.toSet().forEach { it.commandSpec.addOption(helpOption()) } }

        // mixinStandardHelpOptions is not inherited, and on a subcommand it would add -V too: --version belongs to
        // the root alone. Built here rather than annotated, so it needs no reflection metadata.
        private fun helpOption(): OptionSpec = OptionSpec.builder("-h", "--help")
            .usageHelp(true).description("Show this help message and exit.").build()
    }
}

// Every command reads the inherited delay from the root, so one check covers them all, JSON mode included.
// picocli handles a ParameterException thrown here as a usage error: message, usage help, exit code 2.
private fun executeValidated(parseResult: ParseResult): Int {
    val root = parseResult.commandSpec().commandLine()
    if (root.getCommand<LightenCommand>().debugStepDelayMillis !in DEBUG_STEP_DELAY_MILLIS) {
        throw ParameterException(root,
            "--debug-step-delay-ms must be between ${DEBUG_STEP_DELAY_MILLIS.first} and ${DEBUG_STEP_DELAY_MILLIS.last}")
    }
    return CommandLine.RunLast().execute(parseResult)
}

/** `EX_SOFTWARE` from BSD `sysexits.h`: an internal software error. */
private const val INTERNAL_ERROR_EXIT_CODE = 70

/**
 * Prints a [ConfigurationException] with exit code 1: it is the user's error. A file whose text or values are wrong
 * gets the Workspace's explanation and how to fix it; any other, such as a missing file, its message alone.
 *
 * Commands handle ordinary failures themselves, so anything else is a bug: one [internalErrorMessage] line and
 * exit code 70, with no stack trace. A bug from a worker arrives wrapped in a [CompletionException].
 */
private fun handleExecutionException(exception: Exception, commandLine: CommandLine, parseResult: ParseResult): Int {
    if (exception is InvalidConfigurationException) {
        unreadableCli(exception.path, exception.message.orEmpty(), exception.line > 0).forEach(commandLine.err::println)
        return 1
    }
    if (exception is ConfigurationException) {
        commandLine.err.println(exception.message)
        return 1
    }
    val bug = (exception as? CompletionException)?.cause ?: exception
    commandLine.err.println(internalErrorMessage(bug))
    return INTERNAL_ERROR_EXIT_CODE
}
