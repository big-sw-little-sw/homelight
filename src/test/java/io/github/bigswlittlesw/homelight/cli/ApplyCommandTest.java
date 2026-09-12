package io.github.bigswlittlesw.homelight.cli;

import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplyCommandTest {
    @Test
    void appliesAnAbsentSourceAndTarget() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var source = root.resolve("home/cache");
        var target = root.resolve("local/cache");

        var result = apply(configuration(root, source, target));

        assertEquals(0, result.exitCode());
        assertTrue(Files.isSymbolicLink(source));
    }

    @Test
    void doesNotApplySourcePublicationBeforeTheStagingTicket() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var source = Files.createDirectories(root.resolve("home/cache"));
        var target = root.resolve("local/cache");

        var result = apply(configuration(root, source, target));

        assertEquals(1, result.exitCode());
        assertTrue(Files.isDirectory(source));
        assertTrue(Files.notExists(target));
    }

    @Test
    void appliesConfiguredTargetAdoptionWithSourceDiscard() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var source = Files.createDirectories(root.resolve("home/cache"));
        var target = Files.createDirectories(root.resolve("local/cache"));
        Files.writeString(target.resolve("entry"), "target");

        var result = apply(configuration(root, source, target, """
                when-source-and-target-directories-exist: adopt
                when-adopting-target: discard-source
                """));

        assertEquals(0, result.exitCode());
        assertTrue(Files.isSymbolicLink(source));
        assertEquals("target", Files.readString(target.resolve("entry")));
    }

    @Test
    void leavesConfiguredDirectoriesUnchanged() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var source = Files.createDirectories(root.resolve("home/cache"));
        var target = Files.createDirectories(root.resolve("local/cache"));

        var result = apply(configuration(root, source, target, """
                when-source-and-target-directories-exist: leave-unchanged
                """));

        assertEquals(0, result.exitCode());
        assertTrue(Files.isDirectory(source));
        assertTrue(Files.isDirectory(target));
    }

    @Test
    void appliesConfiguredDiscardToBothDirectories() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var source = Files.createDirectories(root.resolve("home/cache"));
        var target = Files.createDirectories(root.resolve("local/cache"));
        Files.writeString(source.resolve("source"), "source");
        Files.writeString(target.resolve("target"), "target");

        var result = apply(configuration(root, source, target, """
                when-source-and-target-directories-exist: discard
                """));

        assertEquals(0, result.exitCode());
        assertTrue(Files.isSymbolicLink(source));
        assertTrue(Files.notExists(target.resolve("source")));
        assertTrue(Files.notExists(target.resolve("target")));
    }

    @Test
    void jsonAndNonInteractiveApplyRequireYes() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var source = root.resolve("home/cache");
        var target = root.resolve("local/cache");
        var config = Files.createTempFile("homelight", ".yaml");
        Files.writeString(config, configuration(root, source, target));

        assertEquals(2, execute("apply", "--config", config.toString()).exitCode());
        assertEquals(2, execute("apply", "--json", "--config", config.toString()).exitCode());
        assertTrue(Files.notExists(source));
    }

    @Test
    void convergedRelocationIsANoOpOnTheSecondApply() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var source = root.resolve("home/cache");
        var target = root.resolve("local/cache");
        var yaml = configuration(root, source, target);

        apply(yaml);
        var result = apply(yaml);

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("already configured"));
    }

    private static Result apply(String yaml) throws Exception {
        var config = Files.createTempFile("homelight", ".yaml");
        Files.writeString(config, yaml);
        var output = new StringWriter();
        var command = HomeLightCommand.createCommandLine();
        command.setOut(new PrintWriter(output, true));
        return new Result(command.execute("apply", "--yes", "--config", config.toString()), output.toString());
    }

    private static Result execute(String... arguments) {
        var output = new StringWriter();
        var command = HomeLightCommand.createCommandLine();
        command.setOut(new PrintWriter(output, true));
        return new Result(command.execute(arguments), output.toString());
    }

    private static String configuration(Path root, Path source, Path target) {
        return configuration(root, source, target, "");
    }

    private static String configuration(Path root, Path source, Path target, String decisions) {
        return """
                homelight:
                  target-root: %s
                  relocations:
                    - source-path: %s
                      target-path: %s
                %s""".formatted(root, source, target, indent(decisions));
    }

    private static String indent(String text) {
        return text.lines().map(line -> "      " + line).reduce("", (left, right) -> left + right + "\n");
    }

    private record Result(int exitCode, String output) { }
}
