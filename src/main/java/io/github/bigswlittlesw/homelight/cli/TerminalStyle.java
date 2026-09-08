package io.github.bigswlittlesw.homelight.cli;

import picocli.CommandLine.Help.Ansi;

/// Applies restrained terminal emphasis while preserving plain output for pipes and `--no-color`.
final class TerminalStyle {
    private final Ansi ansi;

    TerminalStyle(boolean noColor) {
        ansi = noColor ? Ansi.OFF : Ansi.AUTO;
    }

    String heading(String text) {
        return format("bold", text);
    }

    String success(String text) {
        return format("green,bold", text);
    }

    String warning(String text) {
        return format("yellow,bold", text);
    }

    String error(String text) {
        return format("red,bold", text);
    }

    private String format(String styles, String text) {
        return ansi.string("@|" + styles + " " + text + "|@");
    }
}
