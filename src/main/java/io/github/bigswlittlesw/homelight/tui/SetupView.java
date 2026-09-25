package io.github.bigswlittlesw.homelight.tui;

import dev.tamboui.style.Color;
import dev.tamboui.toolkit.Toolkit;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import io.github.bigswlittlesw.homelight.application.HomeLightSession;
import io.github.bigswlittlesw.homelight.application.SetupDraft;
import io.github.bigswlittlesw.homelight.config.ConfigurationPublisher;
import io.github.bigswlittlesw.homelight.config.DiscoverySetting;
import io.github.bigswlittlesw.homelight.config.WhenAdoptingTarget;
import io.github.bigswlittlesw.homelight.config.WhenOnlyTargetExists;
import io.github.bigswlittlesw.homelight.config.WhenSourceAndTargetDirectoriesExist;
import io.github.bigswlittlesw.homelight.discovery.CandidateDiscovery;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/// Creation-only presentation. All draft changes and snapshot acceptance happen
/// on the UI thread; workers publish evidence without callbacks into this view.
final class SetupView implements AutoCloseable {
    private enum Mode { LOCATIONS, TABLE, ROW, CANDIDATES }
    private final HomeLightSession session;
    private final Supplier<CandidateDiscovery> discoveryFactory;
    private final SetupDraft draft;
    private final DetailViewport viewport = new DetailViewport();
    private final CandidateBrowser browser = new CandidateBrowser();
    private CandidateDiscovery discovery;
    private Mode mode = Mode.LOCATIONS;
    private int field;
    private int row;
    private boolean discard;
    private boolean closed;
    private boolean locationsChanged;
    private String sourceRoot = System.getProperty("user.home");
    private String targetRoot = "";
    private String sharedList = "";
    private String archiveText = "";
    private String message = "Draft not saved. Validation: not run.";

    SetupView(HomeLightSession session, Supplier<CandidateDiscovery> discoveryFactory) {
        this.session = session;
        this.discoveryFactory = discoveryFactory;
        // Unfinished location text is independent of the last valid model roots.
        var home = Path.of(sourceRoot).toAbsolutePath();
        draft = new SetupDraft(home, home, Optional.empty(), List.of());
    }

    boolean closed() { return closed; }

    Element render() {
        if (discovery != null && !closed) draft.accept(discovery.snapshot());
        Element content;
        if (mode == Mode.CANDIDATES) content = browser.render(draft);
        else {
            var lines = new ArrayList<DetailViewport.Line>();
            lines.add(new DetailViewport.Line("Create configuration", Color.CYAN, true));
            lines.add(new DetailViewport.Line("Config: " + CandidateBrowser.literal(session.configPath().toString())));
            int anchor = switch (mode) {
                case LOCATIONS -> locations(lines);
                case TABLE -> table(lines);
                case ROW -> rowDetails(lines);
                case CANDIDATES -> throw new IllegalStateException();
            };
            lines.add(new DetailViewport.Line(CandidateBrowser.literal(message), Color.YELLOW, false));
            content = Toolkit.column(viewport.render("Setup", lines, true, anchor),
                    viewport.help(help(), commands())).fill();
        }
        if (discard) {
            // One contextual help area while the modal consumes input.
            content = Toolkit.column(viewport.render("Discard setup draft?", List.of(
                    new DetailViewport.Line("Nothing has been written."),
                    new DetailViewport.Line("Discard all locations and relocation choices?")), true, 0),
                    Toolkit.text("Enter: Discard draft · Esc: Keep editing").gray()).fill();
        }
        return Toolkit.column(Toolkit.text("⌂ HOMELIGHT  [Setup]").cyan().bold(), content).fill();
    }

