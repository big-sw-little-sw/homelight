package io.github.bigswlittlesw.homelight.cli;

import com.fasterxml.jackson.core.JsonFactory;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationDiagnostic;
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
        if (plan.actions().isEmpty() && plan.diagnostics().isEmpty() && !plan.hasConflicts()) {
            output.println("No actions required.");
            return;
        }
        for (var diagnostic : plan.diagnostics()) {
            renderDiagnostic(diagnostic, output);
        }
        for (var relocation : plan.relocations()) {
            for (var diagnostic : relocation.diagnostics()) {
                renderDiagnostic(diagnostic, output);
            }
            relocation.conflict().ifPresent(conflict -> output.println("conflict: " + conflict.path()
                    + " (" + conflict.reason() + ")"));
            for (var action : relocation.actions()) {
                output.println(description(action));
            }
        }
        if (plan.hasBlockedActions() || plan.hasConflicts()) {
            output.println("Plan cannot be applied until blocked states and conflicts are resolved.");
        }
    }

    private void renderDiagnostic(ReconciliationDiagnostic diagnostic, PrintWriter output) {
        output.println(diagnostic.severity().name().toLowerCase() + ": " + diagnostic.source()
                + " [" + diagnostic.code() + "] " + diagnostic.message());
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
            generator.writeBooleanField("conflicts", plan.hasConflicts());
            generator.writeArrayFieldStart("diagnostics");
            for (var diagnostic : plan.diagnostics()) {
                writeDiagnostic(generator, diagnostic);
            }
            generator.writeEndArray();
            generator.writeArrayFieldStart("relocations");
            for (var relocation : plan.relocations()) {
                generator.writeStartObject();
                generator.writeStringField("source", relocation.relocation().sourcePath().toString());
                generator.writeStringField("target", relocation.relocation().targetPath().toString());
                generator.writeArrayFieldStart("diagnostics");
                for (var diagnostic : relocation.diagnostics()) {
                    writeDiagnostic(generator, diagnostic);
                }
                generator.writeEndArray();
                relocation.conflict().ifPresent(conflict -> {
                    try {
                        generator.writeObjectFieldStart("conflict");
                        generator.writeStringField("path", conflict.path().toString());
                        generator.writeStringField("reason", conflict.reason());
                        generator.writeArrayFieldStart("resolutions");
                        for (var resolution : conflict.resolutions()) {
                            generator.writeString(resolution.name().toLowerCase().replace('_', '-'));
                        }
                        generator.writeEndArray();
                        generator.writeEndObject();
                    } catch (IOException exception) {
                        throw new IllegalStateException(exception);
                    }
                });
                generator.writeArrayFieldStart("actions");
                for (var action : relocation.actions()) {
                    writeAction(generator, action);
                }
                generator.writeEndArray();
                generator.writeEndObject();
            }
            generator.writeEndArray();
            generator.writeArrayFieldStart("actions");
            for (var action : plan.actions()) {
                writeAction(generator, action);
            }
            generator.writeEndArray();
            generator.writeEndObject();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to render reconciliation plan as JSON", exception);
        }
        return json.toString();
    }

    private void writeDiagnostic(com.fasterxml.jackson.core.JsonGenerator generator, ReconciliationDiagnostic diagnostic)
            throws IOException {
        generator.writeStartObject();
        generator.writeStringField("severity", diagnostic.severity().name().toLowerCase());
        generator.writeStringField("source", diagnostic.source().toString());
        generator.writeStringField("code", diagnostic.code());
        generator.writeStringField("message", diagnostic.message());
        generator.writeEndObject();
    }

    private void writeAction(com.fasterxml.jackson.core.JsonGenerator generator, ReconciliationAction action) throws IOException {
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
