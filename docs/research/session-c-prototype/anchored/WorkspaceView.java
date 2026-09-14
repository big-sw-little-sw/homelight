package io.github.bigswlittlesw.homelight.tui;

import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.toolkit.Toolkit;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.element.StyledElement;
import dev.tamboui.toolkit.elements.ListElement;
import dev.tamboui.widgets.common.ScrollBarPolicy;
import io.github.bigswlittlesw.homelight.application.*;
import java.util.ArrayList;

// Throwaway workspace; preserve the existing Plan view's badges, radio choices and focus colors.
final class WorkspaceView {
    static Element render(HomeLightSession session, int selected, boolean showInSync, PaneFocus focus, int choice, int scroll, int width) {
        if (!(session.planModel() instanceof PlanModel.Configured model)) return Toolkit.column(Toolkit.text("⌂ HOMELIGHT  [Workspace]  [3: Review]  [4: Setup]").cyan().bold(),
                Toolkit.panel("Configuration missing", Toolkit.column(Toolkit.text(session.configPath()), Toolkit.text("Press 4 to set up manually. Nothing is saved until you confirm.").yellow())).fill());
        var items = PlanView.visibleItems(model, showInSync);
        var item = items.get(Math.clamp(selected, 0, items.size() - 1));
        int masterWidth = Math.min(48, Math.max(32, width / 3));
        int detailWidth = Math.max(20, width - masterWidth - 4);
        var master = new ListElement<>().title("Relocations").borderColor(focus == PaneFocus.MASTER ? Color.CYAN : Color.DARK_GRAY)
                .highlightSymbol("").highlightStyle(Style.EMPTY).autoScroll();
        for (int i = 0; i < items.size(); i++) {
            var row = items.get(i);
            var badge = row.badge() == PlanBadge.SKIPPED ? "Unchanged" : row.badge().label();
            var prefix = Toolkit.text(i == selected ? "❯ " : "  ").fg(i == selected && focus == PaneFocus.MASTER ? Color.CYAN : Color.GRAY).length(2);
            var badgeText = Toolkit.text("[" + badge + "] ").fg(PlanView.colorForBadge(row.badge())).length(badge.length() + 3);
            int warningWidth = row.hasDestructiveActions() ? 2 : 0;
            var name = Toolkit.text(row.relocation().sourcePath().getFileName()).ellipsisMiddle()
                    .length(Math.max(1, masterWidth - 2 - 2 - badge.length() - 3 - warningWidth));
            if (i == selected) { badgeText = badgeText.bold(); name = name.bold(); }
            master.add(Toolkit.row(prefix, badgeText, name, Toolkit.text(row.hasDestructiveActions() ? " ⚠" : "").yellow().length(warningWidth)));
        }
        master.selected(selected);
        int hidden = model.items().size() - items.size();
        var hiddenHint = Toolkit.text(hidden > 0 ? "▶ " + hidden + " in sync hidden · c: Show" : "c: Hide/show in sync").gray().dim();
        master.add(Toolkit.text(""));
        master.add(hiddenHint);
        var details = new ListElement<>().title("Relocation details")
                .borderColor(focus == PaneFocus.DETAIL ? Color.CYAN : Color.DARK_GRAY).highlightSymbol("").highlightStyle(Style.EMPTY)
                .scrollbar(ScrollBarPolicy.AS_NEEDED).scrollbarThumbColor(Color.CYAN).scrollbarTrackColor(Color.DARK_GRAY).autoScroll();
        var rows = new ArrayList<StyledElement<?>>();
        styled(rows, "Current state", detailWidth, Color.CYAN, true);
        styled(rows, "  Source: " + item.sourceObservation().state().name().toLowerCase() + " · Target: " + item.targetObservation().state().name().toLowerCase(), detailWidth, Color.WHITE, false);
        styled(rows, "  Source: " + item.relocation().sourcePath(), detailWidth, Color.WHITE, false);
        styled(rows, "  Target: " + item.relocation().targetPath(), detailWidth, Color.WHITE, false);
        rows.add(Toolkit.text(""));
        styled(rows, "Decision", detailWidth, Color.CYAN, true);
        styled(rows, "  Saved policy: " + session.savedPolicy(item.relocation().sourcePath()), detailWidth, Color.GRAY, false);
        styled(rows, "  Draft: " + session.draft(item.relocation().sourcePath()).map(DecisionChoice::label).orElse("None"), detailWidth, Color.WHITE, false);
        styled(rows, "  Expected outcome: " + switch (item.plan().outcome()) { case CONVERGED -> "In sync"; case UNCHANGED -> "Left unmanaged"; case UNRESOLVED -> "Needs a decision"; }, detailWidth, item.hasConflict() ? Color.YELLOW : Color.GREEN, true);
        if (item.hasDestructiveActions()) styled(rows, "  ⚠ Destructive actions: existing content or links will be removed.", detailWidth, Color.YELLOW, false);
        rows.add(Toolkit.text(""));
        if (!item.availableResolutions().isEmpty()) styled(rows, "Available resolutions", detailWidth, Color.CYAN, true);
        int selectedLine = 0;
        for (int i = 0; i < item.availableResolutions().size(); i++) {
            var option = item.availableResolutions().get(i);
            boolean cursor = focus == PaneFocus.DETAIL && i == choice;
            boolean chosen = item.selectedResolution().orElse(null) == option;
            var labelLines = wrap(option.label(), detailWidth - 8);
            for (int n = 0; n < labelLines.size(); n++) {
                var label = Toolkit.text(labelLines.get(n));
                if (cursor) label = label.bold();
                rows.add(Toolkit.row(
                        Toolkit.text(n == 0 && cursor ? "  ❯ " : "    ").fg(cursor ? Color.CYAN : Color.GRAY).length(4),
                        Toolkit.text(n == 0 ? chosen ? "(●) " : "(○) " : "    ").fg(chosen ? Color.GREEN : cursor ? Color.WHITE : Color.GRAY).length(4),
                        label.length(detailWidth - 8)));
            }
            for (var line : wrap(option.description(), detailWidth - 8)) rows.add(Toolkit.text("        " + line).gray());
            if (option == DecisionChoice.ADOPT_AND_ARCHIVE_SOURCE) {
                var source = item.relocation().sourcePath();
                var archive = item.relocation().sourceArchiveRoot().orElseThrow().resolve(source.getRoot().relativize(source));
                for (var line : wrap("Archive: " + archive, detailWidth - 8)) rows.add(Toolkit.text("        " + line).gray());
            }
            // Follow the whole choice block so its consequence is visible as well as its cursor.
            if (i == choice) selectedLine = rows.size() - 1;
            rows.add(Toolkit.text(""));
        }
        if (item.availableResolutions().isEmpty()) {
            styled(rows, "Planned actions", detailWidth, Color.CYAN, true);
            for (var action : item.plan().actions()) styled(rows, "  • " + ApplyView.actionLabel(action), detailWidth, action.destructive() ? Color.YELLOW : Color.WHITE, false);
        }
        for (var row : rows) details.add(row);
        details.selected(scroll >= 0 ? Math.min(scroll, rows.size() - 1) : focus == PaneFocus.DETAIL ? selectedLine : 0);
        long unchanged = model.items().stream().filter(i -> i.plan().outcome() == io.github.bigswlittlesw.homelight.reconcile.RelocationOutcome.UNCHANGED).count();
        var summary = model.items().size() + " relocations · " + model.summary().conflicts() + " conflicts · " + unchanged + " unchanged · ⚠ " + model.summary().destructive() + " destructive";
        var footer = focus == PaneFocus.DETAIL
                ? "↑/↓: Choose · Space: Select · h: Back · [/]: Scroll · 3: Review"
                : "↑/↓: Select · l: Decisions · c: In sync · 3: Review · r: Re-plan";
        return Toolkit.column(
                Toolkit.row(Toolkit.text("⌂ HOMELIGHT  ").cyan().bold(), Toolkit.text("[Workspace]").cyan().bold(), Toolkit.text("  [3: Review]  [4: Setup]").gray().dim()),
                Toolkit.text("Config: " + session.configPath()).gray(),
                Toolkit.text(summary),
                Toolkit.row(master.length(masterWidth), details.fill()).fill(),
                Toolkit.text(footer).gray().dim(),
                Toolkit.text(session.notice).gray().ellipsisMiddle());
    }

    private static void styled(ArrayList<StyledElement<?>> rows, String text, int width, Color color, boolean bold) {
        for (var line : wrap(text, width)) {
            var element = Toolkit.text(line).fg(color);
            rows.add(bold ? element.bold() : element);
        }
    }

    private static java.util.List<String> wrap(String text, int width) {
        var result = new ArrayList<String>();
        while (text.length() > width) {
            int end = text.lastIndexOf(' ', width);
            if (end <= 0) end = width;
            result.add(text.substring(0, end));
            text = text.substring(end);
            if (text.startsWith(" ")) text = text.substring(1);
        }
        if (!text.isEmpty()) result.add(text);
        return result;
    }

    static void add(ArrayList<String> lines, String text, int width) {
        for (int i = 0; i < text.length(); i += width) lines.add(text.substring(i, Math.min(text.length(), i + width)));
    }
}
