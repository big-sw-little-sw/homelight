package io.github.bigswlittlesw.homelight.cli;

import org.junit.jupiter.api.Test;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanCommandTest {
    @Test
    void rendersOutcomeInTextAndJson() throws Exception {
        var root=Files.createTempDirectory("homelight"); var source=Files.createDirectories(root.resolve("source")); var target=Files.createDirectories(root.resolve("target")); var config=root.resolve("config.yaml");
        Files.writeString(config,"""
        homelight:
          target-root: %s
          relocations:
            - source-path: %s
              target-path: %s
              when-source-and-target-directories-exist: leave-unchanged
        """.formatted(root,source,target));
        var command=HomeLightCommand.createCommandLine(); var out=new StringWriter(); command.setOut(new PrintWriter(out,true));
        assertEquals(0,command.execute("plan","--config",config.toString(),"--json")); assertTrue(out.toString().contains("\"outcome\":\"unchanged\""));
    }

    @Test
    void describesTargetAdoptionRatherThanDirectoryCreation() throws Exception {
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
                      when-source-and-target-directories-exist: adopt
                      when-adopting-target: discard-source
                """.formatted(root, source, target));
        var command = HomeLightCommand.createCommandLine();
        var output = new StringWriter();
        command.setOut(new PrintWriter(output, true));

        assertEquals(0, command.execute("plan", "--config", config.toString()));
        assertTrue(output.toString().contains("Adopt the target and replace the source with a link"));
        assertTrue(output.toString().contains("Plan outcome: converged"));
    }

    @Test
    void explainsWhenApplyIsUnavailable() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var source = Files.createDirectories(root.resolve("source"));
        var config = root.resolve("config.yaml");
        Files.writeString(config, """
                homelight:
                  target-root: %s
                  relocations:
                    - source-path: %s
                      target-path: %s
                """.formatted(root, source, root.resolve("target")));
        var command = HomeLightCommand.createCommandLine();
        var output = new StringWriter();
        command.setOut(new PrintWriter(output, true));

        assertEquals(0, command.execute("plan", "--config", config.toString()));
        assertTrue(output.toString().contains("Plan cannot be applied"));
        assertTrue(output.toString().contains("Apply is unavailable: moving an existing source directory is not available yet."));
    }
}
