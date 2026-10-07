package io.github.bigswlittlesw.homelight.tui

import dev.tamboui.backend.jline3.JLineBackend
import dev.tamboui.error.RuntimeIOException
import dev.tamboui.error.TerminalIOException
import dev.tamboui.terminal.Backend
import dev.tamboui.toolkit.app.ToolkitRunner
import dev.tamboui.toolkit.element.Element
import dev.tamboui.tui.TuiConfig
import dev.tamboui.tui.error.RenderErrorHandler
import io.github.bigswlittlesw.homelight.application.ConfigurationEvaluation
import io.github.bigswlittlesw.homelight.application.HomeLightSession
import io.github.bigswlittlesw.homelight.discovery.CandidateDiscovery
import org.jline.terminal.Terminal
import java.io.IOException
import java.io.PrintWriter
import java.io.UncheckedIOException
import java.nio.file.Path
import java.util.function.Supplier

internal const val NOT_INTERACTIVE = "HomeLight TUI requires an interactive terminal. Use --json for automation."
internal const val DUMB_TERMINAL = "HomeLight TUI does not support a dumb terminal. " +
    "Set TERM to a terminal type such as xterm-256color, or use --json for automation."

/**
 * Launches the interactive TUI with error handling and terminal validation, and returns the exit code:
 * 0 after a normal exit, 1 when the terminal fails, 2 when the terminal cannot run it.
 *
 * A bug propagates once the terminal is restored, and the CLI reports it as an internal error.
 */
internal fun launchTui(configPath: Path, debugStepDelayMillis: Long, errorOutput: PrintWriter, startSetup: Boolean = false): Int {
    // Since JDK 22, System.console() may return a console when input or output is redirected.
    terminalRefusal(System.console()?.isTerminal == true, System.getenv("TERM"))?.let { refusal ->
        errorOutput.println(refusal)
        return 2
    }
    try {
        runTui(HomeLightSession(configPath, debugStepDelayMillis), startSetup = startSetup)
        return 0
    } catch (_: DumbTerminalException) {
        errorOutput.println(DUMB_TERMINAL)
        return 2
    } catch (exception: Exception) {
        if (!isTerminalFailure(exception)) throw exception
        errorOutput.println("Failed to run HomeLight TUI: " + (exception.message ?: exception.toString()))
        return 1
    }
}

private fun isTerminalFailure(exception: Exception): Boolean = exception is IOException
    || exception is UncheckedIOException || exception is RuntimeIOException || exception is TerminalIOException

/** Opens manual setup only for a missing configuration; it never edits an existing file. */
internal fun launchInit(configPath: Path, debugStepDelayMillis: Long, errorOutput: PrintWriter): Int {
    val evaluation = ConfigurationEvaluation().load(configPath)
    if (evaluation is ConfigurationEvaluation.Loaded || evaluation is ConfigurationEvaluation.Invalid) {
        errorOutput.println("Configuration already exists or is unreadable; init only creates a missing configuration.")
        return 1
    }
    return launchTui(configPath, debugStepDelayMillis, errorOutput, startSetup = true)
}

/** Returns why the TUI must not start, judged before any terminal is opened. */
internal fun terminalRefusal(interactive: Boolean, term: String?): String? = when {
    !interactive -> NOT_INTERACTIVE
    isDumb(term) -> DUMB_TERMINAL
    else -> null
}

/**
 * The key handlers depend on [KEY_BINDINGS], so a custom configuration gets them too.
 *
 * The mouse is captured so the wheel reaches HomeLight as wheel events. Without capture a terminal sends it as arrow
 * keys, and a trackpad's sideways scrolling as ←/→, which switched Help's tabs. HomeLight uses only the wheel; to
 * select text, the user holds the terminal's bypass modifier (tui-design §3). TamboUI turns capture off again when
 * the runner closes, on every exit path.
 */
internal fun tuiConfig(custom: TuiConfig = TuiConfig.defaults()): TuiConfig =
    custom.toBuilder().bindings(KEY_BINDINGS).mouseCapture(true).build()

/** Runs the TUI on [config]'s backend, or the system terminal when it has none, until the user exits. */
internal fun runTui(
    session: HomeLightSession, config: TuiConfig = TuiConfig.defaults(), startSetup: Boolean = false,
    discoveryFactory: () -> CandidateDiscovery = { CandidateDiscovery() },
) {
    val configured = tuiConfig(config)
    // Propagate render and key-handling failures unchanged through the same waiting/cleanup boundary, so a bug
    // is reported by its own type and message. The toolkit's default error screen intercepts Escape before
    // application navigation can handle it.
    val builder = configured.toBuilder().errorHandler(RenderErrorHandler { error, _ -> throw error.cause() })
    if (configured.backend() == null) {
        builder.backend(systemBackend())
    }
    ToolkitRunner.create(builder.build()).use { runner ->
        val app = HomeLightApp(session, runner.focusManager(), startSetup, discoveryFactory)
        runner.eventRouter().addGlobalHandler(app.keyHandler)
        try {
            runner.run(Supplier<Element> {
                val view = app.render()
                if (app.exitRequested()) {
                    runner.quit()
                }
                view
            })
        } finally {
            app.closeSetup()
            session.awaitExecution()
        }
    }
}

// TERM may be unset.
private fun isDumb(terminalType: String?): Boolean =
    Terminal.TYPE_DUMB == terminalType || Terminal.TYPE_DUMB_COLOR == terminalType

/**
 * Opens the system terminal, refusing the dumb terminal JLine falls back to when no provider works;
 * it cannot render the TUI and would leave it waiting for input it never draws.
 */
private fun systemBackend(): Backend {
    // A native image always uses JLine's exec provider, even over an explicit -D: the JNI provider extracts a
    // library into java.io.tmpdir, which costs startup time and fails on a noexec /tmp, and it does not
    // fully restore terminal settings on exit. Build-time -D values do not reach a native image's runtime.
    // The JVM keeps JLine's default.
    if (System.getProperty("org.graalvm.nativeimage.imagecode") == "runtime") {
        System.setProperty("org.jline.terminal.provider", "exec")
    }
    val backend = JLineBackend()
    if (isDumb(backend.jlineTerminal().type)) {
        backend.close()
        throw DumbTerminalException()
    }
    return backend
}

private class DumbTerminalException : IOException()
