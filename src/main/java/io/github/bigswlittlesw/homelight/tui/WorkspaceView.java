package io.github.bigswlittlesw.homelight.tui;

import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.toolkit.Toolkit;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.elements.ListElement;
import dev.tamboui.widgets.common.ScrollBarPolicy;
import io.github.bigswlittlesw.homelight.application.ApplyModel;
import io.github.bigswlittlesw.homelight.application.ConfigurationEvaluation;
import io.github.bigswlittlesw.homelight.application.DecisionChoice;
import io.github.bigswlittlesw.homelight.application.HomeLightSession;
import io.github.bigswlittlesw.homelight.application.PlanBadge;
import io.github.bigswlittlesw.homelight.application.PlanModel;
import io.github.bigswlittlesw.homelight.application.PlanRelocationItem;
import io.github.bigswlittlesw.homelight.config.Relocation;
import io.github.bigswlittlesw.homelight.config.WhenSourceAndTargetDirectoriesExist;
import io.github.bigswlittlesw.homelight.fs.PathObservation;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction;
import java.util.ArrayList;
import java.util.List;
import static io.github.bigswlittlesw.homelight.tui.DetailViewport.Line;

final class WorkspaceView {
    static List<PlanRelocationItem> visibleItems(PlanModel.Configured model, boolean showInSync) {
        if (showInSync) return model.items();
        var active = model.items().stream().filter(item -> item.badge() != PlanBadge.IN_SYNC).toList();
        return active.isEmpty() ? model.items() : active;
    }

    static Element render(HomeLightSession session, int selected, boolean showInSync, PaneFocus focus,
            int choice, DetailViewport viewport) {
        boolean retained = session.applyModel() instanceof ApplyModel.Result;
        var header = Toolkit.row(Toolkit.text("⌂ HOMELIGHT  [1: Workspace]").cyan().bold(),
                Toolkit.text(retained ? "  [2: Results]" : session.isPlanReady() ? "  [2: Review]" : "  [Review unavailable]").gray());
        var model = session.planModel();
        if (!(model instanceof PlanModel.Configured configured)) {
            var message = model instanceof PlanModel.Invalid invalid ? invalid.message()
                    : "No configuration file found. Configure relocations in the configuration file.";
            return Toolkit.column(header, viewport.render("Configuration", List.of(new Line("Config: " + session.configPath()),
                    new Line(message, Color.YELLOW, false)), focus == PaneFocus.DETAIL, 0),
                    viewport.help(focus == PaneFocus.DETAIL ? "↑/↓: Scroll · Tab/Esc: Back" : "Tab/l: Details", "r: Reload · q: Quit"));
        }
        var items = visibleItems(configured, showInSync);
        var master = new ListElement<>().title("Relocations")
                .borderColor(focus == PaneFocus.MASTER ? Color.CYAN : Color.DARK_GRAY)
                .scrollbar(ScrollBarPolicy.AS_NEEDED).scrollbarThumbColor(Color.CYAN)
                .highlightSymbol("").highlightStyle(Style.EMPTY).autoScroll();
        for (int i = 0; i < items.size(); i++) {
            var item = items.get(i);
            var label = item.badge() == PlanBadge.SKIPPED ? "Unchanged" : item.badge().label();
            master.add(Toolkit.row(Toolkit.text(i == selected ? "❯ " : "  ").cyan().length(2),
                    Toolkit.text("[" + label + "] ").fg(color(item.badge())).length(label.length() + 3),
                    Toolkit.text(item.relocation().sourcePath()).ellipsisMiddle().fill()));
        }
        master.selected(selected);
        int hidden = configured.items().size() - items.size();
        if (hidden > 0) master.add(Toolkit.text(hidden + " in sync hidden").gray());
        var lines = new ArrayList<Line>();
        int anchor = 0;
        if (items.isEmpty()) lines.add(new Line("No configured relocations."));
        else anchor = details(session, items.get(Math.clamp(selected, 0, items.size() - 1)), choice, focus, lines, retained);
        for (var diagnostic : configured.plan().diagnostics()) lines.add(new Line(diagnostic.message(), Color.YELLOW, false));
        var summary = summary(configured.items());
        boolean choices = !retained && !items.isEmpty() && !items.get(Math.clamp(selected, 0, items.size() - 1)).availableResolutions().isEmpty();
        var content = new ArrayList<Element>();
        content.add(header);
        content.add(DetailViewport.text("Config: " + session.configPath(), Color.GRAY));
        content.add(summaryElement(summary.getFirst()));
        if (!summary.getLast().isEmpty()) content.add(DetailViewport.text(summary.getLast(), Color.YELLOW));
        if (!session.discardedChoices().isEmpty()) content.add(DetailViewport.text(
                session.discardedChoices().size() + " draft choices discarded after re-plan; inspect current decisions.", Color.YELLOW));
        content.add(Toolkit.row(master.percent(45), viewport.render("Details", lines, focus == PaneFocus.DETAIL, anchor)).fill());
        if (!session.isPlanReady() && !retained) content.add(DetailViewport.text(
                configured.items().stream().anyMatch(PlanRelocationItem::isBlocked)
                        ? "Review unavailable: repair blocked paths/configuration; inspect Details."
                        : "Review unavailable: choose a decision for each conflict.", Color.YELLOW));
        var navigation = focus == PaneFocus.DETAIL
                ? (choices ? "↑/↓: Choose · Space/Enter: Select" : "↑/↓: Scroll") + " · Tab/Esc: Back"
                : "↑/↓: Select · Tab/l: Details · c: In sync";
        var review = retained ? "2: Results" : session.isPlanReady()
                ? configured.plan().hasChanges() ? "a: Review & apply · 2: Review" : "2: Review" : "";
        content.add(viewport.help(navigation, (review.isEmpty() ? "" : review + " · ") + "r: Re-plan · q: Quit"));
        return Toolkit.column(content.toArray(Element[]::new)).fill();
    }

