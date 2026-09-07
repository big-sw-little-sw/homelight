package io.github.bigswlittlesw.homelight.cli;

import com.fasterxml.jackson.core.JsonFactory;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlan;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

final class PlanRenderer {
    private final JsonFactory jsonFactory = new JsonFactory();

    void render(ReconciliationPlan plan, boolean json, PrintWriter output) {
        if (json) {
            output.println(toJson(plan));
            return;
        }
        if (plan.actions().isEmpty()) {
            output.println("No actions required.");
            return;
        }
        for (var action : plan.actions()) {
            output.println(description(action));
        }
        if (plan.hasBlockedActions()) {
            output.println("Plan blocked: resolve the listed conflicts before applying it.");
        }
    }

    private String description(ReconciliationAction action) {
        return switch (action) {
            case ReconciliationAction.CreateDirectory create -> "create-directory: " + create.path();
            case ReconciliationAction.Move move -> "move: " + move.path() + " -> " + move.target();
            case ReconciliationAction.CreateSymlink link -> "create-symlink: " + link.path() + " -> " + link.target();
            case ReconciliationAction.ReplaceSymlink link -> "replace-symlink: " + link.path() + " -> " + link.target();
            case ReconciliationAction.NoOp noOp -> "no-op: " + noOp.path();
            case ReconciliationAction.Blocked blocked -> "blocked: " + blocked.path() + " (" + blocked.reason() + ")";
        };
    }

    private String toJson(ReconciliationPlan plan) {
        var json = new StringWriter();
        try (var generator = jsonFactory.createGenerator(json)) {
            generator.writeStartObject();
            generator.writeBooleanField("blocked", plan.hasBlockedActions());
            generator.writeArrayFieldStart("actions");
            for (var action : plan.actions()) {
                generator.writeStartObject();
                generator.writeStringField("type", type(action));
                generator.writeStringField("path", action.path().toString());
                generator.writeBooleanField("destructive", action.destructive());
                switch (action) {
                    case ReconciliationAction.Move move -> generator.writeStringField("target", move.target().toString());
                    case ReconciliationAction.CreateSymlink link -> generator.writeStringField("target", link.target().toString());
                    case ReconciliationAction.ReplaceSymlink link -> generator.writeStringField("target", link.target().toString());
                    case ReconciliationAction.Blocked blocked -> generator.writeStringField("reason", blocked.reason());
                    default -> {
                    }
                }
                generator.writeEndObject();
            }
            generator.writeEndArray();
            generator.writeEndObject();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to render reconciliation plan as JSON", exception);
        }
        return json.toString();
    }

    private String type(ReconciliationAction action) {
        return switch (action) {
            case ReconciliationAction.CreateDirectory ignored -> "create-directory";
            case ReconciliationAction.Move ignored -> "move";
            case ReconciliationAction.CreateSymlink ignored -> "create-symlink";
            case ReconciliationAction.ReplaceSymlink ignored -> "replace-symlink";
            case ReconciliationAction.NoOp ignored -> "no-op";
            case ReconciliationAction.Blocked ignored -> "blocked";
        };
    }
}
