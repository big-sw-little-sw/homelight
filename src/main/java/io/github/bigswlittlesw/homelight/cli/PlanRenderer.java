package io.github.bigswlittlesw.homelight.cli;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import io.github.bigswlittlesw.homelight.cli.PlanJson.ConflictJson;
import io.github.bigswlittlesw.homelight.cli.PlanJson.DiagnosticJson;
import io.github.bigswlittlesw.homelight.cli.PlanJson.RelocationJson;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationDiagnostic;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlan;
import io.github.bigswlittlesw.homelight.reconcile.RelocationPlan;

import java.io.PrintWriter;
import java.util.List;

final class PlanRenderer {
    void render(ReconciliationPlan plan, boolean json, PrintWriter output) {
        render(plan, json, false, output);
    }

    void render(ReconciliationPlan plan, boolean json, boolean noColor, PrintWriter output) {
        if (json) {
            renderJson(plan, output);
            return;
        }
        renderForConfirmation(plan, noColor, output);
    }

    void renderJson(ReconciliationPlan plan, PrintWriter output) {
        JsonOutput.print(toJson(plan), output);
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

    private String intent(RelocationPlan relocation) {
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

    private static PlanJson toJson(ReconciliationPlan plan) {
        return new PlanJson(plan.hasBlockedActions(), plan.hasConflicts(), diagnostics(plan.diagnostics()),
                plan.relocations().stream().map(PlanRenderer::toJson).toList(),
                plan.actions().stream().map(ActionJson::of).toList());
    }

    private static RelocationJson toJson(RelocationPlan relocation) {
        var conflict = relocation.conflict().map(found -> new ConflictJson(found.path().toString(), found.reason(),
                found.resolutions().stream().map(resolution -> resolution.name().toLowerCase().replace('_', '-')).toList()));
        return new RelocationJson(relocation.relocation().sourcePath().toString(),
                relocation.relocation().targetPath().toString(), relocation.outcome().value(),
                diagnostics(relocation.diagnostics()), conflict.orElse(null),
                relocation.actions().stream().map(ActionJson::of).toList());
    }

    private static List<DiagnosticJson> diagnostics(List<ReconciliationDiagnostic> diagnostics) {
        return diagnostics.stream().map(diagnostic -> new DiagnosticJson(diagnostic.severity().name().toLowerCase(),
                diagnostic.source().toString(), diagnostic.code(), diagnostic.message())).toList();
    }
}

/// The `plan --json` response. Component order is the contract's field order.
record PlanJson(boolean blocked, boolean conflicts, List<DiagnosticJson> diagnostics,
                List<RelocationJson> relocations, List<ActionJson> actions) {
    /// `conflict` is present only for a relocation that needs a decision.
    record RelocationJson(String source, String target, String outcome, List<DiagnosticJson> diagnostics,
                          @JsonInclude(Include.NON_NULL) ConflictJson conflict, List<ActionJson> actions) {
    }

    record ConflictJson(String path, String reason, List<String> resolutions) {
    }

    record DiagnosticJson(String severity, String source, String code, String message) {
    }
}