    static List<String> summary(List<PlanRelocationItem> items) {
        int actionable = 0, conflict = 0, blocked = 0, unchanged = 0, synced = 0, warnings = 0, destructive = 0;
        for (var item : items) {
            if (item.isBlocked()) blocked++;
            else if (item.hasConflict()) conflict++;
            else if (item.plan().actions().stream().anyMatch(ReconciliationAction::mutatesFilesystem)) actionable++;
            else if (item.plan().outcome() == io.github.bigswlittlesw.homelight.reconcile.RelocationOutcome.UNCHANGED) unchanged++;
            else synced++;
            if (item.hasWarnings()) warnings++;
            if (item.hasDestructiveActions()) destructive++;
        }
        return List.of(items.size() + " relocations · ⚡ " + actionable + " actionable · ⚠ " + conflict + " conflict · ✖ "
                + blocked + " blocked\n✔ " + synced + " in sync · ─ " + unchanged + " unchanged",
                warnings == 0 && destructive == 0 ? "" : "Of these: " + warnings + " with warnings · " + destructive + " with destructive changes");
    }

    private static Element summaryElement(String summary) {
        var rows = new ArrayList<Element>();
        for (var line : summary.split("\n")) {
            var parts = line.split(" · ");
            var cells = new ArrayList<Element>();
            for (int i = 0; i < parts.length; i++) {
                var part = parts[i];
                var color = part.startsWith("⚠") ? Color.YELLOW : part.startsWith("✖") ? Color.RED
                        : part.startsWith("✔") ? Color.GREEN : part.startsWith("─") ? Color.GRAY : Color.CYAN;
                var label = (i == 0 ? "" : " · ") + part;
                cells.add(Toolkit.text(label).fg(color).length(dev.tamboui.text.CharWidth.of(label)));
            }
            rows.add(Toolkit.row(cells.toArray(Element[]::new)));
        }
        return Toolkit.column(rows.toArray(Element[]::new));
    }

