package io.github.bigswlittlesw.homelight.tui;

import dev.tamboui.style.Color;
import dev.tamboui.text.CharWidth;
import dev.tamboui.toolkit.Toolkit;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import io.github.bigswlittlesw.homelight.application.SetupDraft;
import io.github.bigswlittlesw.homelight.config.CandidateDefinition;
import io.github.bigswlittlesw.homelight.config.CandidateSource;
import io.github.bigswlittlesw.homelight.discovery.CandidateDiscovery;
import io.github.bigswlittlesw.homelight.discovery.CandidateObservation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/// App expansion, focused identity and draft membership are independent states.
final class CandidateBrowser {
    private sealed interface Item {
        record Group(Optional<String> app, int count) implements Item {}
        record Directory(Path path) implements Item {}
    }
    private final DetailViewport viewport = new DetailViewport();
    private final HashSet<Optional<String>> collapsed = new HashSet<>();
    private Item focus;
    private boolean details;
    private boolean diagnostics;
    private boolean reveal;
    private String message = "";

    Element render(SetupDraft draft) {
        var lines = new ArrayList<DetailViewport.Line>();
        int anchor = -1;
        String title;
        String navigation;
        String commands;
        if (diagnostics) {
            title = "Discovery sources";
            sourceDetails(lines, draft);
            navigation = "↑/↓: Scroll · Esc: Back";
            commands = "r: Refresh · q: Discard draft";
        } else if (details) {
            title = "Candidate details";
            var entry = focusedEntry(draft);
            if (entry.isEmpty()) {
                lines.add(new DetailViewport.Line("No longer in current discovery."));
                if (focus instanceof Item.Directory directory) lines.add(new DetailViewport.Line("Source: " + literal(directory.path().toString())));
                lines.add(new DetailViewport.Line("Existing draft rows are retained. Esc returns to the list."));
            } else detailLines(lines, entry.orElseThrow(), draft);
            navigation = "↑/↓: Scroll · Esc: Back";
            commands = entry.map(e -> action(e, draft)).orElse("") + "r: Refresh · q: Discard draft";
        } else {
            title = "Browse candidates";
            var unique = entriesByPath(draft);
            var items = items(draft, unique);
            if (focus == null && !items.isEmpty()) focus = items.getFirst();
            long configured = unique.values().stream().filter(e -> e.configured().isPresent()).count();
            long inDraft = unique.values().stream().filter(e -> e.configured().isEmpty() && e.draft().isPresent()).count();
            lines.add(new DetailViewport.Line(unique.size() + " candidates · " + inDraft + " in draft"
                    + (configured == 0 ? "" : " · " + configured + " configured"), Color.GRAY, false));
            if (draft.discovery().stream().flatMap(r -> r.sources().stream())
                    .anyMatch(s -> s.status() != CandidateDiscovery.SourceStatus.CURRENT)) {
                lines.add(new DetailViewport.Line(sourceSummary(draft), Color.YELLOW, false));
            }
            long hidden = hiddenCount(draft);
            if (hidden > 0) lines.add(new DetailViewport.Line(hidden + " usually-unnecessary " + (hidden == 1 ? "directory " : "directories ") + (reveal ? "revealed" : "hidden"), Color.YELLOW, true));
            if (items.isEmpty()) lines.add(new DetailViewport.Line("No candidates available yet. Esc returns to manual setup."));
            for (var item : items) {
                boolean selected = same(item, focus);
                if (selected) anchor = lines.size();
                String label = switch (item) {
                    case Item.Group group -> (collapsed.contains(group.app()) ? "▸ " : "▾ ") + group.app().orElse("Other directories") + " (" + group.count() + ")";
                    case Item.Directory directory -> {
                        var entry = unique.get(directory.path());
                        var path = compact(relative(draft, directory.path()));
                        var marker = entry.configured().isPresent() ? "[=]" : entry.draft().isPresent() ? "[x]" : canAdd(entry, draft) ? "[ ]" : " − ";
                        yield "  " + marker + " " + path + " ".repeat(Math.max(1, 32 - CharWidth.of(path))) + listNotes(entry, draft);
                    }
                };
                var color = selected ? Color.CYAN : item instanceof Item.Group ? Color.BLUE
                        : unique.get(((Item.Directory) item).path()).draft().isPresent() ? Color.GREEN : Color.GRAY;
                lines.add(new DetailViewport.Line((selected ? "❯ " : "  ") + literal(label), color, selected || item instanceof Item.Group));
            }
            boolean listedFocus = focus != null && items.stream().anyMatch(i -> same(i, focus));
            if (focus != null && !listedFocus) {
                anchor = lines.size();
                lines.add(new DetailViewport.Line(focus instanceof Item.Directory
                        ? "❯ Focused path is hidden or no longer listed."
                        : "❯ Focused app is no longer listed.", Color.CYAN, true));
            }
            navigation = (items.isEmpty() ? "" : "↑/↓: Move · ")
                    + focusedEntry(draft).map(e -> listAction(e, draft)).orElse("")
                    + (focus instanceof Item.Directory ? "Enter: Inspect · " : listedFocus ? "Enter: Expand/collapse · " : "") + "Esc: Back";
            commands = "r: Refresh · i: Sources" + (hidden > 0 ? " · u: " + (reveal ? "Hide " : "Show ") + hidden : "") + " · q: Discard";
        }
        if (!message.isEmpty() && details) lines.addFirst(new DetailViewport.Line(literal(message), Color.YELLOW, false));
        var reader = viewport.render(title, lines, true, anchor);
        var help = viewport.help(navigation, commands);
        return !message.isEmpty() && !details && !diagnostics
                ? Toolkit.column(reader, DetailViewport.text("Not added. Inspect the row for details; prior choices are unchanged.", Color.YELLOW), help).fill()
                : Toolkit.column(reader, help).fill();
    }

