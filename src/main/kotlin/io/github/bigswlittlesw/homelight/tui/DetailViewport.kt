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

/** Wraps at the actual pane width on every render, including resize and quit dialogs. */
internal class DetailViewport {
    private var top = 0
    private var maximum = 0
    private var followingChoice = false
    private var keepVisible = false

    fun overflows(): Boolean = maximum > 0

    // Resolve overflow after the reader renders, so help reflects this frame's size.
    fun help(navigation: String, commands: String): Element {
        class Help : StyledElement<Help>() {
            override fun preferredSize(width: Int, height: Int, context: RenderContext): Size = Size.heightOnly(2)
            override fun renderContent(frame: Frame, area: Rect, context: RenderContext) {
                var keys = navigation
                if (keys.startsWith("↑/↓: Scroll")) {
                    keys = if (overflows()) keys.replace("↑/↓: Scroll", "↑/↓/[/]: Scroll")
                    else keys.replace("↑/↓: Scroll · ", "")
                } else if (overflows()) keys += " · [/]: Scroll"
                text(keys + "\n" + commands, Color.GRAY)
                    .render(frame, area, context)
            }
        }
        return Help()
    }

    fun reset() { top = 0; followingChoice = false }
    fun followChoice() { followingChoice = true; keepVisible = false }
    fun keepChoiceVisible() { followingChoice = true; keepVisible = true }
    fun scroll(delta: Int) {
        followingChoice = false
        top = Math.clamp(top.toLong() + delta, 0, maximum)
    }

    @JvmRecord
    data class Line(val text: String, val color: Color, val bold: Boolean) {
        constructor(text: String) : this(text, Color.WHITE, false)
    }

    fun render(title: String, lines: List<Line>, focused: Boolean, choiceLine: Int): Element {
        class Pane : StyledElement<Pane>() {
            override fun preferredSize(width: Int, height: Int, context: RenderContext): Size = Size.UNKNOWN
            override fun renderContent(frame: Frame, area: Rect, context: RenderContext) {
                var width = Math.max(1, area.width() - 2)
                val height = Math.max(1, area.height() - 2)
                val overflow = lines.stream().mapToInt { line -> wrap(line.text, Math.max(1, area.width() - 2)).size }.sum() > height
                if (overflow) width = Math.max(1, width - 1)
                val wrapped = ArrayList<Line>()
                var anchor = 0
                for (i in lines.indices) {
                    if (i == choiceLine) anchor = wrapped.size
                    val line = lines[i]
                    for (part in wrap(line.text, width)) wrapped.add(Line(part, line.color, line.bold))
                }
                maximum = Math.max(0, wrapped.size - height)
                if (followingChoice && focused) {
                    if (!keepVisible) top = Math.min(anchor, maximum)
                    else if (anchor < top) top = anchor
                    else if (anchor >= top + height) top = anchor - height + 1
                }
                top = Math.clamp(top.toLong(), 0, maximum)
                val rows = ArrayList<Element>()
                for (i in top until Math.min(top + height, wrapped.size)) {
                    val line = wrapped[i]
                    val text = Toolkit.text(line.text).fg(line.color)
                    rows.add(if (line.bold) text.bold() else text)
                }
                Toolkit.panel(title, Toolkit.column(*rows.toTypedArray()).fill())
                    .borderColor(if (focused) Color.CYAN else Color.DARK_GRAY).fill()
                    .render(frame, area, context)
                if (overflow) {
                    val thumb = Math.max(1, height * height / wrapped.size)
                    val start = if (maximum == 0) 0 else top * (height - thumb) / maximum
                    for (row in 0 until height) {
                        Toolkit.text(if (row >= start && row < start + thumb) "█" else "│").cyan()
                            .render(frame, Rect(area.x() + area.width() - 2, area.y() + 1 + row, 1, 1), context)
                    }
                }
            }
        }
        return Pane().fill()
    }

    companion object {
        @JvmStatic
        fun text(value: String, color: Color): Element {
            class WrappedText : StyledElement<WrappedText>() {
                override fun preferredSize(width: Int, height: Int, context: RenderContext): Size =
                    Size.heightOnly(wrap(value, Math.max(1, width)).size)
                override fun renderContent(frame: Frame, area: Rect, context: RenderContext) {
                    Toolkit.column(*wrap(value, Math.max(1, area.width())).stream()
                        .map { line -> Toolkit.text(line).fg(color) }.toList().toTypedArray())
                        .render(frame, area, context)
                }
            }
            return WrappedText()
        }

        @JvmStatic
        fun wrap(text: String, width: Int): List<String> {
            val result = ArrayList<String>()
            for (paragraph in text.split("\n")) {
                val line = StringBuilder()
                var cells = 0
                var offset = 0
                while (offset < paragraph.length) {
                    val point = paragraph.codePointAt(offset)
                    val character = String(Character.toChars(point))
                    val size = CharWidth.of(character)
                    if (cells + size > width && !line.isEmpty()) {
                        val space = line.lastIndexOf(" ")
                        if (space > 0 && point != ' '.code) {
                            result.add(line.substring(0, space))
                            line.delete(0, space + 1)
                            cells = CharWidth.of(line.toString())
                        } else {
                            result.add(line.toString())
                            line.setLength(0)
                            cells = 0
                            if (point == ' '.code) { offset += Character.charCount(point); continue }
                        }
                    }
                    line.append(character)
                    cells += size
                    offset += Character.charCount(point)
                }
                result.add(line.toString())
            }
            return result
        }
    }
}
