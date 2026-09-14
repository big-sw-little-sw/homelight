package io.github.bigswlittlesw.homelight.tui;

import dev.tamboui.style.Color;
import dev.tamboui.style.Overflow;
import dev.tamboui.style.Style;
import dev.tamboui.toolkit.Toolkit;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.elements.ListElement;
import dev.tamboui.widgets.common.ScrollBarPolicy;
import io.github.bigswlittlesw.homelight.application.ApplyModel;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlan;

import java.nio.file.Path;
import java.util.List;

final class ApplyView {
    private static final String[] SPINNER_FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    private ApplyView() { }

    static Element render(Path config, ApplyModel model, int selectedIndex) {
        return render(config, model, selectedIndex, 0);
    }

    static Element render(Path config, ApplyModel model, int selectedIndex, int spinnerFrame) {
        var header = Toolkit.row(Toolkit.text("⌂ HOMELIGHT  ").cyan().bold(),
                Toolkit.text("[1: Status]  [2: Plan]  ").gray().dim(),
                Toolkit.text("[3: Apply]").cyan().bold());
        if (model instanceof ApplyModel.Idle) {
            return Toolkit.column(header, Toolkit.text("Review a resolved plan before applying.").yellow(),
                    Toolkit.text("2: Plan  ·  q: Quit").gray());
        }
        var plan = switch (model) {
            case ApplyModel.Confirmation confirmation -> confirmation.plan();
            case ApplyModel.Running running -> running.plan();
            case ApplyModel.Result result -> result.plan();
            case ApplyModel.Idle _ -> throw new IllegalStateException();
        };
        var steps = steps(model);
        int selected = steps.isEmpty() ? 0 : Math.clamp(selectedIndex, 0, steps.size() - 1);
        var checklist = new ListElement<>().title("Reviewed actions").borderColor(Color.CYAN)
                .scrollbar(ScrollBarPolicy.AS_NEEDED).highlightSymbol("").highlightStyle(Style.EMPTY).autoScroll();
        int row = 0;
        int selectedRow = 0;
        int index = 0;
        for (var relocation : plan.relocations()) {
            checklist.add(Toolkit.text(relocation.relocation().sourcePath()).cyan().bold().ellipsisMiddle());
            row++;
            for (var action : relocation.actions()) {
                var step = steps.get(index);
                if (index == selected) {
                    selectedRow = row;
                }
                var prefix = index == selected ? "❯ " : "  ";
                checklist.add(Toolkit.text(prefix + glyph(step, spinnerFrame) + " " + actionLabel(action)
                        + (action.destructive() ? " ⚠" : "")).fg(color(step)));
                row++;
                index++;
            }
        }
        checklist.selected(selectedRow);
        Element details = steps.isEmpty() ? Toolkit.text("No actions required.").green()
                : details(steps.get(selected));
        var destructive = plan.actions().stream().filter(ReconciliationAction::destructive).count();
        var headline = switch (model) {
            case ApplyModel.Confirmation _ when !plan.hasChanges() -> "No changes to apply.";
            case ApplyModel.Confirmation _ -> destructive > 0
                    ? "Confirm reviewed plan: " + destructive + " destructive action(s). Content may be permanently removed."
                    : "Confirm reviewed plan. No changes have been made.";
            case ApplyModel.Running _ -> "Applying reviewed plan. Wait for execution to finish.";
            case ApplyModel.Result result -> result.stale() ? "Plan stale. Review filesystem changes and re-plan."
                    : result.succeeded() ? "Application complete. Status refreshed."
                    : "Application stopped. Inspect failed and pending actions, then re-plan.";
            case ApplyModel.Idle _ -> throw new IllegalStateException();
        };
        var footer = switch (model) {
            case ApplyModel.Confirmation _ when !plan.hasChanges() -> "↑/↓: Inspect  ·  Enter: Status  ·  r: Re-plan  ·  q: Quit";
            case ApplyModel.Confirmation _ -> destructive > 0
                    ? "↑/↓: Inspect  ·  y: Confirm destructive plan  ·  n/Esc: Cancel"
                    : "↑/↓: Inspect  ·  y: Confirm apply  ·  n/Esc: Cancel";
            case ApplyModel.Running _ -> "↑/↓: Inspect  ·  Execution in progress; leaving is disabled";
            case ApplyModel.Result _ -> "↑/↓: Inspect  ·  Enter: Status  ·  r: Re-plan  ·  q: Quit";
            case ApplyModel.Idle _ -> throw new IllegalStateException();
        };
        var diagnostics = model instanceof ApplyModel.Result result ? String.join("\n", result.diagnostics()) : "";
        return Toolkit.column(header,
                Toolkit.text("Config: " + config).gray().ellipsisMiddle(),
                Toolkit.text(headline).fg(model instanceof ApplyModel.Result result && result.succeeded()
                        ? Color.GREEN : Color.YELLOW).bold().overflow(Overflow.WRAP_WORD).length(2),
                Toolkit.text(progress(steps)).cyan(),
                Toolkit.text(diagnostics).red().overflow(Overflow.WRAP_CHARACTER).length(diagnostics.isEmpty() ? 0 : 2),
                Toolkit.row(checklist.percent(42).fill(),
                        Toolkit.panel("Action details", details).borderColor(Color.DARK_GRAY).fill()).fill(),
                Toolkit.text(footer).gray().overflow(Overflow.WRAP_WORD).length(2));
    }