    private int locations(ArrayList<DetailViewport.Line> lines) {
        lines.add(new DetailViewport.Line("Storage locations", Color.CYAN, true));
        var names = List.of("Source root", "Target root", "Shared candidate list (optional)");
        var values = List.of(sourceRoot, targetRoot, sharedList);
        for (int i = 0; i < names.size(); i++) choice(lines, names.get(i) + ": " + values.get(i), i == field);
        lines.add(new DetailViewport.Line(switch (field) {
            case 0 -> "Source paths are relative to this root. Changing it re-resolves existing rows.";
            case 1 -> "Target paths are relative to this root. Use an absolute path.";
            default -> "Blank: bundled candidates only. Otherwise use an absolute path or ~/path.";
        }));
        if (!sharedList.isBlank()) {
            try { lines.add(new DetailViewport.Line("Resolved list: " + DiscoverySetting.parse(sharedList).orElseThrow())); }
            catch (IllegalArgumentException error) { lines.add(new DetailViewport.Line(error.getMessage(), Color.YELLOW, false)); }
        }
        return 3 + field;
    }

    private int table(ArrayList<DetailViewport.Line> lines) {
        lines.add(new DetailViewport.Line("Storage locations", Color.CYAN, true));
        lines.add(new DetailViewport.Line("Source root: " + sourceRoot));
        lines.add(new DetailViewport.Line("Target root: " + targetRoot));
        lines.add(new DetailViewport.Line("Relocations", Color.CYAN, true));
        lines.add(new DetailViewport.Line("  Source (relative)        Target (relative)        Policies", Color.GRAY, true));
        if (draft.rows().isEmpty()) lines.add(new DetailViewport.Line("No relocations yet. Add a directory manually or browse candidates."));
        for (int i = 0; i < draft.rows().size(); i++) {
            var value = draft.rows().get(i);
            choice(lines, cell(value.sourceRelative(), 23) + "  " + cell(value.targetRelative(), 23)
                    + "  " + policies(value), i == row);
        }
        return 7 + row;
    }

    private int rowDetails(ArrayList<DetailViewport.Line> lines) {
        var value = draft.rows().get(row);
        lines.add(new DetailViewport.Line("Edit relocation " + (row + 1), Color.CYAN, true));
        var names = List.of("Source path", "Target path", "Both directories", "Only target", "Adopt target", "Archive root");
        var values = List.of(value.sourceRelative(), value.targetRelative(), both(value.both()),
                only(value.onlyTarget()), adopting(value.adopting()), archiveText);
        for (int i = 0; i < names.size(); i++) {
            choice(lines, names.get(i) + ": " + values.get(i), i == field);
            if (i == 2 && discardPolicyFocused()) {
                lines.add(new DetailViewport.Line(bothConsequence(value.both()), Color.YELLOW, true));
            }
        }
        lines.add(new DetailViewport.Line("Paths (resolved)", Color.CYAN, true));
        lines.add(new DetailViewport.Line("Source: " + resolved(sourceRoot, value.sourceRelative())));
        lines.add(new DetailViewport.Line("Target: " + resolved(targetRoot, value.targetRelative())));
        var entry = draft.entries().get(row);
        CandidateBrowser.attribution(lines, entry, draft);
        return 3 + field;
    }

    private static String resolved(String root, String relative) {
        try { return Path.of(root).resolve(relative).normalize().toString(); }
        catch (IllegalArgumentException error) { return "Invalid path: " + error.getMessage(); }
    }

    private String help() {
        return switch (mode) {
            case LOCATIONS -> "↑/↓/Tab: Field · Type: Edit · Ctrl-U: Clear";
            case TABLE -> draft.rows().isEmpty() ? "e: Edit locations · Esc: Back" : "↑/↓: Row · Enter: Details · d: Remove · e: Locations · Esc: Back";
            case ROW -> switch (field) {
                case 0 -> "Source is relative; matching target follows until edited.";
                case 1 -> "Target is relative to the target root.";
                case 5 -> "Archive root is optional; use an absolute path.";
                case 2 -> discardPolicyFocused() ? "Save writes configuration only; Apply requires review."
                        : "Both exist: " + bothConsequence(draft.rows().get(row).both());
                case 3 -> "When only target exists: " + (draft.rows().get(row).onlyTarget().orElse(WhenOnlyTargetExists.PROMPT) == WhenOnlyTargetExists.PROMPT
                        ? "ask before acting." : "link the source to that target.");
                default -> "When adopting: " + switch (draft.rows().get(row).adopting().orElse(WhenAdoptingTarget.PROMPT)) {
                    case PROMPT -> "ask what to do with source contents.";
                    case DISCARD_SOURCE -> "delete source contents.";
                    case ARCHIVE_SOURCE -> "move source contents to the archive root.";
                };
            };
            case CANDIDATES -> "";
        };
    }

