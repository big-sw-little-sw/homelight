package io.github.bigswlittlesw.lighten.tui

import dev.tamboui.layout.Rect
import dev.tamboui.markdown.MarkdownStyles
import dev.tamboui.style.Color
import dev.tamboui.style.Overflow
import dev.tamboui.terminal.Frame
import dev.tamboui.text.CharWidth
import dev.tamboui.toolkit.Toolkit
import dev.tamboui.toolkit.element.Element
import dev.tamboui.toolkit.element.RenderContext
import dev.tamboui.toolkit.element.Size
import dev.tamboui.toolkit.element.StyledElement
import dev.tamboui.toolkit.elements.Panel
import dev.tamboui.toolkit.elements.ScrollbarElement
import dev.tamboui.toolkit.markdown.MarkdownElement
import dev.tamboui.widgets.block.BorderType

/**
 * A scrollable pane of text. It wraps the text at the pane's current width on every render, so the text follows a
 * resize, also while a dialog is open over it.
 */
internal class DetailViewport {
    private var top = 0
    private var maximum = 0
    // One line less than the pane's height, so a page keeps a line of context.
    private var page = 1
    // Where the pane last rendered, so the mouse wheel can scroll the pane under the pointer.
    private var area: Rect? = null
    private var followingChoice = false
    private var keepVisible = false

    data class Line(val text: String, val color: Color = palette.text, val bold: Boolean = false)

    /** Lines and the index of the one the pane keeps in view while a choice among them is focused. */
    data class Anchored(val lines: List<Line>, val anchor: Int)

    /**
     * The help lines for this pane. They check for overflow after the pane renders, so they match this frame's size.
     * While a dialog is open and takes every key, help is not `shown` and its two lines stay blank. A `note` about
     * the focused field takes the navigation line's place.
     */
    fun help(keys: ScreenHelp, shown: Boolean = true, note: String? = null): Element {
        class Help : StyledElement<Help>() {
            override fun preferredSize(width: Int, height: Int, context: RenderContext): Size = Size.heightOnly(2)
            override fun renderContent(frame: Frame, area: Rect, context: RenderContext) {
                if (!shown) return
                // Never advertise a key that does nothing now: scroll keys show only while the pane overflows, and
                // `[`/`]` join ↑/↓ then, or follow the line when ↑/↓ does something else.
                val overflows = maximum > 0
                val hints = keys.navigation.filter { overflows || !it.scrolls }
                    .map { hint -> if (overflows && hint == SCROLL_KEY) KeyHint("↑/↓/[/]", hint.action) else hint }
                val brackets = KeyHint("[/]", "Scroll").takeIf { overflows && SCROLL_KEY !in keys.navigation }
                val navigation = listOfNotNull(note ?: helpLine(hints).ifEmpty { null }, brackets?.text).joinToString(" · ")
                wrappedText(navigation + "\n" + helpLine(keys.commands), palette.dim).render(frame, area, context)
            }
        }
        return Help()
    }

    fun reset() { top = 0; followingChoice = false }
    fun followChoice() { followingChoice = true; keepVisible = false }
    fun keepChoiceVisible() { followingChoice = true; keepVisible = true }
    fun scroll(delta: Int) {
        followingChoice = false
        // Add in Long: callers pass ±Int.MAX_VALUE to reach either end, and that would overflow an Int.
        top = (top.toLong() + delta).coerceIn(0, maximum.toLong()).toInt()
    }
    fun scrollPage(direction: Int) = scroll(direction * page)