    static List<ApplyModel.Step> steps(ApplyModel model) {
        return switch (model) {
            case ApplyModel.Idle _ -> List.of();
            case ApplyModel.Confirmation confirmation -> pendingSteps(confirmation.plan());
            case ApplyModel.Running running -> running.steps();
            case ApplyModel.Result result -> result.steps();
        };
    }

    private static List<ApplyModel.Step> pendingSteps(ReconciliationPlan plan) {
        return plan.relocations().stream().flatMap(relocation -> relocation.actions().stream()
                .map(action -> new ApplyModel.Step(relocation, action, ApplyModel.StepStatus.PENDING, "Not started"))).toList();
    }

    private static Element details(ApplyModel.Step step) {
        var action = step.action();
        var paths = "Source: " + step.relocation().relocation().sourcePath()
                + "\nTarget: " + step.relocation().relocation().targetPath()
                + "\nStep path: " + action.path() + "\n" + destination(action);
        return Toolkit.column(
                Toolkit.text(actionLabel(action)).fg(color(step)).bold(),
                Toolkit.text(!action.mutatesFilesystem() && step.status() != ApplyModel.StepStatus.FAILED
                        ? "No filesystem change required" : step.message()).fg(color(step)).overflow(Overflow.WRAP_WORD).length(2),
                Toolkit.richTextArea(paths).gray().wrapCharacter().fill(),
                Toolkit.text(action.destructive() ? "⚠ Destructive: existing content or link will be removed." : "")
                        .yellow().overflow(Overflow.WRAP_WORD).length(action.destructive() ? 2 : 0)).fill();
    }

    private static String destination(ReconciliationAction action) {
        return switch (action) {
            case ReconciliationAction.ArchiveDirectory archive -> "Archive: " + archive.target();
            case ReconciliationAction.CopyDirectory copy -> "Copy to: " + copy.target();
            case ReconciliationAction.MigrateDirectoryForPublication migration -> "Publish to: " + migration.target();
            case ReconciliationAction.CreateSymlink link -> "Link to: " + link.target();
            case ReconciliationAction.ReplaceDirectoryWithSymlink link -> "Link to: " + link.target();
            case ReconciliationAction.ReplaceSymlink link -> "Link to: " + link.target();
            default -> "";
        };
    }

    static String actionLabel(ReconciliationAction action) {
        return switch (action) {
            case ReconciliationAction.EnsureDirectory _ -> "Ensure parent directory";
            case ReconciliationAction.CreateDirectory _ -> "Create directory";
            case ReconciliationAction.CopyDirectory _ -> "Copy directory";
            case ReconciliationAction.MigrateDirectoryForPublication _ -> "Copy, verify and publish";
            case ReconciliationAction.ArchiveDirectory _ -> "Archive source";
            case ReconciliationAction.DeleteDirectory _ -> "Delete directory";
            case ReconciliationAction.CreateSymlink _ -> "Create source link";
            case ReconciliationAction.ReplaceDirectoryWithSymlink _ -> "Replace directory with link";
            case ReconciliationAction.ReplaceSymlink _ -> "Replace source link";
            case ReconciliationAction.NoOp _ -> "Already in sync";
            case ReconciliationAction.LeaveUnchanged _ -> "Leave unchanged";
            case ReconciliationAction.Blocked _ -> "Blocked";
        };
    }

    private static String glyph(ApplyModel.Step step, int spinnerFrame) {
        if (!step.action().mutatesFilesystem() && step.status() != ApplyModel.StepStatus.FAILED) {
            return "─";
        }
        return switch (step.status()) {
            case PENDING -> "○";
            case RUNNING -> SPINNER_FRAMES[Math.floorMod(spinnerFrame, SPINNER_FRAMES.length)];
            case COMPLETED -> step.action().mutatesFilesystem() ? "✔" : "─";
            case FAILED -> "✖";
        };
    }

    private static Color color(ApplyModel.Step step) {
        return switch (step.status()) {
            case PENDING -> Color.GRAY;
            case RUNNING -> Color.CYAN;
            case COMPLETED -> step.action().mutatesFilesystem() ? Color.GREEN : Color.GRAY;
            case FAILED -> Color.RED;
        };
    }

    private static String progress(List<ApplyModel.Step> steps) {
        var changes = steps.stream().filter(step -> step.action().mutatesFilesystem()).toList();
        if (changes.isEmpty()) {
            return "";
        }
        long completed = changes.stream().filter(step -> step.status() == ApplyModel.StepStatus.COMPLETED).count();
        int filled = (int) (20 * completed / changes.size());
        return "[" + "█".repeat(filled) + "░".repeat(20 - filled) + "] " + completed + "/" + changes.size() + " actions completed";
    }
}
