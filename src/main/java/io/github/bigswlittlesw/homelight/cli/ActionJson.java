package io.github.bigswlittlesw.homelight.cli;

import com.fasterxml.jackson.core.JsonGenerator;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction;

import java.io.IOException;

/// Keeps the machine-readable action contract consistent between plans and execution results.
final class ActionJson {
    private ActionJson() {
    }

    static void writeFields(JsonGenerator generator, ReconciliationAction action) throws IOException {
        generator.writeStringField("type", action.type());
        generator.writeStringField("path", action.path().toString());
        generator.writeBooleanField("destructive", action.destructive());
        switch (action) {
            case ReconciliationAction.Move move -> generator.writeStringField("target", move.target().toString());
            case ReconciliationAction.CreateSymlink link -> generator.writeStringField("target", link.target().toString());
            case ReconciliationAction.ReplaceSymlink link -> generator.writeStringField("target", link.target().toString());
            case ReconciliationAction.Blocked blocked -> generator.writeStringField("reason", blocked.reason());
            default -> { }
        }
    }
}
