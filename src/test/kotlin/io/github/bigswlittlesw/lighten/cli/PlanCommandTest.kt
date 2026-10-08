package io.github.bigswlittlesw.homelight.cli

import io.github.bigswlittlesw.homelight.application.ConfigurationEvaluation
import io.github.bigswlittlesw.homelight.config.ConfigurationLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path

class PlanCommandTest {

    @Test
    fun pathOverridesAffectOnlyFirstRelocationAndPreserveJsonContract(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val config = root.resolve("config.json")
        val json = ("""
                {
                  "homelight": {
                    "target-root": "%s",
                    "relocations": [
                      {"source-path": "%s", "target-path": "%s"},
                      {"source-path": "%s", "target-path": "%s"}
                    ]
                  }
                }
                """.trimIndent() + "\n").format(root, root.resolve("old-source"), root.resolve("old-target"),
            root.resolve("second-source"), root.resolve("second-target"))
        Files.writeString(config, json)
        val source = root.resolve("new-source")
        val target = root.resolve("new-target")
        val command = HomeLightCommand.createCommandLine()
        val out = StringWriter()
        val err = StringWriter()
        command.setOut(PrintWriter(out, true))
        command.setErr(PrintWriter(err, true))

        assertEquals(0, command.execute("plan", "-c", config.toString(), "--json",
            "--source-path", source.toString(), "--target-path", target.toString()))
        val expected = ConfigurationEvaluation().loadRequired(config,
            ConfigurationLoader.PathOverride(source, target))
        val rendered = StringWriter()
        renderPlanJson(expected.plan, PrintWriter(rendered, true))
        assertEquals(rendered.toString(), out.toString())
        assertEquals(source, expected.plan.relocations.first().relocation.sourcePath)
        assertEquals(root.resolve("second-source"), expected.plan.relocations.last().relocation.sourcePath)
        assertEquals(json, Files.readString(config))
        assertTrue(Files.notExists(source))
        assertTrue(Files.notExists(target))

        assertEquals(2, command.execute("plan", "-c", config.toString(), "--json", "--source-path", source.toString()))
        assertTrue(err.toString().contains("must be provided together"))
    }

    @Test
    fun rendersPlanAsJson(@TempDir root: Path) {
        val source = Files.createDirectories(root.resolve("source"))
        val target = Files.createDirectories(root.resolve("target"))
        val config = root.resolve("config.json")
        Files.writeString(config, ("""
                {
                  "homelight": {
                    "target-root": "%s",
                    "relocations": [
                      {"source-path": "%s", "target-path": "%s", "when-source-and-target-directories-exist": "leave-unchanged"}
                    ]
                  }
                }
                """.trimIndent() + "\n").format(root, source, target))

        val command = HomeLightCommand.createCommandLine()
        val out = StringWriter()
        command.setOut(PrintWriter(out, true))

        assertEquals(0, command.execute("plan", "--config", config.toString(), "--json"))
        val output = out.toString()
        assertTrue(output.contains("\"outcome\":\"unchanged\""))
        assertTrue(output.contains("\"relocations\":["))
        assertTrue(output.contains("\"actions\":["))
    }

    @Test
    fun rendersAnArchiveLocationThatIsAFileAsABlockedAction(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val source = Files.createDirectories(root.resolve("source"))
        val target = Files.createDirectories(root.resolve("target"))
        val archive = Files.writeString(root.resolve("archive"), "a file")
        val config = root.resolve("config.json")
        Files.writeString(config, ("""
                {
                  "homelight": {
                    "target-root": "%s",
                    "relocations": [
                      {"source-path": "%s", "target-path": "%s", "when-source-and-target-directories-exist": "adopt",
                       "when-adopting-target": "archive-source", "archive-root": "%s"}
                    ]
                  }
                }
                """.trimIndent() + "\n").format(root, source, target, archive))
        val command = HomeLightCommand.createCommandLine()
        val out = StringWriter()
        command.setOut(PrintWriter(out, true))

        assertEquals(0, command.execute("plan", "--config", config.toString(), "--json"))
        val output = out.toString()
        assertTrue(output.contains("\"blocked\":true"), output)
        assertTrue(output.contains("\"type\":\"blocked\""), output)
        assertTrue(output.contains("\"reason\":\"$archive is a file, not a folder\""), output)
    }

