package io.github.bigswlittlesw.lighten.cli

import io.github.bigswlittlesw.lighten.application.resolveVersion
import io.github.bigswlittlesw.lighten.application.userGuide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path

/**
 * Every option of every command, as the CLI accepts or rejects it today: the parity bar for replacing the parser
 * (#70). Each case records the exit code and a line of output that shows the option took effect.
 *
 * Commands that open the TUI cannot run here: stdin is not a terminal, so they exit 2 with [NOT_A_TERMINAL]. That
 * outcome still shows the options parsed, as opposed to a usage error. `ci/native/test.sh` proves end to end that
 * `--debug-step-delay-ms` slows a TUI apply.
 */
class CliOptionCoverageTest {

    @TempDir
    lateinit var root: Path

    private val commands = listOf("status", "plan", "apply", "init", "config", "guide")

    @TestFactory
    fun configBeforeAndAfterTheCommandName(): List<DynamicTest> {
        val missing = root.resolve("missing.json").toString()
        val malformed = Files.writeString(root.resolve("malformed.json"), "{\"lighten\": [").toString()
        // The file each command reads, and what it prints when it read that file.
        fun target(command: String) = when (command) {
            "status", "plan", "apply" -> missing to Expect(1, err = "Configuration file does not exist: $missing")
            "init", "config" -> malformed to Expect(1, err = "Lighten can't read $malformed")
            "guide" -> missing to Expect(0, out = "# Lighten")
            else -> error(command)
        }
        fun json(command: String) = when (command) {
            "status", "plan" -> listOf("--json")
            "apply" -> listOf("--json", "--yes")
            else -> listOf()
        }
        val cases = buildList {
            for (option in listOf("-c", "--config")) {
                add(Case("root $option", listOf(option, missing), Expect(2, err = NOT_A_TERMINAL)))
                for (command in commands) {
                    val (path, expect) = target(command)
                    add(Case("$option before $command", listOf(option, path, command) + json(command), expect))
                    add(Case("$option after $command", listOf(command) + json(command) + listOf(option, path), expect))
                }
            }
            add(Case("--config=path after status", listOf("status", "--json", "--config=$missing"), target("status").second))
            add(Case("-cpath after status", listOf("status", "--json", "-c$missing"), target("status").second))
            // Given on both sides, the value after the command name wins.
            add(Case("config on both sides", listOf("-c", malformed, "status", "--json", "-c", missing), target("status").second))
        }
        return cases.map(::test)
    }

    @TestFactory
    fun hiddenDebugStepDelay(): List<DynamicTest> {
        val config = writeConfig("delay")
        val cases = buildList {
            add(Case("delay at root", listOf("--debug-step-delay-ms", "3000"), Expect(2, err = NOT_A_TERMINAL)))
            add(Case("delay before status --json", listOf("--debug-step-delay-ms", "5", "-c", config, "status", "--json"),
                Expect(0, out = "\"configured\":true")))
            add(Case("delay after status --json", listOf("-c", config, "status", "--json", "--debug-step-delay-ms", "5"),
                Expect(0, out = "\"configured\":true")))
            for (command in commands - "guide") {
                add(Case("delay before $command", listOf("--debug-step-delay-ms", "3000", "-c", config, command),
                    Expect(2, err = NOT_A_TERMINAL)))
                add(Case("delay after $command", listOf("-c", config, command, "--debug-step-delay-ms", "3000"),
                    Expect(2, err = NOT_A_TERMINAL)))
            }
            add(Case("delay after guide", listOf("guide", "--debug-step-delay-ms", "5"), Expect(0, out = "# Lighten")))
            for (value in listOf("-1", "60001", "abc", "1.5")) {
                add(Case("delay $value before", listOf("--debug-step-delay-ms", value, "status", "--json"), Expect(2)))
                add(Case("delay $value after", listOf("status", "--json", "--debug-step-delay-ms", value), Expect(2)))
            }
            add(Case("delay without a value", listOf("status", "--json", "--debug-step-delay-ms"), Expect(2)))
            add(Case("delay 0 and 60000 are allowed", listOf("--debug-step-delay-ms", "0", "-c", config, "status",
                "--json", "--debug-step-delay-ms", "60000"), Expect(0, out = "\"configured\":true")))
        }
        return cases.map(::test)
    }

