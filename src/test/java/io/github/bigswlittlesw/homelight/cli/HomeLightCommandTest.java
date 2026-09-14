package io.github.bigswlittlesw.homelight.cli;

import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HomeLightCommandTest {

    @Test
    void acceptsVisualDelayAtRootAndOnEveryTuiCommand() {
        for (var arguments : List.of(
                new String[] {"--debug-step-delay-ms", "3000"},
                new String[] {"status", "--debug-step-delay-ms", "3000"},
                new String[] {"plan", "--debug-step-delay-ms", "3000"},
                new String[] {"apply", "--debug-step-delay-ms", "3000"})) {
            var command = HomeLightCommand.createCommandLine();
            command.parseArgs(arguments);
            HomeLightCommand root = command.getCommand();
            assertEquals(3000, root.debugStepDelayMillis());
        }
        for (var delay : List.of("-1", "60001")) {
            var result = execute("--debug-step-delay-ms", delay);
            assertEquals(2, result.exitCode());
            assertTrue(result.errorOutput().contains("must be between 0 and 60000"), result.errorOutput());
        }
    }

    @Test
    void shouldDisplayHelpWithLongOption() {
        var result = execute("--help");

        assertEquals(0, result.exitCode());
        String output = result.output();
        assertTrue(output.contains("Usage: homelight"));
        assertTrue(output.contains("--help"));
        assertTrue(output.contains("--version"));
        assertTrue(output.contains("--config"));
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
    void shouldFailClearlyWhenInvokedNonInteractivelyWithoutArguments() {
        var result = execute();

        assertEquals(2, result.exitCode());
        assertTrue(result.errorOutput().contains("HomeLight TUI requires an interactive terminal"));
    }

    @Test
    void shouldFailClearlyWhenInvokedNonInteractivelyWithTopLevelConfig() {
        var result = execute("--config", "/tmp/custom.yaml");

        assertEquals(2, result.exitCode());
        assertTrue(result.errorOutput().contains("HomeLight TUI requires an interactive terminal"));
    }

    @Test
    void shouldFailClearlyWhenStatusInvokedNonInteractivelyWithoutJson() {
        var result = execute("status");

        assertEquals(2, result.exitCode());
        assertTrue(result.errorOutput().contains("HomeLight TUI requires an interactive terminal"));
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
        Files.writeString(config, "homelight:\n  target-root: " + root + "\n  relocations:\n    - source-path: " + sourcePath + "\n      target-path: " + targetPath + "\n");

        var result = execute("status", "--config", config.toString(), "--json");

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("\"state\":\"correct_symlink\""));
        assertTrue(result.output().contains("\"sourcePath\":\"" + sourcePath));
    }

    @Test
    void statusReportsJsonFilesystemStateWithShortConfigOption() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var sourcePath = root.resolve("home");
        var targetPath = root.resolve("local");
        Files.createDirectories(targetPath);
        Files.createSymbolicLink(sourcePath, targetPath);
        var config = root.resolve("config.yaml");
        Files.writeString(config, "homelight:\n  target-root: " + root + "\n  relocations:\n    - source-path: " + sourcePath + "\n      target-path: " + targetPath + "\n");

        var result = execute("status", "-c", config.toString(), "--json");

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("\"state\":\"correct_symlink\""));
        assertTrue(result.output().contains("\"sourcePath\":\"" + sourcePath));
    }

    @Test
    void statusReportsJsonFilesystemStateWithTopLevelConfigOption() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var sourcePath = root.resolve("home");
        var targetPath = root.resolve("local");
        Files.createDirectories(targetPath);
        Files.createSymbolicLink(sourcePath, targetPath);
        var config = root.resolve("config.yaml");
        Files.writeString(config, "homelight:\n  target-root: " + root + "\n  relocations:\n    - source-path: " + sourcePath + "\n      target-path: " + targetPath + "\n");

        var result = execute("--config", config.toString(), "status", "--json");

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("\"state\":\"correct_symlink\""));
        assertTrue(result.output().contains("\"sourcePath\":\"" + sourcePath));
    }

    @Test
    void statusReportsJsonFilesystemStateWithTopLevelShortConfigOption() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var sourcePath = root.resolve("home");
        var targetPath = root.resolve("local");
        Files.createDirectories(targetPath);
        Files.createSymbolicLink(sourcePath, targetPath);
        var config = root.resolve("config.yaml");
        Files.writeString(config, "homelight:\n  target-root: " + root + "\n  relocations:\n    - source-path: " + sourcePath + "\n      target-path: " + targetPath + "\n");

        var result = execute("-c", config.toString(), "status", "--json");

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("\"state\":\"correct_symlink\""));
        assertTrue(result.output().contains("\"sourcePath\":\"" + sourcePath));
    }

    @Test
    void statusJsonEscapesPathControlCharacters() {
        var output = new StringWriter();
        var snapshot = new StatusSnapshot(Path.of("/source/line\nbreak"), Path.of("/target"),
                io.github.bigswlittlesw.homelight.domain.RelocationSourceState.ABSENT);

        new StatusRenderer().renderJson(List.of(snapshot), new PrintWriter(output, true));

        assertTrue(output.toString().contains("line\\nbreak"));
    }

    @Test
    void unconfiguredStatusReportsJson() {
        var output = new StringWriter();

        new StatusRenderer().renderUnconfiguredJson(Path.of("/tmp/.homelight.yaml"),
                new PrintWriter(output, true));

        assertTrue(output.toString().contains("\"configured\":false"));
        assertTrue(output.toString().contains("\"configPath\":"));
        assertTrue(output.toString().contains("\"relocations\":[]"));
    }

    private static CapturedOutput execute(String... args) {
        var commandLine = HomeLightCommand.createCommandLine();
        var output = new StringWriter();
        var errorOutput = new StringWriter();
        commandLine.setOut(new PrintWriter(output, true));
        commandLine.setErr(new PrintWriter(errorOutput, true));

        int exitCode = commandLine.execute(args);
        return new CapturedOutput(exitCode, output.toString(), errorOutput.toString());
    }

    private record CapturedOutput(int exitCode, String output, String errorOutput) {
    }
}