    private String commands() {
        return switch (mode) {
            case LOCATIONS -> "Enter: Relocations · Esc: Cancel without writing";
            case TABLE -> "a: Add manual · b: Browse candidates · v: Validate · s: Save · q: Discard";
            case ROW -> "↑/↓/Tab: Field · " + (textField() ? "Type: Edit · Ctrl-U: Clear" : "Space: Policy · d: Remove") + " · Esc: Table";
            case CANDIDATES -> "";
        };
    }

    void key(KeyEvent key) {
        if (closed) return;
        if (discovery != null) draft.accept(discovery.snapshot());
        if (discard) {
            if (key.isKey(KeyCode.ESCAPE) || key.isCharIgnoreCase('n')) discard = false;
            else if (key.isKey(KeyCode.ENTER) || key.isCharIgnoreCase('y')) close();
            return;
        }
        if (key.isKey(KeyCode.ESCAPE)) {
            if (mode == Mode.CANDIDATES) {
                if (!browser.back()) changeMode(Mode.TABLE);
            } else if (mode == Mode.ROW) changeMode(Mode.TABLE);
            else if (mode == Mode.TABLE) changeMode(Mode.LOCATIONS);
            else close();
            return;
        }
        if (mode == Mode.CANDIDATES) {
            if (key.isCharIgnoreCase('q') || key.isQuit()) discard = true;
            else if (key.isCharIgnoreCase('r')) refreshDiscovery();
            else {
                int previousCount = draft.rows().size();
                int editRow = browser.key(key, draft);
                if (draft.rows().size() != previousCount) invalidated();
                if (editRow >= 0) { row = editRow; changeMode(Mode.ROW); invalidated(); }
            }
            return;
        }
        if (key.isQuit() && key.character() != 'q') { discard = true; return; }
        if (key.isChar('[') || key.isChar(']')) { viewport.scroll(key.isChar(']') ? 1 : -1); return; }
        if (mode == Mode.LOCATIONS) { locationsKey(key); return; }
        if (mode == Mode.TABLE) { tableKey(key); return; }
        if (key.isKey(KeyCode.TAB) || key.isChar('\t') || key.isDown() || key.isUp()) {
            field = Math.floorMod(field + (key.isUp() ? -1 : 1), 6); viewport.followChoice(); return;
        }
        if (textField()) { editRow(key); return; }
        if (key.isCharIgnoreCase('q')) discard = true;
        else if (key.isCharIgnoreCase('d')) removeRow();
        else if (key.isChar(' ')) cyclePolicy();
    }

    private void locationsKey(KeyEvent key) {
        if (key.isKey(KeyCode.TAB) || key.isChar('\t') || key.isDown() || key.isUp()) {
            field = Math.floorMod(field + (key.isUp() ? -1 : 1), 3); viewport.followChoice();
        } else if (key.isKey(KeyCode.ENTER)) {
            // Manual incomplete drafts remain editable even before roots validate.
            try { applyLocations(); }
            catch (IllegalArgumentException error) { message = error.getMessage(); }
            changeMode(Mode.TABLE);
        } else {
            var current = switch (field) { case 0 -> sourceRoot; case 1 -> targetRoot; default -> sharedList; };
            var next = edit(current, key);
            if (current.equals(next)) return;
            switch (field) { case 0 -> sourceRoot = next; case 1 -> targetRoot = next; default -> sharedList = next; }
            locationsChanged = true;
            // Invalidate immediately, including an edit away from and back to a root.
            if (discovery != null) discovery.cancel();
            draft.roots(draft.sourceRoot(), draft.targetRoot());
            invalidated();
        }
    }

