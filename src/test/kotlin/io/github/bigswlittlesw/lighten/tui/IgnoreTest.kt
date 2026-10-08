package io.github.bigswlittlesw.lighten.tui

import dev.tamboui.tui.event.KeyCode
import io.github.bigswlittlesw.lighten.application.ConfigurationEvaluation
import io.github.bigswlittlesw.lighten.application.LightenSession
import io.github.bigswlittlesw.lighten.config.ConfigurationLoader
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** `x` on the Workspace ignores a relocation or stops ignoring a source; `i` opens the ignored group (tui-design §5). */
class IgnoreTest {
    @TempDir lateinit var temporary: Path
    private lateinit var root: Path
    private lateinit var config: Path

    /**
     * `cache` has both directories, so it needs a choice and lists first; `linked` is in sync, a link to its target;
     * `stow` is ignored already.
     */
    @BeforeEach
    fun fixture() {
        root = temporary.toRealPath()
        for (name in listOf("home/cache", "local/cache", "local/linked", "home/stow")) {
            Files.writeString(Files.createDirectories(root.resolve(name)).resolve("file"), name)
        }
        Files.createSymbolicLink(root.resolve("home/linked"), root.resolve("local/linked"))
        config = Files.writeString(root.resolve("config.json"), file())
    }

    @Test
    fun ignoringMovesTheRelocationToTheIgnoredPathsAfterAsking() {
        val ui = HeadlessTui(LightenSession(config))
        assertTrue(ui.screen().contains("x: Ignore"), ui.screen())
        ui.press('x')
        val dialog = squeezed(ui.screen(200, 50))
        for (line in listOf(ignoreTitle(path("home/cache"))) + ignoreBody(config) + IGNORE_KEYS) {
            assertTrue(dialog.contains(squeezed(line)), "$line\n$dialog")
        }
        // Not linked, so there is no link to leave behind.
        assertFalse(dialog.contains("It is linked now"), dialog)
        assertTrue(ui.screen().lines().any { it.contains(IGNORE_KEYS) }, "The dialog fits at 80 columns\n" + ui.screen())
        assertEquals(file(), Files.readString(config))

        ui.press('y')
        val saved = ConfigurationLoader().read(config).file
        assertEquals(listOf(path("home/linked").toString()), saved.relocations.map { it.sourcePath })
        assertEquals(listOf(path("home/stow").toString(), path("home/cache").toString()), saved.ignoredSourcePaths)
        // Checked again: the group heading counts it, and the next step is said.
        val after = ui.screen()
        assertTrue(after.contains(ignoredHeading(2, shown = false)), after)
        assertTrue(after.contains("Saved. Nothing needs to change."), after)
        assertEquals(WORKSPACE_LIST, ui.focused())
        assertEquals(listOf(path("home/stow"), path("home/cache")), loaded(ui).ignored)
    }

    @Test
    fun ignoringALinkedRelocationSaysTheLinkStaysAndHowToUndoIt() {
        val ui = HeadlessTui(LightenSession(config))
        ui.press('c')
        ui.press(KeyCode.DOWN)
        ui.press('x')
        // Wide enough for the temporary directory's long paths.
        val dialog = squeezed(ui.screen(320, 50))
        for (line in ignoreLinkedWarning(path("home/linked"), path("local/linked"))) {
            assertTrue(dialog.contains(squeezed(line)), "$line\n$dialog")
        }
        val rows = ui.screen(200, 50).lines()
        val y = rows.indexOfFirst { it.contains("⚠ It is linked now") }
        assertEquals(palette.warn, ui.frame(200, 50).get(rows[y].indexOf("⚠"), y).style().fg().orElse(null))
        ui.press('y')
        // Nothing on disk changed: the link is still there.
        assertEquals(path("local/linked"), Files.readSymbolicLink(path("home/linked")))
        assertTrue(path("home/linked").toString() in ConfigurationLoader().read(config).file.ignoredSourcePaths)
    }

    @Test
    fun theIgnoredGroupOpensWithIAndItsRowsSayHowToUndo() {
        val ui = HeadlessTui(LightenSession(config))
        val closed = ui.screen()
        assertTrue(closed.contains(ignoredHeading(1, shown = false)) && !closed.contains("[Ignored]"), closed)
        // The heading lists last, after the relocations, and x does nothing on it.
        ui.press(KeyCode.DOWN)
        assertFalse(ui.screen().contains("x: "), ui.screen())
        ui.press('x')
        assertFalse(ui.screen().contains(IGNORE_KEYS) || ui.screen().contains(STOP_IGNORING_KEYS), ui.screen())
        assertTrue(squeezed(ui.screen(200, 50)).contains(squeezed(ignoredGroupDetails(1, shown = false).first())))

        ui.press('i')
        assertTrue(ui.screen().contains(ignoredHeading(1, shown = true)), ui.screen())
        ui.press(KeyCode.DOWN)
        val row = ui.screen(200, 50)
        assertTrue(row.lines().any { it.contains("❯ [Ignored] ") && it.contains("home/stow") }, row)
        assertTrue(row.contains(IGNORED_BY_YOU), row)
        assertTrue(squeezed(row).contains(squeezed(ignoredDetails(path("home/stow"), retained = false).last())), row)
        assertTrue(ui.screen().contains("x: Stop ignoring"), ui.screen())

        ui.press('x')
        val dialog = squeezed(ui.screen(200, 50))
        for (line in listOf(stopIgnoringTitle(path("home/stow"))) + stopIgnoringBody(config) + STOP_IGNORING_KEYS) {
            assertTrue(dialog.contains(squeezed(line)), "$line\n$dialog")
        }
        ui.press('y')
        assertEquals(listOf<String>(), ConfigurationLoader().read(config).file.ignoredSourcePaths)
        assertFalse(ui.screen().contains("ignored"), ui.screen())
        assertTrue(loaded(ui).ignored.isEmpty())
    }

