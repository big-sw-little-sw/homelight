package io.github.bigswlittlesw.homelight.tui;

import io.github.bigswlittlesw.homelight.application.Screen;

import java.io.PrintWriter;
import java.nio.file.Path;

/// Launches the interactive HomeLight TUI with error handling and terminal validation.
public final class TuiLauncher {

    public static boolean isInteractive() {
        return System.console() != null;
    }

    public static int launchStatus(Path configPath, PrintWriter errorOutput) {
        if (!isInteractive()) {
            errorOutput.println("HomeLight TUI requires an interactive terminal. Use --json for automation.");
            return 2;
        }
        try {
            var app = new HomeLightApp(configPath, Screen.STATUS);
            app.run();
            return 0;
        } catch (Exception exception) {
            errorOutput.println("Failed to run HomeLight TUI: " + (exception.getMessage() != null ? exception.getMessage() : exception.toString()));
            return 1;
        }
    }

    public static int launchPlan(Path configPath, PrintWriter errorOutput) {
        if (!isInteractive()) {
            errorOutput.println("HomeLight TUI requires an interactive terminal. Use --json for automation.");
            return 2;
        }
        try {
            var app = new HomeLightApp(configPath, Screen.PLAN);
            app.run();
            return 0;
        } catch (Exception exception) {
            errorOutput.println("Failed to run HomeLight TUI: " + (exception.getMessage() != null ? exception.getMessage() : exception.toString()));
            return 1;
        }
    }
}