    @Test
    fun rendersPlanAsJsonWithSubcommandShortConfig(@TempDir root: Path) {
        val source = Files.createDirectories(root.resolve("source"))
        val target = Files.createDirectories(root.resolve("target"))
        val config = root.resolve("config.json")
        Files.writeString(config, ("""
                {
                  "homelight": {
                    "target-root": "%s",
                    "relocations": [
                      {"source-path": "%s", "target-path": "%s", "when-source-and-target-directories-exist": "leave-unchanged"}
                    ]
                  }
                }
                """.trimIndent() + "\n").format(root, source, target))

        val command = HomeLightCommand.createCommandLine()
        val out = StringWriter()
        command.setOut(PrintWriter(out, true))

        assertEquals(0, command.execute("plan", "-c", config.toString(), "--json"))
        val output = out.toString()
        assertTrue(output.contains("\"outcome\":\"unchanged\""))
        assertTrue(output.contains("\"relocations\":["))
        assertTrue(output.contains("\"actions\":["))
    }

    @Test
    fun rendersPlanAsJsonWithTopLevelConfig(@TempDir root: Path) {
        val source = Files.createDirectories(root.resolve("source"))
        val target = Files.createDirectories(root.resolve("target"))
        val config = root.resolve("config.json")
        Files.writeString(config, ("""
                {
                  "homelight": {
                    "target-root": "%s",
                    "relocations": [
                      {"source-path": "%s", "target-path": "%s", "when-source-and-target-directories-exist": "leave-unchanged"}
                    ]
                  }
                }
                """.trimIndent() + "\n").format(root, source, target))

        val command = HomeLightCommand.createCommandLine()
        val out = StringWriter()
        command.setOut(PrintWriter(out, true))

        assertEquals(0, command.execute("--config", config.toString(), "plan", "--json"))
        val output = out.toString()
        assertTrue(output.contains("\"outcome\":\"unchanged\""))
        assertTrue(output.contains("\"relocations\":["))
        assertTrue(output.contains("\"actions\":["))
    }

    @Test
    fun rendersPlanAsJsonWithTopLevelShortConfig(@TempDir root: Path) {
        val source = Files.createDirectories(root.resolve("source"))
        val target = Files.createDirectories(root.resolve("target"))
        val config = root.resolve("config.json")
        Files.writeString(config, ("""
                {
                  "homelight": {
                    "target-root": "%s",
                    "relocations": [
                      {"source-path": "%s", "target-path": "%s", "when-source-and-target-directories-exist": "leave-unchanged"}
                    ]
                  }
                }
                """.trimIndent() + "\n").format(root, source, target))

        val command = HomeLightCommand.createCommandLine()
        val out = StringWriter()
        command.setOut(PrintWriter(out, true))

        assertEquals(0, command.execute("-c", config.toString(), "plan", "--json"))
        val output = out.toString()
        assertTrue(output.contains("\"outcome\":\"unchanged\""))
        assertTrue(output.contains("\"relocations\":["))
        assertTrue(output.contains("\"actions\":["))
    }

    @Test
    fun nonInteractivePlanWithoutJsonFailsGracefully(@TempDir root: Path) {
        val source = Files.createDirectories(root.resolve("source"))
        val target = Files.createDirectories(root.resolve("target"))
        val config = root.resolve("config.json")
        Files.writeString(config, ("""
                {
                  "homelight": {
                    "target-root": "%s",
                    "relocations": [
                      {"source-path": "%s", "target-path": "%s"}
                    ]
                  }
                }
                """.trimIndent() + "\n").format(root, source, target))

        val command = HomeLightCommand.createCommandLine()
        val err = StringWriter()
        command.setErr(PrintWriter(err, true))

        assertEquals(2, command.execute("plan", "--config", config.toString()))
        assertTrue(err.toString().contains("HomeLight TUI requires an interactive terminal. Use --json for automation."))
    }

    @Test
    fun rendersUnconfiguredPlanAsJson() {
        val command = HomeLightCommand.createCommandLine()
        val out = StringWriter()
        command.setOut(PrintWriter(out, true))

        assertEquals(0, command.execute("plan", "--config", ConfigurationLoader.DEFAULT_PATH.toString(), "--json"))
        assertTrue(out.toString().contains("\"relocations\":[]"))
    }
}
