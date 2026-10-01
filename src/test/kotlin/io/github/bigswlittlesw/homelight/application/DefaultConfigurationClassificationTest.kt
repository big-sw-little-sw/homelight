package io.github.bigswlittlesw.homelight.application

import io.github.bigswlittlesw.homelight.cli.HomeLightCommand
import io.github.bigswlittlesw.homelight.config.ConfigurationLoader
import io.github.bigswlittlesw.homelight.config.javaStrip
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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
                } catch (exception: java.io.IOException) {
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
            @JvmStatic fun main(args: Array<String>) {
                val defaultPath = ConfigurationLoader.DEFAULT_PATH
                val config = if (args[0].equals("explicit-directory"))
                    defaultPath.resolveSibling("explicit.yaml") else defaultPath
                when (args[0]) {
                    "default-directory", "explicit-directory" -> Files.createDirectory(config)
                    "default-malformed" -> Files.writeString(config, "homelight: [")
                    "default-missing" -> { }
                    else -> throw IllegalArgumentException(args[0])
                }
                val unconfigured = args[0].equals("default-directory") || args[0].equals("default-missing")
                val evaluator = ConfigurationEvaluation()
                // Include a lexically different spelling of the same default path.
                for (path in listOf(config, config.parent.resolve(".").resolve(config.fileName))) {
                    val evaluation = evaluator.load(path)
                    val session = HomeLightSession(path)
                    assertClassification(evaluation, session.planModel(), unconfigured)
                    assertClassification(evaluation, PlanWorkflow().loadPlan(path), unconfigured)
                    session.refresh()
                    assertClassification(session.evaluation(), session.planModel(), unconfigured)
                    assertFalse(session.requestApply())
                }
                for (command in listOf("status", "plan", "apply")) {
                    val cli = HomeLightCommand.createCommandLine()
                    val out = StringWriter()
                    val err = StringWriter()
                    cli.setOut(PrintWriter(out, true))
                    cli.setErr(PrintWriter(err, true))
                    val arguments = ArrayList(listOf(command, "--config", config.toString(), "--json"))
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
                            assertEquals("{\"succeeded\":true,\"relocations\":[]}", out.toString().javaStrip())
                        }
                    } else {
                        assertNotEquals(0, exit)
                        assertTrue(out.toString().isEmpty(), out.toString())
                        assertFalse(err.toString().isEmpty())
                    }
                }
                if (args[0].equals("default-directory")) {
                    val retained = evaluator.load(config)
                    Files.delete(config)
                    Files.writeString(config, "homelight: [")
                    // Adapting a retained result must not reclassify it using a later filesystem state.
                    assertInstanceOf(PlanModel.Unconfigured::class.java, PlanWorkflow.from(retained))
                    assertInstanceOf(ConfigurationEvaluation.Invalid::class.java, evaluator.replan(retained).evaluation)
                }
            }

            private fun assertClassification(evaluation: ConfigurationEvaluation.Evaluation?,
                    plan: PlanModel, unconfigured: Boolean) {
                if (unconfigured) {
                    assertInstanceOf(ConfigurationEvaluation.Unconfigured::class.java, evaluation)
                    assertInstanceOf(PlanModel.Unconfigured::class.java, plan)
                } else {
                    assertInstanceOf(ConfigurationEvaluation.Invalid::class.java, evaluation)
                    assertInstanceOf(PlanModel.Invalid::class.java, plan)
                }
            }
        }
    }
}
