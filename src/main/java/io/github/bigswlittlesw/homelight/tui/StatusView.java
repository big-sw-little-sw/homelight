package io.github.bigswlittlesw.homelight.tui;

import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.toolkit.Toolkit;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.elements.ListElement;
import dev.tamboui.widgets.common.ScrollBarPolicy;
import io.github.bigswlittlesw.homelight.application.RelocationStatusItem;
import io.github.bigswlittlesw.homelight.application.StatusModel;
import io.github.bigswlittlesw.homelight.fs.PathObservation;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationDiagnostic;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// Declarative element builder for the HomeLight status view.
public final class StatusView {

    public static List<RelocationStatusItem> visibleItems(StatusModel.Configured model, boolean showInSync) {
        if (showInSync) {
            return model.items();
        }
        return model.items().stream()
                .filter(item -> item.badge() != RelocationStatusItem.StatusBadge.IN_SYNC)
                .toList();
    }

    public static Element render(StatusModel model, int selectedIndex) {
        return render(model, selectedIndex, true);
    }

    public static Element render(StatusModel model, int selectedIndex, boolean showInSync) {
        return switch (model) {
            case StatusModel.Unconfigured unconfigured -> renderUnconfigured(unconfigured);
            case StatusModel.Invalid invalid -> renderInvalid(invalid);
            case StatusModel.Configured configured -> renderConfigured(configured, selectedIndex, showInSync);
        };
    }

    private static Element renderUnconfigured(StatusModel.Unconfigured model) {
        return Toolkit.column(
                renderHeader("Status", abbreviateHome(model.configPath()), null),
                Toolkit.text(""),
                Toolkit.panel("HomeLight Not Configured",
                        Toolkit.column(
                                Toolkit.text("No paths are currently managed.").yellow().bold(),
                                Toolkit.text(""),
                                Toolkit.text("To get started with HomeLight:"),
                                Toolkit.text("  1. Run ./homelight init to create a configuration file").cyan(),
                                Toolkit.text("  2. Add relocations to " + abbreviateHome(model.configPath())),
                                Toolkit.text("  3. Run ./homelight plan or ./homelight apply"),
                                Toolkit.text("")
                        )
                ).fill(),
                Toolkit.text(""),
                renderFooter("q: Quit  ·  r: Refresh")
        );
    }

    private static Element renderInvalid(StatusModel.Invalid model) {
        return Toolkit.column(
                renderHeader("Status (Configuration Error)", abbreviateHome(model.configPath()), null),
                Toolkit.text(""),
                Toolkit.panel("Error",
                        Toolkit.column(
                                Toolkit.text("Failed to load configuration:").red().bold(),
                                Toolkit.text("  Path: " + model.configPath()),
                                Toolkit.text(""),
                                Toolkit.text("Details:").yellow(),
                                Toolkit.text("  " + model.message()),
                                Toolkit.text("")
                        )
                ).fill(),
                Toolkit.text(""),
                renderFooter("q: Quit  ·  r: Refresh")
        );
    }

