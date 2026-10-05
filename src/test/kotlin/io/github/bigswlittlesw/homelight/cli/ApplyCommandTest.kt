package io.github.bigswlittlesw.homelight.cli

import io.github.bigswlittlesw.homelight.reconcile.lockOf
import io.github.bigswlittlesw.homelight.reconcile.stagedCopy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path

class ApplyCommandTest {
    // Config files stay outside each test's relocation root.
    @TempDir lateinit var configDirectory: Path

    @Test
    fun appliesAnAbsentSourceAndTarget(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val source = root.resolve("home/cache")
        val target = root.resolve("local/cache")

        val result = apply(configuration(root, source, target))

        assertEquals(0, result.exitCode, result.output)
        assertTrue(Files.isSymbolicLink(source))
    }

    @Test
    fun stagesPublishesAndLinksAnExistingSourceDirectory(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val source = Files.createDirectories(root.resolve("home/cache"))
        val target = root.resolve("local/cache")
        Files.writeString(source.resolve("entry"), "source")

        val result = apply(configuration(root, source, target))

        assertEquals(0, result.exitCode, result.output)
        assertTrue(Files.isSymbolicLink(source))
        assertEquals("source", Files.readString(target.resolve("entry")))
    }

    @Test
    fun usesAConfiguredTargetLocalStagingRoot(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val source = Files.createDirectories(root.resolve("home/cache"))
        val target = root.resolve("local/cache")
        val stagingRoot = root.resolve("local/staging")
        Files.writeString(source.resolve("entry"), "source")

        val json = configuration(root, source, target, stagingRoot)
        val result = apply(json)

        assertEquals(0, result.exitCode, result.output)
        assertTrue(Files.isDirectory(stagingRoot))
        assertTrue(Files.notExists(target.parent.resolve(".homelight-staging")))
        Files.list(stagingRoot).use { entries ->
            assertEquals(listOf(lockOf(stagedCopy(stagingRoot, target))), entries.toList())
        }
    }

    @Test
    fun clearsTheTargetsLeftoverAndKeepsOtherEntries(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val source = Files.createDirectories(root.resolve("home/cache"))
        val target = root.resolve("local/cache")
        val stagingRoot = Files.createDirectories(root.resolve("local/staging"))
        val leftover = Files.createDirectory(stagedCopy(stagingRoot, target))
        Files.writeString(leftover.resolve("entry"), "stale")
        val other = Files.createDirectory(stagingRoot.resolve("operation-other"))
        Files.writeString(other.resolve("entry"), "keep")
        Files.writeString(source.resolve("entry"), "source")

        val result = apply(configuration(root, source, target, stagingRoot))

        assertEquals(0, result.exitCode, result.output)
        assertTrue(Files.notExists(leftover))
        assertEquals("keep", Files.readString(other.resolve("entry")))
        assertEquals("source", Files.readString(target.resolve("entry")))
    }

    @Test
    fun usesAStagingRootElsewhereOnTheTargetFilesystem(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val source = Files.createDirectories(root.resolve("home/cache"))
        val target = root.resolve("local/cache")
        Files.writeString(source.resolve("entry"), "source")

        val stagingRoot = root.resolve("other/staging")
        val json = configuration(root, source, target, stagingRoot)
        val result = apply(json)

        assertEquals(0, result.exitCode, result.output)
        assertTrue(Files.isDirectory(source))
        assertTrue(Files.isDirectory(stagingRoot))
        assertTrue(Files.isDirectory(target))
    }

