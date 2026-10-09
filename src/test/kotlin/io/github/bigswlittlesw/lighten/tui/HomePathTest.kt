package io.github.bigswlittlesw.lighten.tui

import dev.tamboui.tui.event.KeyCode
import io.github.bigswlittlesw.lighten.application.LightenSession
import io.github.bigswlittlesw.lighten.cli.LightenCommand
import io.github.bigswlittlesw.lighten.discovery.CandidateDiscovery
import io.github.bigswlittlesw.lighten.discovery.CandidateObservation
import io.github.bigswlittlesw.lighten.discovery.SetupDiscoveryFixture
import io.github.bigswlittlesw.lighten.fifoAt
import io.github.bigswlittlesw.lighten.pollUntil
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executor

/**
 * Every path on screen shows the home directory as `~`: no screen, dialog, Help page or human-readable CLI
 * message may show the home directory's absolute path, while `--json` keeps every path in full. The home directory is
 * a temporary one, so its absolute path is known; the fixtures write their paths with `~/` as a user does.
 *
 * Screens render at 80x24, and again at 200x60 so that no path wraps or is cut off where the check could miss it.
 */
class HomePathTest {
    @TempDir lateinit var temporary: Path
    private lateinit var home: Path
    private lateinit var storage: Path
    private lateinit var realHome: String

    @BeforeEach
    fun temporaryHome() {
        val root = temporary.toRealPath()
        home = Files.createDirectory(root.resolve(HOME_NAME))
        storage = Files.createDirectory(root.resolve("storage"))
        realHome = System.getProperty("user.home")
        System.setProperty("user.home", home.toString())
    }

    @AfterEach
    fun realHome() {
        System.setProperty("user.home", realHome)
    }

    @Test
    fun noScreenShowsTheHomeDirectory() {
        val screens = firstRun() + unreadable() + workspace() + configurationAndBrowse() + overlapping() + applied()
        assertNoHome(screens)
        val wide = squeezed(screens.joinToString(""))
        for (shown in listOf(
            "Config: ~/.lighten.json",
            "Problem: ~/blocker is a file, not a folder.",
            "Current link destination: ~/elsewhere",
            "Source: ~/.cache/both",
            "Target: $storage/.cache/both",
            "Ignore ~/.cache/move?",
            "Stop ignoring ~/.cache/ignored?",
            "rm ~/.cache/synced",
            "Replace ~/.lighten.json?",
            "~/.cache/a contains ~/.cache/a/b, the target of ~/.cache/b",
            "~/.cache/a/b is inside ~/.cache/a, which is also a relocation",
            "Copy from: ~/.cache/piped",
            "~/.cache/piped/sub/ipc is a named pipe",
            "Your list · ~/team.json",
            "Note: not found · ~/.cache/uv",
            "Lighten can't read ~/unreadable.json",
        )) {
            assertTrue(wide.contains(squeezed(shown)), "no screen showed \"$shown\"")
        }
    }

    @Test
    fun cliMessagesShowTheHomeDirectoryAsTildeAndJsonKeepsItInFull() {
        val unreadable = Files.writeString(home.resolve("unreadable.json"), "not json")
        val (_, err) = run("plan", "--json", "-c", unreadable.toString())
        assertTrue(err.contains("Lighten can't read ~/unreadable.json"), err)
        assertTrue(err.contains("lighten init --config ~/unreadable.json"), err)
        val (_, missing) = run("status", "--json", "-c", home.resolve("missing.json").toString())
        assertEquals("Configuration file does not exist: ~/missing.json", missing.trim())
        val (_, init) = run("init", "-c", unreadable.toString())
        assertTrue(init.contains("Lighten can't read ~/unreadable.json"), init)
        for (text in listOf(err, missing, init)) assertFalse(text.contains(home.toString()), text)

        val (json, _) = run("plan", "--json", "-c", workspaceConfiguration().toString())
        assertTrue(json.contains("\"reason\":\"$home/blocker is a file, not a folder\""), json)
        assertFalse(json.contains("~/"), json)
    }

    /** Only the home directory is `~`: a source root elsewhere, and the paths under it, show in full. */
    @Test
    fun aSourceRootOutsideHomeShowsInFull() {
        val sourceRoot = Files.createDirectories(temporary.toRealPath().resolve("data/me"))
        Files.createDirectories(sourceRoot.resolve("cache"))
        Files.createDirectories(home.resolve(".cache/tool"))
        val config = Files.writeString(home.resolve(".lighten.json"), """
            {"lighten": {"source-root": "$sourceRoot", "target-root": "$storage", "relocations": [
              {"source-path": "$sourceRoot/cache"},
              {"source-path": "~/.cache/tool", "target-path": "$storage/tool"}
            ]}}
            """.trimIndent())
        val ui = HeadlessTui(LightenSession(config))
        val screens = rows(ui, 2)
        assertNoHome(screens)
        val wide = squeezed(screens.joinToString(""))
        for (shown in listOf("Source: $sourceRoot/cache", "Target: $storage/cache", "Source: ~/.cache/tool")) {
            assertTrue(wide.contains(squeezed(shown)), "$shown\n$wide")
        }
        assertFalse(screens.any { it.contains("~/cache") || it.contains("~/data") }, screens.joinToString("\n"))
    }

