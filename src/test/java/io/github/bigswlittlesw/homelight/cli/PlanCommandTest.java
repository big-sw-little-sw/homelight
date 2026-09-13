package io.github.bigswlittlesw.homelight.cli;

import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanCommandTest {

    @Test
    void rendersPlanAsJson() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var source = Files.createDirectories(root.resolve("source"));
        var target = Files.createDirectories(root.resolve("target"));
        var config = root.resolve("config.yaml");
        Files.writeString(config, """
                homelight:
                  target-root: %s
                  relocations:
                    - source-path: %s
                      target-path: %s
                      when-source-and-target-directories-exist: leave-unchanged
                """.formatted(root, source, target));

        var command = HomeLightCommand.createCommandLine();
        var out = new StringWriter();
        command.setOut(new PrintWriter(out, true));

        assertEquals(0, command.execute("plan", "--config", config.toString(), "--json"));
        var output = out.toString();
        assertTrue(output.contains("\"outcome\":\"unchanged\""));
        assertTrue(output.contains("\"relocations\":["));
        assertTrue(output.contains("\"actions\":["));
    }

    @Test
    void rendersPlanAsJsonWithSubcommandShortConfig() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var source = Files.createDirectories(root.resolve("source"));
        var target = Files.createDirectories(root.resolve("target"));
        var config = root.resolve("config.yaml");
        Files.writeString(config, """
                homelight:
                  target-root: %s
                  relocations:
                    - source-path: %s
                      target-path: %s
                      when-source-and-target-directories-exist: leave-unchanged
                """.formatted(root, source, target));

        var command = HomeLightCommand.createCommandLine();
        var out = new StringWriter();
        command.setOut(new PrintWriter(out, true));

        assertEquals(0, command.execute("plan", "-c", config.toString(), "--json"));
        var output = out.toString();
        assertTrue(output.contains("\"outcome\":\"unchanged\""));
        assertTrue(output.contains("\"relocations\":["));
        assertTrue(output.contains("\"actions\":["));
    }

    @Test
    void rendersPlanAsJsonWithTopLevelConfig() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var source = Files.createDirectories(root.resolve("source"));
        var target = Files.createDirectories(root.resolve("target"));
        var config = root.resolve("config.yaml");
        Files.writeString(config, """
                homelight:
                  target-root: %s
                  relocations:
                    - source-path: %s
                      target-path: %s
                      when-source-and-target-directories-exist: leave-unchanged
                """.formatted(root, source, target));

        var command = HomeLightCommand.createCommandLine();
        var out = new StringWriter();
        command.setOut(new PrintWriter(out, true));

        assertEquals(0, command.execute("--config", config.toString(), "plan", "--json"));
        var output = out.toString();
        assertTrue(output.contains("\"outcome\":\"unchanged\""));
        assertTrue(output.contains("\"relocations\":["));
        assertTrue(output.contains("\"actions\":["));
    }

    @Test
    void rendersPlanAsJsonWithTopLevelShortConfig() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var source = Files.createDirectories(root.resolve("source"));
        var target = Files.createDirectories(root.resolve("target"));
        var config = root.resolve("config.yaml");
        Files.writeString(config, """
                homelight:
                  target-root: %s
                  relocations:
                    - source-path: %s
                      target-path: %s
                      when-source-and-target-directories-exist: leave-unchanged
                """.formatted(root, source, target));

        var command = HomeLightCommand.createCommandLine();
        var out = new StringWriter();
        command.setOut(new PrintWriter(out, true));

        assertEquals(0, command.execute("-c", config.toString(), "plan", "--json"));
        var output = out.toString();
        assertTrue(output.contains("\"outcome\":\"unchanged\""));
        assertTrue(output.contains("\"relocations\":["));
        assertTrue(output.contains("\"actions\":["));
    }

    @Test
    void nonInteractivePlanWithoutJsonFailsGracefully() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var source = Files.createDirectories(root.resolve("source"));
        var target = Files.createDirectories(root.resolve("target"));
        var config = root.resolve("config.yaml");
        Files.writeString(config, """
                homelight:
                  target-root: %s
                  relocations:
                    - source-path: %s
                      target-path: %s
                """.formatted(root, source, target));

        var command = HomeLightCommand.createCommandLine();
        var err = new StringWriter();
        command.setErr(new PrintWriter(err, true));

        assertEquals(2, command.execute("plan", "--config", config.toString()));
        assertTrue(err.toString().contains("HomeLight TUI requires an interactive terminal. Use --json for automation."));
    }

    @Test
    void rendersUnconfiguredPlanAsJson() {
        var command = HomeLightCommand.createCommandLine();
        var out = new StringWriter();
        command.setOut(new PrintWriter(out, true));

        assertEquals(0, command.execute("plan", "--config", io.github.bigswlittlesw.homelight.config.ConfigurationLoader.DEFAULT_PATH.toString(), "--json"));
        assertTrue(out.toString().contains("\"relocations\":[]"));
    }
}
