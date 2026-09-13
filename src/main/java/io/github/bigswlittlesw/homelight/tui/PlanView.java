package io.github.bigswlittlesw.homelight.tui;

import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.toolkit.Toolkit;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.elements.ListElement;
import dev.tamboui.widgets.common.ScrollBarPolicy;
import io.github.bigswlittlesw.homelight.application.DecisionChoice;
import io.github.bigswlittlesw.homelight.application.PlanBadge;
import io.github.bigswlittlesw.homelight.application.PlanModel;
import io.github.bigswlittlesw.homelight.application.PlanRelocationItem;
import io.github.bigswlittlesw.homelight.fs.PathObservation;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationDiagnostic;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// Renders the reconciliation plan full-screen view in TamboUI.
public final class PlanView {
    private PlanView() {
    }

    public static Element render(PlanModel model, int selectedIndex) {
        return render(model, selectedIndex, true, PaneFocus.MASTER, 0);
    }

    public static Element render(PlanModel model, int selectedIndex, boolean showInSync) {
        return render(model, selectedIndex, showInSync, PaneFocus.MASTER, 0);
    }

    public static Element render(
            PlanModel model,
            int selectedIndex,
            boolean showInSync,
            PaneFocus paneFocus,
            int detailSelectedIndex
    ) {
        return switch (model) {
            case PlanModel.Unconfigured unconfigured -> renderUnconfigured(unconfigured);
            case PlanModel.Invalid invalid -> renderInvalid(invalid);
            case PlanModel.Configured configured -> renderConfigured(configured, selectedIndex, showInSync, paneFocus, detailSelectedIndex);
        };
    }

    public static List<PlanRelocationItem> visibleItems(PlanModel.Configured model, boolean showInSync) {
        if (showInSync) {
            return model.items();
        }
        var active = model.items().stream()
                .filter(item -> item.badge() != PlanBadge.IN_SYNC)
                .toList();
        return active.isEmpty() ? model.items() : active;
    }

    private static Element renderUnconfigured(PlanModel.Unconfigured model) {
        var header = renderHeader("2: Plan", abbreviateHome(model.configPath()), null);
        var body = Toolkit.panel("HomeLight Not Configured",
                Toolkit.column(
                        Toolkit.text("No configuration file found at:").gray(),
                        Toolkit.text("  " + model.configPath()).bold(),
                        Toolkit.text(""),
                        Toolkit.text("Run 'homelight init' or create ~/.homelight.yaml to get started.").yellow()
                )
        ).fill();
        var footer = renderFooter("q: Quit  ·  r: Refresh");
        return Toolkit.column(header, Toolkit.text(""), body, Toolkit.text(""), footer);
    }

    private static Element renderInvalid(PlanModel.Invalid model) {
        var header = renderHeader("2: Plan (Error)", abbreviateHome(model.configPath()), null);
        var body = Toolkit.panel("Configuration Error",
                Toolkit.column(
                        Toolkit.text("Failed to load or plan configuration:").red().bold(),
                        Toolkit.text(""),
                        Toolkit.text(model.message()).red(),
                        Toolkit.text(""),
                        Toolkit.text("Please fix the configuration file and press 'r' to reload.").gray()
                )
        ).fill();
        var footer = renderFooter("q: Quit  ·  r: Refresh");
        return Toolkit.column(header, Toolkit.text(""), body, Toolkit.text(""), footer);
    }

