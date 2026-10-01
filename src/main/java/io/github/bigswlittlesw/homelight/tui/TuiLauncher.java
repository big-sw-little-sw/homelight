package io.github.bigswlittlesw.homelight.tui;

import dev.tamboui.backend.jline3.JLineBackend;
import dev.tamboui.terminal.Backend;
import dev.tamboui.toolkit.app.ToolkitRunner;
import org.jline.terminal.Terminal;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Optional;

/// Launches the interactive HomeLight TUI with error handling and terminal validation.
public final class TuiLauncher {
    static final String NOT_INTERACTIVE = "HomeLight TUI requires an interactive terminal. Use --json for automation.";
    static final String DUMB_TERMINAL = "HomeLight TUI does not support a dumb terminal. "
            + "Set TERM to a terminal type such as xterm-256color, or use --json for automation.";

    static void run(HomeLightApp app) throws Exception {
        var configured = app.configure();
        // Propagate render failures through the same waiting/cleanup boundary. The toolkit's
        // default error screen intercepts Escape before application navigation can handle it.
        var builder = configured.toBuilder().errorHandler((error, _) -> {
            throw new IllegalStateException("Unable to render HomeLight", error.cause());
        });
        if (configured.backend() == null) {
            builder.backend(systemBackend());
        }
        try (var runner = ToolkitRunner.create(builder.build())) {
            try {
                runner.run(() -> {
                    var view = app.render();
                    if (app.exitRequested()) {
                        runner.quit();
                    }
                    return view;
                });
            } finally {
                app.closeSetup();
                app.session().awaitExecution();
            }
        }
    }

    public static boolean isInteractive() {
        return System.console() != null;
    }

    /// Returns why the TUI must not start, judged before any terminal is opened.
    static Optional<String> refusal(boolean interactive, String term) {
        if (!interactive) return Optional.of(NOT_INTERACTIVE);
        if (isDumb(term)) return Optional.of(DUMB_TERMINAL);
        return Optional.empty();
    }

    static boolean isDumb(String terminalType) {
        return Terminal.TYPE_DUMB.equals(terminalType) || Terminal.TYPE_DUMB_COLOR.equals(terminalType);
    }

    /// Opens the system terminal, refusing the dumb terminal JLine falls back to when no provider works;
    /// it cannot render the TUI and would leave it waiting for input it never draws.
    private static Backend systemBackend() throws IOException {
        // The JNI provider extracts a library into java.io.tmpdir, which fails on a noexec /tmp, and
        // build-time -D values do not reach a native image's runtime. An explicit -D still wins.
        if ("runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"))
                && System.getProperty("org.jline.terminal.provider") == null) {
            System.setProperty("org.jline.terminal.provider", "exec");
        }
        var backend = new JLineBackend();
        if (isDumb(backend.jlineTerminal().getType())) {
            backend.close();
            throw new DumbTerminalException();
        }
        return backend;
    }

    public static int launchStatus(Path configPath, long debugStepDelayMillis, PrintWriter errorOutput) {
        return launch(configPath, debugStepDelayMillis, errorOutput);
    }

    public static int launchPlan(Path configPath, long debugStepDelayMillis, PrintWriter errorOutput) {
        return launch(configPath, debugStepDelayMillis, errorOutput);
    }

    public static int launchInit(Path configPath, long debugStepDelayMillis, PrintWriter errorOutput) {
        var evaluation = new io.github.bigswlittlesw.homelight.application.ConfigurationEvaluation().load(configPath);
        if (evaluation instanceof io.github.bigswlittlesw.homelight.application.ConfigurationEvaluation.Loaded
                || evaluation instanceof io.github.bigswlittlesw.homelight.application.ConfigurationEvaluation.Invalid) {
            errorOutput.println("Configuration already exists or is unreadable; init only creates a missing configuration.");
            return 1;
        }
        return launch(configPath, debugStepDelayMillis, errorOutput, true);
    }

    private static int launch(Path configPath, long debugStepDelayMillis, PrintWriter errorOutput) {
        return launch(configPath, debugStepDelayMillis, errorOutput, false);
    }

    private static int launch(Path configPath, long debugStepDelayMillis, PrintWriter errorOutput, boolean startSetup) {
        var refusal = refusal(isInteractive(), System.getenv("TERM"));
        if (refusal.isPresent()) {
            errorOutput.println(refusal.get());
            return 2;
        }
        try {
            var app = new HomeLightApp(new io.github.bigswlittlesw.homelight.application.HomeLightSession(
                    configPath, debugStepDelayMillis), startSetup);
            app.run();
            return 0;
        } catch (DumbTerminalException exception) {
            errorOutput.println(DUMB_TERMINAL);
            return 2;
        } catch (Exception exception) {
            errorOutput.println("Failed to run HomeLight TUI: " + (exception.getMessage() != null ? exception.getMessage() : exception.toString()));
            return 1;
        }
    }

    private static final class DumbTerminalException extends IOException { }
}
