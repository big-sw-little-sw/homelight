package io.github.bigswlittlesw.lighten.tui

import dev.tamboui.tui.event.KeyCode
import io.github.bigswlittlesw.lighten.application.LightenSession
import io.github.bigswlittlesw.lighten.emptiedTestHome
import io.github.bigswlittlesw.lighten.tui.WorkspaceViewTest.Companion.rightPane
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * On the Workspace, overlapping relocations block only themselves, and each names the other. A staging root on another
 * filesystem blocks a move before Review. Checked at 80x24 and 120x30.
 */
class PlanTimeBlockTest {
    @TempDir lateinit var temporary: Path
    private lateinit var home: Path
    private lateinit var storage: Path

    @BeforeEach
    fun fixture() {
        home = emptiedTestHome()
        storage = Files.createDirectory(temporary.toRealPath().resolve("storage"))
    }

    @AfterEach
    fun cleanHome() {
        emptiedTestHome()
    }

    @Test
    fun onlyTheOverlappingRelocationsAreBlocked() {
        for (name in listOf(".cache/a/b", ".cache/c")) Files.createDirectories(home.resolve(name))
        val config = write("""
            {"lighten": {"target-root": "$storage", "relocations": [
              {"source-path": "~/.cache/a"}, {"source-path": "~/.cache/c"}, {"source-path": "~/.cache/a/b"}
            ]}}
            """)
        for ((width, height) in SIZES) {
            val ui = HeadlessTui(LightenSession(config), width = width, height = height)
            val first = ui.screen()
            assertTrue(first.contains("2 blocked"), first)
            assertTrue(squeezed(first).contains(squeezed("1 to change")), first)
            assertTrue(squeezed(rightPane(first, width)).contains(
                squeezed("Problem: ~/.cache/a contains ~/.cache/a/b, which is also a relocation.")), first)
            ui.press(KeyCode.DOWN)
            val second = ui.screen()
            assertTrue(squeezed(rightPane(second, width)).contains(
                squeezed("Problem: ~/.cache/a/b is inside ~/.cache/a, which is also a relocation.")), second)
            ui.press(KeyCode.DOWN)
            assertFalse(ui.screen().contains("Problem:"), ui.screen())
        }
    }

    /** `/dev` is its own filesystem on Linux and macOS, so a staging root there is not on the target's. */
    @Test
    fun aStagingRootOnAnotherFilesystemBlocksTheMove() {
        Files.createDirectories(home.resolve(".cache/move"))
        val config = write("""
            {"lighten": {"target-root": "/dev", "staging-root": "/dev/lighten-staging", "relocations": [
              {"source-path": "~/.cache/move", "target-path": "$storage/move"}, {"source-path": "~/.cache/new"}
            ]}}
            """)
        for ((width, height) in SIZES) {
            val screen = HeadlessTui(LightenSession(config), width = width, height = height).screen()
            assertTrue(screen.contains("1 blocked"), screen)
            assertTrue(squeezed(rightPane(screen, width)).contains(squeezed(
                "Problem: the staging directory /dev/lighten-staging is on another filesystem than $storage/move, so " +
                    "Lighten can't move the copy there in one step.")), screen)
        }
    }

    private fun write(json: String): Path = Files.writeString(home.resolve(".lighten.json"), json.trimIndent())

    private fun squeezed(text: String) = text.replace(Regex("[\\s║│┃]+"), "")

    private companion object {
        val SIZES = listOf(80 to 24, 120 to 30)
    }
}