    private fun firstRun(): List<String> = withHelp(HeadlessTui(LightenSession(home.resolve(".lighten.json"))))

    private fun unreadable(): List<String> =
        withHelp(HeadlessTui(LightenSession(Files.writeString(home.resolve("unreadable.json"), "not json"))))

    /**
     * Every Workspace row with its Details, and each dialog the Workspace opens: Always do this, Ignore (with the
     * linked warning), Stop ignoring and Quit.
     */
    private fun workspace(): List<String> {
        val ui = HeadlessTui(LightenSession(workspaceConfiguration()))
        ui.press('c')
        ui.press('i')
        val screens = withHelp(ui).toMutableList()
        ui.press(KeyCode.TAB)
        ui.press(' ')
        screens += capture(ui)
        ui.press('s')
        screens += capture(ui)
        ui.press('n')
        ui.press(KeyCode.ESCAPE)
        ui.press('q')
        screens += capture(ui)
        ui.press('n')
        // Each row, then the dialog `x` opens on it: Ignore, or Stop ignoring on the ignored source.
        ui.press(KeyCode.HOME)
        repeat(ROWS) {
            screens += capture(ui)
            ui.press('x')
            screens += capture(ui)
            ui.press('n')
            ui.press(KeyCode.DOWN)
        }
        return screens
    }

    /** Configuration's rows with their Resolved paths, the Replace dialog, then Browse, a directory's Details and the lists. */
    private fun configurationAndBrowse(): List<String> = SetupDiscoveryFixture().use { workers ->
        val ui = HeadlessTui(LightenSession(workspaceConfiguration()), discoveryFactory = workers::get)
        ui.press('e')
        val screens = withHelp(ui).toMutableList()
        repeat(ROWS) {
            ui.press(KeyCode.DOWN)
            screens += capture(ui)
        }
        ui.press(KeyCode.HOME)
        ui.press('b')
        pollUntil("Discovery did not settle") {
            val result = workers.workers.last().snapshot()
            result.sources.none { it.status == CandidateDiscovery.SourceStatus.PENDING } &&
                result.candidates.none { it.observation.kind == CandidateObservation.Kind.PENDING }
        }
        screens += withHelp(ui)
        for (directory in listOf(".cache/uv", ".m2", ".cache/team")) {
            select(ui, directory)
            ui.press(KeyCode.ENTER)
            screens += capture(ui)
            ui.press(KeyCode.ESCAPE)
        }
        ui.press('i')
        screens += capture(ui)
        ui.press(KeyCode.ESCAPE)
        ui.press(KeyCode.ESCAPE)
        ui.press(KeyCode.DOWN)
        ui.press('d')
        ui.press('s')
        screens += capture(ui)
        ui.app.closeEditor()
        screens
    }

    /** Overlapping relocations: each blocked row names the other. */
    private fun overlapping(): List<String> {
        Files.createDirectories(home.resolve(".cache/a/b"))
        val config = Files.writeString(home.resolve("overlap.json"), """
            {"lighten": {"target-root": "$storage", "relocations": [
              {"source-path": "~/.cache/a"}, {"source-path": "~/.cache/b", "target-path": "~/.cache/a/b"}
            ]}}
            """.trimIndent())
        return rows(HeadlessTui(LightenSession(config)), 2)
    }

    /** Review, Applying and Results for a copy a named pipe stops. */
    private fun applied(): List<String> {
        val pipe = fifoAt(Files.createDirectories(home.resolve(".cache/piped/sub")).resolve("ipc"))
        val config = Files.writeString(home.resolve("piped.json"), """
            {"lighten": {"target-root": "$storage", "relocations": [{"source-path": "~/.cache/piped"}]}}
            """.trimIndent())
        val ui = HeadlessTui(LightenSession(config))
        ui.press('a')
        val review = withHelp(ui) + steps(ui)
        val tasks = mutableListOf<Runnable>()
        ui.app.session.confirmApply(Executor { tasks.add(it) })
        val applying = withHelp(ui)
        tasks.forEach(Runnable::run)
        ui.app.session.awaitExecution()
        val results = withHelp(ui) + steps(ui)
        assertTrue(Files.exists(pipe))
        return review + applying + results
    }