    /** A pane with an `id` takes part in focus when `focusable`; `focused` is what it looks like and follows. */
    fun render(
        title: String, lines: List<Line>, focused: Boolean, choiceLine: Int, id: String? = null, focusable: Boolean = false,
    ): Element {
        class Pane : StyledElement<Pane>() {
            override fun preferredSize(width: Int, height: Int, context: RenderContext): Size = Size.UNKNOWN
            override fun renderContent(frame: Frame, area: Rect, context: RenderContext) {
                this@DetailViewport.area = area
                val inner = inner(area)
                var width = inner.width()
                val overflow = lines.sumOf { line -> wrap(line.text, width).size } > inner.height()
                if (overflow) width = maxOf(1, width - 1)
                val parts = lines.map { line -> wrap(line.text, width).map { part -> Line(part, line.color, line.bold) } }
                val anchor = if (choiceLine in parts.indices) parts.take(choiceLine).sumOf { it.size } else 0
                val wrapped = parts.flatten()
                measure(wrapped.size, inner.height())
                if (followingChoice && focused) {
                    if (!keepVisible) top = minOf(anchor, maximum)
                    else if (anchor < top) top = anchor
                    else if (anchor >= top + inner.height()) top = anchor - inner.height() + 1
                }
                top = top.coerceIn(0, maximum)
                val rows = wrapped.subList(top, minOf(top + inner.height(), wrapped.size)).map { line ->
                    val text = Toolkit.text(line.text).fg(line.color)
                    if (line.bold) text.bold() else text
                }
                framed(Toolkit.panel(title, Toolkit.column(*rows.toTypedArray()).fill()), focused)
                    .render(frame, area, context)
                if (overflow) scrollbar(frame, inner, wrapped.size, context)
            }
        }
        val pane = Pane().fill()
        return if (id == null) pane else pane.id(id).focusable(focusable)
    }

    /**
     * A pane of Markdown rendered by TamboUI, scrolled by this viewport. It is the only pane on its screen, so it
     * looks focused; it takes focus only when `focusable`.
     */
    fun markdown(title: String, source: String, styles: MarkdownStyles, id: String, focusable: Boolean): Element {
        class MarkdownPane : StyledElement<MarkdownPane>() {
            override fun preferredSize(width: Int, height: Int, context: RenderContext): Size = Size.UNKNOWN
            override fun renderContent(frame: Frame, area: Rect, context: RenderContext) {
                this@DetailViewport.area = area
                val inner = inner(area)
                val text = MarkdownElement.markdown(source).styles(styles).overflow(Overflow.WRAP_WORD)
                fun rows(width: Int) = text.preferredSize(width, inner.height(), context).heightOr(0)
                var width = inner.width()
                var total = rows(width)
                val overflow = total > inner.height()
                if (overflow) { width = maxOf(1, width - 1); total = rows(width) }
                measure(total, inner.height())
                top = top.coerceIn(0, maximum)
                framed(Toolkit.panel(title), focused = true).render(frame, area, context)
                text.scroll(top).render(frame, Rect(inner.x(), inner.y(), width, inner.height()), context)
                if (overflow) scrollbar(frame, inner, total, context)
            }
        }
        return MarkdownPane().fill().id(id).focusable(focusable)
    }

    /** Whether the pane last rendered over the cell at `x`, `y`. */
    fun contains(x: Int, y: Int): Boolean = area?.contains(x, y) == true

    /** Whether the cell at `x`, `y` is left of the pane and level with it, where a master-detail screen has its list. */
    fun besideOnTheLeft(x: Int, y: Int): Boolean =
        area?.let { pane -> x < pane.x() && y >= pane.y() && y < pane.y() + pane.height() } == true

    private fun inner(area: Rect): Rect =
        Rect(area.x() + 1, area.y() + 1, maxOf(1, area.width() - 2), maxOf(1, area.height() - 2))

    private fun measure(rows: Int, height: Int) {
        maximum = maxOf(0, rows - height)
        page = maxOf(1, height - 1)
    }

    // TamboUI's Scrollbar patches the inherited text color over thumb and track colors, so the element's own color
    // is the only one that shows.
    private fun scrollbar(frame: Frame, inner: Rect, rows: Int, context: RenderContext) =
        ScrollbarElement().state(rows, inner.height(), top).hideMarkers().fg(palette.focus)
            .render(frame, Rect(inner.x() + inner.width() - 1, inner.y(), 1, inner.height()), context)
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

/**
 * A pane's frame. The focused pane's border is thick as well as in the focus color, so it shows without color; the
 * others are plain and dim.
 */
internal fun framed(panel: Panel, focused: Boolean): Panel =
    panel.borderType(if (focused) BorderType.THICK else BorderType.PLAIN)
        .borderColor(if (focused) palette.focus else palette.dim).fill()
