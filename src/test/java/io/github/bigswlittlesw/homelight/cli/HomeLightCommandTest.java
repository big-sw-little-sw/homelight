package io.github.bigswlittlesw.homelight.cli;

import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HomeLightCommandTest {

    @Test
    void shouldDisplayHelpWithLongOption() {var result = execute("--help");

        assertEquals(0, result.exitCode());
        String output = result.output();
        assertTrue(output.contains("Usage: homelight"));
        assertTrue(output.contains("--help"));
        assertTrue(output.contains("--version"));
    }

    @Test
    void shouldDisplayHelpWithShortOption() {
        var result = execute("-h");

        assertEquals(0, result.exitCode());
        String output = result.output();
        assertTrue(output.contains("Usage: homelight"));
    }

    @Test
    void shouldDisplayVersionWithLongOption() {
        var result = execute("--version");

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("homelight " + HomeLightVersionProvider.resolveVersion()));
    }

    @Test
    void shouldDisplayVersionWithShortOption() {
        var result = execute("-V");

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("homelight " + HomeLightVersionProvider.resolveVersion()));
    }

    @Test
    void shouldExecuteWithoutArgumentsAndDisplayUsage() {
        var result = execute();

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("Usage: homelight"));
    }

    @Test
    void shouldProvideVersionFromVersionProvider() {
        var provider = new HomeLightVersionProvider();
        String[] version = provider.getVersion();

        assertEquals(1, version.length);
        assertEquals("homelight " + HomeLightVersionProvider.resolveVersion(), version[0]);
    }

    @Test
    void statusReportsJsonFilesystemState() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var homePath = root.resolve("home");
        var localRoot = root.resolve("local");
        Files.createDirectories(localRoot.resolve("home"));
        Files.createSymbolicLink(homePath, localRoot.resolve("home"));
        var config = root.resolve("config.yaml");
        Files.writeString(config, "homelight:\n  local-root: " + localRoot + "\n  relocation-path: " + homePath + "\n");

        var result = execute("status", "--config", config.toString(), "--json");

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("\"state\":\"correct_symlink\""));
        assertTrue(result.output().contains("\"path\":\"" + homePath));
    }

    @Test
    void tuiDisplaysBaselineStatusView() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var config = root.resolve("config.yaml");
        Files.writeString(config, "homelight:\n  local-root: " + root + "/local\n  relocation-path: " + root + "/home\n");

        var result = execute("tui", "--config", config.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("HomeLight TUI"));
        assertTrue(result.output().contains("status: absent"));
    }

    private static CapturedOutput execute(String... args) {
        var commandLine = HomeLightCommand.createCommandLine();
        var output = new StringWriter();
        commandLine.setOut(new PrintWriter(output, true));

        int exitCode = commandLine.execute(args);
        return new CapturedOutput(exitCode, output.toString());
    }

    private record CapturedOutput(int exitCode, String output) {
    }
}
