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
    // One line less than the pane's height, so a page keeps a line of context.
    private var page = 1
    private var followingChoice = false
    private var keepVisible = false

    data class Line(val text: String, val color: Color = palette.text, val bold: Boolean = false)

    /** Lines and the index of the one the pane keeps in view while a choice among them is focused. */
    data class Anchored(val lines: List<Line>, val anchor: Int)

    /**
     * Resolves overflow after the reader renders, so help reflects this frame's size. While a dialog is open and
     * takes every key, help is not `shown` and its two lines stay blank. A `note` about the focused field takes the
     * navigation line's place.
     */
    fun help(keys: ScreenKeys, shown: Boolean = true, note: String? = null): Element {
        class Help : StyledElement<Help>() {
            override fun preferredSize(width: Int, height: Int, context: RenderContext): Size = Size.heightOnly(2)
            override fun renderContent(frame: Frame, area: Rect, context: RenderContext) {
                if (!shown) return
                val navigation = note ?: helpLine(keys.navigation)
                val commands = helpLine(keys.commands)
                val overflows = maximum > 0
                val keys = when {
                    !navigation.startsWith("↑/↓: Scroll") -> if (overflows) "$navigation · [/]: Scroll" else navigation
                    overflows -> navigation.replace("↑/↓: Scroll", "↑/↓/[/]: Scroll")
                    else -> navigation.removePrefix("↑/↓: Scroll").removePrefix(" · ")
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
    fun scrollPage(direction: Int) = scroll(direction * page)

    /**
     * A pane with an `id` takes part in focus when `focusable`; `focused` is what it looks like and follows. A pane
     * without a `title` has no border, for a dialog that draws its own.
     */
    fun render(
        title: String?, lines: List<Line>, focused: Boolean, choiceLine: Int, id: String? = null, focusable: Boolean = false,
    ): Element {
        class Pane : StyledElement<Pane>() {
            override fun preferredSize(width: Int, height: Int, context: RenderContext): Size = Size.UNKNOWN
            override fun renderContent(frame: Frame, area: Rect, context: RenderContext) {
                val inner = if (title == null) area
                else Rect(area.x() + 1, area.y() + 1, maxOf(0, area.width() - 2), maxOf(0, area.height() - 2))
                var width = maxOf(1, inner.width())
                val height = maxOf(1, inner.height())
                page = maxOf(1, height - 1)
                val overflow = lines.sumOf { line -> wrap(line.text, width).size } > height
                if (overflow) width = maxOf(1, width - 1)
                val parts = lines.map { line -> wrap(line.text, width).map { part -> Line(part, line.color, line.bold) } }
                val anchor = if (choiceLine in parts.indices) parts.take(choiceLine).sumOf { it.size } else 0
                val wrapped = parts.flatten()
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
                val column = Toolkit.column(*rows.toTypedArray()).fill()
                if (title == null) column.render(frame, inner, context)
                else Toolkit.panel(title, column).borderColor(if (focused) palette.focus else palette.dim).fill()
                    .render(frame, area, context)
                if (overflow) {
                    // TamboUI's Scrollbar patches the inherited text color over thumb and track colors, so the
                    // element's own color is the only one that shows.
                    ScrollbarElement().state(wrapped.size, height, top).hideMarkers().fg(palette.focus)
                        .render(frame, Rect(inner.x() + inner.width() - 1, inner.y(), 1, height), context)
                }
            }
        }
        val pane = Pane().fill()
        return if (id == null) pane else pane.id(id).focusable(focusable)
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
