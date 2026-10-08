package io.github.bigswlittlesw.lighten.cli

import io.github.bigswlittlesw.lighten.application.ConfigurationEvaluation
import io.github.bigswlittlesw.lighten.application.guideUrl
import io.github.bigswlittlesw.lighten.application.resolveVersion
import io.github.bigswlittlesw.lighten.application.userGuide
import io.github.bigswlittlesw.lighten.config.ConfigurationException
import io.github.bigswlittlesw.lighten.domain.RelocationSourceState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.CompletionException

class LightenCommandTest {

    @Test
    fun acceptsVisualDelayAtRootAndOnEveryTuiCommand() {
        for (arguments in listOf(
            arrayOf("--debug-step-delay-ms", "3000"),
            arrayOf("status", "--debug-step-delay-ms", "3000"),
            arrayOf("plan", "--debug-step-delay-ms", "3000"),
            arrayOf("apply", "--debug-step-delay-ms", "3000"),
            arrayOf("init", "--debug-step-delay-ms", "3000"),
        )) {
            val command = LightenCommand.createCommandLine()
            command.parseArgs(*arguments)
            val root: LightenCommand = command.getCommand()
            assertEquals(3000L, root.debugStepDelayMillis)
        }
    }

    @Test
    fun rejectsAnOutOfRangeVisualDelayAsAUsageError() {
        for (arguments in listOf(
            arrayOf("--debug-step-delay-ms", "-1"),
            arrayOf("--debug-step-delay-ms", "60001"),
            arrayOf("status", "--json", "--debug-step-delay-ms", "60001"),
            arrayOf("plan", "--debug-step-delay-ms", "-1"),
        )) {
            val result = execute(*arguments)
            assertEquals(2, result.exitCode, result.errorOutput)
            assertEquals("--debug-step-delay-ms must be between 0 and 60000", result.errorOutput.lines().first())
            assertFalse(result.errorOutput.contains("Could not invoke"), result.errorOutput)
            assertEquals("", result.output)
        }
    }

    /**
     * A file whose text or values are wrong gets the Workspace's explanation and how to fix it on stderr; a missing
     * file stays one line. Nothing goes to stdout, and the exit code is 1.
     */
    @Test
    fun explainsAConfigurationErrorWithoutJsonOutput(@TempDir root: Path) {
        fun explained(name: String, problem: String, fix: String) = listOf(
            "Lighten can't read ${root.resolve(name)}", problem,
            "To fix it: open the file in a text editor, $fix, then run the command again.",
            "To start over: rename or delete the file, then run lighten init --config ${root.resolve(name)} to create a new one.",
        )
        val configs = mapOf(
            "missing.json" to (null to listOf("Configuration file does not exist: ${root.resolve("missing.json")}")),
            "malformed.json" to ("{\"lighten\": {\"target-root\": \"unclosed\n" to explained("malformed.json",
                "It isn't valid JSON: line 1, column 38 should have a double quote (\") but the line ends there.",
                "correct that line")),
            "missing-key.json" to ("""{"lighten": {"relocations": []}}""" to explained("missing-key.json",
                "target-root is missing. Add it under \"lighten\".", "correct that setting")),
            "wrong-kind.json" to ("""{"lighten": {"target-root": "/local",
                "relocations": [{"source-path": 5}]}}""" to explained("wrong-kind.json",
                "Line 2: relocations[0].source-path should be text, but it is a number.", "correct that line")),
            "unknown-key.json" to ("""{"lighten": {"target-root": "/local", "relocations": [
                {"source-path": "/home/cache", "target-path": "/local/cache", "existing": "move"}]}}""" to explained(
                "unknown-key.json", "Line 2: relocations[0] has an unknown setting \"existing\". Check its spelling or remove it.",
                "correct that line")),
            "bad-enum.json" to ("""{"lighten": {"target-root": "/local", "relocations": [
                {"source-path": "/home/cache", "target-path": "/local/cache", "when-only-target-exists": "sometimes"}]}}""" to
                explained("bad-enum.json", "relocations[0].when-only-target-exists can't be \"sometimes\"." +
                    " Use one of: prompt, adopt-target.", "correct that setting")),
            "relative-path.json" to ("""{"lighten": {"target-root": "local"}}""" to
                explained("relative-path.json", "lighten.target-root: Use a full path, or one starting with ~/",
                    "correct that setting")),
        )
        for ((name, case) in configs) {
            val (content, expected) = case
            val config = root.resolve(name)
            content?.let { Files.writeString(config, it) }
            for (command in listOf(arrayOf("status", "--json"), arrayOf("plan", "--json"), arrayOf("apply", "--json", "--yes"))) {
                val result = execute("-c", config.toString(), *command)

                val context = "$name ${command.joinToString(" ")}: ${result.errorOutput}"
                assertEquals(1, result.exitCode, context)
                assertEquals(expected, result.errorOutput.lines().dropLast(1), context)
                assertEquals("", result.output, context)
            }
        }
    }