    private static Element renderConfigured(StatusModel.Configured model, int selectedIndex, boolean showInSync) {
        var header = renderHeader("Status", abbreviateHome(model.configPath()), abbreviateHome(model.targetRoot()));
        var summaryBar = renderSummaryBar(model);

        if (model.items().isEmpty()) {
            return Toolkit.column(
                    header,
                    Toolkit.text(""),
                    summaryBar,
                    Toolkit.text(""),
                    Toolkit.panel("Relocations", Toolkit.text("No relocations defined in configuration.").gray()).fill(),
                    Toolkit.text(""),
                    renderFooter("q: Quit  ·  r: Refresh")
            );
        }

        var visibleItems = visibleItems(model, showInSync);
        Element listPanel;
        Element detailsPanel;

        if (visibleItems.isEmpty()) {
            listPanel = renderRelocationList(model, visibleItems, 0, showInSync).percent(45).fill();
            detailsPanel = Toolkit.panel("Details",
                    Toolkit.column(
                            Toolkit.text("All relocations are in sync.").green().bold(),
                            Toolkit.text(""),
                            Toolkit.text("Press 'c' or Space to view in sync relocations.").gray()
                    )
            ).borderColor(Color.DARK_GRAY).fill();
        } else {
            int clampedIndex = Math.clamp(selectedIndex, 0, visibleItems.size() - 1);
            var selectedItem = visibleItems.get(clampedIndex);

            listPanel = renderRelocationList(model, visibleItems, clampedIndex, showInSync).percent(45).fill();
            detailsPanel = renderRelocationDetails(selectedItem).fill();
        }

        var mainContent = Toolkit.row(listPanel, detailsPanel).fill();

        return Toolkit.column(
                header,
                Toolkit.text(""),
                summaryBar,
                Toolkit.text(""),
                mainContent,
                Toolkit.text(""),
                renderFooter("↑/↓/j/k: Select  ·  c: Toggle In Sync  ·  r: Refresh  ·  2: Plan  ·  q: Quit")
        );
    }

