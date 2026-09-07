package io.github.bigswlittlesw.homelight.cli;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanCommandTest {
    @Test
    void planShowsSafeActionsWithoutChangingTheFilesystem() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var source = root.resolve("home/cache");
        var target = root.resolve("local/cache");
        var config = root.resolve("config.yaml");
        Files.writeString(config, configuration(root, source, target));

        var result = execute("plan", "--config", config.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("create-directory: " + target));
        assertTrue(result.output().contains("create-symlink: " + source + " -> " + target));
        assertTrue(Files.notExists(source));
        assertTrue(Files.notExists(target));
    }

    @Test
    void planJsonContainsStructuredActions() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var source = root.resolve("home/cache");
        var target = root.resolve("local/cache");
        var config = root.resolve("config.yaml");
        Files.writeString(config, configuration(root, source, target));

        var result = execute("plan", "--config", config.toString(), "--json");

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("\"type\":\"create-directory\""));
        assertTrue(result.output().contains("\"type\":\"create-symlink\""));
        assertTrue(result.output().contains("\"blocked\":false"));
    }

    @Test
    void pairedSourceAndTargetOverridesTakePrecedenceAtThePlanCommand() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var configuredSource = root.resolve("configured/home");
        var configuredTarget = root.resolve("configured/local");
        var overrideSource = root.resolve("override/home");
        var overrideTarget = root.resolve("override/local");
        var config = root.resolve("config.yaml");
        Files.writeString(config, configuration(root, configuredSource, configuredTarget));

        var result = execute("plan", "--config", config.toString(),
                "--source-path", overrideSource.toString(),
                "--target-path", overrideTarget.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("create-directory: " + overrideTarget));
        assertTrue(result.output().contains("create-symlink: " + overrideSource + " -> " + overrideTarget));
        assertTrue(!result.output().contains(configuredSource.toString()));
    }

    private static String configuration(java.nio.file.Path root, java.nio.file.Path source, java.nio.file.Path target) {
        return "homelight:\n  target-root: " + root + "\n  relocations:\n"
                + "    - source-path: " + source + "\n      target-path: " + target + "\n";
    }

    private static CapturedOutput execute(String... args) {
        var commandLine = HomeLightCommand.createCommandLine();
        var output = new java.io.StringWriter();
        commandLine.setOut(new java.io.PrintWriter(output, true));
        int exitCode = commandLine.execute(args);
        return new CapturedOutput(exitCode, output.toString());
    }

    private record CapturedOutput(int exitCode, String output) {
    }
}
