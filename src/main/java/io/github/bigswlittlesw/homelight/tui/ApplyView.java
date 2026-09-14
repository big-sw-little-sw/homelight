package io.github.bigswlittlesw.homelight.tui;

import dev.tamboui.style.Color;
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
import java.util.Optional;

final class ApplyView {
    private static final String[] SPINNER_FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    private ApplyView() { }

    static Element render(Path config, ApplyModel model, int selectedIndex) {
        return render(config, model, selectedIndex, 0);
    }

    static Element render(Path config, ApplyModel model, int selectedIndex, int spinnerFrame) {
        return render(config, model, selectedIndex, spinnerFrame, PaneFocus.MASTER, new DetailViewport());
    }

    static Element render(Path config, ApplyModel model, int selectedIndex, int spinnerFrame,
            PaneFocus focus, DetailViewport viewport) {
        var header = Toolkit.row(Toolkit.text("⌂ HOMELIGHT  ").cyan().bold(),
                Toolkit.text(model instanceof ApplyModel.Running ? "[Workspace unavailable]  " : "[1: Workspace]  ").gray().dim(),
                Toolkit.text(model instanceof ApplyModel.Result ? "[2: Results]" : model instanceof ApplyModel.Running
                        ? "[Applying]" : "[2: Review]").cyan().bold());
        if (model instanceof ApplyModel.Idle) {
            return Toolkit.column(header, Toolkit.text("Review a resolved plan before applying.").yellow(),
                    Toolkit.text("1: Workspace  ·  q: Quit").gray());
        }
        var plan = switch (model) {
            case ApplyModel.Confirmation confirmation -> confirmation.plan();
            case ApplyModel.Running running -> running.plan();
            case ApplyModel.Result result -> result.plan();
            case ApplyModel.Idle _ -> throw new IllegalStateException();
        };
        var steps = steps(model);
        int selected = steps.isEmpty() ? 0 : Math.clamp(selectedIndex, 0, steps.size() - 1);
        var checklist = new ListElement<>().title("Reviewed actions")
                .borderColor(focus == PaneFocus.MASTER ? Color.CYAN : Color.DARK_GRAY)
                .scrollbar(ScrollBarPolicy.AS_NEEDED).scrollbarThumbColor(Color.CYAN)
                .highlightSymbol("").highlightStyle(Style.EMPTY).autoScroll();
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
        var detailLines = new java.util.ArrayList<DetailViewport.Line>();
        if (steps.isEmpty()) detailLines.add(new DetailViewport.Line("No actions required."));
        else detailLines.addAll(details(steps.get(selected)));
        if (model instanceof ApplyModel.Result result) for (var diagnostic : result.diagnostics())
            detailLines.add(new DetailViewport.Line(diagnostic, Color.RED, false));
        var destructive = plan.actions().stream().filter(ReconciliationAction::destructive).count();
        var headline = switch (model) {
            case ApplyModel.Confirmation _ when !plan.hasChanges() -> "No changes to apply.";
            case ApplyModel.Confirmation _ -> destructive > 0
                    ? "Confirm reviewed plan: " + destructive + " destructive action(s). Content may be permanently removed."
                    : "Confirm reviewed plan. No changes have been made.";
            case ApplyModel.Running _ -> "Applying reviewed plan. Wait for execution to finish.";
            case ApplyModel.Result result -> result.stale() && result.execution().isEmpty()
                    && result.steps().stream().allMatch(step -> step.status() == ApplyModel.StepStatus.PENDING)
                    ? "Plan stale: preflight rejected before any mutation. Inspect details."
                    : result.stale() ? "Plan stale during execution. Inspect failed and not-run actions."
                    : result.succeeded() ? "Application complete. Observations refreshed. Results retained."
                    : result.execution().isEmpty() ? "Worker stopped unexpectedly. Mutation extent may be uncertain; inspect evidence."
                    : "Application stopped. Inspect failed and not-run actions, then re-plan.";
            case ApplyModel.Idle _ -> throw new IllegalStateException();
        };
        var footer = switch (model) {
            case ApplyModel.Confirmation _ when !plan.hasChanges() -> "1/Enter/n/Esc: Workspace · q: Quit";
            case ApplyModel.Confirmation _ -> destructive > 0
                    ? "y: Confirm destructive plan · n/Esc/1: Cancel review · q: Quit"
                    : "y: Confirm apply · n/Esc/1: Cancel review · q: Quit";
            case ApplyModel.Running _ -> "q: Quit options";
            case ApplyModel.Result _ -> "1/Enter: Workspace · r: Re-plan · q: Quit";
            case ApplyModel.Idle _ -> throw new IllegalStateException();
        };
        var content = new java.util.ArrayList<Element>();
        content.add(header);
        content.add(DetailViewport.text("Config: " + config, Color.GRAY));
        content.add(DetailViewport.text(headline, model instanceof ApplyModel.Result result && result.succeeded()
                ? Color.GREEN : Color.YELLOW));
        if (model instanceof ApplyModel.Confirmation) {
            if (plan.hasChanges()) content.add(DetailViewport.text(plan.actions().stream()
                    .filter(ReconciliationAction::mutatesFilesystem).count() + " planned changes · "
                    + destructive + " destructive actions", Color.CYAN));
        } else {
            if (!progress(steps).isEmpty()) content.add(DetailViewport.text(progress(steps), Color.CYAN));
            content.add(DetailViewport.text(counts(steps, model instanceof ApplyModel.Result), Color.GRAY));
        }
        content.add(Toolkit.row(checklist.percent(45), viewport.render("Action details", detailLines, focus == PaneFocus.DETAIL, 0)).fill());
        var navigation = focus == PaneFocus.MASTER ? "↑/↓: Inspect · Tab/l: Details"
                : "↑/↓: Scroll · Tab/h: List" + (model instanceof ApplyModel.Confirmation ? "" : " · Esc: Back");
        content.add(viewport.help(navigation, footer));
        return Toolkit.column(content.toArray(Element[]::new)).fill();
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

    static List<DetailViewport.Line> details(ApplyModel.Step step) {
        var action = step.action();
        var lines = new java.util.ArrayList<DetailViewport.Line>();
        lines.add(new DetailViewport.Line(actionLabel(action), color(step), true));
        if (step.status() != ApplyModel.StepStatus.PENDING)
            lines.add(new DetailViewport.Line(step.message(), color(step), false));
        if (action.destructive()) lines.add(new DetailViewport.Line(
                "⚠ Destructive: existing content or link will be removed.", Color.YELLOW, true));
        lines.add(new DetailViewport.Line(affectedPath(action)));
        if (!destination(action).isEmpty()) lines.add(new DetailViewport.Line(destination(action)));
        var relocation = step.relocation().relocation();
        if (!action.path().equals(relocation.sourcePath())) lines.add(new DetailViewport.Line("Source: " + relocation.sourcePath()));
        if (!action.path().equals(relocation.targetPath())
                && destinationPath(action).filter(relocation.targetPath()::equals).isEmpty())
            lines.add(new DetailViewport.Line("Target: " + relocation.targetPath()));
        return lines;
    }

    static String affectedPath(ReconciliationAction action) {
        return switch (action) {
            case ReconciliationAction.EnsureDirectory _ -> "Parent directory: ";
            case ReconciliationAction.CreateDirectory _ -> "Create at: ";
            case ReconciliationAction.CopyDirectory _, ReconciliationAction.MigrateDirectoryForPublication _ -> "Copy from: ";
            case ReconciliationAction.ArchiveDirectory _ -> "Archive from: ";
            case ReconciliationAction.DeleteDirectory _ -> "Delete at: ";
            case ReconciliationAction.CreateSymlink _, ReconciliationAction.ReplaceDirectoryWithSymlink _, ReconciliationAction.ReplaceSymlink _ -> "Link at: ";
            case ReconciliationAction.NoOp _, ReconciliationAction.LeaveUnchanged _ -> "Unchanged path: ";
            case ReconciliationAction.Blocked _ -> "Blocked path: ";
        } + action.path();
    }

    static String destination(ReconciliationAction action) {
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

    private static Optional<Path> destinationPath(ReconciliationAction action) {
        return switch (action) {
            case ReconciliationAction.ArchiveDirectory archive -> Optional.of(archive.target());
            case ReconciliationAction.CopyDirectory copy -> Optional.of(copy.target());
            case ReconciliationAction.MigrateDirectoryForPublication migration -> Optional.of(migration.target());
            case ReconciliationAction.CreateSymlink link -> Optional.of(link.target());
            case ReconciliationAction.ReplaceDirectoryWithSymlink link -> Optional.of(link.target());
            case ReconciliationAction.ReplaceSymlink link -> Optional.of(link.target());
            default -> Optional.empty();
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

    private static String counts(List<ApplyModel.Step> steps, boolean result) {
        var changes = steps.stream().filter(step -> step.action().mutatesFilesystem()).toList();
        long completed = changes.stream().filter(step -> step.status() == ApplyModel.StepStatus.COMPLETED).count();
        long failed = changes.stream().filter(step -> step.status() == ApplyModel.StepStatus.FAILED).count();
        long pending = changes.stream().filter(step -> step.status() == ApplyModel.StepStatus.PENDING).count();
        long running = changes.stream().filter(step -> step.status() == ApplyModel.StepStatus.RUNNING).count();
        long inSync = steps.stream().filter(step -> step.action() instanceof ReconciliationAction.NoOp).count();
        long unchanged = steps.stream().filter(step -> step.action() instanceof ReconciliationAction.LeaveUnchanged).count();
        return "Mutations: " + completed + " completed · " + failed + " failed · " + pending + (result ? " not run" : " pending")
                + " · " + running + " running\nNo change: " + inSync + " in sync · " + unchanged + " intentionally unchanged";
    }
}
