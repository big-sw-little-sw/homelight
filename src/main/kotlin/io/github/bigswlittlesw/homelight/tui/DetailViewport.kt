package io.github.bigswlittlesw.homelight.tui

import dev.tamboui.layout.Rect
import dev.tamboui.style.Color
import dev.tamboui.terminal.Frame
import dev.tamboui.text.CharWidth
import dev.tamboui.toolkit.Toolkit
import dev.tamboui.toolkit.element.Element
import dev.tamboui.toolkit.element.RenderContext
import dev.tamboui.toolkit.element.Size
import dev.tamboui.toolkit.element.StyledElement
import dev.tamboui.toolkit.elements.ScrollbarElement

/** Wraps at the actual pane width on every render, including resize and quit dialogs. */
internal class DetailViewport {
    private var top = 0
    private var maximum = 0
    private var followingChoice = false
    private var keepVisible = false

    data class Line(val text: String, val color: Color = palette.text, val bold: Boolean = false)

    // Resolve overflow after the reader renders, so help reflects this frame's size.
    fun help(navigation: String, commands: String): Element {
        class Help : StyledElement<Help>() {
            override fun preferredSize(width: Int, height: Int, context: RenderContext): Size = Size.heightOnly(2)
            override fun renderContent(frame: Frame, area: Rect, context: RenderContext) {
                val overflows = maximum > 0
                val keys = when {
                    !navigation.startsWith("↑/↓: Scroll") -> if (overflows) "$navigation · [/]: Scroll" else navigation
                    overflows -> navigation.replace("↑/↓: Scroll", "↑/↓/[/]: Scroll")
                    else -> navigation.replace("↑/↓: Scroll · ", "")
                }
                wrappedText(keys + "\n" + commands, palette.dim).render(frame, area, context)
            }
        }
        return Help()
    }

    fun reset() { top = 0; followingChoice = false }
    fun followChoice() { followingChoice = true; keepVisible = false }
    fun keepChoiceVisible() { followingChoice = true; keepVisible = true }
    fun scroll(delta: Int) {
        followingChoice = false
        // In Long: callers scroll by ±Int.MAX_VALUE to reach either end.
        top = (top.toLong() + delta).coerceIn(0, maximum.toLong()).toInt()
    }

    fun render(title: String, lines: List<Line>, focused: Boolean, choiceLine: Int): Element {
        class Pane : StyledElement<Pane>() {
            override fun preferredSize(width: Int, height: Int, context: RenderContext): Size = Size.UNKNOWN
            override fun renderContent(frame: Frame, area: Rect, context: RenderContext) {
                var width = maxOf(1, area.width() - 2)
                val height = maxOf(1, area.height() - 2)
                val overflow = lines.sumOf { line -> wrap(line.text, width).size } > height
                if (overflow) width = maxOf(1, width - 1)
                val wrapped = mutableListOf<Line>()
                var anchor = 0
                for ((i, line) in lines.withIndex()) {
                    if (i == choiceLine) anchor = wrapped.size
                    wrap(line.text, width).mapTo(wrapped) { part -> Line(part, line.color, line.bold) }
                }
                maximum = maxOf(0, wrapped.size - height)
                if (followingChoice && focused) {
                    if (!keepVisible) top = minOf(anchor, maximum)
                    else if (anchor < top) top = anchor
                    else if (anchor >= top + height) top = anchor - height + 1
                }
                top = top.coerceIn(0, maximum)
                val rows = wrapped.subList(top, minOf(top + height, wrapped.size)).map { line ->
                    val text = Toolkit.text(line.text).fg(line.color)
                    if (line.bold) text.bold() else text
                }
                Toolkit.panel(title, Toolkit.column(*rows.toTypedArray()).fill())
                    .borderColor(if (focused) palette.focus else palette.dim).fill()
                    .render(frame, area, context)
                if (overflow) {
                    // TamboUI's Scrollbar patches the inherited text color over thumb and track colors, so the
                    // element's own color is the only one that shows.
                    ScrollbarElement().state(wrapped.size, height, top).hideMarkers().fg(palette.focus)
                        .render(frame, Rect(area.x() + area.width() - 2, area.y() + 1, 1, height), context)
                }
            }
        }
        return Pane().fill()
    }
}

/** Text that wraps at the width it is given, measuring its height for the layout. */
internal fun wrappedText(value: String, color: Color): Element {
    class WrappedText : StyledElement<WrappedText>() {
        override fun preferredSize(width: Int, height: Int, context: RenderContext): Size =
            Size.heightOnly(wrap(value, maxOf(1, width)).size)
        override fun renderContent(frame: Frame, area: Rect, context: RenderContext) {
            val rows = wrap(value, maxOf(1, area.width())).map { line -> Toolkit.text(line).fg(color) }
            Toolkit.column(*rows.toTypedArray()).render(frame, area, context)
        }
    }
    return WrappedText()
}

private val GRAPHEME_CLUSTER = Regex("\\X")

/**
 * Wraps at spaces where possible, breaking long words at the cell width.
 *
 * Measures whole grapheme clusters, as the terminal draws them: an emoji with VS16 or a ZWJ sequence is one
 * glyph two cells wide, and a break never splits it.
 */
internal fun wrap(text: String, width: Int): List<String> {
    val result = mutableListOf<String>()
    for (paragraph in text.split("\n")) {
        val line = StringBuilder()
        var cells = 0
        for (match in GRAPHEME_CLUSTER.findAll(paragraph)) {
            val cluster = match.value
            val size = CharWidth.of(cluster)
            if (cells + size > width && line.isNotEmpty()) {
                val space = line.lastIndexOf(" ")
                if (space > 0 && cluster != " ") {
                    result.add(line.substring(0, space))
                    line.delete(0, space + 1)
                    cells = CharWidth.of(line.toString())
                } else {
                    result.add(line.toString())
                    line.setLength(0)
                    cells = 0
                    if (cluster == " ") continue
                }
            }
            line.append(cluster)
            cells += size
        }
        result.add(line.toString())
    }
    return result
}