    @Test
    fun reportsAnyOtherExceptionAsOneInternalErrorLineWithExitCode70() {
        for (arguments in listOf(
            arrayOf("fail"), arrayOf("fail", "--json"), arrayOf("fail", "--worker"), arrayOf("fail", "--worker", "--json"),
        )) {
            val commandLine = LightenCommand.createCommandLine().addSubcommand("fail", FailingCommand())
            val output = StringWriter()
            val errorOutput = StringWriter()
            commandLine.setOut(PrintWriter(output, true))
            commandLine.setErr(PrintWriter(errorOutput, true))

            val context = arguments.joinToString(" ") + ": " + errorOutput
            assertEquals(70, commandLine.execute(*arguments), context)
            assertEquals("Internal error (please report): IllegalStateException: unexpected\n",
                errorOutput.toString().replace(System.lineSeparator(), "\n"), context)
            assertEquals("", output.toString(), context)
        }
    }

    /** The handler treats `--json` and a worker's wrapped bug the same way as a plain bug. */
    @Command(name = "fail")
    private class FailingCommand : Callable<Int> {
        @Option(names = ["--json"]) private var json = false
        @Option(names = ["--worker"]) private var worker = false

        override fun call(): Int {
            val bug = IllegalStateException("unexpected")
            throw if (worker) CompletionException(bug) else bug
        }
    }

    @Test
    fun shouldDisplayHelpWithLongOption() {
        val result = execute("--help")

        assertEquals(0, result.exitCode)
        val output: String = result.output
        assertTrue(output.contains("Usage: lighten"))
        assertTrue(output.contains("--help"))
        assertTrue(output.contains("--version"))
        assertTrue(output.contains("--config"))
    }

    @Test
    fun shouldDisplayHelpWithShortOption() {
        val result = execute("-h")

        assertEquals(0, result.exitCode)
        val output: String = result.output
        assertTrue(output.contains("Usage: lighten"))
    }

    @Test
    fun helpEndsWithTheGuidesAddressForThisVersion() {
        val result = execute("--help")

        assertTrue(result.output.lines().contains(guideUrl()), result.output)
        assertTrue(result.output.contains("lighten guide"), result.output)
    }

    @Test
    fun guidePrintsTheEmbeddedGuide() {
        val result = execute("guide")

        assertEquals(0, result.exitCode)
        assertEquals(userGuide(), result.output)
        assertTrue(result.output.startsWith("# Lighten\n"))
    }

    @Test
    fun theGuidesLinkFollowsTheVersion() {
        assertTrue(userGuide("1.0-SNAPSHOT").contains("/blob/main/docs/user-guide.md"))
        val release = userGuide("1.2.0")
        assertTrue(release.contains("https://github.com/big-sw-little-sw/lighten/blob/v1.2.0/docs/user-guide.md"))
        assertFalse(release.contains("/blob/main/"))
    }

