package io.github.bigswlittlesw.homelight.cli

import io.github.bigswlittlesw.homelight.application.ConfigurationEvaluation
import io.github.bigswlittlesw.homelight.config.ConfigurationException
import io.github.bigswlittlesw.homelight.domain.RelocationSourceState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import picocli.CommandLine.Command
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.Callable

class HomeLightCommandTest {

    @Test
    fun acceptsVisualDelayAtRootAndOnEveryTuiCommand() {
        for (arguments in listOf(
            arrayOf("--debug-step-delay-ms", "3000"),
            arrayOf("status", "--debug-step-delay-ms", "3000"),
            arrayOf("plan", "--debug-step-delay-ms", "3000"),
            arrayOf("apply", "--debug-step-delay-ms", "3000"),
            arrayOf("init", "--debug-step-delay-ms", "3000"),
        )) {
            val command = HomeLightCommand.createCommandLine()
            command.parseArgs(*arguments)
            val root: HomeLightCommand = command.getCommand()
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
            "relative-shared-list.json" to """{"homelight": {"target-root": "$local", "discovery": {"shared-list": "x.json"}}}""",
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
    fun keepsTheStackTraceForAnUnexpectedException() {
        val commandLine = HomeLightCommand.createCommandLine().addSubcommand("fail", FailingCommand())
        val output = StringWriter()
        val errorOutput = StringWriter()
        commandLine.setOut(PrintWriter(output, true))
        commandLine.setErr(PrintWriter(errorOutput, true))

        assertEquals(1, commandLine.execute("fail"))
        assertTrue(errorOutput.toString().contains("java.lang.IllegalStateException: unexpected"), errorOutput.toString())
        assertTrue(errorOutput.toString().contains("\tat "), errorOutput.toString())
    }

    @Command(name = "fail")
    private class FailingCommand : Callable<Int> {
        override fun call(): Int = throw IllegalStateException("unexpected")
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
    fun initIsAnExecutableInteractiveEntry() {
        val result = execute("init")
        assertEquals(2, result.exitCode)
        assertTrue(result.errorOutput.contains("interactive terminal"))
    }

    @Test
    fun shouldProvideVersionFromVersionProvider() {
        val provider = HomeLightVersionProvider()
        val version: Array<String> = provider.getVersion()

        assertEquals(1, version.size)
        assertEquals("homelight " + resolveVersion(), version[0])
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

        renderStatusJson(listOf(snapshot), PrintWriter(output, true))

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
            renderStatusJson(listOf(snapshot), PrintWriter(output, true))
        } finally {
            Locale.setDefault(previous)
        }

        assertTrue(output.toString().contains("\"state\":\"inaccessible\""), output.toString())
    }

    @Test
    fun unconfiguredStatusReportsJson() {
        val output = StringWriter()

        renderUnconfiguredStatusJson(Path.of("/tmp/.homelight.json"),
            PrintWriter(output, true))

        assertTrue(output.toString().contains("\"configured\":false"))
        assertTrue(output.toString().contains("\"configPath\":"))
        assertTrue(output.toString().contains("\"relocations\":[]"))
    }

    private data class CapturedOutput(val exitCode: Int, val output: String, val errorOutput: String)

    private companion object {
        fun execute(vararg args: String): CapturedOutput {
            val commandLine = HomeLightCommand.createCommandLine()
            val output = StringWriter()
            val errorOutput = StringWriter()
            commandLine.setOut(PrintWriter(output, true))
            commandLine.setErr(PrintWriter(errorOutput, true))

            val exitCode: Int = commandLine.execute(*args)
            return CapturedOutput(exitCode, output.toString(), errorOutput.toString())
        }
    }
}