    private static Element renderConfigured(
            PlanModel.Configured model,
            int selectedIndex,
            boolean showInSync,
            PaneFocus paneFocus,
            int detailSelectedIndex
    ) {
        var header = renderHeader("2: Plan", abbreviateHome(model.configPath()), abbreviateHome(model.targetRoot()));
        var summaryBar = renderSummaryBar(model);

        var masterBorder = paneFocus == PaneFocus.MASTER ? Color.CYAN : Color.DARK_GRAY;
        var detailBorder = paneFocus == PaneFocus.DETAIL ? Color.CYAN : Color.DARK_GRAY;

        var visibleItems = visibleItems(model, showInSync);
        Element listPanel;
        Element detailsPanel;

        if (visibleItems.isEmpty()) {
            listPanel = renderRelocationList(model, visibleItems, 0, showInSync, paneFocus, masterBorder).percent(45).fill();
            detailsPanel = Toolkit.panel("Plan Details",
                    Toolkit.column(
                            Toolkit.text("All relocations are in sync.").green().bold(),
                            Toolkit.text(""),
                            Toolkit.text("Press 'c' to view in sync relocations.").gray()
                    )
            ).borderColor(detailBorder).fill();
        } else {
            int clampedIndex = Math.clamp(selectedIndex, 0, visibleItems.size() - 1);
            var selectedItem = visibleItems.get(clampedIndex);

            listPanel = renderRelocationList(model, visibleItems, clampedIndex, showInSync, paneFocus, masterBorder).percent(45).fill();
            detailsPanel = renderRelocationDetails(selectedItem, paneFocus, detailSelectedIndex, detailBorder).fill();
        }

        var mainContent = Toolkit.row(listPanel, detailsPanel).fill();

        var footerText = paneFocus == PaneFocus.DETAIL
                ? "↑/↓/j/k: Choose Option  ·  Space/Enter: Select  ·  ←/h: Back  ·  q: Quit"
                : "↑/↓/j/k: Select  ·  →/l: Details  ·  c: Toggle In Sync  ·  r: Refresh  ·  a: Apply  ·  1: Status  ·  q: Quit";

        return Toolkit.column(
                header,
                Toolkit.text(""),
                summaryBar,
                Toolkit.text(""),
                mainContent,
                Toolkit.text(""),
                renderFooter(footerText)
        );
    }

    private static Element renderHeader(String activeScreen, String configPath, String targetRoot) {
        var headerElements = new ArrayList<Element>();

        var brandElement = Toolkit.text("⌂ HOMELIGHT  ").cyan().bold();
        var tab1 = Toolkit.text("[1: Status]").gray().dim();
        var tab2 = activeScreen.contains("Error")
                ? Toolkit.text("  [2: Plan (Error)]").red().bold()
                : Toolkit.text("  [2: Plan]").cyan().bold();
        var tab3 = Toolkit.text("  [3: Apply]").gray().dim();

        var tabRow = Toolkit.row(brandElement, tab1, tab2, tab3);
        headerElements.add(tabRow);

        headerElements.add(Toolkit.row(
                Toolkit.text("Config:      ").gray().dim(),
                Toolkit.text(configPath).gray()
        ));

        if (targetRoot != null && !targetRoot.isBlank()) {
            headerElements.add(Toolkit.row(
                    Toolkit.text("Target Root: ").gray().dim(),
                    Toolkit.text(targetRoot).gray()
            ));
        }

        return Toolkit.column(headerElements.toArray(new Element[0]));
    }

    private static Element renderSummaryBar(PlanModel.Configured model) {
        var summary = model.summary();
        var badges = new ArrayList<Element>();

        badges.add(Toolkit.text(summary.total() + (summary.total() == 1 ? " relocation" : " relocations")).bold());

        if (summary.migrate() > 0) {
            badges.add(Toolkit.text(" · "));
            badges.add(Toolkit.text("⚡ " + summary.migrate() + " migrate").cyan().bold());
        }
        if (summary.adopt() > 0) {
            badges.add(Toolkit.text(" · "));
            badges.add(Toolkit.text("⚡ " + summary.adopt() + " adopt").cyan().bold());
        }
        if (summary.link() > 0) {
            badges.add(Toolkit.text(" · "));
            badges.add(Toolkit.text("⚡ " + summary.link() + " link").cyan().bold());
        }
        if (summary.backup() > 0) {
            badges.add(Toolkit.text(" · "));
            badges.add(Toolkit.text("⚡ " + summary.backup() + " backup").cyan().bold());
        }
        if (summary.discard() > 0) {
            badges.add(Toolkit.text(" · "));
            badges.add(Toolkit.text("⚡ " + summary.discard() + " discard").cyan().bold());
        }
        if (summary.inSync() > 0) {
            badges.add(Toolkit.text(" · "));
            badges.add(Toolkit.text("✔ " + summary.inSync() + " in sync").green());
        }
        if (summary.skipped() > 0) {
            badges.add(Toolkit.text(" · "));
            badges.add(Toolkit.text("— " + summary.skipped() + " skipped").gray());
        }
        if (summary.conflicts() > 0) {
            badges.add(Toolkit.text(" · "));
            badges.add(Toolkit.text("⚠ " + summary.conflicts() + " conflict" + (summary.conflicts() > 1 ? "s" : "")).yellow().bold());
        }
        if (summary.blocked() > 0) {
            badges.add(Toolkit.text(" · "));
            badges.add(Toolkit.text("✖ " + summary.blocked() + " blocked").red().bold());
        }
        if (summary.warnings() > 0) {
            badges.add(Toolkit.text(" · "));
            badges.add(Toolkit.text("! " + summary.warnings() + " warning" + (summary.warnings() > 1 ? "s" : "")).yellow());
        }
        if (summary.destructive() > 0) {
            badges.add(Toolkit.text(" · "));
            badges.add(Toolkit.text("⚠ " + summary.destructive() + " destructive").red().bold());
        }

        return Toolkit.row(badges.toArray(new Element[0]));
    }