    private static int details(HomeLightSession session, PlanRelocationItem item, int choice, PaneFocus focus,
            ArrayList<Line> lines, boolean retained) {
        lines.add(new Line(item.sourceObservation().state() == io.github.bigswlittlesw.homelight.fs.PathState.DIRECTORY
                && item.targetObservation().state() == io.github.bigswlittlesw.homelight.fs.PathState.DIRECTORY
                ? "Current: Source and target are directories."
                : "Current: Source " + observation(item.sourceObservation()) + "; target " + observation(item.targetObservation()) + ".", Color.CYAN, true));
        switch (item.sourceState()) {
            case WRONG_SYMLINK -> lines.add(new Line("Source link points to a different target.", Color.YELLOW, false));
            case BROKEN_SYMLINK -> lines.add(new Line("Source link is broken: its destination is absent.", Color.YELLOW, false));
            case CORRECT_SYMLINK -> lines.add(new Line("Source link points to the configured target.", Color.GREEN, false));
            default -> { }
        }
        if (session.evaluation() instanceof ConfigurationEvaluation.Loaded loaded) {
            var source = item.relocation().sourcePath();
            loaded.savedConfiguration().relocations().stream().filter(r -> r.sourcePath().equals(source)).findFirst()
                    .ifPresent(saved -> lines.add(new Line("Saved policy: " + policy(saved, item), Color.GRAY, false)));
            lines.add(new Line((retained ? "Reviewed draft: " : "Draft (not saved): ")
                    + java.util.Optional.ofNullable(loaded.draft().get(source)).map(DecisionChoice::label).orElse("None; using saved policy")));
        }
        lines.add(new Line("Expected outcome: " + consequence(item), Color.CYAN, true));
        if (retained) lines.add(new Line("Execution history: Results retained in 2: Results. Re-plan before editing.", Color.YELLOW, false));
        for (var diagnostic : item.plan().diagnostics()) lines.add(new Line(diagnostic.message(), Color.YELLOW, false));
        if (item.hasDestructiveActions()) lines.add(new Line("⚠ Destructive: existing content or links will be removed.", Color.YELLOW, true));
        int anchor = 0;
        if (!retained) for (int i = 0; i < item.availableResolutions().size(); i++) {
            var option = item.availableResolutions().get(i);
            lines.add(new Line(""));
            if (i == choice) anchor = lines.size();
            boolean chosen = item.selectedResolution().orElse(null) == option;
            lines.add(new Line((i == choice && focus == PaneFocus.DETAIL ? "❯ " : "  ") + (chosen ? "(●) " : "(○) ")
                    + option.label(), chosen ? Color.GREEN : Color.CYAN, i == choice));
            lines.add(new Line(option.description()
                    + (option == DecisionChoice.ADOPT_AND_ARCHIVE_SOURCE || option == DecisionChoice.ADOPT_AND_DISCARD_SOURCE
                    ? " Replace source with a link to target." : "")));
        }
        lines.add(new Line(""));
        lines.add(new Line("Paths", Color.CYAN, true));
        lines.add(new Line("Source: " + item.relocation().sourcePath()));
        lines.add(new Line("Target: " + item.relocation().targetPath()));
        item.sourceObservation().symlinkTarget().filter(path -> !path.equals(item.relocation().targetPath()))
                .ifPresent(path -> lines.add(new Line("Current link destination: " + path)));
        item.relocation().sourceArchiveRoot().ifPresent(root -> lines.add(new Line("Archive: "
                + root.resolve(item.relocation().sourcePath().getRoot().relativize(item.relocation().sourcePath())))));
        return anchor;
    }

