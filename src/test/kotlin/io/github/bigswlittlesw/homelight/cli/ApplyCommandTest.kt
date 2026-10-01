package io.github.bigswlittlesw.homelight.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path

class ApplyCommandTest {
    @Test
    fun appliesAnAbsentSourceAndTarget() {
        val root = Files.createTempDirectory("homelight").toRealPath()
        val source = root.resolve("home/cache")
        val target = root.resolve("local/cache")

        val result = apply(configuration(root, source, target))

        assertEquals(0, result.exitCode, result.output)
        assertTrue(Files.isSymbolicLink(source))
    }

    @Test
    fun stagesPublishesAndLinksAnExistingSourceDirectory() {
        val root = Files.createTempDirectory("homelight").toRealPath()
        val source = Files.createDirectories(root.resolve("home/cache"))
        val target = root.resolve("local/cache")
        Files.writeString(source.resolve("entry"), "source")

        val result = apply(configuration(root, source, target))

        assertEquals(0, result.exitCode, result.output)
        assertTrue(Files.isSymbolicLink(source))
        assertEquals("source", Files.readString(target.resolve("entry")))
    }

    @Test
    fun usesAConfiguredTargetLocalStagingRoot() {
        val root = Files.createTempDirectory("homelight").toRealPath()
        val source = Files.createDirectories(root.resolve("home/cache"))
        val target = root.resolve("local/cache")
        val stagingRoot = root.resolve("local/staging")
        Files.writeString(source.resolve("entry"), "source")

        val yaml = configuration(root, source, target, stagingRoot)
        val result = apply(yaml)

        assertEquals(0, result.exitCode, result.output)
        assertTrue(Files.isDirectory(stagingRoot))
        assertTrue(Files.notExists(target.parent.resolve(".homelight-staging")))
        Files.list(stagingRoot).use { entries ->
            assertTrue(entries.findAny().isEmpty)
        }
    }

    @Test
    fun cleansAProvenStaleStagingOperationBeforePublishing() {
        val root = Files.createTempDirectory("homelight").toRealPath()
        val source = Files.createDirectories(root.resolve("home/cache"))
        val target = root.resolve("local/cache")
        val stagingRoot = Files.createDirectories(root.resolve("local/staging"))
        val stale = Files.createDirectory(stagingRoot.resolve("operation-00000000-0000-0000-0000-000000000000"))
        Files.writeString(stale.resolve("target"), "homelight-staging-v1\n$target\n")
        Files.createFile(stale.resolve("lock"))
        Files.writeString(source.resolve("entry"), "source")

        val yaml = configuration(root, source, target, stagingRoot)
        val result = apply(yaml)

        assertEquals(0, result.exitCode, result.output)
        assertTrue(Files.notExists(stale))
        assertEquals("source", Files.readString(target.resolve("entry")))
    }

    @Test
    fun retainsAStagingOperationWithAnUnexpectedEntry() {
        val root = Files.createTempDirectory("homelight").toRealPath()
        val source = Files.createDirectories(root.resolve("home/cache"))
        val target = root.resolve("local/cache")
        val stagingRoot = Files.createDirectories(root.resolve("local/staging"))
        val suspicious = Files.createDirectory(stagingRoot.resolve("operation-00000000-0000-0000-0000-000000000000"))
        Files.writeString(suspicious.resolve("target"), "homelight-staging-v1\n$target\n")
        Files.createFile(suspicious.resolve("lock"))
        Files.writeString(suspicious.resolve("unexpected"), "keep")
        Files.writeString(source.resolve("entry"), "source")

        val result = apply(configuration(root, source, target, stagingRoot))

        assertEquals(0, result.exitCode, result.output)
        assertTrue(Files.exists(suspicious))
    }

    @Test
    fun usesAStagingRootElsewhereOnTheTargetFilesystem() {
        val root = Files.createTempDirectory("homelight").toRealPath()
        val source = Files.createDirectories(root.resolve("home/cache"))
        val target = root.resolve("local/cache")
        Files.writeString(source.resolve("entry"), "source")

        val stagingRoot = root.resolve("other/staging")
        val yaml = configuration(root, source, target, stagingRoot)
        val result = apply(yaml)

        assertEquals(0, result.exitCode, result.output)
        assertTrue(Files.isDirectory(source))
        assertTrue(Files.isDirectory(stagingRoot))
        assertTrue(Files.isDirectory(target))
    }

