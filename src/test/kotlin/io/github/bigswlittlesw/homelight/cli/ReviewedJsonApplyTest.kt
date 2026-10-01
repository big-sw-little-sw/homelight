package io.github.bigswlittlesw.homelight.cli

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonToken
import io.github.bigswlittlesw.homelight.application.ConfigurationEvaluation
import io.github.bigswlittlesw.homelight.application.ReviewedExecution
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationExecutor
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import picocli.CommandLine
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

class ReviewedJsonApplyTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun driftInLaterRelocationsRejectsTheWholePlanBeforeAnyMutation() {
        val root = directory.toRealPath()
        val config = configuration(root, "", "first", "second", "third")
        val result = execute(config, Executor { task ->
            write(root.resolve("home/second"), "appeared after planning")
            write(root.resolve("home/third"), "also appeared")
            task.run()
        }, "--json", "--yes")

        assertEquals(1, result.exitCode)
        assertEquals(listOf("false"), values(result.output, "succeeded"))
        assertEquals(listOf("true"), values(result.output, "stale"))
        val statuses = values(result.output, "status")
        assertFalse(statuses.isEmpty())
        assertTrue(statuses.stream().allMatch("pending"::equals))
        assertTrue(result.output.contains("Filesystem state changed since review: " + root.resolve("home/second")))
        assertTrue(result.output.contains("Filesystem state changed since review: " + root.resolve("home/third")))
        assertTrue(Files.notExists(root.resolve("home/first")))
        assertTrue(Files.notExists(root.resolve("local")))
        assertEquals("appeared after planning", Files.readString(root.resolve("home/second")))
        assertEquals("also appeared", Files.readString(root.resolve("home/third")))
        assertEquals("", result.error)
    }

    @Test
    fun successUsesTheCapturedPlanAndRepeatPreservesTheNoChangeJson() {
        val root = directory.toRealPath()
        val config = configuration(root, "", "first")
        val result = execute(config, Executor { task ->
            write(config, "invalid: [")
            task.run()
        }, "--json", "--yes", "--debug-step-delay-ms", "60000")

        assertEquals(0, result.exitCode, result.error)
        assertEquals(listOf("true"), values(result.output, "succeeded"))
        assertTrue(values(result.output, "status").stream().allMatch("completed"::equals))
        assertFalse(result.output.contains("diagnostics"))
        assertTrue(Files.isSymbolicLink(root.resolve("home/first")))
        assertEquals(root.resolve("local/first"), Files.readSymbolicLink(root.resolve("home/first")))
        assertEquals("", result.error)

        configuration(root, "", "first")
        val plan = ConfigurationEvaluation().loadRequired(config).plan
        val expected = StringWriter()
        ApplyRenderer().renderJson(ReconciliationExecutor().execute(plan), PrintWriter(expected, true))
        val repeated = execute(config, Executor(Runnable::run), "--json", "--yes")
        assertEquals(0, repeated.exitCode)
        assertEquals(expected.toString(), repeated.output)
        assertEquals(listOf("no-op"), values(repeated.output, "type"))
    }

    @Test
    fun partialFailureKeepsCompletedFailedAndPendingEvidence() {
        val root = directory.toRealPath()
        Files.createDirectories(root.resolve("home/second"))
        val staging = Files.createDirectories(root.resolve("local")).resolve("staging-file")
        val config = configuration(root, "  staging-root: $staging\n", "first", "second", "third")
        val result = execute(config, Executor { task ->
            write(staging, "not a directory")
            task.run()
        }, "--json", "--yes")

        assertEquals(1, result.exitCode)
        assertEquals(listOf("false"), values(result.output, "succeeded"), result.output + result.error)
        assertTrue(values(result.output, "status").containsAll(listOf("completed", "failed", "pending")))
        assertEquals(listOf("converged", "unresolved", "unresolved"), values(result.output, "outcome"))
        assertFalse(result.output.contains("diagnostics"))
        assertTrue(Files.isSymbolicLink(root.resolve("home/first")))
        assertTrue(Files.isDirectory(root.resolve("home/second")))
        assertFalse(Files.isSymbolicLink(root.resolve("home/second")))
        assertTrue(Files.notExists(root.resolve("home/third")))
        assertTrue(Files.notExists(root.resolve("local/third")))
        assertEquals("", result.error)
    }

    @Test
    fun automationRunsWithRedirectedIoWithoutInitializingTamboUi() {
        val root = directory.toRealPath()
        val config = configuration(root, "", "first")
        val output = root.resolve("stdout.json")
        val error = root.resolve("stderr.log")
        val classes = root.resolve("classes.log")
        val builder = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xlog:class+init=info:file=$classes",
            "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
            HomeLightCommand::class.java.name, "--config", config.toString(), "apply", "--json", "--yes")
            .redirectInput(ProcessBuilder.Redirect.from(root.resolve("stdin").toFile()))
            .redirectOutput(output.toFile()).redirectError(error.toFile())
        Files.createFile(root.resolve("stdin"))
        builder.environment().remove("TERM")
        val process = builder.start()
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "JSON apply timed out")
            assertEquals(0, process.exitValue(), Files.readString(error))
            assertEquals(listOf("true"), values(Files.readString(output), "succeeded"))
            assertEquals("", Files.readString(error))
            assertFalse(Files.readString(classes).contains("Initializing 'dev/tamboui/"))
            assertTrue(Files.isSymbolicLink(root.resolve("home/first")))
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
            }
        }
    }

    @Test
    fun yesCannotResolveConflictsOrBypassBlockedActions() {
        val root = directory.toRealPath()
        val config = configuration(root, "", "first")
        Files.createDirectories(root.resolve("home/first"))
        Files.createDirectories(root.resolve("local/first"))
        val neverStart = Executor { fail<Unit>("An unresolved or blocked plan must not start") }
        val conflict = execute(config, neverStart, "--json", "--yes")
        assertEquals(1, conflict.exitCode)
        assertEquals(listOf("true"), values(conflict.output, "conflicts"))
        assertFalse(conflict.output.contains("succeeded"))
        assertFalse(Files.isSymbolicLink(root.resolve("home/first")))

        Files.delete(root.resolve("home/first"))
        Files.writeString(root.resolve("home/first"), "blocked file")
        val blocked = execute(config, neverStart, "--json", "--yes")
        assertEquals(1, blocked.exitCode)
        assertEquals(listOf("true"), values(blocked.output, "blocked"))
        assertFalse(blocked.output.contains("succeeded"))
        assertEquals("blocked file", Files.readString(root.resolve("home/first")))
    }

    @Test
    fun missingYesRejectsBeforeConfigurationLoadingOrExecution() {
        val result = execute(directory.resolve("missing.yaml"), Executor { fail<Unit>("Must not start") }, "--json")
        assertEquals(2, result.exitCode)
        assertEquals("", result.output)
        assertEquals("JSON apply requires --yes." + System.lineSeparator(), result.error)
    }

    @Test
    fun exceptionalCompletionRendersRetainedDiagnostics() {
        val root = directory.toRealPath()
        val config = configuration(root, "", "first")
        val execution = ReviewedExecution(ConfigurationEvaluation().loadRequired(config).plan)
        val completion = execution.start {
            throw RejectedExecutionException("retained worker diagnostic")
        }
        // Inject completion failure after publication, without provoking a real VM failure.
        completion.obtrudeException(AssertionError("exceptional completion"))
        val output = StringWriter()

        assertEquals(1, ApplyCommand.renderCompletion(execution, PrintWriter(output, true)))
        assertEquals(listOf("false"), values(output.toString(), "succeeded"))
        assertTrue(output.toString().contains("\"diagnostics\":[\"retained worker diagnostic\"]"))
        val statuses = values(output.toString(), "status")
        assertFalse(statuses.isEmpty())
        assertTrue(statuses.stream().allMatch("pending"::equals))
    }

    @Test
    fun exceptionalCompletionPreservesEvidenceAfterPartialMutation() {
        val root = directory.toRealPath()
        Files.createDirectories(root.resolve("home/second"))
        val staging = Files.createDirectories(root.resolve("local")).resolve("staging-file")
        val config = configuration(root, "  staging-root: $staging\n", "first", "second", "third")
        val execution = ReviewedExecution(ConfigurationEvaluation().loadRequired(config).plan)
        Files.writeString(staging, "not a directory")
        val completion = execution.start(Runnable::run)
        completion.obtrudeException(AssertionError("exceptional completion after mutation"))
        val output = StringWriter()

        assertEquals(1, ApplyCommand.renderCompletion(execution, PrintWriter(output, true)))
        assertEquals(listOf("false"), values(output.toString(), "succeeded"))
        assertTrue(values(output.toString(), "status").containsAll(listOf("completed", "failed", "pending")))
        assertTrue(Files.isSymbolicLink(root.resolve("home/first")))
        assertTrue(Files.notExists(root.resolve("home/third")))
    }

    @Test
    fun exceptionalCompletionWithoutATerminalResultStillThrows() {
        val root = directory.toRealPath()
        val config = configuration(root, "", "first")
        val execution = ReviewedExecution(ConfigurationEvaluation().loadRequired(config).plan)
        val completion = execution.start { }
        val cause = AssertionError("no published terminal result")
        completion.completeExceptionally(cause)
        val output = StringWriter()

        val thrown = assertThrows(CompletionException::class.java) {
            ApplyCommand.renderCompletion(execution, PrintWriter(output, true))
        }
        assertSame(cause, thrown.cause)
        assertEquals("", output.toString())
    }

    @Test
    fun schedulingFailureCannotBeReportedAsSuccessfulPendingWork() {
        val root = directory.toRealPath()
        val result = execute(configuration(root, "", "first"), Executor {
            throw RejectedExecutionException("worker unavailable")
        }, "--json", "--yes")
        assertEquals(1, result.exitCode)
        assertEquals(listOf("false"), values(result.output, "succeeded"))
        assertEquals(listOf("false"), values(result.output, "stale"))
        assertTrue(result.output.contains("worker unavailable"))
        assertTrue(values(result.output, "status").stream().allMatch("pending"::equals))
        assertTrue(Files.notExists(root.resolve("local")))
    }

    private data class Result(val exitCode: Int, val output: String, val error: String)

    private companion object {
        fun execute(config: Path, worker: Executor, vararg options: String): Result {
            val command = CommandLine(HomeLightCommand(), object : CommandLine.IFactory {
                override fun <K> create(type: Class<K>): K =
                    if (type == ApplyCommand::class.java) type.cast(ApplyCommand(worker))
                    else CommandLine.defaultFactory().create(type)
            })
            val output = StringWriter()
            val error = StringWriter()
            command.setOut(PrintWriter(output, true))
            command.setErr(PrintWriter(error, true))
            val arguments = ArrayList(listOf("--config", config.toString(), "apply"))
            arguments.addAll(listOf(*options))
            val exitCode: Int = command.execute(*arguments.toTypedArray())
            return Result(exitCode, output.toString(), error.toString())
        }

        fun configuration(root: Path, globals: String, vararg names: String): Path {
            Files.createDirectories(root.resolve("home"))
            val yaml = StringBuilder("homelight:\n  target-root: " + root.resolve("local") + "\n" + globals + "  relocations:\n")
            for (name in names) {
                yaml.append("    - source-path: ").append(root.resolve("home").resolve(name)).append('\n')
                yaml.append("      target-path: ").append(root.resolve("local").resolve(name)).append('\n')
            }
            return Files.writeString(root.resolve("config.yaml"), yaml)
        }

        fun write(path: Path, content: String) {
            try {
                Files.writeString(path, content)
            } catch (exception: java.io.IOException) {
                throw AssertionError(exception)
            }
        }

        fun values(json: String, field: String): List<String> {
            val values = ArrayList<String>()
            JsonFactory().createParser(json).use { parser ->
                while (parser.nextToken() != null) {
                    if (parser.currentToken() == JsonToken.FIELD_NAME && parser.currentName() == field) {
                        parser.nextToken()
                        values.add(parser.text)
                    }
                }
            }
            return values
        }
    }
}