    private static Element renderHeader(String activeScreen, String configPath, String targetRoot) {
        var headerElements = new ArrayList<Element>();

        var brandElement = Toolkit.text("⌂ HOMELIGHT  ").cyan().bold();
        var tab1 = activeScreen.contains("Error")
                ? Toolkit.text("[1: Status (Error)]").red().bold()
                : Toolkit.text("[1: Status]").cyan().bold();
        var tab2 = Toolkit.text("  [2: Plan]").gray().dim();
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

    private static Element renderSummaryBar(StatusModel.Configured model) {
        var summary = model.summary();
        var badges = new ArrayList<Element>();

        badges.add(Toolkit.text(summary.total() + (summary.total() == 1 ? " relocation" : " relocations")).bold());

        if (summary.inSync() > 0) {
            badges.add(Toolkit.text(" · "));
            badges.add(Toolkit.text("✔ " + summary.inSync() + " in sync").green());
        }
        if (summary.pending() > 0) {
            badges.add(Toolkit.text(" · "));
            badges.add(Toolkit.text("⚡ " + summary.pending() + " pending").cyan());
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
        if (summary.inaccessible() > 0) {
            badges.add(Toolkit.text(" · "));
            badges.add(Toolkit.text("✖ " + summary.inaccessible() + " inaccessible").red());
        }

        return Toolkit.row(badges.toArray(new Element[0]));
    }

    private static ListElement<?> renderRelocationList(
            StatusModel.Configured model,
            List<RelocationStatusItem> visibleItems,
            int selectedIndex,
            boolean showInSync
    ) {
        var listElement = new ListElement<>()
                .title("Relocations")
                .borderColor(Color.CYAN)
                .scrollbar(ScrollBarPolicy.AS_NEEDED)
                .scrollbarThumbColor(Color.CYAN)
                .scrollbarTrackColor(Color.DARK_GRAY)
                .highlightSymbol("")
                .highlightStyle(Style.EMPTY)
                .autoScroll();

        if (visibleItems.isEmpty()) {
            int inSyncCount = model.summary().inSync();
            listElement.add(Toolkit.text("  ▶ " + inSyncCount + " in sync item" + (inSyncCount == 1 ? "" : "s") + " hidden (press 'c' to reveal)").gray());
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
                prefixElement = prefixElement.cyan().bold();
            }

            var badgeElement = Toolkit.text(badgeText + " ").fg(badgeColor);
            if (isSelected) {
                badgeElement = badgeElement.bold();
            }

            var itemRow = Toolkit.row(prefixElement, badgeElement, pathElement);
            listElement.add(itemRow);
        }

        if (!showInSync && model.summary().inSync() > 0) {
            int inSyncCount = model.summary().inSync();
            listElement.add(Toolkit.text("  ▶ " + inSyncCount + " in sync item" + (inSyncCount == 1 ? "" : "s") + " hidden (press 'c' to reveal)").gray().dim());
        }

        listElement.selected(selectedIndex);
        return listElement;
    }

    private static dev.tamboui.toolkit.elements.Panel renderRelocationDetails(RelocationStatusItem item) {
        var details = new ArrayList<Element>();

        // Source details
        details.add(Toolkit.text("Source:").cyan().bold());
        details.add(Toolkit.text("  Path: " + item.relocation().sourcePath()));
        details.add(Toolkit.text("  State: " + describeObservation(item.sourceObservation())));
        details.add(Toolkit.text(""));

        // Target details
        details.add(Toolkit.text("Target:").cyan().bold());
        details.add(Toolkit.text("  Path: " + item.relocation().targetPath()));
        details.add(Toolkit.text("  State: " + describeObservation(item.targetObservation())));
        details.add(Toolkit.text(""));

        // Policies
        details.add(Toolkit.text("Configuration:").gray().bold());
        boolean hasCustomPolicy = false;
        if (item.relocation().whenOnlyTargetExists().isPresent()) {
            hasCustomPolicy = true;
            details.add(Toolkit.text("  when-only-target-exists: " + item.relocation().whenOnlyTargetExists().get().value()));
        }
        if (item.relocation().whenSourceAndTargetDirectoriesExist().isPresent()) {
            hasCustomPolicy = true;
            details.add(Toolkit.text("  when-source-and-target-directories-exist: " + item.relocation().whenSourceAndTargetDirectoriesExist().get().value()));
        }
        if (item.relocation().whenAdoptingTarget().isPresent()) {
            hasCustomPolicy = true;
            details.add(Toolkit.text("  when-adopting-target: " + item.relocation().whenAdoptingTarget().get().value()));
        }
        if (item.relocation().sourceArchiveRoot().isPresent()) {
            hasCustomPolicy = true;
            details.add(Toolkit.text("  source-archive-root: " + item.relocation().sourceArchiveRoot().get()));
        }
        if (!hasCustomPolicy) {
            details.add(Toolkit.text("  Policy: Default").gray());
        }
        details.add(Toolkit.text(""));

        // Planned outcome & actions
        details.add(Toolkit.text("Reconciliation:").cyan().bold());
        details.add(Toolkit.text("  Outcome: " + item.plan().outcome().name())
                .fg(colorForBadge(item.badge())).bold());

        if (!item.plan().actions().isEmpty()) {
            details.add(Toolkit.text("  Actions:"));
            for (var action : item.plan().actions()) {
                var actionDesc = describeAction(action);
                var actionElement = Toolkit.text("    • " + actionDesc);
                if (action instanceof ReconciliationAction.Blocked) {
                    actionElement = actionElement.red();
                }
                details.add(actionElement);
            }
        }

        // Diagnostics / Conflicts
        if (item.plan().conflict().isPresent()) {
            details.add(Toolkit.text(""));
            var conflict = item.plan().conflict().get();
            details.add(Toolkit.text("Conflict:").yellow().bold());
            details.add(Toolkit.text("  Path: " + conflict.path()).yellow());
            details.add(Toolkit.text("  Reason: " + conflict.reason()).yellow());
        }

        if (!item.plan().diagnostics().isEmpty()) {
            details.add(Toolkit.text(""));
            details.add(Toolkit.text("Diagnostics:").yellow().bold());
            for (var diagnostic : item.plan().diagnostics()) {
                var diagElement = Toolkit.text("  [" + diagnostic.code() + "] " + diagnostic.message());
                if (diagnostic.severity() == ReconciliationDiagnostic.Severity.ERROR) {
                    diagElement = diagElement.red();
                } else {
                    diagElement = diagElement.yellow();
                }
                details.add(diagElement);
            }
        }

        return Toolkit.panel("Details", Toolkit.column(details.toArray(new Element[0])))
                .borderColor(Color.DARK_GRAY);
    }

    private static Element renderFooter(String helpText) {
        return Toolkit.text(helpText).gray();
    }

    private static Color colorForBadge(RelocationStatusItem.StatusBadge badge) {
        return switch (badge) {
            case IN_SYNC -> Color.GREEN;
            case PENDING -> Color.CYAN;
            case CONFLICT -> Color.YELLOW;
            case BLOCKED -> Color.RED;
            case WARNING -> Color.YELLOW;
            case INACCESSIBLE -> Color.RED;
            case SKIPPED -> Color.MAGENTA;
        };
    }

    private static String describeObservation(PathObservation observation) {
        return switch (observation.state()) {
            case ABSENT -> "Absent";
            case FILE -> "File";
            case DIRECTORY -> "Directory" + (observation.emptyDirectory() ? " (empty)" : " (contains files)");
            case SYMLINK -> "Symlink → " + observation.symlinkTarget().map(Path::toString).orElse("(unresolved)")
                    + " [target " + observation.symlinkTargetAvailability().name().toLowerCase() + "]";
            case INACCESSIBLE -> "Inaccessible";
            case OTHER -> "Other / Special";
        };
    }

    private static String describeAction(ReconciliationAction action) {
        return switch (action) {
            case ReconciliationAction.NoOp noOp -> "No operation needed for " + abbreviateHome(noOp.path());
            case ReconciliationAction.CreateDirectory createDir -> "Create directory " + abbreviateHome(createDir.path());
            case ReconciliationAction.EnsureDirectory ensureDir -> "Ensure directory " + abbreviateHome(ensureDir.path());
            case ReconciliationAction.CreateSymlink createSymlink -> "Create symlink " + abbreviateHome(createSymlink.path()) + " → " + abbreviateHome(createSymlink.target());
            case ReconciliationAction.ReplaceSymlink replaceSymlink -> "Replace symlink " + abbreviateHome(replaceSymlink.path()) + " → " + abbreviateHome(replaceSymlink.target());
            case ReconciliationAction.ReplaceDirectoryWithSymlink replaceDir -> "Replace directory " + abbreviateHome(replaceDir.path()) + " with symlink → " + abbreviateHome(replaceDir.target());
            case ReconciliationAction.DeleteDirectory deleteDir -> "Delete directory " + abbreviateHome(deleteDir.path());
            case ReconciliationAction.ArchiveDirectory archiveDir -> "Archive directory " + abbreviateHome(archiveDir.path()) + " to " + abbreviateHome(archiveDir.target());
            case ReconciliationAction.MigrateDirectoryForPublication migrateDir -> "Migrate directory " + abbreviateHome(migrateDir.path()) + " for publication";
            case ReconciliationAction.LeaveUnchanged leaveUnchanged -> "Leave unmanaged: " + abbreviateHome(leaveUnchanged.path());
            case ReconciliationAction.Blocked blocked -> "Blocked: " + blocked.reason();
            case ReconciliationAction.CopyDirectory copyDir -> "Copy directory " + abbreviateHome(copyDir.path()) + " to " + abbreviateHome(copyDir.target());
        };
    }

    public static String abbreviateHome(Path path) {
        if (path == null) {
            return "";
        }
        var userHome = System.getProperty("user.home");
        if (userHome == null || userHome.isBlank()) {
            return path.toString();
        }
        var normalizedPath = path.toAbsolutePath().normalize().toString();
        var normalizedHome = Path.of(userHome).toAbsolutePath().normalize().toString();
        if (normalizedPath.startsWith(normalizedHome)) {
            return "~" + normalizedPath.substring(normalizedHome.length());
        }
        return path.toString();
    }

    public static String formatListPath(Path path) {
        return formatListPath(path, 40);
    }

    public static String formatListPath(Path path, int maxLength) {
        var abbreviated = abbreviateHome(path);
        return truncateMiddle(abbreviated, maxLength);
    }

    public static String truncateMiddle(String text, int maxLength) {
        if (text == null || text.length() <= maxLength || maxLength <= 5) {
            return text;
        }
        int available = maxLength - 3;
        int prefixLen = available / 2;
        int suffixLen = available - prefixLen;
        return text.substring(0, prefixLen) + "..." + text.substring(text.length() - suffixLen);
    }
}