    private void tableKey(KeyEvent key) {
        if (key.isCharIgnoreCase('a')) {
            draft.append(new SetupDraft.Row("", "")); row = draft.rows().size() - 1;
            changeMode(Mode.ROW); invalidated();
        } else if (key.isCharIgnoreCase('b')) {
            try {
                applyLocations();
                if (discovery == null) { discovery = discoveryFactory.get(); refreshDiscovery(); }
                changeMode(Mode.CANDIDATES);
            } catch (IllegalArgumentException error) { message = error.getMessage(); }
        } else if (key.isCharIgnoreCase('e')) changeMode(Mode.LOCATIONS);
        else if (key.isCharIgnoreCase('q')) discard = true;
        else if (key.isCharIgnoreCase('v')) validate();
        else if (key.isCharIgnoreCase('s')) save();
        else if (key.isCharIgnoreCase('d') && !draft.rows().isEmpty()) removeRow();
        else if (key.isKey(KeyCode.ENTER) && !draft.rows().isEmpty()) changeMode(Mode.ROW);
        else if (key.isUp() || key.isCharIgnoreCase('k') || key.isDown() || key.isCharIgnoreCase('j')) {
            row = Math.clamp(row + (key.isUp() || key.isCharIgnoreCase('k') ? -1 : 1), 0, Math.max(0, draft.rows().size() - 1));
            viewport.followChoice();
        }
    }

    private void applyLocations() {
        if (sourceRoot.isBlank() || targetRoot.isBlank()) throw new IllegalArgumentException("Enter both storage roots");
        var source = Path.of(sourceRoot);
        var target = Path.of(targetRoot);
        if (!source.isAbsolute() || !target.isAbsolute()) throw new IllegalArgumentException("Storage roots must be absolute");
        var shared = DiscoverySetting.parse(sharedList);
        if (!locationsChanged && source.normalize().equals(draft.sourceRoot()) && target.normalize().equals(draft.targetRoot())
                && shared.equals(draft.sharedList())) return;
        draft.roots(source, target);
        draft.sharedList(sharedList);
        locationsChanged = false;
        if (discovery != null) refreshDiscovery();
    }

    private void refreshDiscovery() {
        draft.refresh(discovery);
        draft.accept(discovery.snapshot());
    }

    private void editRow(KeyEvent key) {
        var value = draft.rows().get(row);
        var source = value.sourceRelative();
        var target = value.targetRelative();
        var archive = value.archiveRoot();
        try {
            if (field == 0) {
                boolean mirrored = target.equals(source) || target.isBlank();
                source = edit(source, key); if (mirrored) target = source;
            } else if (field == 1) target = edit(target, key);
            else {
                var text = edit(archiveText, key);
                archive = text.isBlank() ? Optional.empty() : Optional.of(Path.of(text));
                archiveText = text;
            }
            var next = new SetupDraft.Row(source, target, value.both(), value.onlyTarget(), value.adopting(), archive);
            if (!next.equals(value)) { draft.edit(row, next); invalidated(); }
        } catch (IllegalArgumentException error) { message = "Invalid path: " + error.getMessage(); }
    }

    private void cyclePolicy() {
        var value = draft.rows().get(row);
        draft.edit(row, new SetupDraft.Row(value.sourceRelative(), value.targetRelative(),
                field == 2 ? next(value.both(), WhenSourceAndTargetDirectoriesExist.values()) : value.both(),
                field == 3 ? next(value.onlyTarget(), WhenOnlyTargetExists.values()) : value.onlyTarget(),
                field == 4 ? next(value.adopting(), WhenAdoptingTarget.values()) : value.adopting(), value.archiveRoot()));
        invalidated();
    }

    private void removeRow() {
        draft.remove(row); row = Math.min(row, Math.max(0, draft.rows().size() - 1));
        changeMode(Mode.TABLE); message = "Draft not saved. Validation: not run. Relocation removed.";
    }

    private void validate() {
        try { applyLocations(); draft.validate(); message = "Draft not saved. Validation: valid. Save explicitly to create the configuration."; }
        catch (RuntimeException error) { message = "Draft not saved. Validation: invalid. " + error.getMessage(); }
        viewport.scroll(Integer.MAX_VALUE);
    }

