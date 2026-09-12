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
        var root = Files.createTempDirectory("homelight").toRealPath();
        var source = root.resolve("home/cache");
        var target = root.resolve("local/cache");

        var result = apply(configuration(root, source, target));

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(Files.isSymbolicLink(source));
    }

    @Test
    void stagesPublishesAndLinksAnExistingSourceDirectory() throws Exception {
        var root = Files.createTempDirectory("homelight").toRealPath();
        var source = Files.createDirectories(root.resolve("home/cache"));
        var target = root.resolve("local/cache");
        Files.writeString(source.resolve("entry"), "source");

        var result = apply(configuration(root, source, target));

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(Files.isSymbolicLink(source));
        assertEquals("source", Files.readString(target.resolve("entry")));
    }

    @Test
    void usesAConfiguredTargetLocalStagingRoot() throws Exception {
        var root = Files.createTempDirectory("homelight").toRealPath();
        var source = Files.createDirectories(root.resolve("home/cache"));
        var target = root.resolve("local/cache");
        var stagingRoot = root.resolve("local/staging");
        Files.writeString(source.resolve("entry"), "source");

        var yaml = configuration(root, source, target, stagingRoot);
        var result = apply(yaml);

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(Files.isDirectory(stagingRoot));
        assertTrue(Files.notExists(target.getParent().resolve(".homelight-staging")));
        try (var entries = Files.list(stagingRoot)) {
            assertTrue(entries.findAny().isEmpty());
        }
    }

    @Test
    void cleansAProvenStaleStagingOperationBeforePublishing() throws Exception {
        var root = Files.createTempDirectory("homelight").toRealPath();
        var source = Files.createDirectories(root.resolve("home/cache"));
        var target = root.resolve("local/cache");
        var stagingRoot = Files.createDirectories(root.resolve("local/staging"));
        var stale = Files.createDirectory(stagingRoot.resolve("operation-00000000-0000-0000-0000-000000000000"));
        Files.writeString(stale.resolve("target"), "homelight-staging-v1\n" + target + "\n");
        Files.createFile(stale.resolve("lock"));
        Files.writeString(source.resolve("entry"), "source");

        var yaml = configuration(root, source, target, stagingRoot);
        var result = apply(yaml);

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(Files.notExists(stale));
        assertEquals("source", Files.readString(target.resolve("entry")));
    }

    @Test
    void retainsAStagingOperationWithAnUnexpectedEntry() throws Exception {
        var root = Files.createTempDirectory("homelight").toRealPath();
        var source = Files.createDirectories(root.resolve("home/cache"));
        var target = root.resolve("local/cache");
        var stagingRoot = Files.createDirectories(root.resolve("local/staging"));
        var suspicious = Files.createDirectory(stagingRoot.resolve("operation-00000000-0000-0000-0000-000000000000"));
        Files.writeString(suspicious.resolve("target"), "homelight-staging-v1\n" + target + "\n");
        Files.createFile(suspicious.resolve("lock"));
        Files.writeString(suspicious.resolve("unexpected"), "keep");
        Files.writeString(source.resolve("entry"), "source");

        var result = apply(configuration(root, source, target, stagingRoot));

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(Files.exists(suspicious));
    }

    @Test
    void usesAStagingRootElsewhereOnTheTargetFilesystem() throws Exception {
        var root = Files.createTempDirectory("homelight").toRealPath();
        var source = Files.createDirectories(root.resolve("home/cache"));
        var target = root.resolve("local/cache");
        Files.writeString(source.resolve("entry"), "source");

        var stagingRoot = root.resolve("other/staging");
        var yaml = configuration(root, source, target, stagingRoot);
        var result = apply(yaml);

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(Files.isDirectory(source));
        assertTrue(Files.isDirectory(stagingRoot));
        assertTrue(Files.isDirectory(target));
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
        var repeated = apply(configuration(root, source, target, """
                when-source-and-target-directories-exist: adopt
                when-adopting-target: discard-source
                """));
        assertEquals(0, repeated.exitCode(), repeated.output());
        assertTrue(repeated.output().contains("already configured"));
    }

    @Test
    void archivesTheSourceWhenAdoptingAConfiguredTarget() throws Exception {
        var root = Files.createTempDirectory("homelight").toRealPath();
        var source = Files.createDirectories(root.resolve("home/cache"));
        Files.writeString(source.resolve("source-entry"), "source");
        var target = Files.createDirectories(root.resolve("local/cache"));
        Files.writeString(target.resolve("target-entry"), "target");
        var archiveRoot = root.resolve("archive");

        var result = apply(configuration(root, source, target, """
                when-source-and-target-directories-exist: adopt
                when-adopting-target: archive-source
                source-archive-root: %s
                """.formatted(archiveRoot)));

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(Files.isSymbolicLink(source));
        assertEquals("target", Files.readString(target.resolve("target-entry")));
        assertEquals("source", Files.readString(archiveRoot.resolve(source.getRoot().relativize(source)).resolve("source-entry")));
        var repeated = apply(configuration(root, source, target, """
                when-source-and-target-directories-exist: adopt
                when-adopting-target: archive-source
                source-archive-root: %s
                """.formatted(archiveRoot)));
        assertEquals(0, repeated.exitCode(), repeated.output());
        assertTrue(repeated.output().contains("already configured"));
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

    @Test
    void jsonReportsConvergedResultsForPublicationAndItsNoOpRepeat() throws Exception {
        var root = Files.createTempDirectory("homelight").toRealPath();
        var source = Files.createDirectories(root.resolve("home/cache"));
        var target = root.resolve("local/cache");
        Files.writeString(source.resolve("entry"), "source");
        var yaml = configuration(root, source, target);

        var first = applyJson(yaml);
        var second = applyJson(yaml);

        assertEquals(0, first.exitCode(), first.output());
        assertTrue(first.output().contains("\"outcome\":\"converged\""));
        assertEquals(0, second.exitCode(), second.output());
        assertTrue(second.output().contains("\"outcome\":\"converged\""));
    }

    private static Result apply(String yaml) throws Exception {
        var config = Files.createTempFile("homelight", ".yaml");
        Files.writeString(config, yaml);
        var output = new StringWriter();
        var command = HomeLightCommand.createCommandLine();
        command.setOut(new PrintWriter(output, true));
        return new Result(command.execute("apply", "--yes", "--config", config.toString()), output.toString());
    }

    private static Result applyJson(String yaml) throws Exception {
        var config = Files.createTempFile("homelight", ".yaml");
        Files.writeString(config, yaml);
        var output = new StringWriter();
        var command = HomeLightCommand.createCommandLine();
        command.setOut(new PrintWriter(output, true));
        return new Result(command.execute("apply", "--json", "--yes", "--config", config.toString()), output.toString());
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
        return configuration(root, source, target, decisions, "");
    }

    private static String configuration(Path root, Path source, Path target, Path stagingRoot) {
        return configuration(root, source, target, "", "  staging-root: " + stagingRoot + "\n");
    }

    private static String configuration(Path root, Path source, Path target, String decisions, String stagingRoot) {
        return """
                homelight:
                  target-root: %s
                %s\
                  relocations:
                    - source-path: %s
                      target-path: %s
                %s""".formatted(root, stagingRoot, source, target, indent(decisions));
    }

    private static String indent(String text) {
        return text.lines().map(line -> "      " + line).reduce("", (left, right) -> left + right + "\n");
    }

    private record Result(int exitCode, String output) { }
}
