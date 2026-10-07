package io.github.bigswlittlesw.homelight.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.findObject
import com.github.ajalt.clikt.core.obj
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.NullableOption
import com.github.ajalt.clikt.parameters.options.OptionTransformContext
import com.github.ajalt.clikt.parameters.options.OptionWithValues
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.transformAll
import com.github.ajalt.clikt.parameters.options.versionOption
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.path
import io.github.bigswlittlesw.homelight.application.DEBUG_STEP_DELAY_MILLIS
import io.github.bigswlittlesw.homelight.application.guideUrl
import io.github.bigswlittlesw.homelight.application.internalErrorMessage
import io.github.bigswlittlesw.homelight.application.resolveVersion
import io.github.bigswlittlesw.homelight.config.ConfigurationException
import io.github.bigswlittlesw.homelight.config.ConfigurationLoader
import io.github.bigswlittlesw.homelight.tui.launchTui
import java.io.PrintWriter
import java.nio.file.Path
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import kotlin.system.exitProcess

/** The application and native-image entry point. */
fun main(args: Array<String>) {
    exitProcess(homeLightCommand().execute(*args))
}

/**
 * The command tree, writing to [out] and [err]. `apply --json` runs the plan on [worker].
 *
 * The streams write UTF-8, as picocli's did. `System.out` alone follows the locale, and under a POSIX locale
 * the native binary would print the guide's arrows and box drawing as `?`.
 */
internal fun homeLightCommand(
    out: PrintWriter = PrintWriter(System.out.writer(Charsets.UTF_8), true),
    err: PrintWriter = PrintWriter(System.err.writer(Charsets.UTF_8), true),
    worker: Executor = Executor { it.run() },
): HomeLightCommand = HomeLightCommand(out, err).subcommands(
    StatusCommand(out, err), PlanCommand(out, err), ApplyCommand(out, err, worker), InitCommand(err), GuideCommand(out),
)

/** Root command: with no subcommand it opens the TUI. */
internal class HomeLightCommand(out: PrintWriter, private val err: PrintWriter) : ExitCodeCommand("homelight") {
    private val shared by SharedOptions()

    init {
        versionOption(
            resolveVersion(), help = "Print version information and exit.", names = setOf("-V", "--version"),
            message = { "homelight $it" },
        )
        context {
            echoMessage = { _, message, newline, toErr ->
                val writer = if (toErr) err else out
                if (newline) writer.println(message) else writer.print(message)
                writer.flush()
            }
        }
    }

    override val invokeWithoutSubcommand = true

    override fun help(context: Context) = "Relocates selected bulky home directories to machine-local storage."

    // The address depends on the version. clikt-core's plain formatter never wraps, so the address stays whole.
    override fun helpEpilog(context: Context) = "User guide: run homelight guide, or read it online:\n${guideUrl()}"

    override fun aliases() = mapOf("config" to listOf("init"))

    override fun call(): Int {
        val settings = shared.settings(outer = null)
        currentContext.obj = settings
        if (currentContext.invokedSubcommand != null) return 0
        return launchTui(settings.config, settings.debugStepDelayMillis, err)
    }
}

/**
 * Parses [args], runs the chosen command and returns the exit code.
 *
 * - `0`: success, `--help` and `--version`.
 * - `1`: a command's own failure, or a [ConfigurationException], printed as its message alone.
 * - `2`: a usage error. Clikt's default is 1, and automation depends on 2.
 * - `70`: any other exception is a bug: one [internalErrorMessage] line, no stack trace. A bug from a worker
 *   arrives wrapped in a [CompletionException].
 */
internal fun HomeLightCommand.execute(vararg args: String): Int = try {
    parse(args.asList())
    0
} catch (error: CliktError) {
    echoFormattedHelp(error)
    if (error is UsageError) USAGE_EXIT_CODE else error.statusCode
} catch (exception: ConfigurationException) {
    echo(exception.message, err = true)
    1
} catch (exception: Exception) {
    echo(internalErrorMessage((exception as? CompletionException)?.cause ?: exception), err = true)
    INTERNAL_ERROR_EXIT_CODE
}

internal const val USAGE_EXIT_CODE = 2

/** `EX_SOFTWARE` from BSD `sysexits.h`: an internal software error. */
private const val INTERNAL_ERROR_EXIT_CODE = 70

/** A command whose body returns its exit code. Clikt's `run` returns nothing, so a failure ends it by throwing. */
internal abstract class ExitCodeCommand(name: String) : CoreCliktCommand(name) {
    abstract fun call(): Int

    final override fun run() {
        val code = call()
        if (code != 0) throw ProgramResult(code)
    }
}

/**
 * Rejects an option given twice on one command, as picocli did; Clikt's default keeps the last value. [value] turns
 * the one value, or `null`, into the option's value. It replaces Clikt's `default()` and `required()`, which would
 * otherwise drop this check: mark it [required] for help. The same shared option before and after the command name
 * is two options, so that stays allowed.
 */
internal fun <AllT, EachT, ValueT> NullableOption<EachT, ValueT>.once(
    required: Boolean = false, value: OptionTransformContext.(EachT?) -> AllT,
): OptionWithValues<AllT, EachT, ValueT> = transformAll(showAsRequired = required) { values ->
    if (values.size > 1) fail("should be given only once")
    value(values.lastOrNull())
}

/** The options every command shares, resolved. */
internal data class Settings(val config: Path, val debugStepDelayMillis: Long)

/**
 * `--config` and `--debug-step-delay-ms`, accepted before or after the subcommand name. Clikt has no inherited
 * options, so every command declares the group, and a value after the subcommand name wins.
 */
internal class SharedOptions : OptionGroup() {
    private val config by option("-c", "--config", help = "Path to configuration file.").path().once { it }

    private val debugStepDelayMillis by option(
        "--debug-step-delay-ms", hidden = true,
        help = "Hold each TUI action in its running state for visual testing (0–60000 ms).",
    ).long().once { it }.check(
        "must be between ${DEBUG_STEP_DELAY_MILLIS.first} and ${DEBUG_STEP_DELAY_MILLIS.last}",
    ) { it in DEBUG_STEP_DELAY_MILLIS }

    fun settings(outer: Settings?) = Settings(
        config ?: outer?.config ?: ConfigurationLoader.DEFAULT_PATH,
        debugStepDelayMillis ?: outer?.debugStepDelayMillis ?: 0,
    )
}

/** A subcommand's settings: its own shared options over the root's. */
internal fun CoreCliktCommand.settings(shared: SharedOptions): Settings =
    shared.settings(outer = currentContext.findObject<Settings>())