    int key(KeyEvent key, SetupDraft draft) {
        if (key.isChar('[') || key.isChar(']')) { viewport.scroll(key.isChar(']') ? 1 : -1); return -1; }
        if (!diagnostics) {
            var entry = focusedEntry(draft);
            if (key.isCharIgnoreCase('e') && entry.filter(e -> e.configured().isEmpty() && e.draft().isPresent()).isPresent()) {
                var value = entry.orElseThrow().draft().orElseThrow();
                for (int i = 0; i < draft.rows().size(); i++) if (draft.rows().get(i) == value) return i;
            }
            if ((key.isCharIgnoreCase('a') || key.isChar(' ')) && entry.filter(e -> canAdd(e, draft)).isPresent()) {
                try { draft.add(entry.orElseThrow().sourcePath().orElseThrow()); message = ""; }
                catch (IllegalArgumentException error) { message = "Not added. " + error.getMessage() + ". Prior choices are unchanged."; }
                if (details) viewport.reset(); else viewport.keepChoiceVisible();
                return -1;
            }
        }
        if (details || diagnostics) {
            if (key.isUp() || key.isCharIgnoreCase('k')) viewport.scroll(-1);
            else if (key.isDown() || key.isCharIgnoreCase('j')) viewport.scroll(1);
            else if (key.isHome()) viewport.scroll(-Integer.MAX_VALUE);
            else if (key.isEnd()) viewport.scroll(Integer.MAX_VALUE);
            return -1;
        }
        if (key.isCharIgnoreCase('i')) { diagnostics = true; viewport.reset(); message = ""; }
        else if (key.isCharIgnoreCase('u') && hiddenCount(draft) > 0) { reveal = !reveal; viewport.reset(); }
        else if (key.isKey(KeyCode.ENTER)) {
            if (focus instanceof Item.Group group) {
                if (!collapsed.remove(group.app())) collapsed.add(group.app());
            } else if (focus instanceof Item.Directory) { details = true; viewport.reset(); }
        } else if (key.isUp() || key.isCharIgnoreCase('k') || key.isDown() || key.isCharIgnoreCase('j') || key.isHome() || key.isEnd()) {
            var items = items(draft);
            if (!items.isEmpty()) {
                int index = -1;
                for (int i = 0; i < items.size(); i++) if (same(items.get(i), focus)) index = i;
                int next = key.isHome() ? 0 : key.isEnd() ? items.size() - 1 : Math.clamp(index + (key.isUp() || key.isCharIgnoreCase('k') ? -1 : 1), 0, items.size() - 1);
                focus = items.get(next); message = ""; viewport.keepChoiceVisible();
            }
        }
        return -1;
    }

