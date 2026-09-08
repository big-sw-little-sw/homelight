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
        render(plan, json, false, output);
    }

    void render(ReconciliationPlan plan, boolean json, boolean noColor, PrintWriter output) {
        render(plan, json, noColor, true, output);
    }

    void renderForConfirmation(ReconciliationPlan plan, boolean noColor, PrintWriter output) {
        render(plan, false, noColor, false, output);
    }

    private void render(ReconciliationPlan plan, boolean json, boolean noColor, boolean showApplyHint, PrintWriter output) {
        if (json) {
            output.println(toJson(plan));
            return;
        }
        if (plan.actions().isEmpty() && plan.diagnostics().isEmpty() && !plan.hasConflicts()) {
            output.println(new TerminalStyle(noColor).success("Plan is already up to date."));
            return;
        }
        var style = new TerminalStyle(noColor);
        var ready = plan.relocations().stream().filter(relocation -> relocation.conflict().isEmpty()).count();
        var heading = plan.hasBlockedActions() || plan.hasConflicts()
                ? "Plan: " + plan.relocations().size() + plural(plan.relocations().size(), "relocation") + " needs attention"
                : "Plan: " + ready + plural((int) ready, "relocation") + " ready";
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
            relocation.relocation().existingContentPolicy().ifPresent(policy ->
                    output.println("    Existing content: " + policy.value()));
            relocation.conflict().ifPresentOrElse(
                    conflict -> output.println("    " + style.error("! " + conflict.reason())),
                    () -> output.println("    " + intent(relocation.actions())));
        }
        if (plan.hasBlockedActions() || plan.hasConflicts()) {
            output.println();
            output.println(style.error("No changes will be made until these items are resolved."));
        } else {
            output.println();
            output.println(showApplyHint
                    ? "No changes have been made. Run `homelight apply --yes` to apply this plan."
                    : "No changes have been made. Confirm to apply this plan.");
        }
    }

    private void renderDiagnostic(ReconciliationDiagnostic diagnostic, TerminalStyle style, PrintWriter output) {
        var label = diagnostic.severity() == ReconciliationDiagnostic.Severity.ERROR
                ? style.error("! " + diagnostic.message())
                : style.warning("! " + diagnostic.message());
        output.println("  " + label + " (" + diagnostic.source() + ")");
    }

    private String intent(java.util.List<ReconciliationAction> actions) {
        if (actions.stream().anyMatch(ReconciliationAction.Blocked.class::isInstance)) {
            var blocked = (ReconciliationAction.Blocked) actions.stream()
                    .filter(ReconciliationAction.Blocked.class::isInstance).findFirst().orElseThrow();
            return "Blocked: " + blocked.reason();
        }
        if (actions.stream().anyMatch(ReconciliationAction.Move.class::isInstance)) {
            return "Move existing contents and create a link";
        }
        if (actions.stream().anyMatch(ReconciliationAction.DeleteDirectory.class::isInstance)) {
            return "Discard existing contents and create a link";
        }
        if (actions.stream().anyMatch(ReconciliationAction.ReplaceSymlink.class::isInstance)) {
            return "Repair the source link";
        }
        if (actions.stream().anyMatch(ReconciliationAction.NoOp.class::isInstance)) {
            return "Already configured";
        }
        if (actions.stream().anyMatch(ReconciliationAction.Skip.class::isInstance)) {
            return "Leave existing content unchanged";
        }
        return "Create a destination directory and link";
    }

    private static String plural(int count, String noun) {
        return count == 1 ? " " + noun : " " + noun + "s";
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
                var existingContentPolicy = relocation.relocation().existingContentPolicy();
                if (existingContentPolicy.isPresent()) {
                    generator.writeStringField("existing", existingContentPolicy.orElseThrow().value());
                }
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
        ActionJson.writeFields(generator, action);
        generator.writeEndObject();
    }

}