    private static ListElement<?> renderRelocationList(
            PlanModel.Configured model,
            List<PlanRelocationItem> visibleItems,
            int selectedIndex,
            boolean showInSync,
            PaneFocus paneFocus,
            Color borderColor
    ) {
        var listElement = new ListElement<>()
                .title("Plan")
                .borderColor(borderColor)
                .scrollbar(ScrollBarPolicy.AS_NEEDED)
                .scrollbarThumbColor(Color.CYAN)
                .scrollbarTrackColor(Color.DARK_GRAY)
                .highlightSymbol("")
                .highlightStyle(Style.EMPTY)
                .autoScroll();

        if (visibleItems.isEmpty()) {
            int inSyncCount = model.summary().inSync() + model.summary().skipped();
            listElement.add(Toolkit.text("  ▶ " + inSyncCount + " in sync items hidden (press 'c' to reveal)").gray());
            return listElement;
        }

        for (int i = 0; i < visibleItems.size(); i++) {
            var item = visibleItems.get(i);
            boolean isSelected = (i == selectedIndex);
            var prefix = isSelected ? "❯ " : "  ";
            var badgeText = "[" + item.badge().label() + "]";
            var badgeColor = colorForBadge(item.badge());

            var pathElement = Toolkit.text(formatListPath(item.relocation().sourcePath()));
            if (isSelected) {
                pathElement = pathElement.bold();
            }

            var prefixElement = Toolkit.text(prefix);
            if (isSelected) {
                if (paneFocus == PaneFocus.MASTER) {
                    prefixElement = prefixElement.cyan().bold();
                } else {
                    prefixElement = prefixElement.gray();
                }
            }

            var badgeElement = Toolkit.text(badgeText + " ").fg(badgeColor);
            if (isSelected) {
                badgeElement = badgeElement.bold();
            }

            var itemRow = Toolkit.row(prefixElement, badgeElement, pathElement);
            listElement.add(itemRow);
        }

        if (!showInSync && model.summary().inSync() > 0) {
            int hiddenCount = model.summary().inSync();
            listElement.add(Toolkit.text("  ▶ " + hiddenCount + " in sync items hidden (press 'c' to reveal)").gray().dim());
        }

        listElement.selected(selectedIndex);
        return listElement;
    }