    @TestFactory
    fun helpAndVersion(): List<DynamicTest> {
        val cases = buildList {
            for (option in listOf("--help", "-h")) {
                add(Case("root $option", listOf(option), Expect(0, out = "Usage: lighten", hidden = true)))
            }
            for (command in commands) {
                // `config` is an alias, so its help names `init`.
                val name = if (command == "config") "init" else command
                add(Case("$command --help", listOf(command, "--help"), Expect(0, out = "Usage: lighten $name",
                    hidden = true)))
            }
            add(Case("status -h", listOf("status", "-h"), Expect(0, out = "Usage: lighten status", hidden = true)))
            for (option in listOf("--version", "-V")) {
                add(Case("root $option", listOf(option), Expect(0, out = "lighten ${resolveVersion()}")))
            }
            add(Case("status --version", listOf("status", "--version"), Expect(2)))
        }
        return cases.map(::test)
    }

    @TestFactory
    fun badValuesAndUnknownOptions(): List<DynamicTest> = listOf(
        Case("--config without a value", listOf("--config"), Expect(2)),
        Case("-c without a value after status", listOf("status", "--json", "-c"), Expect(2)),
        Case("--source-path without a value", listOf("plan", "--json", "--source-path"), Expect(2)),
        Case("--target-path without a value", listOf("plan", "--json", "--target-path"), Expect(2)),
        Case("--source-path alone", listOf("plan", "--json", "--source-path", "/s"), Expect(2)),
        Case("--target-path alone", listOf("plan", "--json", "--target-path", "/t"), Expect(2)),
        Case("--json=maybe", listOf("status", "--json=maybe"), Expect(2)),
        Case("--yes=maybe", listOf("apply", "--json", "--yes=maybe"), Expect(2)),
        Case("--json twice", listOf("status", "--json", "--json"), Expect(2)),
        Case("--yes twice", listOf("apply", "--json", "--yes", "--yes"), Expect(2)),
        Case("--config twice after status", listOf("status", "--json", "-c", "/a", "-c", "/b"), Expect(2)),
        Case("--config twice before status", listOf("-c", "/a", "--config", "/b", "status", "--json"), Expect(2)),
        Case("delay twice", listOf("status", "--json", "--debug-step-delay-ms", "1", "--debug-step-delay-ms", "2"),
            Expect(2)),
        Case("--source-path twice", listOf("plan", "--json", "--source-path", "/s", "--source-path", "/s",
            "--target-path", "/t"), Expect(2)),
        Case("unknown option at root", listOf("--bogus"), Expect(2)),
        Case("unknown short option at root", listOf("-x"), Expect(2)),
        Case("unknown option on status", listOf("status", "--bogus"), Expect(2)),
        Case("unknown command", listOf("bogus"), Expect(2)),
        Case("extra argument", listOf("status", "extra"), Expect(2)),
        Case("--json on init", listOf("init", "--json"), Expect(2)),
        Case("--json on guide", listOf("guide", "--json"), Expect(2)),
        Case("--yes on status", listOf("status", "--yes"), Expect(2)),
        Case("--yes on plan", listOf("plan", "--json", "--yes"), Expect(2)),
        Case("--version after a command", listOf("plan", "-V"), Expect(2)),
    ).map(::test)

