package io.github.bigswlittlesw.homelight.cli;

import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

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
        var sourcePath = root.resolve("home");
        var targetPath = root.resolve("local");
        Files.createDirectories(targetPath);
        Files.createSymbolicLink(sourcePath, targetPath);
        var config = root.resolve("config.yaml");
        Files.writeString(config, "homelight:\n  source-path: " + sourcePath + "\n  target-path: " + targetPath + "\n");

        var result = execute("status", "--config", config.toString(), "--json");

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("\"state\":\"correct_symlink\""));
        assertTrue(result.output().contains("\"sourcePath\":\"" + sourcePath));
    }

    @Test
    void tuiDisplaysBaselineStatusView() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var config = root.resolve("config.yaml");
        Files.writeString(config, "homelight:\n  source-path: " + root + "/home\n  target-path: " + root + "/local\n");

        var result = execute("tui", "--config", config.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("HomeLight TUI"));
        assertTrue(result.output().contains("status: absent"));
    }

    @Test
    void statusJsonEscapesPathControlCharacters() {
        var output = new StringWriter();
        var snapshot = new StatusSnapshot(Path.of("/source/line\nbreak"), Path.of("/target"),
                io.github.bigswlittlesw.homelight.fs.PathState.ABSENT);

        new StatusRenderer().render(snapshot, true, new PrintWriter(output, true));

        assertTrue(output.toString().contains("line\\nbreak"));
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
