package io.github.bigswlittlesw.homelight.cli;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationDiagnostic;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlan;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

final class PlanRenderer {
    private final JsonFactory jsonFactory = new JsonFactory();

    void render(ReconciliationPlan plan, boolean json, PrintWriter output) {
        render(plan, json, false, output);
    }

    void render(ReconciliationPlan plan, boolean json, boolean noColor, PrintWriter output) {
        if (json) {
            output.println(toJson(plan));
            return;
        }
        renderForConfirmation(plan, noColor, output);
    }

    void renderJson(ReconciliationPlan plan, PrintWriter output) {
        output.println(toJson(plan));
    }

    void renderForConfirmation(ReconciliationPlan plan, boolean noColor, PrintWriter output) {
        if (plan.actions().isEmpty() && plan.diagnostics().isEmpty() && !plan.hasConflicts()) {
            output.println(new TerminalStyle(noColor).success("Plan is already up to date."));
            return;
        }
        var style = new TerminalStyle(noColor);
        var ready = plan.relocations().stream().filter(relocation -> relocation.conflict().isEmpty()).count();
        var heading = heading(plan, ready);
        output.println(style.heading(heading));
        output.println();
        for (var diagnostic : plan.diagnostics()) {
            renderDiagnostic(diagnostic, style, output);
        }
        for (var relocation : plan.relocations()) {
            for (var diagnostic : relocation.diagnostics()) {
                renderDiagnostic(diagnostic, style, output);
            }
            output.println("  " + relocation.relocation().sourcePath() + " → " + relocation.relocation().targetPath());
            output.println("    Plan outcome: " + relocation.outcome().value());
            relocation.conflict().ifPresentOrElse(
                    conflict -> output.println("    " + style.error("! " + conflict.reason())),
                    () -> output.println("    " + intent(relocation)));
        }
        if (plan.hasBlockedActions()) {
            output.println();
            output.println(style.error("Apply is unavailable: " + firstBlockedReason(plan) + "."));
        } else if (plan.hasConflicts()) {
            output.println();
            output.println(style.error("No changes will be made until the required decisions are resolved."));
        } else {
            output.println();
            output.println("No changes have been made. Confirm to apply this plan.");
        }
    }

    private static String heading(ReconciliationPlan plan, long ready) {
        if (plan.hasBlockedActions()) {
            return "Plan cannot be applied";
        }
        if (plan.hasConflicts()) {
            return "Plan has unresolved decisions";
        }
        return "Plan: " + ready + plural((int) ready, "relocation") + " ready";
    }

    private static String firstBlockedReason(ReconciliationPlan plan) {
        return plan.actions().stream()
                .filter(ReconciliationAction.Blocked.class::isInstance)
                .map(ReconciliationAction.Blocked.class::cast)
                .map(ReconciliationAction.Blocked::reason)
                .findFirst()
                .orElseThrow();
    }

    private void renderDiagnostic(ReconciliationDiagnostic diagnostic, TerminalStyle style, PrintWriter output) {
        var label = diagnostic.severity() == ReconciliationDiagnostic.Severity.ERROR
                ? style.error("! " + diagnostic.message())
                : style.warning("! " + diagnostic.message());
        output.println("  " + label + " (" + diagnostic.source() + ")");
    }

    private String intent(io.github.bigswlittlesw.homelight.reconcile.RelocationPlan relocation) {
        var actions = relocation.actions();
        if (actions.stream().anyMatch(ReconciliationAction.Blocked.class::isInstance)) {
            var blocked = (ReconciliationAction.Blocked) actions.stream()
                    .filter(ReconciliationAction.Blocked.class::isInstance).findFirst().orElseThrow();
            return "Blocked: " + blocked.reason();
        }
        if (actions.stream().anyMatch(ReconciliationAction.MigrateDirectoryForPublication.class::isInstance)) {
            return "Migrate, verify, and atomically publish the source directory";
        }
        if (actions.stream().anyMatch(ReconciliationAction.NoOp.class::isInstance)) {
            return "Already configured";
        }
        if (actions.stream().anyMatch(ReconciliationAction.ReplaceDirectoryWithSymlink.class::isInstance)) {
            return "Adopt the target and replace the source with a link";
        }
        if (actions.stream().anyMatch(ReconciliationAction.DeleteDirectory.class::isInstance)) {
            return "Discard configured: delete both source and target contents, then create an empty target and source link";
        }
        if (actions.stream().anyMatch(ReconciliationAction.ReplaceSymlink.class::isInstance)) {
            return "Repair the source link";
        }
        if (actions.stream().anyMatch(ReconciliationAction.LeaveUnchanged.class::isInstance)) {
            return "Leave source and target unmanaged";
        }
        return "Create a destination directory and link";
    }

    private static String plural(int count, String noun) {
        return count == 1 ? " " + noun : " " + noun + "s";
    }

    public String toJson(ReconciliationPlan plan) {
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
                generator.writeStringField("outcome", relocation.outcome().value());
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

    private void writeDiagnostic(JsonGenerator generator, ReconciliationDiagnostic diagnostic)
            throws IOException {
        generator.writeStartObject();
        generator.writeStringField("severity", diagnostic.severity().name().toLowerCase());
        generator.writeStringField("source", diagnostic.source().toString());
        generator.writeStringField("code", diagnostic.code());
        generator.writeStringField("message", diagnostic.message());
        generator.writeEndObject();
    }

    private void writeAction(JsonGenerator generator, ReconciliationAction action) throws IOException {
        generator.writeStartObject();
        ActionJson.writeFields(generator, action);
        generator.writeEndObject();
    }
}