    /** The relocation and each of its steps on Review or Results, with their Details. */
    private fun steps(ui: HeadlessTui): List<String> {
        ui.press(KeyCode.HOME)
        return (0 until 3).flatMap {
            capture(ui).also { ui.press(KeyCode.DOWN) }
        }
    }

    /** The first [n] Workspace rows with their Details. */
    private fun rows(ui: HeadlessTui, n: Int): List<String> = (0 until n).flatMap {
        capture(ui).also { ui.press(KeyCode.DOWN) }
    }

    /**
     * One relocation in each state the Workspace shows: both exist (a choice, which offers to archive the source),
     * a link to somewhere else, a parent that is a file (blocked), a source to move, one in sync, and an ignored source.
     * Your suggestion list is in the home directory too.
     */
    private fun workspaceConfiguration(): Path {
        for (name in listOf(".cache/both", ".cache/blocked", ".cache/move", "elsewhere")) {
            Files.createDirectories(home.resolve(name))
        }
        Files.createDirectories(storage.resolve(".cache/both"))
        Files.createDirectories(storage.resolve(".cache/synced"))
        Files.deleteIfExists(home.resolve(".cache/wrong"))
        Files.createSymbolicLink(home.resolve(".cache/wrong"), home.resolve("elsewhere"))
        Files.deleteIfExists(home.resolve(".cache/synced"))
        Files.createSymbolicLink(home.resolve(".cache/synced"), storage.resolve(".cache/synced"))
        Files.writeString(home.resolve("blocker"), "a file")
        Files.writeString(home.resolve("team.json"), """
            {"apps": [{"name": "Team tool", "category": "Other tools", "directories": [{"path": ".cache/team", "reason": "Ours."}]}]}
            """.trimIndent())
        return Files.writeString(home.resolve(".lighten.json"), """
            {"lighten": {"target-root": "$storage", "suggestion-list": "~/team.json", "relocations": [
              {"source-path": "~/.cache/both"},
              {"source-path": "~/.cache/wrong"},
              {"source-path": "~/.cache/blocked", "target-path": "~/blocker/blocked"},
              {"source-path": "~/.cache/move"},
              {"source-path": "~/.cache/synced"}
            ], "ignored-source-paths": ["~/.cache/ignored"]}}
            """.trimIndent())
    }

    private fun select(ui: HeadlessTui, directory: String) {
        ui.press(KeyCode.HOME)
        repeat(60) {
            if (ui.screen(200, 60).lines().any { it.contains("❯") && it.contains(" $directory") }) return
            ui.press(KeyCode.DOWN)
        }
        throw AssertionError("Could not select $directory\n" + ui.screen(200, 60))
    }

    private fun run(vararg arguments: String): Pair<String, String> {
        val command = LightenCommand.createCommandLine()
        val out = StringWriter()
        val err = StringWriter()
        command.setOut(PrintWriter(out, true))
        command.setErr(PrintWriter(err, true))
        command.execute(*arguments)
        return out.toString() to err.toString()
    }

    private fun capture(ui: HeadlessTui): List<String> = listOf(ui.screen(80, 24), ui.screen(200, 60))

    /** The screen, then every page of both Help tabs; Esc then returns to the screen. F1 opens Help from a text field too. */
    private fun withHelp(ui: HeadlessTui): List<String> {
        val screens = capture(ui)
        ui.press(KeyCode.F1)
        val first = pages(ui)
        ui.press(KeyCode.TAB)
        val second = pages(ui)
        ui.press(KeyCode.ESCAPE)
        return screens + first + second
    }

    /** Pages down the open Help tab at 80x24 until a page repeats. */
    private fun pages(ui: HeadlessTui): List<String> {
        ui.press(KeyCode.HOME)
        val seen = mutableListOf(ui.screen(80, 24))
        while (true) {
            ui.press(KeyCode.PAGE_DOWN)
            val page = ui.screen(80, 24)
            if (page == seen.last()) return seen
            seen.add(page)
        }
    }

    /**
     * The home directory's name is checked as well as its path: at 80x24 a path that wraps may split, but its
     * distinctive last name is short enough to survive on one line.
     */
    private fun assertNoHome(screens: List<String>) {
        for (screen in screens) {
            assertFalse(screen.contains(home.toString()) || screen.contains(HOME_NAME), screen)
            // Nor a Java exception's name, which notes about unreadable paths once showed.
            assertFalse(Regex("[A-Za-z]Exception").containsMatchIn(screen), screen)
        }
    }

    /** Without spaces and borders, so a line Details or a dialog wrapped still matches. */
    private fun squeezed(text: String) = text.replace(Regex("[\\s║│┃]+"), "")

    private companion object {
        const val HOME_NAME = "home-of-201"

        /** The Workspace's rows: five relocations, the ignored group's heading and the ignored source. */
        const val ROWS = 7
    }
}
