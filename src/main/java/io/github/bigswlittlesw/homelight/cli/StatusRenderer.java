package io.github.bigswlittlesw.homelight.cli;

import java.io.PrintWriter;

final class StatusRenderer {
    void render(StatusSnapshot snapshot, boolean json, PrintWriter output) {
        if (json) {
            output.printf("{\"path\":\"%s\",\"target\":\"%s\",\"state\":\"%s\"}%n",
                    escape(snapshot.relocationPath().toString()), escape(snapshot.localTarget().toString()),
                    snapshot.state().name().toLowerCase());
            return;
        }
        output.printf("path: %s%n", snapshot.relocationPath());
        output.printf("target: %s%n", snapshot.localTarget());
        output.printf("status: %s%n", snapshot.state().name().toLowerCase().replace('_', ' '));
    }

    private static String escape(String value) {
        var escaped = new StringBuilder(value.length());
        for (var character : value.toCharArray()) {
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                default -> {
                    if (character < 0x20) {
                        escaped.append("\\u%04x".formatted((int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
