package io.github.bigswlittlesw.homelight.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files

class PlanCommandTest {

    @Test
    fun pathOverridesAffectOnlyFirstRelocationAndPreserveJsonContract(@org.junit.jupiter.api.io.TempDir temporary: java.nio.file.Path) {
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
        val expected = io.github.bigswlittlesw.homelight.application.ConfigurationEvaluation().loadRequired(config,
            io.github.bigswlittlesw.homelight.config.ConfigurationLoader.PathOverride(source, target))
        val rendered = StringWriter()
        PlanRenderer().renderJson(expected.plan, PrintWriter(rendered, true))
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
    fun rendersPlanAsJson() {
        val root = Files.createTempDirectory("homelight")
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
    fun rendersPlanAsJsonWithSubcommandShortConfig() {
        val root = Files.createTempDirectory("homelight")
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
    fun rendersPlanAsJsonWithTopLevelConfig() {
        val root = Files.createTempDirectory("homelight")
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
    fun rendersPlanAsJsonWithTopLevelShortConfig() {
        val root = Files.createTempDirectory("homelight")
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
    fun nonInteractivePlanWithoutJsonFailsGracefully() {
        val root = Files.createTempDirectory("homelight")
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

        assertEquals(0, command.execute("plan", "--config", io.github.bigswlittlesw.homelight.config.ConfigurationLoader.DEFAULT_PATH.toString(), "--json"))
        assertTrue(out.toString().contains("\"relocations\":[]"))
    }
}
