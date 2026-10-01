package io.github.bigswlittlesw.homelight.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path

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
            assertEquals(3000L, root.debugStepDelayMillis())
        }
        for (delay in listOf("-1", "60001")) {
            val result = execute("--debug-step-delay-ms", delay)
            assertEquals(2, result.exitCode)
            assertTrue(result.errorOutput.contains("must be between 0 and 60000"), result.errorOutput)
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
    fun shouldDisplayVersionWithLongOption() {
        val result = execute("--version")

        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("homelight " + HomeLightVersionProvider.resolveVersion()))
    }

    @Test
    fun shouldDisplayVersionWithShortOption() {
        val result = execute("-V")

        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("homelight " + HomeLightVersionProvider.resolveVersion()))
    }

    @Test
    fun shouldFailClearlyWhenInvokedNonInteractivelyWithoutArguments() {
        val result = execute()

        assertEquals(2, result.exitCode)
        assertTrue(result.errorOutput.contains("HomeLight TUI requires an interactive terminal"))
    }

    @Test
    fun shouldFailClearlyWhenInvokedNonInteractivelyWithTopLevelConfig() {
        val result = execute("--config", "/tmp/custom.yaml")

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
        assertEquals("homelight " + HomeLightVersionProvider.resolveVersion(), version[0])
    }

    @Test
    fun statusReportsJsonFilesystemState() {
        val root = Files.createTempDirectory("homelight")
        val sourcePath = root.resolve("home")
        val targetPath = root.resolve("local")
        Files.createDirectories(targetPath)
        Files.createSymbolicLink(sourcePath, targetPath)
        val config = root.resolve("config.yaml")
        Files.writeString(config, "homelight:\n  target-root: $root\n  relocations:\n    - source-path: $sourcePath\n      target-path: $targetPath\n")

        val result = execute("status", "--config", config.toString(), "--json")

        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("\"state\":\"correct_symlink\""))
        assertTrue(result.output.contains("\"sourcePath\":\"$sourcePath"))
    }

    @Test
    fun statusReportsJsonFilesystemStateWithShortConfigOption() {
        val root = Files.createTempDirectory("homelight")
        val sourcePath = root.resolve("home")
        val targetPath = root.resolve("local")
        Files.createDirectories(targetPath)
        Files.createSymbolicLink(sourcePath, targetPath)
        val config = root.resolve("config.yaml")
        Files.writeString(config, "homelight:\n  target-root: $root\n  relocations:\n    - source-path: $sourcePath\n      target-path: $targetPath\n")

        val result = execute("status", "-c", config.toString(), "--json")

        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("\"state\":\"correct_symlink\""))
        assertTrue(result.output.contains("\"sourcePath\":\"$sourcePath"))
    }

    @Test
    fun statusReportsJsonFilesystemStateWithTopLevelConfigOption() {
        val root = Files.createTempDirectory("homelight")
        val sourcePath = root.resolve("home")
        val targetPath = root.resolve("local")
        Files.createDirectories(targetPath)
        Files.createSymbolicLink(sourcePath, targetPath)
        val config = root.resolve("config.yaml")
        Files.writeString(config, "homelight:\n  target-root: $root\n  relocations:\n    - source-path: $sourcePath\n      target-path: $targetPath\n")

        val result = execute("--config", config.toString(), "status", "--json")

        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("\"state\":\"correct_symlink\""))
        assertTrue(result.output.contains("\"sourcePath\":\"$sourcePath"))
    }

    @Test
    fun statusReportsJsonFilesystemStateWithTopLevelShortConfigOption() {
        val root = Files.createTempDirectory("homelight")
        val sourcePath = root.resolve("home")
        val targetPath = root.resolve("local")
        Files.createDirectories(targetPath)
        Files.createSymbolicLink(sourcePath, targetPath)
        val config = root.resolve("config.yaml")
        Files.writeString(config, "homelight:\n  target-root: $root\n  relocations:\n    - source-path: $sourcePath\n      target-path: $targetPath\n")

        val result = execute("-c", config.toString(), "status", "--json")

        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("\"state\":\"correct_symlink\""))
        assertTrue(result.output.contains("\"sourcePath\":\"$sourcePath"))
    }

    @Test
    fun statusJsonEscapesPathControlCharacters() {
        val output = StringWriter()
        val snapshot = StatusSnapshot(Path.of("/source/line\nbreak"), Path.of("/target"),
            io.github.bigswlittlesw.homelight.domain.RelocationSourceState.ABSENT)

        StatusRenderer().renderJson(listOf(snapshot), PrintWriter(output, true))

        assertTrue(output.toString().contains("line\\nbreak"))
    }

    @Test
    fun unconfiguredStatusReportsJson() {
        val output = StringWriter()

        StatusRenderer().renderUnconfiguredJson(Path.of("/tmp/.homelight.yaml"),
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
