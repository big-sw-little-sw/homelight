package io.github.bigswlittlesw.homelight.cli;

import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplyCommandTest {
    @Test
    void appliesAnAbsentRelocation() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var source = root.resolve("home/cache");
        var target = root.resolve("local/cache");
        var config = root.resolve("config.yaml");
        Files.writeString(config, configuration(root, source, target));

        var result = execute("apply", "--yes", "--config", config.toString());

        assertEquals(0, result.exitCode());
        assertTrue(Files.isDirectory(target));
        assertTrue(Files.isSymbolicLink(source));
        assertEquals(target, source.getParent().resolve(Files.readSymbolicLink(source)).normalize());
        assertTrue(result.output().contains("Created " + target + " and linked " + source));
    }

    @Test
    void movesExistingContentBeforeCreatingTheSourceLink() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var source = Files.createDirectories(root.resolve("home/cache"));
        Files.writeString(source.resolve("entry"), "value");
        var target = root.resolve("local/cache");
        var config = root.resolve("config.yaml");
        Files.writeString(config, configuration(root, source, target));

        var result = execute("apply", "--yes", "--config", config.toString());

        assertEquals(0, result.exitCode());
        assertEquals("value", Files.readString(target.resolve("entry")));
        assertTrue(Files.isSymbolicLink(source));
        assertTrue(result.output().contains("Relocated " + source + " → " + target));
    }

    @Test
    void refusesToApplyAnUnresolvedConflict() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var source = Files.createDirectories(root.resolve("home/cache"));
        var target = Files.createDirectories(root.resolve("local/cache"));
        var config = root.resolve("config.yaml");
        Files.writeString(config, configuration(root, source, target));

        var result = execute("apply", "--yes", "--config", config.toString());

        assertEquals(1, result.exitCode());
        assertTrue(Files.isDirectory(source));
        assertTrue(Files.isDirectory(target));
        assertTrue(result.output().contains("needs attention"));
    }

    @Test
    void appliesRelocationsThatShareATargetParent() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var firstSource = root.resolve("home/cache");
        var firstTarget = root.resolve("local/cache");
        var secondSource = root.resolve("home/tool-cache");
        var secondTarget = root.resolve("local/tool-cache");
        var config = root.resolve("config.yaml");
        Files.writeString(config, """
                homelight:
                  target-root: %s
                  relocations:
                    - source-path: %s
                      target-path: %s
                    - source-path: %s
                      target-path: %s
                """.formatted(root, firstSource, firstTarget, secondSource, secondTarget));

        var result = execute("apply", "--yes", "--config", config.toString());

        assertEquals(0, result.exitCode());
        assertTrue(Files.isSymbolicLink(firstSource));
        assertTrue(Files.isSymbolicLink(secondSource));
    }

    @Test
    void reportsThatNoChangesWereRequiredOnAConvergedSecondRun() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var source = root.resolve("home/cache");
        var target = root.resolve("local/cache");
        var config = root.resolve("config.yaml");
        Files.writeString(config, configuration(root, source, target));
        execute("apply", "--yes", "--config", config.toString());

        var result = execute("apply", "--yes", "--config", config.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("Already configured " + source + " → " + target));
        assertTrue(result.output().contains("No changes required. 1 relocation already configured."));
        assertTrue(!result.output().contains("Applied 1 relocation."));
    }

    @Test
    void applyJsonReportsExecutionWithoutTerminalStyling() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var source = root.resolve("home/cache");
        var target = root.resolve("local/cache");
        var config = root.resolve("config.yaml");
        Files.writeString(config, configuration(root, source, target));

        var result = execute("apply", "--yes", "--json", "--verbose", "--config", config.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("\"succeeded\":true"));
        assertTrue(result.output().contains("\"status\":\"completed\""));
        assertTrue(!result.output().contains("\u001B["));
        assertTrue(!result.output().contains("○ Move contents"));
    }

    @Test
    void acceptsTheDebugStepDelayHook() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var source = root.resolve("home/cache");
        var target = root.resolve("local/cache");
        var config = root.resolve("config.yaml");
        Files.writeString(config, configuration(root, source, target));

        var result = execute("apply", "--yes", "--json", "--debug-step-delay-ms", "0", "--config", config.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("\"succeeded\":true"));
    }

    @Test
    void verboseApplyShowsAReconciliationTree() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var source = root.resolve("home/cache");
        var target = root.resolve("local/cache");
        var config = root.resolve("config.yaml");
        Files.writeString(config, configuration(root, source, target));

        var result = execute("apply", "--yes", "--verbose", "--no-color", "--config", config.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains(source + " → " + target));
        assertTrue(result.output().contains("✓ Create " + target));
        assertTrue(result.output().contains("✓ Create source link"));
        assertTrue(!result.output().contains("✓ Created " + target + " and linked " + source));
    }

    @Test
    void verboseApplyDoesNotRepeatAnAlreadyConfiguredRelocation() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var source = root.resolve("home/cache");
        var target = root.resolve("local/cache");
        var config = root.resolve("config.yaml");
        Files.writeString(config, configuration(root, source, target));
        execute("apply", "--yes", "--config", config.toString());

        var result = execute("apply", "--yes", "--verbose", "--no-color", "--config", config.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("Already configured"));
        assertTrue(!result.output().contains("✓ Already configured " + source));
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
        var output = new StringWriter();
        commandLine.setOut(new PrintWriter(output, true));
        return new CapturedOutput(commandLine.execute(args), output.toString());
    }

    private record CapturedOutput(int exitCode, String output) {
    }
}
