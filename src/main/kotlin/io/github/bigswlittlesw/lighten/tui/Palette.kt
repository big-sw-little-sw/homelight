package io.github.bigswlittlesw.lighten.tui

import dev.tamboui.style.AnsiColor
import dev.tamboui.style.Color

/**
 * The TUI's colors by role. Views name roles only; `docs/tui-design.md` §4 says what each role is for.
 *
 * Lighten paints [background] itself, so these colors never depend on the terminal's own background.
 */
internal data class Palette(
    val background: Color,
    val text: Color,
    val brand: Color,
    val focus: Color,
    val ok: Color,
    val change: Color,
    val warn: Color,
    val error: Color,
    val dialog: Color,
    val dim: Color,
)

/** Harbor, as exact RGB colors. */
internal val HARBOR = Palette(
    background = Color.hex("#1b1d22"),
    text = Color.hex("#d8dbe2"),
    brand = Color.hex("#7fd1c7"),
    focus = Color.hex("#6cb6e8"),
    ok = Color.hex("#7cc79a"),
    change = Color.hex("#7fd1c7"),
    warn = Color.hex("#e6b55c"),
    error = Color.hex("#e76f6f"),
    dialog = Color.hex("#b39cf0"),
    dim = Color.hex("#6f7a88"),
)

/**
 * Harbor's nearest basic ANSI color per role, chosen by hue. Harbor's soft colors are closer in RGB distance to
 * gray than to any basic hue, so a computed nearest color would turn `ok` and `change` gray.
 */
internal val HARBOR_BASIC = Palette(
    background = Color.ansi(AnsiColor.BLACK),
    text = Color.ansi(AnsiColor.WHITE),
    brand = Color.ansi(AnsiColor.CYAN),
    focus = Color.ansi(AnsiColor.BRIGHT_BLUE),
    ok = Color.ansi(AnsiColor.GREEN),
    change = Color.ansi(AnsiColor.CYAN),
    warn = Color.ansi(AnsiColor.YELLOW),
    error = Color.ansi(AnsiColor.RED),
    dialog = Color.ansi(AnsiColor.MAGENTA),
    dim = Color.ansi(AnsiColor.BRIGHT_BLACK),
)

/**
 * Picks Harbor only when the terminal reports full color through `COLORTERM`, the de facto signal: terminfo has
 * no reliable true-color entry, and a terminal without it would map RGB to whatever its own approximation is.
 */
internal fun paletteFor(colorTerm: String?): Palette =
    if (colorTerm == "truecolor" || colorTerm == "24bit") HARBOR else HARBOR_BASIC

/** Read once: the terminal's color support does not change while Lighten runs. */
internal val palette: Palette = paletteFor(System.getenv("COLORTERM"))
