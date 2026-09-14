package io.github.bigswlittlesw.homelight.cli;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonToken;
import io.github.bigswlittlesw.homelight.application.ConfigurationEvaluation;
import io.github.bigswlittlesw.homelight.application.ReviewedExecution;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ReviewedJsonApplyTest {
    @TempDir
    Path directory;

    @Test
    void driftInLaterRelocationsRejectsTheWholePlanBeforeAnyMutation() throws Exception {
        var root = directory.toRealPath();
        var config = configuration(root, "", "first", "second", "third");
        var result = execute(config, task -> {
            write(root.resolve("home/second"), "appeared after planning");
            write(root.resolve("home/third"), "also appeared");
            task.run();
        }, "--json", "--yes");

        assertEquals(1, result.exitCode());
        assertEquals(List.of("false"), values(result.output(), "succeeded"));
        assertEquals(List.of("true"), values(result.output(), "stale"));
        var statuses = values(result.output(), "status");
        assertFalse(statuses.isEmpty());
        assertTrue(statuses.stream().allMatch("pending"::equals));
        assertTrue(result.output().contains("Filesystem state changed since review: " + root.resolve("home/second")));
        assertTrue(result.output().contains("Filesystem state changed since review: " + root.resolve("home/third")));
        assertTrue(Files.notExists(root.resolve("home/first")));
        assertTrue(Files.notExists(root.resolve("local")));
        assertEquals("appeared after planning", Files.readString(root.resolve("home/second")));
        assertEquals("also appeared", Files.readString(root.resolve("home/third")));
        assertEquals("", result.error());
    }

    @Test
    void successUsesTheCapturedPlanAndRepeatPreservesTheNoChangeJson() throws Exception {
        var root = directory.toRealPath();
        var config = configuration(root, "", "first");
        var result = execute(config, task -> {
            write(config, "invalid: [");
            task.run();
        }, "--json", "--yes", "--debug-step-delay-ms", "60000");

        assertEquals(0, result.exitCode(), result.error());
        assertEquals(List.of("true"), values(result.output(), "succeeded"));
        assertTrue(values(result.output(), "status").stream().allMatch("completed"::equals));
        assertFalse(result.output().contains("diagnostics"));
        assertTrue(Files.isSymbolicLink(root.resolve("home/first")));
        assertEquals(root.resolve("local/first"), Files.readSymbolicLink(root.resolve("home/first")));
        assertEquals("", result.error());

        configuration(root, "", "first");
        var plan = new ConfigurationEvaluation().loadRequired(config, Map.of()).plan();
        var expected = new StringWriter();
        new ApplyRenderer().renderJson(new ReconciliationExecutor().execute(plan), new PrintWriter(expected, true));
        var repeated = execute(config, Runnable::run, "--json", "--yes");
        assertEquals(0, repeated.exitCode());
        assertEquals(expected.toString(), repeated.output());
        assertEquals(List.of("no-op"), values(repeated.output(), "type"));
    }

    @Test
    void partialFailureKeepsCompletedFailedAndPendingEvidence() throws Exception {
        var root = directory.toRealPath();
        Files.createDirectories(root.resolve("home/second"));
        var staging = Files.createDirectories(root.resolve("local")).resolve("staging-file");
        var config = configuration(root, "  staging-root: " + staging + "\n", "first", "second", "third");
        var result = execute(config, task -> {
            write(staging, "not a directory");
            task.run();
        }, "--json", "--yes");

        assertEquals(1, result.exitCode());
        assertEquals(List.of("false"), values(result.output(), "succeeded"), result.output() + result.error());
        assertTrue(values(result.output(), "status").containsAll(List.of("completed", "failed", "pending")));
        assertEquals(List.of("converged", "unresolved", "unresolved"), values(result.output(), "outcome"));
        assertFalse(result.output().contains("diagnostics"));
        assertTrue(Files.isSymbolicLink(root.resolve("home/first")));
        assertTrue(Files.isDirectory(root.resolve("home/second")));
        assertFalse(Files.isSymbolicLink(root.resolve("home/second")));
        assertTrue(Files.notExists(root.resolve("home/third")));
        assertTrue(Files.notExists(root.resolve("local/third")));
        assertEquals("", result.error());
    }

    @Test
    void automationRunsWithRedirectedIoWithoutInitializingTamboUi() throws Exception {
        var root = directory.toRealPath();
        var config = configuration(root, "", "first");
        var output = root.resolve("stdout.json");
        var error = root.resolve("stderr.log");
        var classes = root.resolve("classes.log");
        var builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xlog:class+init=info:file=" + classes,
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                HomeLightCommand.class.getName(), "--config", config.toString(), "apply", "--json", "--yes")
                .redirectInput(ProcessBuilder.Redirect.from(root.resolve("stdin").toFile()))
                .redirectOutput(output.toFile()).redirectError(error.toFile());
        Files.createFile(root.resolve("stdin"));
        builder.environment().remove("TERM");
        var process = builder.start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "JSON apply timed out");
            assertEquals(0, process.exitValue(), Files.readString(error));
            assertEquals(List.of("true"), values(Files.readString(output), "succeeded"));
            assertEquals("", Files.readString(error));
            assertFalse(Files.readString(classes).contains("Initializing 'dev/tamboui/"));
            assertTrue(Files.isSymbolicLink(root.resolve("home/first")));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    @Test
    void yesCannotResolveConflictsOrBypassBlockedActions() throws Exception {
        var root = directory.toRealPath();
        var config = configuration(root, "", "first");
        Files.createDirectories(root.resolve("home/first"));
        Files.createDirectories(root.resolve("local/first"));
        Executor neverStart = _ -> fail("An unresolved or blocked plan must not start");
        var conflict = execute(config, neverStart, "--json", "--yes");
        assertEquals(1, conflict.exitCode());
        assertEquals(List.of("true"), values(conflict.output(), "conflicts"));
        assertFalse(conflict.output().contains("succeeded"));
        assertFalse(Files.isSymbolicLink(root.resolve("home/first")));

        Files.delete(root.resolve("home/first"));
        Files.writeString(root.resolve("home/first"), "blocked file");
        var blocked = execute(config, neverStart, "--json", "--yes");
        assertEquals(1, blocked.exitCode());
        assertEquals(List.of("true"), values(blocked.output(), "blocked"));
        assertFalse(blocked.output().contains("succeeded"));
        assertEquals("blocked file", Files.readString(root.resolve("home/first")));
    }

    @Test
    void missingYesRejectsBeforeConfigurationLoadingOrExecution() throws Exception {
        var result = execute(directory.resolve("missing.yaml"), _ -> fail("Must not start"), "--json");
        assertEquals(2, result.exitCode());
        assertEquals("", result.output());
        assertEquals("JSON apply requires --yes." + System.lineSeparator(), result.error());
    }

    @Test
    void exceptionalCompletionRendersRetainedDiagnostics() throws Exception {
        var root = directory.toRealPath();
        var config = configuration(root, "", "first");
        var execution = new ReviewedExecution(new ConfigurationEvaluation().loadRequired(config, Map.of()).plan());
        var completion = execution.start(_ -> {
            throw new RejectedExecutionException("retained worker diagnostic");
        });
        // Inject completion failure after publication, without provoking a real VM failure.
        completion.obtrudeException(new AssertionError("exceptional completion"));
        var output = new StringWriter();

        assertEquals(1, ApplyCommand.renderCompletion(execution, new PrintWriter(output, true)));
        assertEquals(List.of("false"), values(output.toString(), "succeeded"));
        assertTrue(output.toString().contains("\"diagnostics\":[\"retained worker diagnostic\"]"));
        var statuses = values(output.toString(), "status");
        assertFalse(statuses.isEmpty());
        assertTrue(statuses.stream().allMatch("pending"::equals));
    }

    @Test
    void exceptionalCompletionPreservesEvidenceAfterPartialMutation() throws Exception {
        var root = directory.toRealPath();
        Files.createDirectories(root.resolve("home/second"));
        var staging = Files.createDirectories(root.resolve("local")).resolve("staging-file");
        var config = configuration(root, "  staging-root: " + staging + "\n", "first", "second", "third");
        var execution = new ReviewedExecution(new ConfigurationEvaluation().loadRequired(config, Map.of()).plan());
        Files.writeString(staging, "not a directory");
        var completion = execution.start(Runnable::run);
        completion.obtrudeException(new AssertionError("exceptional completion after mutation"));
        var output = new StringWriter();

        assertEquals(1, ApplyCommand.renderCompletion(execution, new PrintWriter(output, true)));
        assertEquals(List.of("false"), values(output.toString(), "succeeded"));
        assertTrue(values(output.toString(), "status").containsAll(List.of("completed", "failed", "pending")));
        assertTrue(Files.isSymbolicLink(root.resolve("home/first")));
        assertTrue(Files.notExists(root.resolve("home/third")));
    }

    @Test
    void exceptionalCompletionWithoutATerminalResultStillThrows() throws Exception {
        var root = directory.toRealPath();
        var config = configuration(root, "", "first");
        var execution = new ReviewedExecution(new ConfigurationEvaluation().loadRequired(config, Map.of()).plan());
        var completion = execution.start(_ -> { });
        var cause = new AssertionError("no published terminal result");
        completion.completeExceptionally(cause);
        var output = new StringWriter();

        var thrown = assertThrows(CompletionException.class,
                () -> ApplyCommand.renderCompletion(execution, new PrintWriter(output, true)));
        assertSame(cause, thrown.getCause());
        assertEquals("", output.toString());
    }

    @Test
    void schedulingFailureCannotBeReportedAsSuccessfulPendingWork() throws Exception {
        var root = directory.toRealPath();
        var result = execute(configuration(root, "", "first"), _ -> {
            throw new RejectedExecutionException("worker unavailable");
        }, "--json", "--yes");
        assertEquals(1, result.exitCode());
        assertEquals(List.of("false"), values(result.output(), "succeeded"));
        assertEquals(List.of("false"), values(result.output(), "stale"));
        assertTrue(result.output().contains("worker unavailable"));
        assertTrue(values(result.output(), "status").stream().allMatch("pending"::equals));
        assertTrue(Files.notExists(root.resolve("local")));
    }

    private static Result execute(Path config, Executor worker, String... options) {
        var command = new CommandLine(new HomeLightCommand(), new CommandLine.IFactory() {
            @Override
            public <K> K create(Class<K> type) throws Exception {
                return type == ApplyCommand.class ? type.cast(new ApplyCommand(worker))
                        : CommandLine.defaultFactory().create(type);
            }
        });
        var output = new StringWriter();
        var error = new StringWriter();
        command.setOut(new PrintWriter(output, true));
        command.setErr(new PrintWriter(error, true));
        var arguments = new ArrayList<>(List.of("--config", config.toString(), "apply"));
        arguments.addAll(List.of(options));
        int exitCode = command.execute(arguments.toArray(String[]::new));
        return new Result(exitCode, output.toString(), error.toString());
    }

    private static Path configuration(Path root, String globals, String... names) throws Exception {
        Files.createDirectories(root.resolve("home"));
        var yaml = new StringBuilder("homelight:\n  target-root: " + root.resolve("local") + "\n" + globals + "  relocations:\n");
        for (var name : names) {
            yaml.append("    - source-path: ").append(root.resolve("home").resolve(name)).append('\n');
            yaml.append("      target-path: ").append(root.resolve("local").resolve(name)).append('\n');
        }
        return Files.writeString(root.resolve("config.yaml"), yaml);
    }

    private static void write(Path path, String content) {
        try {
            Files.writeString(path, content);
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static List<String> values(String json, String field) throws Exception {
        var values = new ArrayList<String>();
        try (var parser = new JsonFactory().createParser(json)) {
            while (parser.nextToken() != null) {
                if (parser.currentToken() == JsonToken.FIELD_NAME && parser.currentName().equals(field)) {
                    parser.nextToken();
                    values.add(parser.getText());
                }
            }
        }
        return values;
    }

    private record Result(int exitCode, String output, String error) { }
}
