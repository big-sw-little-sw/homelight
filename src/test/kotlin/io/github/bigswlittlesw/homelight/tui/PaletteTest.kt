package io.github.bigswlittlesw.homelight.tui

import dev.tamboui.buffer.Buffer
import dev.tamboui.layout.Rect
import dev.tamboui.style.AnsiColor
import dev.tamboui.style.Color
import dev.tamboui.terminal.Frame
import dev.tamboui.toolkit.element.RenderContext
import dev.tamboui.tui.event.KeyCode
import dev.tamboui.tui.event.KeyEvent
import io.github.bigswlittlesw.homelight.application.HomeLightSession
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PaletteTest {
    @TempDir lateinit var temporary: Path

    /** `docs/tui-design.md` §4 is the source of truth for both palettes. */
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
        val session = HomeLightSession(WorkspaceViewTest.fixture(temporary))
        val app = HomeLightApp(session)
        val colors = roles(palette).values.toSet()
        for (step in listOf<KeyCode?>(null, KeyCode.TAB)) {
            step?.let { app.handleKeyEvent(KeyEvent.ofKey(it, KEY_BINDINGS)) }
            for ((width, height) in listOf(80 to 24, 120 to 30)) {
                val buffer = render(app, width, height)
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

    private fun render(app: HomeLightApp, width: Int, height: Int): Buffer {
        val renderThread = Class.forName("dev.tamboui.tui.RenderThread")
        val marker = renderThread.getDeclaredMethod("markAsRenderThread").apply { isAccessible = true }
        val clear = renderThread.getDeclaredMethod("clearRenderThread").apply { isAccessible = true }
        marker.invoke(null)
        try {
            val buffer = Buffer.empty(Rect.of(width, height))
            app.render().render(Frame.forTesting(buffer), Rect.of(width, height), RenderContext.empty())
            return buffer
        } finally { clear.invoke(null) }
    }
}
