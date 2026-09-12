package io.github.bigswlittlesw.homelight.cli;

import dev.tamboui.style.Color;
import dev.tamboui.toolkit.app.InlineApp;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.elements.TextElement;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.InlineTuiConfig;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationConflict;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.panel;
import static dev.tamboui.toolkit.Toolkit.text;

/// Lets an interactive apply resolve existing-content conflicts without changing its configuration file.
final class ConflictResolutionPrompt {
    private ConflictResolutionPrompt() {
    }

    static boolean supports(ReconciliationPlan plan) {
        return plan.relocations().stream()
                .flatMap(relocation -> relocation.conflict().stream())
                .allMatch(conflict -> conflict.resolutions().contains(
                        ReconciliationConflict.Resolution.RESOLVE_EXISTING_CONTENT));
    }

    static Optional<Map<String, String>> resolve(ReconciliationPlan plan, boolean noColor) {
        var app = new ResolverApp(plan, noColor);
        var thread = Thread.ofVirtual().name("homelight-conflict-resolution").start(app::runApplication);
        try {
            thread.join();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        return app.selection();
    }

    private record Choice(int relocationIndex, Map<String, String> overrides, String label,
                          String conflict, String paths) {
    }

    private static final class ResolverApp extends InlineApp {
        private final boolean noColor;
        private final List<Choice> choices;
        private final int conflicts;
        private final Map<Integer, Integer> selectedByRelocation = new HashMap<>();
        private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1,
                Thread.ofVirtual().name("homelight-conflict-ui-", 0).factory());
        private Map<String, String> selection;
        private int cursor;
        private String validationMessage;

        private ResolverApp(ReconciliationPlan plan, boolean noColor) {
            this.noColor = noColor;
            choices = choices(plan);
            conflicts = (int) plan.relocations().stream().filter(relocation -> relocation.conflict().isPresent()).count();
        }

        private void runApplication() {
            try {
                run();
            } catch (Exception ignored) {
                // Returning without a selection preserves the existing safe unresolved-plan behavior.
            }
        }

        private Optional<Map<String, String>> selection() {
            return Optional.ofNullable(selection);
        }

        @Override
        protected int height() {
            return choices.size() + conflicts * 2 + 5;
        }

        @Override
        protected InlineTuiConfig configure(int height) {
            return InlineTuiConfig.builder(height)
                    .clearOnClose(true)
                    .scheduler(scheduler)
                    .build();
        }

        @Override
        protected Element render() {
            var lines = new ArrayList<Element>();
            lines.add(text("Select one resolution for each item, then press Enter.").dim());
            lines.add(text("↑↓ navigate  Space select  Enter continue  q cancel").dim());
            var previousRelocation = -1;
            for (var index = 0; index < choices.size(); index++) {
                var choice = choices.get(index);
                if (choice.relocationIndex() != previousRelocation) {
                    lines.add(noColor ? text(choice.conflict()).bold() : text(choice.conflict()).yellow().bold());
                    lines.add(text(choice.paths()).dim().ellipsis());
                    previousRelocation = choice.relocationIndex();
                }
                lines.add(choiceLine(index, choice));
            }
            if (validationMessage != null) {
                lines.add(noColor ? text(validationMessage) : text(validationMessage).red().bold());
            }
            return panel("Resolve existing content", column(lines.toArray(Element[]::new))).rounded().padding(0)
                    .onKeyEvent(event -> handle(event));
        }

        private EventResult handle(dev.tamboui.tui.event.KeyEvent event) {
            if (event.isUp()) {
                cursor = Math.floorMod(cursor - 1, choices.size());
                return EventResult.HANDLED;
            }
            if (event.isDown()) {
                cursor = (cursor + 1) % choices.size();
                return EventResult.HANDLED;
            }
            if (event.isChar(' ')) {
                toggleCurrentChoice();
                return EventResult.HANDLED;
            }
            if (event.isConfirm()) {
                submit();
                return EventResult.HANDLED;
            }
            if (event.isChar('q')) {
                quit();
                return EventResult.HANDLED;
            }
            return EventResult.UNHANDLED;
        }

        private TextElement choiceLine(int index, Choice choice) {
            var selected = selectedByRelocation.get(choice.relocationIndex()) != null
                    && selectedByRelocation.get(choice.relocationIndex()) == index;
            var line = text((index == cursor ? "› " : "  ") + (selected ? "◉ " : "○ ") + choice.label());
            if (!noColor) {
                if (selected) {
                    line.cyan().bold();
                } else if (index == cursor) {
                    line.bold();
                } else {
                    line.dim();
                }
            }
            return line;
        }

        private void toggleCurrentChoice() {
            var relocationIndex = choices.get(cursor).relocationIndex();
            if (selectedByRelocation.get(relocationIndex) != null && selectedByRelocation.get(relocationIndex) == cursor) {
                selectedByRelocation.remove(relocationIndex);
            } else {
                selectedByRelocation.put(relocationIndex, cursor);
            }
            validationMessage = null;
        }

        private void submit() {
            if (selectedByRelocation.size() != conflicts) {
                validationMessage = "Select one resolution for every conflicted relocation.";
                return;
            }
            selection = overrides();
            quit();
        }

        private Map<String, String> overrides() {
            var overrides = new LinkedHashMap<String, String>();
            selectedByRelocation.forEach((relocationIndex, choiceIndex) -> choices.get(choiceIndex).overrides()
                    .forEach((setting, value) -> overrides.put(
                            "homelight.relocations[" + relocationIndex + "]." + setting, value)));
            return Map.copyOf(overrides);
        }

        @Override
        protected void onStop() {
            scheduler.shutdownNow();
        }

        private static List<Choice> choices(ReconciliationPlan plan) {
            var choices = new ArrayList<Choice>();
            for (var index = 0; index < plan.relocations().size(); index++) {
                var relocation = plan.relocations().get(index);
                if (relocation.conflict().isEmpty()) {
                    continue;
                }
                var configured = relocation.relocation();
                var conflict = relocation.conflict().orElseThrow();
                var context = configured.sourcePath().getFileName() + " → " + configured.targetPath().getFileName()
                        + ": " + conflict.reason();
                var paths = configured.sourcePath() + " → " + configured.targetPath();
                if (conflict.reason().contains("target directory requires")) {
                    choices.add(new Choice(index, Map.of("when-only-target-exists", "adopt-target"),
                            "Adopt the target and create the source link", context, paths));
                    continue;
                }
                choices.add(new Choice(index, Map.of(
                        "when-source-and-target-directories-exist", "adopt",
                        "when-adopting-target", "discard-source"),
                        "Adopt target and discard the source", context, paths));
                if (configured.sourceArchiveRoot().isPresent()) {
                    choices.add(new Choice(index, Map.of(
                            "when-source-and-target-directories-exist", "adopt",
                            "when-adopting-target", "archive-source"),
                            "Adopt target and archive the source", context, paths));
                }
                choices.add(new Choice(index, Map.of("when-source-and-target-directories-exist", "leave-unchanged"),
                        "Leave source and target unchanged", context, paths));
                choices.add(new Choice(index, Map.of("when-source-and-target-directories-exist", "discard"),
                        "Discard source and target contents, then create the link", context, paths));
            }
            return List.copyOf(choices);
        }
    }
}
