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
        assertTrue(result.output().contains("completed"));
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
        assertTrue(result.output().contains("conflict:"));
    }

    private static String configuration(java.nio.file.Path root, java.nio.file.Path source, java.nio.file.Path target) {
        return "homelight:\n  target-root: " + root + "\n  relocations:\n"
                + "    - source-path: " + source + "\n      target-path: " + target + "\n";
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