    private static dev.tamboui.toolkit.elements.Panel renderRelocationDetails(
            PlanRelocationItem item,
            PaneFocus paneFocus,
            int detailSelectedIndex,
            Color borderColor
    ) {
        var details = new ArrayList<Element>();

        // Paths & Status
        details.add(Toolkit.text("Relocation:").cyan().bold());
        details.add(Toolkit.text("  Source: " + item.relocation().sourcePath()));
        details.add(Toolkit.text("  Target: " + item.relocation().targetPath()));
        details.add(Toolkit.text("  Action: " + item.badge().label())
                .fg(colorForBadge(item.badge())).bold());
        details.add(Toolkit.text(""));

        // Dry-run actions
        details.add(Toolkit.text("Actions (dry-run):").cyan().bold());
        if (item.plan().actions().isEmpty()) {
            details.add(Toolkit.text("  (no actions planned)").gray());
        } else {
            for (var action : item.plan().actions()) {
                var desc = describeAction(action);
                if (action.destructive()) {
                    desc += "  [DESTRUCTIVE]";
                }
                if (action instanceof ReconciliationAction.Blocked) {
                    desc += "  [BLOCKED]";
                }
                var actionElement = Toolkit.text("  • " + desc);
                if (action.destructive()) {
                    actionElement = actionElement.red().bold();
                } else if (action instanceof ReconciliationAction.Blocked) {
                    actionElement = actionElement.red().bold();
                }
                details.add(actionElement);
            }
        }

        // Destructive safety warning
        if (item.hasDestructiveActions()) {
            details.add(Toolkit.text(""));
            details.add(Toolkit.text("⚠ Safety Warning: This relocation includes destructive actions.").red().bold());
        }

        // Diagnostics / Warnings
        if (!item.plan().diagnostics().isEmpty()) {
            details.add(Toolkit.text(""));
            details.add(Toolkit.text("Diagnostics:").yellow().bold());
            for (var diagnostic : item.plan().diagnostics()) {
                var isError = diagnostic.severity() == ReconciliationDiagnostic.Severity.ERROR;
                var prefix = isError ? "  ✖ " : "  ⚠ ";
                var diagElement = Toolkit.text(prefix + diagnostic.message());
                if (isError) {
                    diagElement = diagElement.red().bold();
                } else {
                    diagElement = diagElement.yellow();
                }
                details.add(diagElement);
            }
        }

        // Conflict & Available Resolutions
        if (item.hasConflict() || !item.availableResolutions().isEmpty()) {
            if (item.hasConflict()) {
                details.add(Toolkit.text(""));
                details.add(Toolkit.text("Conflict:").yellow().bold());
                if (item.plan().conflict().isPresent()) {
                    var conflict = item.plan().conflict().get();
                    var wrappedReason = wrapText(conflict.reason(), 45);
                    if (!wrappedReason.isEmpty()) {
                        details.add(Toolkit.text("  Reason: " + wrappedReason.getFirst()).yellow());
                        for (int r = 1; r < wrappedReason.size(); r++) {
                            details.add(Toolkit.text("          " + wrappedReason.get(r)).yellow());
                        }
                    }
                }
            }

            if (!item.availableResolutions().isEmpty()) {
                details.add(Toolkit.text(""));
                details.add(Toolkit.text("Available Resolutions:").cyan().bold());
                var chosen = item.selectedResolution().orElse(null);
                for (int i = 0; i < item.availableResolutions().size(); i++) {
                    var resolution = item.availableResolutions().get(i);
                    boolean isChosen = (chosen == resolution);
                    var radio = isChosen ? "(●)" : "(○)";
                    boolean isCursor = (paneFocus == PaneFocus.DETAIL && i == detailSelectedIndex);

                    var prefixText = isCursor ? "  ❯ " : "    ";
                    var prefixElem = Toolkit.text(prefixText);
                    if (isCursor) {
                        prefixElem = prefixElem.cyan().bold();
                    }

                    var radioElem = Toolkit.text(radio + " ");
                    if (isChosen) {
                        radioElem = radioElem.green().bold();
                    } else if (isCursor) {
                        radioElem = radioElem.bold();
                    } else {
                        radioElem = radioElem.gray();
                    }

                    var labelElem = Toolkit.text(resolution.label());
                    if (isCursor) {
                        labelElem = labelElem.bold();
                    }

                    var row = Toolkit.row(prefixElem, radioElem, labelElem);
                    details.add(row);
                    var wrappedDesc = wrapText(resolution.description(), 45);
                    for (var line : wrappedDesc) {
                        details.add(Toolkit.text("        " + line).gray());
                    }
                }
                if (paneFocus == PaneFocus.DETAIL) {
                    details.add(Toolkit.text("  (Press Space / Enter to select, ← / h to return to list)").gray().dim());
                } else {
                    details.add(Toolkit.text("  (Press → / l to choose resolution)").gray().dim());
                }
            }
        }

        return Toolkit.panel("Plan Details", Toolkit.column(details.toArray(new Element[0])))
                .borderColor(borderColor);
    }