    @Test
    fun appliesConfiguredTargetAdoptionWithSourceDiscard() {
        val root = Files.createTempDirectory("homelight")
        val source = Files.createDirectories(root.resolve("home/cache"))
        val target = Files.createDirectories(root.resolve("local/cache"))
        Files.writeString(target.resolve("entry"), "target")

        val result = apply(configuration(root, source, target, """
                when-source-and-target-directories-exist: adopt
                when-adopting-target: discard-source
                """.trimIndent() + "\n"))

        assertEquals(0, result.exitCode)
        assertTrue(Files.isSymbolicLink(source))
        assertEquals("target", Files.readString(target.resolve("entry")))
        val repeated = apply(configuration(root, source, target, """
                when-source-and-target-directories-exist: adopt
                when-adopting-target: discard-source
                """.trimIndent() + "\n"))
        assertEquals(0, repeated.exitCode, repeated.output)
        assertTrue(repeated.output.contains("\"type\":\"no-op\""))
    }

    @Test
    fun archivesTheSourceWhenAdoptingAConfiguredTarget() {
        val root = Files.createTempDirectory("homelight").toRealPath()
        val source = Files.createDirectories(root.resolve("home/cache"))
        Files.writeString(source.resolve("source-entry"), "source")
        val target = Files.createDirectories(root.resolve("local/cache"))
        Files.writeString(target.resolve("target-entry"), "target")
        val archiveRoot = root.resolve("archive")

        val result = apply(configuration(root, source, target, """
                when-source-and-target-directories-exist: adopt
                when-adopting-target: archive-source
                source-archive-root: %s
                """.trimIndent().format(archiveRoot) + "\n"))

        assertEquals(0, result.exitCode, result.output)
        assertTrue(Files.isSymbolicLink(source))
        assertEquals("target", Files.readString(target.resolve("target-entry")))
        assertEquals("source", Files.readString(archiveRoot.resolve(source.root.relativize(source)).resolve("source-entry")))
        val repeated = apply(configuration(root, source, target, """
                when-source-and-target-directories-exist: adopt
                when-adopting-target: archive-source
                source-archive-root: %s
                """.trimIndent().format(archiveRoot) + "\n"))
        assertEquals(0, repeated.exitCode, repeated.output)
        assertTrue(repeated.output.contains("\"type\":\"no-op\""))
    }

    @Test
    fun leavesConfiguredDirectoriesUnchanged() {
        val root = Files.createTempDirectory("homelight")
        val source = Files.createDirectories(root.resolve("home/cache"))
        val target = Files.createDirectories(root.resolve("local/cache"))

        val result = apply(configuration(root, source, target, """
                when-source-and-target-directories-exist: leave-unchanged
                """.trimIndent() + "\n"))

        assertEquals(0, result.exitCode)
        assertTrue(Files.isDirectory(source))
        assertTrue(Files.isDirectory(target))
    }

    @Test
    fun appliesConfiguredDiscardToBothDirectories() {
        val root = Files.createTempDirectory("homelight")
        val source = Files.createDirectories(root.resolve("home/cache"))
        val target = Files.createDirectories(root.resolve("local/cache"))
        Files.writeString(source.resolve("source"), "source")
        Files.writeString(target.resolve("target"), "target")

        val result = apply(configuration(root, source, target, """
                when-source-and-target-directories-exist: discard
                """.trimIndent() + "\n"))

        assertEquals(0, result.exitCode)
        assertTrue(Files.isSymbolicLink(source))
        assertTrue(Files.notExists(target.resolve("source")))
        assertTrue(Files.notExists(target.resolve("target")))
    }

    @Test
    fun jsonAndNonInteractiveApplyRequireYes() {
        val root = Files.createTempDirectory("homelight")
        val source = root.resolve("home/cache")
        val target = root.resolve("local/cache")
        val config = Files.createTempFile("homelight", ".yaml")
        Files.writeString(config, configuration(root, source, target))

        assertEquals(2, execute("apply", "--config", config.toString()).exitCode)
        assertEquals(2, execute("apply", "--yes", "--config", config.toString()).exitCode)
        assertEquals(2, execute("apply", "--json", "--config", config.toString()).exitCode)
        assertTrue(Files.notExists(source))
    }

