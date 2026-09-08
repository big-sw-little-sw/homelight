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
        assertTrue(result.output().contains("Plan: 1 relocation ready"));
        assertTrue(result.output().contains(source + " → " + target));
        assertTrue(result.output().contains("Create a destination directory and link"));
        assertTrue(!result.output().contains("create-directory:"));
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
    void planRendersConfiguredExistingContentPolicyForPeopleAndAutomation() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var source = Files.createDirectories(root.resolve("home/cache"));
        var target = root.resolve("local/cache");
        var config = root.resolve("config.yaml");
        Files.writeString(config, configuration(root, source, target).replace("target-path: " + target,
                "target-path: " + target + "\n      existing: move"));

        var text = execute("plan", "--config", config.toString());
        var json = execute("plan", "--config", config.toString(), "--json");

        assertEquals(0, text.exitCode());
        assertTrue(text.output().contains("Existing content: move"));
        assertEquals(0, json.exitCode());
        assertTrue(json.output().contains("\"existing\":\"move\""));
    }

    @Test
    void planWarnsBeforeDiscardingExistingContent() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var source = Files.createDirectories(root.resolve("home/cache"));
        var target = Files.createDirectories(root.resolve("local/cache"));
        var config = root.resolve("config.yaml");
        Files.writeString(config, configuration(root, source, target).replace("target-path: " + target,
                "target-path: " + target + "\n      existing: discard"));

        var result = execute("plan", "--config", config.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("discard policy will permanently remove"));
        assertTrue(result.output().contains("Discard existing contents and create a link"));
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
        assertTrue(result.output().contains(overrideSource + " → " + overrideTarget));
        assertTrue(!result.output().contains(configuredSource.toString()));
    }

    private static String configuration(java.nio.file.Path root, java.nio.file.Path source, java.nio.file.Path target) {
        return """
                homelight:
                  target-root: %s
                  relocations:
                    - source-path: %s
                      target-path: %s
                """.formatted(root, source, target);
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