    @Test
    fun appliesConfiguredTargetAdoptionWithSourceDiscard(@TempDir root: Path) {
        val source = Files.createDirectories(root.resolve("home/cache"))
        val target = Files.createDirectories(root.resolve("local/cache"))
        Files.writeString(target.resolve("entry"), "target")

        val result = apply(configuration(root, source, target, "\"when-source-and-target-directories-exist\": \"adopt\", \"when-adopting-target\": \"discard-source\""))

        assertEquals(0, result.exitCode)
        assertTrue(Files.isSymbolicLink(source))
        assertEquals("target", Files.readString(target.resolve("entry")))
        val repeated = apply(configuration(root, source, target, "\"when-source-and-target-directories-exist\": \"adopt\", \"when-adopting-target\": \"discard-source\""))
        assertEquals(0, repeated.exitCode, repeated.output)
        assertTrue(repeated.output.contains("\"type\":\"no-op\""))
    }

    @Test
    fun archivesTheSourceWhenAdoptingAConfiguredTarget(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val source = Files.createDirectories(root.resolve("home/cache"))
        Files.writeString(source.resolve("source-entry"), "source")
        val target = Files.createDirectories(root.resolve("local/cache"))
        Files.writeString(target.resolve("target-entry"), "target")
        val archiveRoot = root.resolve("archive")

        val result = apply(configuration(root, source, target, "\"when-source-and-target-directories-exist\": \"adopt\", \"when-adopting-target\": \"archive-source\", \"archive-root\": \"$archiveRoot\""))

        assertEquals(0, result.exitCode, result.output)
        assertTrue(Files.isSymbolicLink(source))
        assertEquals("target", Files.readString(target.resolve("target-entry")))
        assertEquals("source", Files.readString(archiveRoot.resolve("cache/source-entry")))
        val repeated = apply(configuration(root, source, target, "\"when-source-and-target-directories-exist\": \"adopt\", \"when-adopting-target\": \"archive-source\", \"archive-root\": \"$archiveRoot\""))
        assertEquals(0, repeated.exitCode, repeated.output)
        assertTrue(repeated.output.contains("\"type\":\"no-op\""))
    }

    @Test
    fun leavesConfiguredDirectoriesUnchanged(@TempDir root: Path) {
        val source = Files.createDirectories(root.resolve("home/cache"))
        val target = Files.createDirectories(root.resolve("local/cache"))

        val result = apply(configuration(root, source, target, "\"when-source-and-target-directories-exist\": \"leave-unchanged\""))

        assertEquals(0, result.exitCode)
        assertTrue(Files.isDirectory(source))
        assertTrue(Files.isDirectory(target))
    }

    @Test
    fun appliesConfiguredDiscardToBothDirectories(@TempDir root: Path) {
        val source = Files.createDirectories(root.resolve("home/cache"))
        val target = Files.createDirectories(root.resolve("local/cache"))
        Files.writeString(source.resolve("source"), "source")
        Files.writeString(target.resolve("target"), "target")

        val result = apply(configuration(root, source, target, "\"when-source-and-target-directories-exist\": \"discard\""))

        assertEquals(0, result.exitCode)
        assertTrue(Files.isSymbolicLink(source))
        assertTrue(Files.notExists(target.resolve("source")))
        assertTrue(Files.notExists(target.resolve("target")))
    }

    @Test
    fun jsonAndNonInteractiveApplyRequireYes(@TempDir root: Path) {
        val source = root.resolve("home/cache")
        val target = root.resolve("local/cache")
        val config = Files.createTempFile(configDirectory, "homelight", ".json")
        Files.writeString(config, configuration(root, source, target))

        assertEquals(2, execute("apply", "--config", config.toString()).exitCode)
        assertEquals(2, execute("apply", "--yes", "--config", config.toString()).exitCode)
        assertEquals(2, execute("apply", "--json", "--config", config.toString()).exitCode)
        assertTrue(Files.notExists(source))
    }

