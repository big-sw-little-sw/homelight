package io.github.bigswlittlesw.homelight.cli

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.github.bigswlittlesw.homelight.application.ConfigurationEvaluation
import io.github.bigswlittlesw.homelight.application.guideUrl
import io.github.bigswlittlesw.homelight.application.resolveVersion
import io.github.bigswlittlesw.homelight.application.userGuide
import io.github.bigswlittlesw.homelight.config.ConfigurationException
import io.github.bigswlittlesw.homelight.domain.RelocationSourceState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.CompletionException

class HomeLightCommandTest {

    @Test
    fun acceptsVisualDelayAtRootAndOnEveryTuiCommand() {
        for (arguments in listOf(
            arrayOf("--debug-step-delay-ms", "3000", "status"),
            arrayOf("status", "--debug-step-delay-ms", "3000"),
            arrayOf("plan", "--debug-step-delay-ms", "3000"),
            arrayOf("apply", "--debug-step-delay-ms", "3000"),
            arrayOf("init", "--debug-step-delay-ms", "3000"),
        )) {
            val probe = DelayProbe(arguments.first { !it.startsWith("-") && it != "3000" })
            val err = StringWriter()
            // The probe stands in for the real command, so nothing opens the TUI.
            val command = HomeLightCommand(PrintWriter(StringWriter()), PrintWriter(err)).subcommands(probe)
            assertEquals(0, command.execute(*arguments), err.toString())
            assertEquals(3000L, probe.delay, arguments.joinToString(" "))
        }
    }

    /** Resolves the shared options like a real command, without opening the TUI. */
    private class DelayProbe(name: String) : ExitCodeCommand(name) {
        private val shared by SharedOptions()
        var delay: Long? = null
            private set

        override fun call(): Int {
            delay = settings(shared).debugStepDelayMillis
            return 0
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
            assertTrue(result.errorOutput.lines().contains(
                "Error: invalid value for --debug-step-delay-ms: must be between 0 and 60000"), result.errorOutput)
            assertFalse(result.errorOutput.contains("Could not invoke"), result.errorOutput)
            assertEquals("", result.output)
        }
    }

    @Test
    fun printsAConfigurationErrorAsOneLineWithoutJsonOutput(@TempDir root: Path) {
        val local = root.resolve("local")
        val source = root.resolve("home/cache")
        val configs = mapOf(
            "missing.json" to null,
            "malformed.json" to "{\"homelight\": {\"target-root\": \"unclosed\n",
            "unknown-key.json" to """{"homelight": {"target-root": "$local", "relocations": [
                {"source-path": "$source", "target-path": "$local/cache", "existing": "move"}]}}""",
            "bad-enum.json" to """{"homelight": {"target-root": "$local", "relocations": [
                {"source-path": "$source", "target-path": "$local/cache", "when-only-target-exists": "sometimes"}]}}""",
            "relative-suggestion-list.json" to """{"homelight": {"target-root": "$local", "suggestion-list": "x.json"}}""",
            "nul-source-root.json" to """{"homelight": {"source-root": "/a\u0000b", "target-root": "$local"}}""",
        )
        for ((name, content) in configs) {
            val config = root.resolve(name)
            content?.let { Files.writeString(config, it) }
            val expected = assertThrows<ConfigurationException> { ConfigurationEvaluation().loadRequired(config) }.message
            for (command in listOf(arrayOf("status", "--json"), arrayOf("plan", "--json"), arrayOf("apply", "--json", "--yes"))) {
                val result = execute("-c", config.toString(), *command)

                val context = "$name ${command.joinToString(" ")}: ${result.errorOutput}"
                assertEquals(1, result.exitCode, context)
                assertEquals("$expected\n", result.errorOutput.replace(System.lineSeparator(), "\n"), context)
                assertEquals("", result.output, context)
            }
        }
    }

    @Test
    fun reportsAnyOtherExceptionAsOneInternalErrorLineWithExitCode70() {
        for (arguments in listOf(
            arrayOf("fail"), arrayOf("fail", "--json"), arrayOf("fail", "--worker"), arrayOf("fail", "--worker", "--json"),
        )) {
            val output = StringWriter()
            val errorOutput = StringWriter()
            val command = homeLightCommand(PrintWriter(output, true), PrintWriter(errorOutput, true))
                .subcommands(FailingCommand())

            val context = arguments.joinToString(" ") + ": " + errorOutput
            assertEquals(70, command.execute(*arguments), context)
            assertEquals("Internal error (please report): IllegalStateException: unexpected\n",
                errorOutput.toString().replace(System.lineSeparator(), "\n"), context)
            assertEquals("", output.toString(), context)
        }
    }

    /** The handler treats `--json` and a worker's wrapped bug the same way as a plain bug. */
    private class FailingCommand : ExitCodeCommand("fail") {
        private val json by option("--json").flag()
        private val worker by option("--worker").flag()

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
        assertTrue(output.contains("Usage: homelight"))
        assertTrue(output.contains("--help"))
        assertTrue(output.contains("--version"))
        assertTrue(output.contains("--config"))
    }

    @Test
    fun shouldDisplayHelpWithShortOption() {
        val result = execute("-h")

        assertEquals(0, result.exitCode)
        val output: String = result.output
        assertTrue(output.contains("Usage: homelight"))
    }

    @Test
    fun helpEndsWithTheGuidesAddressForThisVersion() {
        val result = execute("--help")

        assertTrue(result.output.lines().contains(guideUrl()), result.output)
        assertTrue(result.output.contains("homelight guide"), result.output)
    }

    @Test
    fun guidePrintsTheEmbeddedGuide() {
        val result = execute("guide")

        assertEquals(0, result.exitCode)
        assertEquals(userGuide(), result.output)
        assertTrue(result.output.startsWith("# HomeLight\n"))
    }

