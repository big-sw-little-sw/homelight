package io.github.bigswlittlesw.lighten.application

import io.github.bigswlittlesw.lighten.cli.LightenCommand
import io.github.bigswlittlesw.lighten.config.ConfigurationLoader
import io.github.bigswlittlesw.lighten.config.javaStrip
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class DefaultConfigurationClassificationTest {
    @ParameterizedTest
    @ValueSource(strings = ["default-directory", "default-missing", "default-malformed", "explicit-directory"])
    fun callersAgreeOnClassificationWithAnIsolatedHome(scenario: String, @TempDir temporary: Path) {
        // DEFAULT_PATH is initialized once per JVM. Isolate user.home before class loading, without touching the real home.
        val output = temporary.resolve("probe.log")
        val process = ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Duser.home=" + temporary.toRealPath(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                Probe::class.java.name, scenario)
                .redirectErrorStream(true).redirectOutput(output.toFile()).start()
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Classification probe timed out")
            assertEquals(0, process.exitValue()) {
                try {
                    Files.readString(output)
                } catch (exception: IOException) {
                    exception.toString()
                }
            }
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
            }
        }
    }

    class Probe {
        companion object {
            // JVM entry point for the subprocess this test launches.
            @JvmStatic fun main(args: Array<String>) {
                val defaultPath = ConfigurationLoader.DEFAULT_PATH
                val config = if (args[0] == "explicit-directory")
                    defaultPath.resolveSibling("explicit.json") else defaultPath
                when (args[0]) {
                    "default-directory", "explicit-directory" -> Files.createDirectory(config)
                    "default-malformed" -> Files.writeString(config, "{\"lighten\": [")
                    "default-missing" -> { }
                    else -> throw IllegalArgumentException(args[0])
                }
                val unconfigured = args[0].equals("default-directory") || args[0].equals("default-missing")
                val evaluator = ConfigurationEvaluation()
                // Include a lexically different spelling of the same default path.
                for (path in listOf(config, config.parent.resolve(".").resolve(config.fileName))) {
                    val session = LightenSession(path)
                    assertClassification(evaluator.load(path), unconfigured)
                    assertClassification(session.evaluation(), unconfigured)
                    session.refresh()
                    assertClassification(session.evaluation(), unconfigured)
                    assertFalse(session.requestApply())
                }
                for (command in listOf("status", "plan", "apply")) {
                    val cli = LightenCommand.createCommandLine()
                    val out = StringWriter()
                    val err = StringWriter()
                    cli.setOut(PrintWriter(out, true))
                    cli.setErr(PrintWriter(err, true))
                    val arguments = mutableListOf(command, "--config", config.toString(), "--json")
                    if (command.equals("apply")) {
                        arguments.add("--yes")
                    }
                    val exit = cli.execute(*arguments.toTypedArray())
                    if (unconfigured) {
                        assertEquals(0, exit, err.toString())
                        assertTrue(out.toString().contains("\"relocations\":[]"), out.toString())
                        if (command.equals("status")) {
                            assertTrue(out.toString().contains("\"configured\":false"), out.toString())
                        } else if (command.equals("apply")) {
                            assertEquals("{\"schema\":1,\"succeeded\":true,\"relocations\":[]}", out.toString().javaStrip())
                        }
                    } else {
                        assertNotEquals(0, exit)
                        assertTrue(out.toString().isEmpty(), out.toString())
                        assertFalse(err.toString().isEmpty())
                    }
                }
                if (args[0].equals("default-directory")) {
                    Files.delete(config)
                    Files.writeString(config, "{\"lighten\": [")
                    assertInstanceOf(ConfigurationEvaluation.Invalid::class.java, evaluator.load(config))
                }
            }

            private fun assertClassification(evaluation: ConfigurationEvaluation.Evaluation, unconfigured: Boolean) {
                if (unconfigured) {
                    assertInstanceOf(ConfigurationEvaluation.Unconfigured::class.java, evaluation)
                } else {
                    assertInstanceOf(ConfigurationEvaluation.Invalid::class.java, evaluation)
                }
            }
        }
    }
}