    private static String consequence(PlanRelocationItem item) {
        if (item.isBlocked()) return "Blocked; repair the problem described below, then re-plan.";
        if (item.hasConflict()) return "Choose a decision to see planned changes.";
        if (item.plan().outcome() == io.github.bigswlittlesw.homelight.reconcile.RelocationOutcome.UNCHANGED)
            return "No changes; left unmanaged by choice.";
        if (item.plan().actions().stream().noneMatch(ReconciliationAction::mutatesFilesystem))
            return "No changes needed; already in sync.";
        return switch (item.badge()) {
            case DISCARD -> "Delete source and target contents; recreate an empty target and source link.";
            case BACKUP -> "Archive the source directory; keep target contents and create a source link.";
            case ADOPT -> "Keep target contents; delete the source directory and replace it with a link.";
            case MIGRATE -> "Copy, verify and publish source contents to target; replace source with a link.";
            case LINK -> item.plan().actions().stream().anyMatch(ReconciliationAction.CreateDirectory.class::isInstance)
                    ? "Create an empty target directory and link source to it."
                    : item.plan().actions().stream().anyMatch(ReconciliationAction.ReplaceSymlink.class::isInstance)
                    ? "Remove the existing source link and replace it with a link to the configured target."
                    : "Keep target contents and create a source link to it.";
            default -> "Reconcile source and target; inspect exact changes in Review.";
        };
    }

    private static String observation(PathObservation observation) {
        return switch (observation.state()) {
            case ABSENT -> "Absent";
            case DIRECTORY -> observation.emptyDirectory() ? "Empty directory" : "Directory";
            case FILE -> "File";
            case OTHER -> "Other filesystem entry";
            case INACCESSIBLE -> "Inaccessible";
            case SYMLINK -> switch (observation.symlinkTargetAvailability()) {
                case EXISTS -> "link destination exists";
                case ABSENT -> "link destination is missing";
                case INACCESSIBLE -> "link destination is inaccessible";
                case NOT_A_SYMLINK -> "symlink";
            };
        };
    }
    static String policy(Relocation relocation, PlanRelocationItem item) {
        if (item.sourceObservation().state() == io.github.bigswlittlesw.homelight.fs.PathState.ABSENT
                && item.targetObservation().state() == io.github.bigswlittlesw.homelight.fs.PathState.DIRECTORY)
            return relocation.whenOnlyTargetExists().map(value -> switch (value) {
                case PROMPT -> "Ask before adopting the existing target.";
                case ADOPT_TARGET -> "Adopt the existing target and create a source link.";
            }).orElse("Ask before adopting the existing target.");
        if (item.sourceObservation().state() != io.github.bigswlittlesw.homelight.fs.PathState.DIRECTORY
                || item.targetObservation().state() != io.github.bigswlittlesw.homelight.fs.PathState.DIRECTORY)
            return "No conflict policy needed for this observed case.";
        var bothPolicy = relocation.whenSourceAndTargetDirectoriesExist()
                .orElse(WhenSourceAndTargetDirectoriesExist.PROMPT);
        var both = switch (bothPolicy) {
            case PROMPT -> "Ask";
            case ADOPT -> "Adopt target";
            case LEAVE_UNCHANGED -> "Leave unmanaged";
            case DISCARD -> "Discard both";
        };
        var adopting = relocation.whenAdoptingTarget().map(value -> switch (value) {
            case PROMPT -> "ask about source";
            case DISCARD_SOURCE -> "discard source";
            case ARCHIVE_SOURCE -> "archive source";
        }).orElse("ask about source");
        return both + (bothPolicy == WhenSourceAndTargetDirectoriesExist.ADOPT ? "; " + adopting : "") + ".";
    }
    static Color color(PlanBadge badge) {
        return switch (badge) {
            case IN_SYNC -> Color.GREEN;
            case MIGRATE, ADOPT, LINK, BACKUP, DISCARD -> Color.CYAN;
            case CONFLICT, WARNING -> Color.YELLOW;
            case BLOCKED, INACCESSIBLE -> Color.RED;
            case SKIPPED -> Color.GRAY;
        };
    }
}
