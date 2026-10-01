package io.github.bigswlittlesw.homelight.cli;

import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanCommandTest {

    @Test
    void pathOverridesAffectOnlyFirstRelocationAndPreserveJsonContract(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary) throws Exception {
        var root = temporary.toRealPath();
        var config = root.resolve("config.yaml");
        var yaml = """
                homelight:
                  target-root: %s
                  relocations:
                    - source-path: %s
                      target-path: %s
                    - source-path: %s
                      target-path: %s
                """.formatted(root, root.resolve("old-source"), root.resolve("old-target"),
                root.resolve("second-source"), root.resolve("second-target"));
        Files.writeString(config, yaml);
        var source = root.resolve("new-source");
        var target = root.resolve("new-target");
        var command = HomeLightCommand.createCommandLine();
        var out = new StringWriter();
        var err = new StringWriter();
        command.setOut(new PrintWriter(out, true));
        command.setErr(new PrintWriter(err, true));

        assertEquals(0, command.execute("plan", "-c", config.toString(), "--json",
                "--source-path", source.toString(), "--target-path", target.toString()));
        var expected = new io.github.bigswlittlesw.homelight.application.ConfigurationEvaluation().loadRequired(config,
                java.util.Optional.of(new io.github.bigswlittlesw.homelight.config.ConfigurationLoader.PathOverride(source, target)));
        var rendered = new StringWriter();
        new PlanRenderer().renderJson(expected.plan(), new PrintWriter(rendered, true));
        assertEquals(rendered.toString(), out.toString());
        assertEquals(source, expected.plan().relocations().getFirst().relocation().sourcePath());
        assertEquals(root.resolve("second-source"), expected.plan().relocations().getLast().relocation().sourcePath());
        assertEquals(yaml, Files.readString(config));
        assertTrue(Files.notExists(source));
        assertTrue(Files.notExists(target));

        assertEquals(2, command.execute("plan", "-c", config.toString(), "--json", "--source-path", source.toString()));
        assertTrue(err.toString().contains("must be provided together"));
    }

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
