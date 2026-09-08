package io.github.bigswlittlesw.homelight.cli;

import io.github.kusoroadeolu.clique.Clique;

/// Applies restrained terminal emphasis while preserving plain output for pipes and `--no-color`.
final class TerminalStyle {
    private final boolean noColor;

    TerminalStyle(boolean noColor) {
        this.noColor = noColor;
    }

    String heading(String text) {
        return noColor ? text : Clique.ink().bold().on(text);
    }

    String success(String text) {
        return noColor ? text : Clique.ink().green().bold().on(text);
    }

    String pending(String text) {
        return noColor ? text : Clique.ink().brightBlack().on(text);
    }

    String active(String text) {
        return noColor ? text : Clique.ink().cyan().on(text);
    }

    String warning(String text) {
        return noColor ? text : Clique.ink().yellow().bold().on(text);
    }

    String error(String text) {
        return noColor ? text : Clique.ink().red().bold().on(text);
    }
}
