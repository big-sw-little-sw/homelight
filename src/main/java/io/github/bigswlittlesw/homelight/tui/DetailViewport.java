package io.github.bigswlittlesw.homelight.tui;

import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.CharWidth;
import dev.tamboui.toolkit.Toolkit;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.toolkit.element.Size;
import dev.tamboui.toolkit.element.StyledElement;
import java.util.ArrayList;
import java.util.List;

/// Wraps at the actual pane width on every render, including resize and quit dialogs.
final class DetailViewport {
    private int top;
    private int maximum;
    private boolean followingChoice;
    private boolean keepVisible;

    boolean overflows() { return maximum > 0; }

    // Resolve overflow after the reader renders, so help reflects this frame's size.
    Element help(String navigation, String commands) {
        class Help extends StyledElement<Help> {
            @Override public Size preferredSize(int width, int height, RenderContext context) { return Size.heightOnly(2); }
            @Override protected void renderContent(Frame frame, Rect area, RenderContext context) {
                var keys = navigation;
                if (keys.startsWith("↑/↓: Scroll")) {
                    keys = overflows() ? keys.replace("↑/↓: Scroll", "↑/↓/[/]: Scroll")
                            : keys.replace("↑/↓: Scroll · ", "");
                } else if (overflows()) keys += " · [/]: Scroll";
                text(keys + "\n" + commands, Color.GRAY)
                        .render(frame, area, context);
            }
        }
        return new Help();
    }

    static Element text(String value, Color color) {
        class WrappedText extends StyledElement<WrappedText> {
            @Override public Size preferredSize(int width, int height, RenderContext context) {
                return Size.heightOnly(wrap(value, Math.max(1, width)).size());
            }
            @Override protected void renderContent(Frame frame, Rect area, RenderContext context) {
                Toolkit.column(wrap(value, Math.max(1, area.width())).stream()
                        .map(line -> Toolkit.text(line).fg(color)).toArray(Element[]::new)).render(frame, area, context);
            }
        }
        return new WrappedText();
    }

    void reset() { top = 0; followingChoice = false; }
    void followChoice() { followingChoice = true; keepVisible = false; }
    void keepChoiceVisible() { followingChoice = true; keepVisible = true; }
    void scroll(int delta) {
        followingChoice = false;
        top = (int) Math.clamp((long) top + delta, 0, maximum);
    }

    record Line(String text, Color color, boolean bold) {
        Line(String text) { this(text, Color.WHITE, false); }
    }

    Element render(String title, List<Line> lines, boolean focused, int choiceLine) {
        class Pane extends StyledElement<Pane> {
            @Override public Size preferredSize(int width, int height, RenderContext context) { return Size.UNKNOWN; }
            @Override protected void renderContent(Frame frame, Rect area, RenderContext context) {
                int width = Math.max(1, area.width() - 2);
                int height = Math.max(1, area.height() - 2);
                boolean overflow = lines.stream().mapToInt(line -> wrap(line.text(), Math.max(1, area.width() - 2)).size()).sum() > height;
                if (overflow) width = Math.max(1, width - 1);
                var wrapped = new ArrayList<Line>();
                int anchor = 0;
                for (int i = 0; i < lines.size(); i++) {
                    if (i == choiceLine) anchor = wrapped.size();
                    var line = lines.get(i);
                    for (var part : wrap(line.text(), width)) wrapped.add(new Line(part, line.color(), line.bold()));
                }
                maximum = Math.max(0, wrapped.size() - height);
                if (followingChoice && focused) {
                    if (!keepVisible) top = Math.min(anchor, maximum);
                    else if (anchor < top) top = anchor;
                    else if (anchor >= top + height) top = anchor - height + 1;
                }
                top = Math.clamp(top, 0, maximum);
                var rows = new ArrayList<Element>();
                for (int i = top; i < Math.min(top + height, wrapped.size()); i++) {
                    var line = wrapped.get(i);
                    var text = Toolkit.text(line.text()).fg(line.color());
                    rows.add(line.bold() ? text.bold() : text);
                }
                Toolkit.panel(title, Toolkit.column(rows.toArray(Element[]::new)).fill())
                        .borderColor(focused ? Color.CYAN : Color.DARK_GRAY).fill()
                        .render(frame, area, context);
                if (overflow) {
                    int thumb = Math.max(1, height * height / wrapped.size());
                    int start = maximum == 0 ? 0 : top * (height - thumb) / maximum;
                    for (int row = 0; row < height; row++) {
                        Toolkit.text(row >= start && row < start + thumb ? "█" : "│").cyan()
                                .render(frame, new Rect(area.x() + area.width() - 2, area.y() + 1 + row, 1, 1), context);
                    }
                }
            }
        }
        return new Pane().fill();
    }

    static List<String> wrap(String text, int width) {
        var result = new ArrayList<String>();
        for (var paragraph : text.split("\n", -1)) {
            var line = new StringBuilder();
            int cells = 0;
            for (int offset = 0; offset < paragraph.length();) {
                int point = paragraph.codePointAt(offset);
                var character = new String(Character.toChars(point));
                int size = CharWidth.of(character);
                if (cells + size > width && !line.isEmpty()) {
                    int space = line.lastIndexOf(" ");
                    if (space > 0 && point != ' ') {
                        result.add(line.substring(0, space));
                        line.delete(0, space + 1);
                        cells = CharWidth.of(line.toString());
                    } else {
                        result.add(line.toString());
                        line.setLength(0);
                        cells = 0;
                        if (point == ' ') { offset += Character.charCount(point); continue; }
                    }
                }
                line.append(character);
                cells += size;
                offset += Character.charCount(point);
            }
            result.add(line.toString());
        }
        return result;
    }
}