    @Test
    fun convergedRelocationIsANoOpOnTheSecondApply(@TempDir root: Path) {
        val source = root.resolve("home/cache")
        val target = root.resolve("local/cache")
        val json = configuration(root, source, target)

        apply(json)
        val result = apply(json)

        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("\"type\":\"no-op\""))
    }

    @Test
    fun jsonReportsConvergedResultsForPublicationAndItsNoOpRepeat(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val source = Files.createDirectories(root.resolve("home/cache"))
        val target = root.resolve("local/cache")
        Files.writeString(source.resolve("entry"), "source")
        val json = configuration(root, source, target)

        val first = applyJson(json)
        val second = applyJson(json)

        assertEquals(0, first.exitCode, first.output)
        assertTrue(first.output.contains("\"outcome\":\"converged\""))
        assertEquals(0, second.exitCode, second.output)
        assertTrue(second.output.contains("\"outcome\":\"converged\""))
    }

    @Test
    fun appliesWithTopLevelConfigOption(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val source = root.resolve("home/cache")
        val target = root.resolve("local/cache")
        val config = Files.createTempFile(configDirectory, "homelight", ".json")
        Files.writeString(config, configuration(root, source, target))

        val result = execute("--config", config.toString(), "apply", "--json", "--yes")

        assertEquals(0, result.exitCode, result.output)
        assertTrue(Files.isSymbolicLink(source))
    }

    @Test
    fun appliesWithTopLevelShortConfigOption(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val source = root.resolve("home/cache")
        val target = root.resolve("local/cache")
        val config = Files.createTempFile(configDirectory, "homelight", ".json")
        Files.writeString(config, configuration(root, source, target))

        val result = execute("-c", config.toString(), "apply", "--json", "--yes")

        assertEquals(0, result.exitCode, result.output)
        assertTrue(Files.isSymbolicLink(source))
    }

    @Test
    fun appliesWithSubcommandShortConfigOption(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val source = root.resolve("home/cache")
        val target = root.resolve("local/cache")
        val config = Files.createTempFile(configDirectory, "homelight", ".json")
        Files.writeString(config, configuration(root, source, target))

        val result = execute("apply", "-c", config.toString(), "--json", "--yes")

        assertEquals(0, result.exitCode, result.output)
        assertTrue(Files.isSymbolicLink(source))
    }

    private fun apply(json: String): Result {
        val config = Files.createTempFile(configDirectory, "homelight", ".json")
        Files.writeString(config, json)
        val output = StringWriter()
        val command = HomeLightCommand.createCommandLine()
        command.setOut(PrintWriter(output, true))
        return Result(command.execute("apply", "--json", "--yes", "--config", config.toString()), output.toString())
    }

    private fun applyJson(json: String): Result {
        val config = Files.createTempFile(configDirectory, "homelight", ".json")
        Files.writeString(config, json)
        val output = StringWriter()
        val command = HomeLightCommand.createCommandLine()
        command.setOut(PrintWriter(output, true))
        return Result(command.execute("apply", "--json", "--yes", "--config", config.toString()), output.toString())
    }

    private data class Result(val exitCode: Int, val output: String)

    private companion object {
        fun execute(vararg arguments: String): Result {
            val output = StringWriter()
            val command = HomeLightCommand.createCommandLine()
            command.setOut(PrintWriter(output, true))
            return Result(command.execute(*arguments), output.toString())
        }

        fun configuration(root: Path, source: Path, target: Path): String =
            configuration(root, source, target, "")

        fun configuration(root: Path, source: Path, target: Path, decisions: String): String =
            configuration(root, source, target, decisions, "")

        fun configuration(root: Path, source: Path, target: Path, stagingRoot: Path): String =
            configuration(root, source, target, "", "\"staging-root\": \"$stagingRoot\", ")

        /** `decisions` are relocation members without a trailing comma; `globals` are members each followed by one. */
        fun configuration(root: Path, source: Path, target: Path, decisions: String, globals: String): String =
            "{\"homelight\": {\"target-root\": \"$root\", $globals\"relocations\": [\n" +
                "  {\"source-path\": \"$source\", \"target-path\": \"$target\"" +
                (if (decisions.isEmpty()) "" else ", $decisions") + "}\n]}}\n"
    }
}