    @TestFactory
    fun jsonAndYes(): List<DynamicTest> {
        val good = writeConfig("good")
        val conflict = root.resolve("conflict").let { dir ->
            Files.createDirectories(dir.resolve("home/a"))
            Files.createDirectories(dir.resolve("local/a"))
            Files.writeString(dir.resolve("local/a/t"), "t")
            Files.writeString(dir.resolve("config.json"), """{"lighten": {"target-root": "$dir/local", "relocations":
                [{"source-path": "$dir/home/a", "target-path": "$dir/local/a"}]}}""").toString()
        }
        return listOf(
            Case("status", listOf("-c", good, "status"), Expect(2, err = NOT_A_TERMINAL)),
            Case("status --json", listOf("-c", good, "status", "--json"), Expect(0, out = "\"configured\":true")),
            Case("plan", listOf("-c", good, "plan"), Expect(2, err = NOT_A_TERMINAL)),
            Case("plan --json", listOf("-c", good, "plan", "--json"), Expect(0, out = "\"blocked\":false")),
            Case("plan --json with paths", listOf("-c", good, "plan", "--json", "--source-path", "$root/s",
                "--target-path", "$root/t"), Expect(0, out = "\"source\":\"$root/s\"")),
            Case("apply", listOf("-c", good, "apply"), Expect(2, err = NOT_A_TERMINAL)),
            Case("apply --yes", listOf("-c", good, "apply", "--yes"), Expect(2, err = NOT_A_TERMINAL)),
            Case("apply --json", listOf("-c", good, "apply", "--json"), Expect(2, err = "JSON apply requires --yes.")),
            Case("apply --json --yes, conflict", listOf("-c", conflict, "apply", "--json", "--yes"),
                Expect(1, out = "\"conflicts\":true")),
            Case("apply --yes --json, flags in either order", listOf("-c", conflict, "apply", "--yes", "--json"),
                Expect(1, out = "\"conflicts\":true")),
            Case("apply --json --yes", listOf("-c", good, "apply", "--json", "--yes"),
                Expect(0, out = "\"succeeded\":true")),
        ).map(::test)
    }

    @TestFactory
    fun configIsInitAndGuidePrintsTheGuide(): List<DynamicTest> {
        val good = writeConfig("alias")
        val malformed = Files.writeString(root.resolve("alias-malformed.json"), "{\"lighten\": [").toString()
        val init = { args: List<String> -> run(listOf("init") + args) }
        val config = { args: List<String> -> run(listOf("config") + args) }
        return listOf(
            dynamicTest("config on a good file runs as init") {
                assertEquals(init(listOf("-c", good)), config(listOf("-c", good)))
            },
            dynamicTest("config on a malformed file runs as init") {
                assertEquals(init(listOf("-c", malformed)), config(listOf("-c", malformed)))
            },
            dynamicTest("config is listed with init in --help") {
                assertTrue(run(listOf("--help")).out.lines().any { "init" in it && "config" in it })
            },
            dynamicTest("guide prints the guide") {
                assertEquals(Run(0, userGuide(), ""), run(listOf("guide")))
            },
        )
    }

    private fun writeConfig(name: String): String {
        val dir = Files.createDirectories(root.resolve(name))
        Files.createDirectories(dir.resolve("home/a"))
        Files.writeString(dir.resolve("home/a/f"), "a")
        return Files.writeString(dir.resolve("config.json"), """{"lighten": {"target-root": "$dir/local",
            "relocations": [{"source-path": "$dir/home/a", "target-path": "$dir/local/a"}]}}""").toString()
    }

    private fun test(case: Case): DynamicTest = dynamicTest(case.name) {
        val run = run(case.args)
        val context = "${case.args.joinToString(" ")}\n--- stdout\n${run.out}--- stderr\n${run.err}"
        assertEquals(case.expect.exit, run.exit, context)
        case.expect.out?.let { assertTrue(it in run.out, context) }
        case.expect.err?.let { assertTrue(it in run.err, context) }
        if (case.expect.hidden) assertFalse("debug-step-delay" in run.out + run.err, context)
    }

    private data class Case(val name: String, val args: List<String>, val expect: Expect)

    /** [out] and [err] are substrings the stream must contain; [hidden] checks the delay option is not shown. */
    private data class Expect(val exit: Int, val out: String? = null, val err: String? = null, val hidden: Boolean = false)

    private data class Run(val exit: Int, val out: String, val err: String)

    /** The only place that knows the parser; porting the CLI changes this function, not the cases. */
    private fun run(args: List<String>): Run {
        val out = StringWriter()
        val err = StringWriter()
        val commandLine = LightenCommand.createCommandLine()
        commandLine.setOut(PrintWriter(out, true))
        commandLine.setErr(PrintWriter(err, true))
        val exit = commandLine.execute(*args.toTypedArray())
        return Run(exit, out.toString(), err.toString())
    }

    private companion object {
        const val NOT_A_TERMINAL = "Lighten TUI requires an interactive terminal"
    }
}