    boolean back() {
        if (!details && !diagnostics) return false;
        details = false; diagnostics = false; message = ""; viewport.reset(); viewport.keepChoiceVisible(); return true;
    }

    private List<Item> items(SetupDraft draft) {
        return items(draft, entriesByPath(draft));
    }

    private static Map<Path, SetupDraft.Entry> entriesByPath(SetupDraft draft) {
        var entries = new LinkedHashMap<Path, SetupDraft.Entry>();
        draft.entries().forEach(e -> e.sourcePath().ifPresent(path -> entries.putIfAbsent(path, e)));
        // Membership changes must not reorder the list while marking adjacent rows.
        var ordered = new LinkedHashMap<Path, SetupDraft.Entry>();
        draft.discovery().ifPresent(r -> r.candidates().forEach(c -> {
            var path = c.catalog().sourcePath();
            if (entries.containsKey(path)) ordered.put(path, entries.get(path));
        }));
        entries.forEach(ordered::putIfAbsent);
        return ordered;
    }

    private List<Item> items(SetupDraft draft, Map<Path, SetupDraft.Entry> entriesByPath) {
        var groups = new LinkedHashMap<Optional<String>, List<SetupDraft.Entry>>();
        for (var entry : entriesByPath.values()) {
            if (!reveal && hidden(entry, draft)) continue;
            var app = definitions(entry).stream().map(CandidateDefinition::app).flatMap(Optional::stream).findFirst();
            groups.computeIfAbsent(app, _ -> new ArrayList<>()).add(entry);
        }
        var result = new ArrayList<Item>();
        groups.forEach((app, entries) -> {
            result.add(new Item.Group(app, entries.size()));
            for (var entry : entries) if (!collapsed.contains(app) || member(entry)) result.add(new Item.Directory(entry.sourcePath().orElseThrow()));
        });
        return result;
    }