    @Test
    fun convergedRelocationIsANoOpOnTheSecondApply() {
        val root = Files.createTempDirectory("homelight")
        val source = root.resolve("home/cache")
        val target = root.resolve("local/cache")
        val yaml = configuration(root, source, target)

        apply(yaml)
        val result = apply(yaml)

        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("\"type\":\"no-op\""))
    }

    @Test
    fun jsonReportsConvergedResultsForPublicationAndItsNoOpRepeat() {
        val root = Files.createTempDirectory("homelight").toRealPath()
        val source = Files.createDirectories(root.resolve("home/cache"))
        val target = root.resolve("local/cache")
        Files.writeString(source.resolve("entry"), "source")
        val yaml = configuration(root, source, target)

        val first = applyJson(yaml)
        val second = applyJson(yaml)

        assertEquals(0, first.exitCode, first.output)
        assertTrue(first.output.contains("\"outcome\":\"converged\""))
        assertEquals(0, second.exitCode, second.output)
        assertTrue(second.output.contains("\"outcome\":\"converged\""))
    }

    @Test
    fun appliesWithTopLevelConfigOption() {
        val root = Files.createTempDirectory("homelight").toRealPath()
        val source = root.resolve("home/cache")
        val target = root.resolve("local/cache")
        val config = Files.createTempFile("homelight", ".yaml")
        Files.writeString(config, configuration(root, source, target))

        val result = execute("--config", config.toString(), "apply", "--json", "--yes")

        assertEquals(0, result.exitCode, result.output)
        assertTrue(Files.isSymbolicLink(source))
    }

    @Test
    fun appliesWithTopLevelShortConfigOption() {
        val root = Files.createTempDirectory("homelight").toRealPath()
        val source = root.resolve("home/cache")
        val target = root.resolve("local/cache")
        val config = Files.createTempFile("homelight", ".yaml")
        Files.writeString(config, configuration(root, source, target))

        val result = execute("-c", config.toString(), "apply", "--json", "--yes")

        assertEquals(0, result.exitCode, result.output)
        assertTrue(Files.isSymbolicLink(source))
    }

    @Test
    fun appliesWithSubcommandShortConfigOption() {
        val root = Files.createTempDirectory("homelight").toRealPath()
        val source = root.resolve("home/cache")
        val target = root.resolve("local/cache")
        val config = Files.createTempFile("homelight", ".yaml")
        Files.writeString(config, configuration(root, source, target))

        val result = execute("apply", "-c", config.toString(), "--json", "--yes")

        assertEquals(0, result.exitCode, result.output)
        assertTrue(Files.isSymbolicLink(source))
    }

    private data class Result(val exitCode: Int, val output: String)

    private companion object {
        fun apply(yaml: String): Result {
            val config = Files.createTempFile("homelight", ".yaml")
            Files.writeString(config, yaml)
            val output = StringWriter()
            val command = HomeLightCommand.createCommandLine()
            command.setOut(PrintWriter(output, true))
            return Result(command.execute("apply", "--json", "--yes", "--config", config.toString()), output.toString())
        }

        fun applyJson(yaml: String): Result {
            val config = Files.createTempFile("homelight", ".yaml")
            Files.writeString(config, yaml)
            val output = StringWriter()
            val command = HomeLightCommand.createCommandLine()
            command.setOut(PrintWriter(output, true))
            return Result(command.execute("apply", "--json", "--yes", "--config", config.toString()), output.toString())
        }

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
            configuration(root, source, target, "", "  staging-root: $stagingRoot\n")

        fun configuration(root: Path, source: Path, target: Path, decisions: String, stagingRoot: String): String =
            // The staging-root `%s` has no line break after it, as in the Java text block's `\` continuation.
            ("homelight:\n" +
                "  target-root: %s\n" +
                "%s" +
                "  relocations:\n" +
                "    - source-path: %s\n" +
                "      target-path: %s\n" +
                "%s").format(root, stagingRoot, source, target, indent(decisions))

        // Java's String.lines: no trailing empty line, unlike Kotlin's lines().
        fun indent(text: String): String =
            text.lines().let { if (it.last().isEmpty()) it.dropLast(1) else it }
                .map { line -> "      $line" }.fold("") { left, right -> left + right + "\n" }
    }
}
