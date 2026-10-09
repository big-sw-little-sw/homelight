package io.github.bigswlittlesw.lighten.tui

import dev.tamboui.style.AnsiColor
import dev.tamboui.style.Color
import dev.tamboui.tui.event.KeyCode
import io.github.bigswlittlesw.lighten.application.LightenSession
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executor

class PaletteTest {
    @TempDir lateinit var temporary: Path

    /** Both palettes must match the color table in `docs/tui-design.md`. */
    @Test
    fun bothPalettesMatchTheDesignDocument() {
        val rows = Regex("""^\| (\w+) \| `(#[0-9a-f]{6})` \| ([a-z ]+) \|""", RegexOption.MULTILINE)
            .findAll(Files.readString(Path.of("docs/tui-design.md"))).map { it.groupValues }.toList()
        assertEquals(roles(HARBOR), rows.associate { (_, role, hex) -> role to Color.hex(hex) })
        assertEquals(
            roles(HARBOR_BASIC),
            rows.associate { (_, role, _, basic) -> role to Color.ansi(AnsiColor.valueOf(basic.uppercase().replace(' ', '_'))) },
        )
    }

    @Test
    fun onlyAFullColorTerminalGetsHarbor() {
        assertSame(HARBOR, paletteFor("truecolor"))
        assertSame(HARBOR, paletteFor("24bit"))
        for (value in listOf(null, "", "yes", "256", "TRUECOLOR", "truecolor ")) assertSame(HARBOR_BASIC, paletteFor(value), "$value")
    }

    @Test
    fun everyCellUsesTheBackgroundAndOnlyPaletteColors() {
        val session = LightenSession(WorkspaceViewTest.fixture(temporary))
        val ui = HeadlessTui(session)
        val colors = roles(palette).values.toSet()
        val steps = listOf(
            {},
            { ui.press(KeyCode.TAB) },
            // The quit dialog during an apply that never runs.
            { ui.press(' '); ui.press('a'); session.confirmApply(Executor { }); ui.press('q') },
        )
        for (step in steps) {
            step()
            for ((width, height) in listOf(80 to 24, 120 to 30)) {
                val buffer = ui.frame(width, height)
                for (y in 0 until height) for (x in 0 until width) {
                    // The terminal draws a wide glyph's second cell with the first cell's style.
                    if (buffer.get(x, y).isContinuation) continue
                    val style = buffer.get(x, y).style()
                    assertEquals(palette.background, style.bg().orElse(null), "background at $x,$y")
                    val fg = style.fg().orElse(null)
                    assertTrue(fg in colors, "foreground $fg at $x,$y is not a palette color")
                }
            }
        }
    }

    private fun roles(palette: Palette): Map<String, Color> = mapOf(
        "background" to palette.background, "text" to palette.text, "brand" to palette.brand,
        "focus" to palette.focus, "ok" to palette.ok, "change" to palette.change, "warn" to palette.warn,
        "error" to palette.error, "dialog" to palette.dialog, "dim" to palette.dim,
    )
}