    private static boolean same(Item left, Item right) {
        return switch (left) {
            case Item.Group group -> right instanceof Item.Group other && group.app().equals(other.app());
            case Item.Directory directory -> right instanceof Item.Directory other && directory.path().equals(other.path());
        };
    }
    private Optional<SetupDraft.Entry> focusedEntry(SetupDraft draft) {
        return focus instanceof Item.Directory directory ? entry(draft, directory.path()) : Optional.empty();
    }
    private static Optional<SetupDraft.Entry> entry(SetupDraft draft, Path path) {
        return draft.entries().stream().filter(e -> e.sourcePath().filter(path::equals).isPresent()).findFirst();
    }
    private static List<CandidateDefinition> definitions(SetupDraft.Entry entry) {
        return entry.discovery().map(c -> c.catalog().definitions()).orElse(List.of());
    }
    private static boolean member(SetupDraft.Entry entry) { return entry.configured().isPresent() || entry.draft().isPresent(); }
    private static String membership(SetupDraft.Entry entry) {
        return entry.configured().isPresent() ? "Configured" : entry.draft().isPresent() ? "In draft" : "Not added";
    }
    private static boolean hidden(SetupDraft.Entry entry, SetupDraft draft) {
        var definitions = definitions(entry);
        return !member(entry) && !definitions.isEmpty() && definitions.stream().allMatch(d ->
                d.advice().equals(Optional.of(CandidateDefinition.Advice.USUALLY_UNNECESSARY))
                && draft.discovery().stream().flatMap(r -> r.sources().stream()).anyMatch(s -> s.source().equals(d.source())
                && s.status() == CandidateDiscovery.SourceStatus.CURRENT));
    }
    private static long hiddenCount(SetupDraft draft) { return draft.entries().stream().filter(e -> hidden(e, draft)).map(SetupDraft.Entry::sourcePath).distinct().count(); }
    private static String relative(SetupDraft draft, Path path) { return path.startsWith(draft.sourceRoot()) ? draft.sourceRoot().relativize(path).toString() : path.toString(); }
    private static String compact(String path) {
        var value = literal(path);
        return value.length() <= 30 ? value : value.substring(0, 14) + "…" + value.substring(value.length() - 15);
    }
    private static boolean canAdd(SetupDraft.Entry entry, SetupDraft draft) {
        return draft.canAdd(entry);
    }
    private static String action(SetupDraft.Entry entry, SetupDraft draft) {
        if (entry.configured().isPresent()) return "";
        if (entry.draft().isPresent()) return "e: Edit draft row · ";
        return canAdd(entry, draft) ? "a: Add to draft · " : "";
    }
    private static String listAction(SetupDraft.Entry entry, SetupDraft draft) {
        if (entry.configured().isPresent()) return "";
        if (entry.draft().isPresent()) return "e: Edit · ";
        return canAdd(entry, draft) ? "Space/a: Add · " : "";
    }
    private static String listNotes(SetupDraft.Entry entry, SetupDraft draft) {
        var notes = new ArrayList<String>();
        if (entry.configured().isPresent()) notes.add("Configured");
        var ordinary = entry.discovery().filter(c -> c.observation().kind() == CandidateObservation.Kind.DIRECTORY
                || c.observation().kind() == CandidateObservation.Kind.MISSING);
        if (ordinary.isEmpty()) notes.add(state(entry, draft));
        else if (draft.discovery().filter(r -> r.generation() != ordinary.orElseThrow().observation().generation()).isPresent()) {
            notes.add("Earlier observation");
        }
        var advice = adviceSummary(entry);
        if (advice.equals(" · Mixed advice") || advice.equals(" · Usually unnecessary")) notes.add(advice.substring(3));
        return String.join(" · ", notes);
    }
    private static String adviceSummary(SetupDraft.Entry entry) {
        var values = definitions(entry).stream().map(CandidateDefinition::advice).distinct().toList();
        if (values.stream().flatMap(Optional::stream).distinct().count() > 1) return " · Mixed advice";
        if (values.size() > 1) return " · " + advice(values.stream().filter(Optional::isPresent).findFirst().orElseThrow()) + "; some advice omitted";
        return values.isEmpty() || values.getFirst().isEmpty() ? "" : " · " + advice(values.getFirst());
    }
    private static String advice(Optional<CandidateDefinition.Advice> advice) {
        return advice.map(a -> switch (a) { case CONSIDER -> "Consider"; case USUALLY_UNNECESSARY -> "Usually unnecessary"; }).orElse("Not supplied");
    }
    private static String state(SetupDraft.Entry entry, SetupDraft draft) {
        return entry.discovery().map(c -> kind(c.observation().kind()) + (draft.discovery()
                .filter(r -> r.generation() != c.observation().generation()).isPresent() ? " (earlier observation)" : "")).orElse("Not observed");
    }
    private static String kind(CandidateObservation.Kind kind) {
        return switch (kind) {
            case PENDING -> "Pending";
            case DIRECTORY -> "Directory";
            case LINK -> "Symbolic link";
            case MISSING -> "Not created yet";
            case REGULAR_FILE -> "Regular file";
            case OTHER -> "Other file type";
            case INACCESSIBLE -> "Inaccessible";
            case BLOCKED_BY_LINK -> "Blocked by parent link";
            case BLOCKED_BY_NON_DIRECTORY -> "Parent is not a directory";
            case UNKNOWN -> "Unknown";
        };
    }

