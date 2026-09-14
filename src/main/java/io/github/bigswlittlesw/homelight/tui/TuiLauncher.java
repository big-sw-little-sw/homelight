package io.github.bigswlittlesw.homelight.tui;

import dev.tamboui.toolkit.app.ToolkitRunner;

import java.io.PrintWriter;
import java.nio.file.Path;

/// Launches the interactive HomeLight TUI with error handling and terminal validation.
public final class TuiLauncher {

    static void run(HomeLightApp app) throws Exception {
        // Propagate render failures through the same waiting/cleanup boundary. The toolkit's
        // default error screen intercepts Escape before application navigation can handle it.
        var config = app.configure().toBuilder().errorHandler((error, _) -> {
            throw new IllegalStateException("Unable to render HomeLight", error.cause());
        }).build();
        try (var runner = ToolkitRunner.create(config)) {
            try {
                runner.run(() -> {
                    var view = app.render();
                    if (app.exitRequested()) {
                        runner.quit();
                    }
                    return view;
                });
            } finally {
                app.session().awaitExecution();
            }
        }
    }

    public static boolean isInteractive() {
        return System.console() != null;
    }

    public static int launchStatus(Path configPath, long debugStepDelayMillis, PrintWriter errorOutput) {
        return launch(configPath, debugStepDelayMillis, errorOutput);
    }

    public static int launchPlan(Path configPath, long debugStepDelayMillis, PrintWriter errorOutput) {
        return launch(configPath, debugStepDelayMillis, errorOutput);
    }

    private static int launch(Path configPath, long debugStepDelayMillis, PrintWriter errorOutput) {
        if (!isInteractive()) {
            errorOutput.println("HomeLight TUI requires an interactive terminal. Use --json for automation.");
            return 2;
        }
        try {
            var app = new HomeLightApp(new io.github.bigswlittlesw.homelight.application.HomeLightSession(
                    configPath, debugStepDelayMillis));
            app.run();
            return 0;
        } catch (Exception exception) {
            errorOutput.println("Failed to run HomeLight TUI: " + (exception.getMessage() != null ? exception.getMessage() : exception.toString()));
            return 1;
        }
    }

}
