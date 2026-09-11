package io.github.bigswlittlesw.homelight.cli;

import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.AnsiStringBuilder;

/// Applies restrained terminal emphasis while preserving plain output for pipes and `--no-color`.
final class TerminalStyle {
    private final boolean noColor;

    TerminalStyle(boolean noColor) {
        this.noColor = noColor;
    }

    String heading(String text) {
        return format(text, headingStyle());
    }

    String success(String text) {
        return format(text, successStyle());
    }

    String pending(String text) {
        return format(text, pendingStyle());
    }

    String active(String text) {
        return format(text, activeStyle());
    }

    String warning(String text) {
        return format(text, warningStyle());
    }

    String error(String text) {
        return format(text, errorStyle());
    }

    Style headingStyle() {
        return Style.EMPTY.bold();
    }

    Style successStyle() {
        return Style.EMPTY.fg(Color.GREEN).bold();
    }

    Style pendingStyle() {
        return Style.EMPTY.fg(Color.DARK_GRAY);
    }

    Style activeStyle() {
        return Style.EMPTY.fg(Color.CYAN);
    }

    Style warningStyle() {
        return Style.EMPTY.fg(Color.YELLOW).bold();
    }

    Style errorStyle() {
        return Style.EMPTY.fg(Color.RED).bold();
    }

    private String format(String text, Style style) {
        return noColor ? text : AnsiStringBuilder.styleToAnsi(style) + text + AnsiStringBuilder.RESET;
    }
}