    private static void detailLines(List<DetailViewport.Line> lines, SetupDraft.Entry entry, SetupDraft draft) {
        lines.add(new DetailViewport.Line(membership(entry) + (entry.outsideRoot() ? " · Outside this source root" : ""), Color.CYAN, true));
        lines.add(new DetailViewport.Line("Source: " + literal(entry.sourcePath().orElseThrow().toString())));
        entry.configured().ifPresent(r -> {
            lines.add(new DetailViewport.Line("Saved target: " + literal(r.targetPath().toString())));
            lines.add(new DetailViewport.Line("Saved policies: both directories: " + r.whenSourceAndTargetDirectoriesExist().map(v -> switch (v) {
                        case DISCARD -> "Discard both";
                        case PROMPT, ADOPT, LEAVE_UNCHANGED -> v.value().replace('-', ' ');
                    }).orElse("Default (prompt)")
                    + "; only target: " + r.whenOnlyTargetExists().map(v -> v.value().replace('-', ' ')).orElse("Default (prompt)")
                    + "; adopt target: " + r.whenAdoptingTarget().map(v -> v.value().replace('-', ' ')).orElse("Default (prompt)")));
            r.sourceArchiveRoot().ifPresent(p -> lines.add(new DetailViewport.Line("Saved archive root: " + literal(p.toString()))));
        });
        entry.draft().ifPresent(r -> lines.add(new DetailViewport.Line("Draft target: " + literal(draft.targetRoot().resolve(r.targetRelative()).normalize().toString()))));
        lines.add(new DetailViewport.Line("Metadata: " + state(entry, draft)));
        if (entry.discovery().filter(c -> c.observation().kind() == CandidateObservation.Kind.MISSING).isPresent()) {
            lines.add(new DetailViewport.Line("Not found under the source root. You can configure it before the app creates it."));
            lines.add(new DetailViewport.Line("On Apply, if source and target are both missing: create the target directory and source link."));
            lines.add(new DetailViewport.Line("If only the target exists: follow the row's policy (Prompt by default, or Adopt target)."));
            lines.add(new DetailViewport.Line("Save writes configuration only. Apply checks the paths again."));
        }
        lines.add(new DetailViewport.Line("Size: not estimated · Ownership: not evaluated"));
        entry.discovery().ifPresent(candidate -> {
            var observation = candidate.observation();
            if (observation.kind() != CandidateObservation.Kind.PENDING) lines.add(new DetailViewport.Line("Observed: " + observation.observedAt()
                    + (draft.discovery().filter(r -> r.generation() == observation.generation()).isPresent() ? " · This request" : " · Earlier request")));
            observation.rawLinkTarget().ifPresent(path -> lines.add(new DetailViewport.Line("Link text: " + literal(path.toString()) + " · Target not checked")));
            observation.diagnostics().forEach(d -> lines.add(new DetailViewport.Line("Metadata note: " + literal(d.detail()) + " · " + literal(d.path().toString()))));
            candidate.ancestors().forEach(path -> lines.add(new DetailViewport.Line("Overlaps catalog parent: " + literal(path.toString()))));
            draft.discovery().ifPresent(result -> result.candidates().stream().filter(c -> c.ancestors().contains(candidate.catalog().sourcePath()))
                    .forEach(c -> lines.add(new DetailViewport.Line("Overlaps catalog child: " + literal(c.catalog().sourcePath().toString())))));
        });
        if (entry.configured().isPresent()) lines.add(new DetailViewport.Line("Inspection only. Saved target and policies remain authoritative."));
        else if (entry.draft().isPresent()) lines.add(new DetailViewport.Line("Target and policies remain editable in Row details."));
        else if (canAdd(entry, draft)) lines.add(new DetailViewport.Line("Adding uses a matching target path and Default (prompt) policies."));
        else lines.add(new DetailViewport.Line("Add needs a directory or missing path observed in this request. Manual entry is available from Relocations."));
        attribution(lines, entry, draft);
    }

    static void attribution(List<DetailViewport.Line> lines, SetupDraft.Entry entry, SetupDraft draft) {
        var current = definitions(entry);
        lines.add(new DetailViewport.Line("Advice is optional, not a safety assessment or a requirement.", Color.GRAY, false));
        if (current.isEmpty()) lines.add(new DetailViewport.Line("No current catalog attribution."));
        else {
            lines.add(new DetailViewport.Line("Discovery attribution · source status below", Color.CYAN, true));
            for (var definition : current) {
                var status = draft.discovery().stream().flatMap(r -> r.sources().stream()).filter(s -> s.source().equals(definition.source()))
                        .map(s -> sourceState(s.status())).findFirst().orElse("unavailable");
                definition(lines, definition, status);
            }
        }
        var history = entry.lastKnownDefinitions().stream().filter(d -> !current.contains(d)).toList();
        if (!history.isEmpty()) {
            lines.add(new DetailViewport.Line("Historical attribution · no longer in current discovery", Color.YELLOW, true));
            history.forEach(d -> definition(lines, d, "historical; not current advice"));
        }
    }

