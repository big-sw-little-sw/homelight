package io.github.bigswlittlesw.homelight.tui

import dev.tamboui.backend.jline3.JLineBackend
import dev.tamboui.terminal.Backend
import dev.tamboui.toolkit.app.ToolkitRunner
import dev.tamboui.toolkit.element.Element
import dev.tamboui.tui.error.RenderErrorHandler
import io.github.bigswlittlesw.homelight.application.ConfigurationEvaluation
import io.github.bigswlittlesw.homelight.application.HomeLightSession
import org.jline.terminal.Terminal
import java.io.IOException
import java.io.PrintWriter
import java.nio.file.Path
import java.util.Optional
import java.util.function.Supplier

/** Launches the interactive HomeLight TUI with error handling and terminal validation. */
object TuiLauncher {
    internal const val NOT_INTERACTIVE = "HomeLight TUI requires an interactive terminal. Use --json for automation."
    internal const val DUMB_TERMINAL = "HomeLight TUI does not support a dumb terminal. " +
        "Set TERM to a terminal type such as xterm-256color, or use --json for automation."

    internal fun run(app: HomeLightApp) {
        val configured = app.configure()
        // Propagate render failures through the same waiting/cleanup boundary. The toolkit's
        // default error screen intercepts Escape before application navigation can handle it.
        val builder = configured.toBuilder().errorHandler(RenderErrorHandler { error, _ ->
            throw IllegalStateException("Unable to render HomeLight", error.cause())
        })
        if (configured.backend() == null) {
            builder.backend(systemBackend())
        }
        ToolkitRunner.create(builder.build()).use { runner ->
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
                app.session().awaitExecution()
            }
        }
    }

    @JvmStatic
    fun isInteractive(): Boolean = System.console() != null

    /** Returns why the TUI must not start, judged before any terminal is opened. */
    @JvmStatic
    @JvmName("refusal") // keeps the unmangled name the Java tests call
    internal fun refusal(interactive: Boolean, term: String?): Optional<String> {
        if (!interactive) return Optional.of(NOT_INTERACTIVE)
        if (isDumb(term)) return Optional.of(DUMB_TERMINAL)
        return Optional.empty()
    }

    internal fun isDumb(terminalType: String?): Boolean =
        Terminal.TYPE_DUMB == terminalType || Terminal.TYPE_DUMB_COLOR == terminalType

    /**
     * Opens the system terminal, refusing the dumb terminal JLine falls back to when no provider works;
     * it cannot render the TUI and would leave it waiting for input it never draws.
     */
    private fun systemBackend(): Backend {
        // The JNI provider extracts a library into java.io.tmpdir, which fails on a noexec /tmp, and
        // build-time -D values do not reach a native image's runtime. An explicit -D still wins.
        if ("runtime" == System.getProperty("org.graalvm.nativeimage.imagecode")
            && System.getProperty("org.jline.terminal.provider") == null
        ) {
            System.setProperty("org.jline.terminal.provider", "exec")
        }
        val backend = JLineBackend()
        if (isDumb(backend.jlineTerminal().type)) {
            backend.close()
            throw DumbTerminalException()
        }
        return backend
    }

    @JvmStatic
    fun launchStatus(configPath: Path, debugStepDelayMillis: Long, errorOutput: PrintWriter): Int =
        launch(configPath, debugStepDelayMillis, errorOutput)

    @JvmStatic
    fun launchPlan(configPath: Path, debugStepDelayMillis: Long, errorOutput: PrintWriter): Int =
        launch(configPath, debugStepDelayMillis, errorOutput)

    @JvmStatic
    fun launchInit(configPath: Path, debugStepDelayMillis: Long, errorOutput: PrintWriter): Int {
        val evaluation = ConfigurationEvaluation().load(configPath)
        if (evaluation is ConfigurationEvaluation.Loaded || evaluation is ConfigurationEvaluation.Invalid) {
            errorOutput.println("Configuration already exists or is unreadable; init only creates a missing configuration.")
            return 1
        }
        return launch(configPath, debugStepDelayMillis, errorOutput, true)
    }

    private fun launch(configPath: Path, debugStepDelayMillis: Long, errorOutput: PrintWriter): Int =
        launch(configPath, debugStepDelayMillis, errorOutput, false)

    private fun launch(configPath: Path, debugStepDelayMillis: Long, errorOutput: PrintWriter, startSetup: Boolean): Int {
        val refusal = refusal(isInteractive(), System.getenv("TERM"))
        if (refusal.isPresent) {
            errorOutput.println(refusal.get())
            return 2
        }
        try {
            val app = HomeLightApp(HomeLightSession(configPath, debugStepDelayMillis), startSetup)
            app.run()
            return 0
        } catch (exception: DumbTerminalException) {
            errorOutput.println(DUMB_TERMINAL)
            return 2
        } catch (exception: Exception) {
            errorOutput.println("Failed to run HomeLight TUI: " + (exception.message ?: exception.toString()))
            return 1
        }
    }

    private class DumbTerminalException : IOException()
}