    @Test
    fun shouldDisplayVersionWithLongOption() {
        val result = execute("--version")

        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("lighten " + resolveVersion()))
    }

    @Test
    fun shouldDisplayVersionWithShortOption() {
        val result = execute("-V")

        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("lighten " + resolveVersion()))
    }

    @Test
    fun shouldFailClearlyWhenInvokedNonInteractivelyWithoutArguments() {
        val result = execute()

        assertEquals(2, result.exitCode)
        assertTrue(result.errorOutput.contains("Lighten TUI requires an interactive terminal"))
    }

    @Test
    fun shouldFailClearlyWhenInvokedNonInteractivelyWithTopLevelConfig() {
        val result = execute("--config", "/tmp/custom.json")

        assertEquals(2, result.exitCode)
        assertTrue(result.errorOutput.contains("Lighten TUI requires an interactive terminal"))
    }

    @Test
    fun shouldFailClearlyWhenStatusInvokedNonInteractivelyWithoutJson() {
        val result = execute("status")

        assertEquals(2, result.exitCode)
        assertTrue(result.errorOutput.contains("Lighten TUI requires an interactive terminal"))
    }

    @Test
    fun initAndConfigAreExecutableInteractiveEntries() {
        for (name in listOf("init", "config")) {
            val result = execute(name)
            assertEquals(2, result.exitCode, name)
            assertTrue(result.errorOutput.contains("interactive terminal"), name)
        }
    }

    /** Configuration opens a file that loads, or a new one; a file Lighten cannot read is fixed by hand. */
    @Test
    fun configRefusesAFileItCannotReadAndSaysHowToFixIt(@TempDir root: Path) {
        val config = Files.writeString(root.resolve("config.json"), "lighten")
        val result = execute("-c", config.toString(), "config")
        assertEquals(1, result.exitCode)
        assertEquals(
            listOf(
                "Lighten can't read $config",
                "It isn't valid JSON: line 1, column 1 should start with \"{\" but starts with \"l\".",
                "To fix it: open the file in a text editor, correct that line, then run the command again.",
                "To start over: rename or delete the file, then run lighten init --config $config to create a new one.",
            ),
            result.errorOutput.lines().dropLast(1),
        )
    }

    @Test
    fun shouldProvideVersionFromVersionProvider() {
        val provider = LightenVersionProvider()
        val version: Array<String> = provider.getVersion()

        assertEquals(1, version.size)
        assertEquals("lighten " + resolveVersion(), version[0])
    }

    @Test
    fun statusReportsJsonFilesystemState(@TempDir root: Path) {
        val sourcePath = root.resolve("home")
        val targetPath = root.resolve("local")
        Files.createDirectories(targetPath)
        Files.createSymbolicLink(sourcePath, targetPath)
        val config = root.resolve("config.json")
        Files.writeString(config, "{\"lighten\": {\"target-root\": \"$root\", \"relocations\": [{\"source-path\": \"$sourcePath\", \"target-path\": \"$targetPath\"}]}}\n")

        val result = execute("status", "--config", config.toString(), "--json")

        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("\"state\":\"correct_symlink\""))
        assertTrue(result.output.contains("\"sourcePath\":\"$sourcePath"))
    }

    @Test
    fun everyJsonResponseStartsWithTheSchemaVersion(@TempDir root: Path) {
        val sourcePath = root.resolve("home/cache")
        Files.createDirectories(sourcePath)
        val config = root.resolve("config.json")
        Files.writeString(config, "{\"lighten\": {\"target-root\": \"$root/local\", \"relocations\": [{\"source-path\": \"$sourcePath\", \"target-path\": \"$root/local/cache\"}]}}\n")

        for (arguments in listOf(arrayOf("status", "--json"), arrayOf("plan", "--json"), arrayOf("apply", "--json", "--yes"))) {
            val result = execute("-c", config.toString(), *arguments)

            assertEquals(0, result.exitCode, result.errorOutput)
            assertTrue(result.output.startsWith("{\"schema\":1,"), result.output)
        }
    }

    @Test
    fun planNoLongerAcceptsNoColor() {
        assertEquals(2, execute("plan", "--no-color").exitCode)
    }

    @Test
    fun statusReportsJsonFilesystemStateWithShortConfigOption(@TempDir root: Path) {
        val sourcePath = root.resolve("home")
        val targetPath = root.resolve("local")
        Files.createDirectories(targetPath)
        Files.createSymbolicLink(sourcePath, targetPath)
        val config = root.resolve("config.json")
        Files.writeString(config, "{\"lighten\": {\"target-root\": \"$root\", \"relocations\": [{\"source-path\": \"$sourcePath\", \"target-path\": \"$targetPath\"}]}}\n")

        val result = execute("status", "-c", config.toString(), "--json")

        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("\"state\":\"correct_symlink\""))
        assertTrue(result.output.contains("\"sourcePath\":\"$sourcePath"))
    }

    @Test
    fun statusReportsJsonFilesystemStateWithTopLevelConfigOption(@TempDir root: Path) {
        val sourcePath = root.resolve("home")
        val targetPath = root.resolve("local")
        Files.createDirectories(targetPath)
        Files.createSymbolicLink(sourcePath, targetPath)
        val config = root.resolve("config.json")
        Files.writeString(config, "{\"lighten\": {\"target-root\": \"$root\", \"relocations\": [{\"source-path\": \"$sourcePath\", \"target-path\": \"$targetPath\"}]}}\n")

        val result = execute("--config", config.toString(), "status", "--json")

        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("\"state\":\"correct_symlink\""))
        assertTrue(result.output.contains("\"sourcePath\":\"$sourcePath"))
    }

    @Test
    fun statusReportsJsonFilesystemStateWithTopLevelShortConfigOption(@TempDir root: Path) {
        val sourcePath = root.resolve("home")
        val targetPath = root.resolve("local")
        Files.createDirectories(targetPath)
        Files.createSymbolicLink(sourcePath, targetPath)
        val config = root.resolve("config.json")
        Files.writeString(config, "{\"lighten\": {\"target-root\": \"$root\", \"relocations\": [{\"source-path\": \"$sourcePath\", \"target-path\": \"$targetPath\"}]}}\n")

        val result = execute("-c", config.toString(), "status", "--json")

        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("\"state\":\"correct_symlink\""))
        assertTrue(result.output.contains("\"sourcePath\":\"$sourcePath"))
    }

    @Test
    fun statusJsonEscapesPathControlCharacters() {
        val output = StringWriter()
        val snapshot = StatusSnapshot(Path.of("/source/line\nbreak"), Path.of("/target"),
            RelocationSourceState.ABSENT)

        renderStatusJson(Path.of("/tmp/.lighten.json"), listOf(snapshot), PrintWriter(output, true))

        assertTrue(output.toString().contains("line\\nbreak"))
    }

    @Test
    fun statusJsonValuesDoNotDependOnTheDefaultLocale() {
        val output = StringWriter()
        val snapshot = StatusSnapshot(Path.of("/source"), Path.of("/target"),
            RelocationSourceState.INACCESSIBLE)
        val previous = Locale.getDefault()
        // Turkish lower-cases I to a dotless ı.
        Locale.setDefault(Locale.forLanguageTag("tr"))
        try {
            renderStatusJson(Path.of("/tmp/.lighten.json"), listOf(snapshot), PrintWriter(output, true))
        } finally {
            Locale.setDefault(previous)
        }

        assertTrue(output.toString().contains("\"state\":\"inaccessible\""), output.toString())
    }

    @Test
    fun unconfiguredStatusReportsJson() {
        val output = StringWriter()

        renderStatusJson(Path.of("/tmp/.lighten.json"), listOf(), PrintWriter(output, true), configured = false)

        assertEquals("{\"schema\":1,\"configured\":false,\"configPath\":\"/tmp/.lighten.json\",\"relocations\":[]}",
            output.toString().trim())
    }

    @Test
    fun configuredStatusHasTheSameShape() {
        val output = StringWriter()
        val snapshot = StatusSnapshot(Path.of("/source"), Path.of("/target"), RelocationSourceState.ABSENT)

        renderStatusJson(Path.of("/tmp/.lighten.json"), listOf(snapshot), PrintWriter(output, true))

        assertEquals("{\"schema\":1,\"configured\":true,\"configPath\":\"/tmp/.lighten.json\"," +
            "\"relocations\":[{\"sourcePath\":\"/source\",\"targetPath\":\"/target\",\"state\":\"absent\"}]}",
            output.toString().trim())
    }

    private data class CapturedOutput(val exitCode: Int, val output: String, val errorOutput: String)

    private companion object {
        fun execute(vararg args: String): CapturedOutput {
            val commandLine = LightenCommand.createCommandLine()
            val output = StringWriter()
            val errorOutput = StringWriter()
            commandLine.setOut(PrintWriter(output, true))
            commandLine.setErr(PrintWriter(errorOutput, true))

            val exitCode: Int = commandLine.execute(*args)
            return CapturedOutput(exitCode, output.toString(), errorOutput.toString())
        }
    }
}