    private static void definition(List<DetailViewport.Line> lines, CandidateDefinition definition, String freshness) {
        lines.add(new DetailViewport.Line(literal(definition.app().orElse("Ungrouped")) + " · " + sourceName(definition.source()) + " · " + freshness));
        lines.add(new DetailViewport.Line("Advice: " + advice(definition.advice())));
        definition.reason().ifPresent(reason -> lines.add(new DetailViewport.Line("Reason: " + literal(reason))));
        lines.add(new DetailViewport.Line("From: " + literal(definition.source().location()) + " · " + literal(definition.location())
                + " · original path: " + literal(definition.originalPath())));
    }
    private static String sourceName(CandidateSource source) { return source.kind() == CandidateSource.Kind.BUNDLED ? "Bundled" : "Shared"; }
    private static String sourceState(CandidateDiscovery.SourceStatus status) {
        return switch (status) { case CURRENT -> "current"; case PENDING -> "pending"; case STALE -> "stale"; case FAILED -> "unavailable"; };
    }
    private static String sourceSummary(SetupDraft draft) {
        return draft.discovery().map(result -> String.join(" · ", result.sources().stream().map(s -> sourceName(s.source()) + ": " + sourceState(s.status())).toList()))
                .orElse("Discovery has not started");
    }
    private static void sourceDetails(List<DetailViewport.Line> lines, SetupDraft draft) {
        lines.add(new DetailViewport.Line("Manual editing, saving and exit do not wait for discovery."));
        draft.discovery().ifPresent(result -> {
            for (var source : result.sources()) {
                lines.add(new DetailViewport.Line(sourceName(source.source()) + ": " + sourceState(source.status()), Color.CYAN, true));
                lines.add(new DetailViewport.Line("Location: " + literal(source.source().location())));
                if (source.status() == CandidateDiscovery.SourceStatus.STALE) {
                    lines.add(new DetailViewport.Line("Retaining earlier definitions; this read supplied no replacement."));
                }
                source.problems().forEach(problem -> {
                    lines.add(new DetailViewport.Line(switch (problem.kind()) {
                        case MISSING -> "The list was not found. Check its location or clear the optional field.";
                        case UNREADABLE -> "The list could not be read. Check access permissions.";
                        case NOT_REGULAR -> "Choose a regular YAML file, not a directory or special file.";
                        case IO_ERROR -> "Reading the list failed. Retry when storage is available.";
                        case DEADLINE -> "No response within five seconds. Manual setup remains available.";
                        case PREVIOUS_PENDING -> "Previous read still pending; manual setup remains available.";
                    }, Color.YELLOW, false));
                    lines.add(new DetailViewport.Line("Diagnostic: " + literal(problem.detail())));
                });
                if (!source.diagnostics().isEmpty()) lines.add(new DetailViewport.Line("List rejected. Fix the YAML and refresh.", Color.YELLOW, false));
                source.diagnostics().forEach(d -> lines.add(new DetailViewport.Line(literal(d.message()) + (d.location().isEmpty() ? "" : " · " + literal(d.location()))
                        + " · input line " + d.line() + ", column " + d.column(), Color.YELLOW, false)));
            }
            result.rootFailure().ifPresent(d -> lines.add(new DetailViewport.Line("Root: " + literal(d.detail()) + " · " + literal(d.path().toString()))));
        });
    }

    static String literal(String text) {
        var escaped = new StringBuilder();
        text.codePoints().forEach(point -> {
            if (Character.isISOControl(point) || Character.getType(point) == Character.FORMAT) escaped.append(String.format("\\u%04x", point));
            else escaped.appendCodePoint(point);
        });
        return escaped.toString();
    }
}