    private static String describeAction(ReconciliationAction action) {
        return switch (action) {
            case ReconciliationAction.CreateDirectory cd -> "Create directory " + abbreviateHome(cd.path());
            case ReconciliationAction.EnsureDirectory ed -> "Ensure directory " + abbreviateHome(ed.path());
            case ReconciliationAction.CopyDirectory cp -> "Copy directory " + abbreviateHome(cp.path()) + " to " + abbreviateHome(cp.target());
            case ReconciliationAction.MigrateDirectoryForPublication st -> "Migrate " + abbreviateHome(st.path()) + " -> " + abbreviateHome(st.target()) + " (atomic publication)";
            case ReconciliationAction.ArchiveDirectory ar -> "Archive " + abbreviateHome(ar.path()) + " -> " + abbreviateHome(ar.target());
            case ReconciliationAction.DeleteDirectory dd -> "Delete directory " + abbreviateHome(dd.path());
            case ReconciliationAction.CreateSymlink cs -> "Create symlink " + abbreviateHome(cs.path()) + " -> " + abbreviateHome(cs.target());
            case ReconciliationAction.ReplaceDirectoryWithSymlink rd -> "Replace directory with symlink " + abbreviateHome(rd.path()) + " -> " + abbreviateHome(rd.target());
            case ReconciliationAction.ReplaceSymlink rs -> "Replace symlink " + abbreviateHome(rs.path()) + " -> " + abbreviateHome(rs.target());
            case ReconciliationAction.NoOp no -> "No operation (in sync) on " + abbreviateHome(no.path());
            case ReconciliationAction.LeaveUnchanged lu -> "Leave unmanaged at " + abbreviateHome(lu.path());
            case ReconciliationAction.Blocked b -> "Blocked: " + b.reason();
        };
    }

    public static Color colorForBadge(PlanBadge badge) {
        return switch (badge) {
            case IN_SYNC -> Color.GREEN;
            case MIGRATE, ADOPT, LINK, BACKUP, DISCARD -> Color.CYAN;
            case CONFLICT, WARNING -> Color.YELLOW;
            case BLOCKED, INACCESSIBLE -> Color.RED;
            case SKIPPED -> Color.DARK_GRAY;
        };
    }

    private static Element renderFooter(String shortcutText) {
        return Toolkit.row(Toolkit.text(shortcutText).gray().dim());
    }

    private static String abbreviateHome(Path path) {
        if (path == null) {
            return "";
        }
        return abbreviateHome(path.toString());
    }

    private static String abbreviateHome(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        var home = System.getProperty("user.home");
        if (home != null && !home.isBlank() && path.startsWith(home)) {
            return "~" + path.substring(home.length());
        }
        return path;
    }

    private static String formatListPath(Path path) {
        var abbreviated = abbreviateHome(path);
        return formatListPath(abbreviated, 35);
    }

    private static String formatListPath(String path, int maxLength) {
        if (path.length() <= maxLength) {
            return path;
        }
        int keep = (maxLength - 3) / 2;
        return path.substring(0, keep) + "..." + path.substring(path.length() - keep);
    }

    static List<String> wrapText(String text, int maxLineLength) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        var lines = new ArrayList<String>();
        var words = text.split("\\s+");
        var currentLine = new StringBuilder();
        for (var word : words) {
            if (currentLine.isEmpty()) {
                currentLine.append(word);
            } else if (currentLine.length() + 1 + word.length() <= maxLineLength) {
                currentLine.append(" ").append(word);
            } else {
                lines.add(currentLine.toString());
                currentLine = new StringBuilder(word);
            }
        }
        if (!currentLine.isEmpty()) {
            lines.add(currentLine.toString());
        }
        return lines;
    }
}