    @Test
    fun theGuidesLinkFollowsTheVersion() {
        assertTrue(userGuide("1.0-SNAPSHOT").contains("/blob/main/docs/user-guide.md"))
        val release = userGuide("1.2.0")
        assertTrue(release.contains("https://github.com/big-sw-little-sw/homelight/blob/v1.2.0/docs/user-guide.md"))
        assertFalse(release.contains("/blob/main/"))
    }

    @Test
    fun shouldDisplayVersionWithLongOption() {
        val result = execute("--version")

        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("homelight " + resolveVersion()))
    }

    @Test
    fun shouldDisplayVersionWithShortOption() {
        val result = execute("-V")

        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("homelight " + resolveVersion()))
    }

    @Test
    fun shouldFailClearlyWhenInvokedNonInteractivelyWithoutArguments() {
        val result = execute()

        assertEquals(2, result.exitCode)
        assertTrue(result.errorOutput.contains("HomeLight TUI requires an interactive terminal"))
    }

    @Test
    fun shouldFailClearlyWhenInvokedNonInteractivelyWithTopLevelConfig() {
        val result = execute("--config", "/tmp/custom.json")

        assertEquals(2, result.exitCode)
        assertTrue(result.errorOutput.contains("HomeLight TUI requires an interactive terminal"))
    }

    @Test
    fun shouldFailClearlyWhenStatusInvokedNonInteractivelyWithoutJson() {
        val result = execute("status")

        assertEquals(2, result.exitCode)
        assertTrue(result.errorOutput.contains("HomeLight TUI requires an interactive terminal"))
    }

    @Test
    fun initAndConfigAreExecutableInteractiveEntries() {
        for (name in listOf("init", "config")) {
            val result = execute(name)
            assertEquals(2, result.exitCode, name)
            assertTrue(result.errorOutput.contains("interactive terminal"), name)
        }
    }

    /** Configuration opens a file that loads, or a new one; a file HomeLight cannot read is fixed by hand. */
    @Test
    fun configRefusesAFileItCannotRead(@TempDir root: Path) {
        val config = Files.writeString(root.resolve("config.json"), "{\"homelight\": [")
        val result = execute("-c", config.toString(), "config")
        assertEquals(1, result.exitCode)
        assertTrue(result.errorOutput.contains("so Configuration cannot open it. Fix the file by hand: Line 1"), result.errorOutput)
    }

    @Test
    fun statusReportsJsonFilesystemState(@TempDir root: Path) {
        val sourcePath = root.resolve("home")
        val targetPath = root.resolve("local")
        Files.createDirectories(targetPath)
        Files.createSymbolicLink(sourcePath, targetPath)
        val config = root.resolve("config.json")
        Files.writeString(config, "{\"homelight\": {\"target-root\": \"$root\", \"relocations\": [{\"source-path\": \"$sourcePath\", \"target-path\": \"$targetPath\"}]}}\n")

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
        Files.writeString(config, "{\"homelight\": {\"target-root\": \"$root/local\", \"relocations\": [{\"source-path\": \"$sourcePath\", \"target-path\": \"$root/local/cache\"}]}}\n")

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
        Files.writeString(config, "{\"homelight\": {\"target-root\": \"$root\", \"relocations\": [{\"source-path\": \"$sourcePath\", \"target-path\": \"$targetPath\"}]}}\n")

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
        Files.writeString(config, "{\"homelight\": {\"target-root\": \"$root\", \"relocations\": [{\"source-path\": \"$sourcePath\", \"target-path\": \"$targetPath\"}]}}\n")

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
        Files.writeString(config, "{\"homelight\": {\"target-root\": \"$root\", \"relocations\": [{\"source-path\": \"$sourcePath\", \"target-path\": \"$targetPath\"}]}}\n")

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

        renderStatusJson(Path.of("/tmp/.homelight.json"), listOf(snapshot), PrintWriter(output, true))

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
            renderStatusJson(Path.of("/tmp/.homelight.json"), listOf(snapshot), PrintWriter(output, true))
        } finally {
            Locale.setDefault(previous)
        }

        assertTrue(output.toString().contains("\"state\":\"inaccessible\""), output.toString())
    }

    @Test
    fun unconfiguredStatusReportsJson() {
        val output = StringWriter()

        renderStatusJson(Path.of("/tmp/.homelight.json"), listOf(), PrintWriter(output, true), configured = false)

        assertEquals("{\"schema\":1,\"configured\":false,\"configPath\":\"/tmp/.homelight.json\",\"relocations\":[]}",
            output.toString().trim())
    }

    @Test
    fun configuredStatusHasTheSameShape() {
        val output = StringWriter()
        val snapshot = StatusSnapshot(Path.of("/source"), Path.of("/target"), RelocationSourceState.ABSENT)

        renderStatusJson(Path.of("/tmp/.homelight.json"), listOf(snapshot), PrintWriter(output, true))

        assertEquals("{\"schema\":1,\"configured\":true,\"configPath\":\"/tmp/.homelight.json\"," +
            "\"relocations\":[{\"sourcePath\":\"/source\",\"targetPath\":\"/target\",\"state\":\"absent\"}]}",
            output.toString().trim())
    }

    private data class CapturedOutput(val exitCode: Int, val output: String, val errorOutput: String)

    private companion object {
        fun execute(vararg args: String): CapturedOutput {
            val output = StringWriter()
            val errorOutput = StringWriter()
            val exitCode = homeLightCommand(PrintWriter(output, true), PrintWriter(errorOutput, true)).execute(*args)
            return CapturedOutput(exitCode, output.toString(), errorOutput.toString())
        }
    }
}