    @Test
    fun cancellingKeepsTheFile() {
        val ui = HeadlessTui(LightenSession(config))
        for (cancel in listOf({ ui.press('n') }, { ui.press(KeyCode.ESCAPE) })) {
            ui.press('x')
            assertTrue(ui.screen().contains(IGNORE_KEYS))
            cancel()
            assertFalse(ui.screen().contains(IGNORE_KEYS))
            assertEquals(WORKSPACE_LIST, ui.focused())
            assertEquals(file(), Files.readString(config))
        }
    }

    @Test
    fun aFileChangedSinceItWasReadIsNotReplaced() {
        val ui = HeadlessTui(LightenSession(config))
        val changed = file().replace("\"relocations\"", "\"relocations\" ")
        Files.writeString(config, changed)
        ui.press('x')
        ui.press('y')
        assertEquals(changed, Files.readString(config))
        assertTrue(squeezed(ui.screen(200, 50)).contains(squeezed(IGNORE_NOT_SAVED)), ui.screen(200, 50))
    }

    /** A file that lists a path as both is one Lighten can't read; the Workspace names both settings. */
    @Test
    fun aPathInBothListsIsRefusedInPlainWords() {
        Files.writeString(config, file().replace("home/stow", "home/cache"))
        val screen = squeezed(HeadlessTui(LightenSession(config)).screen(200, 50))
        for (line in listOf(
            "relocations[0].source-path and ignored-source-paths[0] are both ${path("home/cache")}.",
            "A path can't be both a relocation and ignored: remove it from one of the two lists.",
            "correct that setting",
        )) assertTrue(screen.contains(squeezed(line)), "$line\n$screen")
    }

    /** Ignoring every relocation still saves: a file that only ignores paths is a choice, not an empty file. */
    @Test
    fun theLastRelocationCanBeIgnored() {
        Files.writeString(config, file(relocations = listOf("cache")))
        val ui = HeadlessTui(LightenSession(config))
        ui.press('x')
        ui.press('y')
        assertTrue(ConfigurationLoader().read(config).file.relocations.isEmpty())
        assertTrue(ui.screen().contains(ignoredHeading(2, shown = false)), ui.screen())
    }

    /** Help lists `x` under Do and `i` for the group, though the help lines show `x` beside the moves and `i` on the row. */
    @Test
    fun helpListsXAndI() {
        val ui = HeadlessTui(LightenSession(config))
        ui.press('?')
        val rows = ui.screen(120, 80).lines()
        val doKeys = rows.indexOfFirst { it.contains(DO_KEYS) }
        val x = rows.indexOfFirst { it.contains("x  ") && it.contains(IGNORE_DESCRIPTION.take(30)) }
        val i = rows.indexOfFirst { it.contains("i  ") && it.contains("Show the sources you ignored") }
        assertTrue(doKeys in 0 until x && doKeys < i, rows.joinToString("\n"))
    }

    /** With the choice keys showing, Details has no room for `x` on its help line, but `x` works there too. */
    @Test
    fun xWorksFromDetails() {
        val ui = HeadlessTui(LightenSession(config))
        ui.press(KeyCode.TAB)
        assertFalse(ui.screen().contains("x: Ignore"), ui.screen())
        ui.press('x')
        assertTrue(ui.screen().contains(IGNORE_KEYS), ui.screen())
        ui.press('n')
        assertEquals(WORKSPACE_DETAILS, ui.focused())
    }

    private fun loaded(ui: HeadlessTui) =
        assertInstanceOf(ConfigurationEvaluation.Loaded::class.java, ui.app.session.evaluation())

    private fun path(relative: String): Path = root.resolve(relative)

    private fun file(relocations: List<String> = listOf("cache", "linked")) = """
        {"lighten": {
          "target-root": "${path("local")}",
          "relocations": [
        """.trimIndent() + "\n" + relocations.joinToString(",\n") { name ->
        """    {"source-path": "${path("home/$name")}", "target-path": "${path("local/$name")}"}"""
    } + "\n" + """
          ],
          "ignored-source-paths": ["${path("home/stow")}"]
        }}
        """.trimIndent() + "\n"

    /** Without spaces, so a line Details or a dialog wrapped still matches. */
    private fun squeezed(text: String) = text.replace(Regex("\\s+"), "").replace("║", "").replace("│", "").replace("┃", "")
}
