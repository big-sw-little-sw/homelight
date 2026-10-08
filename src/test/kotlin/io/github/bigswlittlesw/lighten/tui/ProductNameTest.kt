package io.github.bigswlittlesw.lighten.tui

import dev.tamboui.tui.event.KeyCode
import io.github.bigswlittlesw.lighten.application.LightenSession
import io.github.bigswlittlesw.lighten.discovery.SetupDiscoveryFixture
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executor

/** The tool was renamed from HomeLight to Lighten (#174): no screen, Help tab or dialog may show the old name. */
class ProductNameTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun noScreenShowsTheOldName() {
        val screens = firstRun() + unreadable() + workspaceAndReview() + applying() + results() + newConfiguration() +
            editedConfiguration()
        for (title in listOf(QUIT_TITLE, QUITTING, DISCARD_SETUP_TITLE, DISCARD_CHANGES_TITLE, FIRST_RUN_HINT, GUIDE_TAB)) {
            assertTrue(screens.any { it.contains(title) }, "no screen showed \"$title\"")
        }
        assertTrue(screens.any { it.contains("╔Replace ") }, "no screen showed the replace question")
        for (screen in screens) {
            assertFalse(screen.contains("HomeLight") || screen.contains("HOMELIGHT"), screen)
        }
    }

    private fun firstRun(): List<String> = withHelp(HeadlessTui(LightenSession(temporary.resolve("missing.json"))))

    private fun unreadable(): List<String> =
        withHelp(HeadlessTui(LightenSession(Files.writeString(temporary.resolve("unreadable.json"), "not json"))))

    private fun workspaceAndReview(): List<String> {
        val ui = HeadlessTui(LightenSession(conflict(temporary.resolve("conflict"))))
        val workspace = withHelp(ui)
        ui.press(KeyCode.TAB)
        val details = withHelp(ui)
        ui.press(KeyCode.ENTER)
        ui.press('q')
        val quit = ui.screen(80, 24)
        ui.press(KeyCode.ESCAPE)
        ui.press('a')
        return workspace + details + quit + withHelp(ui)
    }

    private fun applying(): List<String> {
        val ui = HeadlessTui(LightenSession(move(temporary.resolve("applying"))))
        ui.press('a')
        val tasks = mutableListOf<Runnable>()
        ui.app.session.confirmApply(Executor { tasks.add(it) })
        val running = withHelp(ui)
        ui.press('q')
        val quit = ui.screen(80, 24)
        ui.press('y')
        val quitting = ui.screen(80, 24)
        tasks.forEach(Runnable::run)
        return running + quit + quitting
    }

    private fun results(): List<String> {
        val ui = HeadlessTui(LightenSession(move(temporary.resolve("results"))))
        ui.press('a')
        ui.press('y')
        ui.app.session.awaitExecution()
        return withHelp(ui)
    }

    private fun newConfiguration(): List<String> = SetupDiscoveryFixture().use { workers ->
        val ui = HeadlessTui(LightenSession(temporary.resolve("new.json")), discoveryFactory = workers::get)
        ui.press('i')
        val field = withHelp(ui)
        ui.type("/srv")
        ui.press(KeyCode.ESCAPE)
        val list = withHelp(ui)
        ui.ctrl('c')
        val discard = ui.screen(80, 24)
        ui.press(KeyCode.ESCAPE)
        ui.press('b')
        val browse = withHelp(ui)
        ui.app.closeEditor()
        field + list + discard + browse
    }

    private fun editedConfiguration(): List<String> {
        val ui = HeadlessTui(LightenSession(conflict(temporary.resolve("edited"))))
        ui.press('e')
        ui.press(KeyCode.DOWN)
        ui.press('d')
        ui.press(KeyCode.ESCAPE)
        val discard = ui.screen(80, 24)
        ui.press('n')
        ui.press('s')
        return listOf(discard, ui.screen(80, 24))
    }

    /**
     * The screen at 80x24, then every page of both Help tabs at that size; Esc then returns to the screen. F1 opens
     * Help from a text field too.
     */
    private fun withHelp(ui: HeadlessTui): List<String> {
        val screen = ui.screen(80, 24)
        ui.press(KeyCode.F1)
        val first = pages(ui)
        ui.press(KeyCode.TAB)
        val second = pages(ui)
        ui.press(KeyCode.ESCAPE)
        return listOf(screen) + first + second
    }

    /** Pages down the open Help tab until a page repeats. */
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

    /** Three relocations: both directories exist, only the source exists, already in sync. */
    private fun conflict(directory: Path): Path {
        val root = Files.createDirectories(directory).toRealPath()
        for (path in listOf("home/both", "local/both", "home/move", "local/synced")) Files.createDirectories(root.resolve(path))
        Files.createSymbolicLink(root.resolve("home/synced"), root.resolve("local/synced"))
        return configuration(root, listOf("both", "move", "synced"))
    }

    /** One relocation whose source directory moves to its target. */
    private fun move(directory: Path): Path {
        val root = Files.createDirectories(directory).toRealPath()
        Files.createDirectories(root.resolve("home/move"))
        return configuration(root, listOf("move"))
    }

    private fun configuration(root: Path, names: List<String>): Path {
        val relocations = names.joinToString(",\n") { name ->
            "{\"source-path\": \"${root.resolve("home/$name")}\", \"target-path\": \"${root.resolve("local/$name")}\"}"
        }
        return Files.writeString(
            root.resolve("config.json"),
            "{\"lighten\": {\"target-root\": \"${root.resolve("local")}\", \"relocations\": [\n$relocations\n]}}\n",
        )
    }
}