    private void save() {
        try {
            applyLocations();
            new ConfigurationPublisher().saveNew(session.configPath(), draft.validate());
            close();
            session.refresh();
        } catch (RuntimeException error) { message = "Save failed: " + error.getMessage(); viewport.scroll(Integer.MAX_VALUE); }
    }

    @Override public void close() {
        if (discovery != null) { discovery.close(); discovery = null; }
        closed = true;
    }

    private void changeMode(Mode next) {
        mode = next; field = 0; viewport.reset();
        if (next == Mode.ROW) archiveText = draft.rows().get(row).archiveRoot().map(Path::toString).orElse("");
    }
    private boolean textField() { return field == 0 || field == 1 || field == 5; }
    private void invalidated() { message = "Draft not saved. Validation: not run."; }
    private static String edit(String value, KeyEvent key) {
        if (key.isChar('\u0015') || key.hasCtrl() && key.isCharIgnoreCase('u')) return "";
        if (key.isKey(KeyCode.BACKSPACE)) return value.isEmpty() ? value : value.substring(0, value.length() - 1);
        return key.character() != '\0' && !Character.isISOControl(key.character()) ? value + key.character() : value;
    }
    private static <T extends Enum<T>> Optional<T> next(Optional<T> current, T[] values) {
        return current.map(value -> value.ordinal() + 1 == values.length ? Optional.<T>empty() : Optional.of(values[value.ordinal() + 1])).orElse(Optional.of(values[0]));
    }
    private static void choice(List<DetailViewport.Line> lines, String value, boolean focused) {
        lines.add(new DetailViewport.Line((focused ? "❯ " : "  ") + CandidateBrowser.literal(value), focused ? Color.CYAN : Color.GRAY, focused));
    }
    private static String cell(String value, int width) {
        value = CandidateBrowser.literal(value.isBlank() ? "(new relocation)" : value);
        return value.length() > width ? value.substring(0, width - 1) + "…" : value + " ".repeat(width - value.length());
    }
    private static String policies(SetupDraft.Row row) {
        var values = new ArrayList<String>();
        if (row.both().isPresent()) values.add(both(row.both()));
        if (row.onlyTarget().isPresent()) values.add(only(row.onlyTarget()));
        if (row.adopting().isPresent()) values.add(adopting(row.adopting()));
        return values.isEmpty() ? "Default (prompt)" : String.join(", ", values);
    }
    private boolean discardPolicyFocused() {
        return mode == Mode.ROW && field == 2
                && draft.rows().get(row).both().equals(Optional.of(WhenSourceAndTargetDirectoriesExist.DISCARD));
    }
    private static String both(Optional<WhenSourceAndTargetDirectoriesExist> value) {
        return value.map(v -> switch (v) { case PROMPT -> "Prompt"; case ADOPT -> "Adopt target"; case LEAVE_UNCHANGED -> "Leave unchanged"; case DISCARD -> "Discard both"; }).orElse("Default (prompt)");
    }
    private static String only(Optional<WhenOnlyTargetExists> value) {
        return value.map(v -> switch (v) { case PROMPT -> "Prompt"; case ADOPT_TARGET -> "Adopt target"; }).orElse("Default (prompt)");
    }
    private static String adopting(Optional<WhenAdoptingTarget> value) {
        return value.map(v -> switch (v) { case PROMPT -> "Prompt"; case DISCARD_SOURCE -> "Discard source"; case ARCHIVE_SOURCE -> "Archive source"; }).orElse("Default (prompt)");
    }
    private static String bothConsequence(Optional<WhenSourceAndTargetDirectoriesExist> value) {
        return switch (value.orElse(WhenSourceAndTargetDirectoriesExist.PROMPT)) {
            case PROMPT -> "ask before acting.";
            case ADOPT -> "use target contents; choose source disposition below.";
            case LEAVE_UNCHANGED -> "leave both paths unmanaged.";
            case DISCARD -> "Permanently delete both source and target directory trees. Create an empty target directory and link the source to it.";
        };
    }
}
