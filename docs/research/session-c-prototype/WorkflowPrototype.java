import dev.tamboui.toolkit.Toolkit;
import dev.tamboui.toolkit.app.ToolkitApp;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

// THROWAWAY #22: two TamboUI journeys, identical in-memory scenarios, no filesystem access.
@SuppressWarnings("deprecation")
public class WorkflowPrototype extends ToolkitApp {
    final String[] names = {"conflict-cache", "discard-cache", "stage-cache", "new-cache", "linked-cache", "unmanaged-cache"};
    final String[] choices = {"Adopt target; discard source", "Adopt target; archive source", "Leave both unmanaged"};
    final String[] effects = {"Delete the source directory; keep target contents; create source link.", "Move source to archive; keep target contents; create source link.", "Keep both directories in place. No mutation for this relocation."};
    final String[] scenarios = {"success", "preflight rejection", "partial failure", "missing default config", "missing explicit config"};
    boolean tabs, detail, configured = true, saved, shared = true, available = true;
    boolean running, finished, applied, replanned, recoveryBlocked;
    int scenario, item, choice, draft = -1, page, keys, navigation, lostContext, active = -1, inspected;
    int selectedCandidate, revision = 1, reviewedRevision;
    boolean[] selected = {false, false, false};
    String view = "work", returnView = "work", notice = "Choose a relocation, inspect paths, then review.";
    String result = "No execution yet", field = "";
    String root = "/home/demo", targetRoot = "/mnt/storage/demo", configPath = "/home/demo/.config/homelight/config.yaml";
    String listPath = "/net/team/bulky-candidates.txt";
    String input = "";
    long nextStep;

    public static void main(String[] args) throws Exception {
        var app = new WorkflowPrototype();
        app.tabs = List.of(args).contains("--tabs");
        if (app.tabs) app.view = "status";
        app.run();
    }

    @Override protected void onStart() {
        runner().scheduleRepeating(this::tick, Duration.ofMillis(100));
    }

    void tick() {
        if (!running || System.currentTimeMillis() < nextStep) return;
        active++;
        inspected = Math.min(active, actionCount() - 1);
        page = 0;
        nextStep = System.currentTimeMillis() + 1800;
        if (scenario == 2 && active == stageIndex()) {
            running = false; finished = true;
            result = "PARTIAL FAILURE: " + active + " completed, 1 failed, 5 not run";
        } else if (active == actionCount()) {
            running = false; finished = true; applied = true;
            result = "SUCCESS: " + actionCount() + " completed, 0 failed, 0 not run";
        }
    }

    void reset() {
        configured = scenario < 3; saved = false; applied = false; replanned = false;
        running = finished = recoveryBlocked = false; active = -1; draft = -1; item = page = choice = 0;
        detail = false; selected = new boolean[3]; selectedCandidate = 0;
        shared = available = true;
        keys = navigation = lostContext = 0; revision = 1;
        result = "No execution yet"; view = tabs ? "status" : "work";
        configPath = scenario == 4 ? "/tmp/explicit-demo/config.yaml" : "/home/demo/.config/homelight/config.yaml";
        notice = "Scenario reset. All data is simulated.";
    }

    String source(int i) { return root + "/projects/very-long-shared-prefix/application-data/cache/" + names[i]; }
    String target(int i) { return targetRoot + "/relocations/very-long-shared-prefix/application-data/cache/" + names[i]; }
    String archive() { return targetRoot + "/archive/2026-09-14/very-long-shared-prefix/application-data/cache/conflict-cache"; }
    String observed(int i) {
        if (applied && i < 4 && !(i == 0 && draft == 2)) return "In sync: source link -> target directory";
        if (scenario == 2 && active >= stageIndex() && i < 2 && !(i == 0 && draft == 2)) return "Changed: relocation completed; source is now a link";
        if (scenario == 1 && active >= 0 && i == 2) return "Source directory + newly appeared target directory (external change)";
        if (i < 2) return "Source directory + target directory";
        return switch (i) { case 2 -> "Source directory; target absent"; case 3 -> "Source and target absent"; case 4 -> "In sync: source link -> target directory"; default -> "Both directories present, intentionally unmanaged"; };
    }
    String primaryState(int i) {
        if (i == 5 || i == 0 && applied && draft == 2) return "unchanged";
        if (i == 4 || applied || scenario == 2 && active >= stageIndex() && i < 2 && !(i == 0 && draft == 2)) return "in sync";
        if (i == 0 || scenario == 1 && active >= 0 && i == 2) return "conflict";
        return "pending";
    }

    List<String> itemDetail() {
        var lines = new ArrayList<String>();
        lines.add("Relocation: " + names[item]);
        lines.add("CURRENT observation: " + observed(item));
        lines.add("Source: " + source(item)); lines.add("Destination: " + target(item));
        lines.add("SAVED policy: " + (item == 0 ? "ask when both directories exist" : item == 1 ? "discard contents in both locations" : item == 5 ? "leave unchanged" : "relocate"));
        lines.add("DRAFT choice: " + (item == 0 && draft >= 0 ? choices[draft] + " (session only, not saved)" : "none"));
        lines.add("EXPECTED outcome: " + (primaryState(item).equals("in sync") ? "in sync" : item == 5 || item == 0 && draft == 2 ? "unchanged / unmanaged" : item == 0 && (draft < 0 || tabs && view.equals("status")) ? "unresolved under saved policy" : "in sync"));
        lines.add("WARNING (independent): " + (primaryState(item).equals("in sync") ? "none" : item == 1 ? "Pending AND destructive: delete source AND target contents" : item == 0 && draft == 0 ? "Delete source contents" : "none"));
        lines.add("EXECUTION history: " + result);
        return lines;
    }

    List<String> content() {
        var lines = new ArrayList<String>();
        if (!field.isEmpty()) return List.of("Edit " + field + " (simulation only)", input + "_", "Enter accepts; Esc cancels. Type a full absolute path.");
        if (view.equals("setup")) {
            lines.add("MANUAL SETUP | no configuration written");
            lines.add("Config: " + configPath + " [c edit]");
            lines.add("Source root: " + root + " [o edit]");
            lines.add("Target root: " + targetRoot + " [t edit]");
            lines.add("Optional shared list: " + (shared ? listPath : "disabled") + " [l edit, e toggle]");
            lines.add("Shared input: " + (!shared ? "disabled" : available ? "available (fixture)" : "UNAVAILABLE: NFS unreachable. Manual/built-in candidates still usable"));
            lines.add("u toggles availability; f refreshes suggestions only");
            lines.add("Suggestions (existence/size/provenance simulated):");
            String[] candidates = {"cache/manual-demo | manual | exists ~1 GiB", "cache/build | built-in + shared | exists ~8 GiB", "cache/team | shared | exists ~3 GiB"};
            for (int i = 0; i < 3; i++) lines.add((selectedCandidate == i ? "> " : "  ") + (selected[i] ? "[x] " : "[ ] ") + candidates[i] + (i == 2 && (!shared || !available) ? " (unavailable for new selection)" : ""));
            lines.add("Saved relocations: " + (saved ? "explicit selections only" : "none") + "; draft selections survive refresh");
            lines.add("s: review deliberate save | n: cancel setup without saving");
        } else if (view.equals("save")) {
            lines.add("REVIEW SIMULATED SAVE to " + configPath);
            lines.add("Root: " + root); lines.add("Target root: " + targetRoot);
            for (int i = 0; i < 3; i++) if (selected[i]) lines.add("Selected relocation: " + root + "/cache/" + new String[]{"manual-demo", "build", "team"}[i] + " -> " + targetRoot + "/cache/" + new String[]{"manual-demo", "build", "team"}[i]);
            lines.add("Only selected entries become configured. No relocation execution.");
            lines.add("y: save in memory | n: return to setup | Enter does nothing");
        } else if (!configured) {
            lines.add("CONFIGURATION MISSING (" + (scenario == 4 ? "explicit path" : "default path") + ")");
            lines.add(configPath);
            lines.add("m: manual setup with optional candidate discovery");
            lines.add("Cancel never writes. This prototype never reads these paths.");
        } else if (saved) {
            lines.add("CONFIGURATION SAVED IN MEMORY; no relocation applied");
            lines.add("Configured selections:");
            for (int i = 0; i < 3; i++) if (selected[i]) lines.add(root + "/cache/" + new String[]{"manual-demo", "build", "team"}[i] + " -> " + targetRoot + "/cache/" + new String[]{"manual-demo", "build", "team"}[i]);
            lines.add("f: refresh shared list | u: simulate unavailable shared list");
            lines.add("Existing configured relocations remain identical. Shared entries are suggestions.");
            lines.add("Use x to try another scenario; z resets this fixture.");
        } else if (view.equals("review")) {
            lines.add("REVIEW plan revision " + reviewedRevision + " | explicit confirmation required");
            lines.add("Conflict decision: " + choices[draft]);
            lines.add("WARNING: discard-cache deletes source AND destination contents.");
            lines.add("Conflict consequence: " + effects[draft]);
            for (int i = 0; i < 4; i++) {
                lines.add(names[i] + ": " + (i == 0 && draft == 2 ? "leave unchanged" : i == 1 ? "delete both; create empty target and source link" : "create source link to target"));
                lines.add("Affected source: " + source(i)); lines.add("Destination: " + target(i));
                if (i == 0 && draft == 1) lines.add("Archive destination: " + archive());
            }
            lines.add("linked-cache: already in sync; unmanaged-cache: unchanged.");
            lines.add("y: execute this reviewed snapshot | n: cancel | Enter does nothing");
        } else if (view.equals("history")) {
            lines.add("EXECUTION HISTORY | " + (running ? "RUNNING, action " + (active + 1) + "/" + actionCount() : result));
            lines.add("j/k inspect action; next action transition resumes following.");
            for (int i = 0; i < actionCount(); i++) lines.add((inspected == i ? "> " : "  ") + (i + 1) + " " + actionState(i) + " " + actionName(i));
            lines.add("Inspected affected path: " + actionPath(inspected));
            if (scenario == 2 && finished) lines.add("Failure: staging root is a file at " + target(2) + "/.staging/session-demo; conflict-cache " + (draft == 2 ? "unchanged" : "completed") + ", discard-cache completed, stage-cache failed, new-cache did not run. Inspect, then r for a fresh review.");
            if (scenario == 1 && finished) lines.add("Preflight: target appeared after review at " + target(2) + ". ZERO mutations. Inspect and r to evaluate again.");
            lines.add("1: observations | 3: retained result | r: explicit replan (clears history)");
        } else if (view.equals("choice")) {
            lines.add("DECIDE conflict-cache | choice " + (choice + 1) + "/3");
            lines.add("> " + choices[choice]); lines.add("Consequence: " + effects[choice]);
            lines.add("Affected source: " + source(0)); lines.add("Destination: " + target(0));
            if (choice == 1) lines.add("Archive destination: " + archive());
            lines.add("j/k: next/previous choice | Space: choose | n: cancel");
            lines.add("Choice is session-only; saved policy stays 'ask'.");
        } else {
            lines.add(tabs ? (view.equals("status") ? "STATUS | saved-policy perspective" : "PLAN | draft-choice perspective") : "WORKSPACE | observations and decisions together");
            lines.add(applied ? "6 total: " + (draft == 2 ? "4 in sync, 2 unchanged" : "5 in sync, 1 unchanged") : scenario == 2 && active >= stageIndex() ? "6 total: " + (draft == 2 ? "2 in sync, 1 conflict, 2 pending, 1 unchanged" : "3 in sync, 2 pending, 1 unchanged") + "; stopped" : scenario == 1 && active >= 0 ? "6 total: 2 pending, 2 conflicts, 1 in sync, 1 unchanged" : "6 total: 3 pending, 1 conflict, 1 in sync, 1 unchanged; 1 warning");
            if (!detail) {
                for (int i = 0; i < 6; i++) lines.add((item == i ? "> " : "  ") + names[i] + " [" + primaryState(i) + "]" + (i == 1 && primaryState(i).equals("pending") ? " [WARNING: destructive]" : ""));
                lines.add("Selected: " + names[item] + " | l: full details / decisions");
                lines.add("Current: " + observed(item));
                lines.add("Draft: " + (draft < 0 ? "none" : choices[draft]) + " | History: " + result);
            } else lines.addAll(itemDetail());
            if (replanned && applied) lines.add("NO CHANGES TO APPLY. y does nothing.");
            if (recoveryBlocked) {
                lines.add("FRESH PLAN BLOCKED: " + (scenario == 1 ? "stage-cache now has a target directory; resolve its new conflict" : "staging root is still a file; external correction is needed"));
                lines.add("Completed changes remain observed. This fixture stops at recovery explanation; z resets it.");
            }
        }
        return lines;
    }

    String actionState(int i) {
        if (scenario == 1 && finished) return "NOT RUN";
        if (i < active) return "COMPLETED";
        if (i == active) return running ? "RUNNING" : "FAILED";
        return "NOT RUN";
    }
    int actionCount() { return draft == 2 ? 8 : 10; }
    int stageIndex() { return actionCount() - 6; }
    String actionName(int i) {
        int index = draft == 2 ? i + 2 : i;
        return new String[]{draft == 1 ? "Archive conflict source" : "Discard conflict source", "Link conflict source to target", "Discard both discard-cache contents", "Recreate discard target and source link", "Stage cache copy", "Verify stage", "Publish cache and source link", "Prepare new-cache parent", "Create new target", "Create new source link"}[index];
    }
    String actionPath(int i) {
        int index = draft == 2 ? i + 2 : i;
        if (index < 2) return source(0) + " -> " + (index == 0 && draft == 1 ? archive() : target(0));
        if (index < 4) return source(1) + " -> " + target(1);
        if (index < 7) return source(2) + " -> " + target(2) + "/.staging/session-demo";
        return source(3) + " -> " + target(3);
    }

    @Override protected Element render() {
        var size = runner().tuiRunner().terminal().size();
        int width = Math.max(20, size.width() - 2), height = Math.max(1, size.height() - 7);
        var wrapped = new ArrayList<String>();
        for (var line : content()) {
            if (line.isEmpty()) wrapped.add("");
            for (int i = 0; i < line.length(); i += width) wrapped.add(line.substring(i, Math.min(line.length(), i + width)));
        }
        int pages = Math.max(1, (wrapped.size() + height - 1) / height);
        page = Math.clamp(page, 0, pages - 1);
        var rows = new ArrayList<Element>();
        rows.add(Toolkit.text("THROWAWAY / NO DISK WRITES | " + (tabs ? "A: tabs" : "B: workspace") + " | " + scenarios[scenario]).cyan().bold());
        rows.add(Toolkit.text("v compare | x scenario | z reset | q quit | keys " + keys + " nav " + navigation + " context-lost " + lostContext));
        rows.add(Toolkit.text("Focus: " + view + "/" + (detail ? "details" : "list") + " | " + (view.equals("history") ? "action " + (inspected + 1) : names[item]) + " | page " + (page + 1) + "/" + pages).yellow());
        for (int i = 0; i < height; i++) {
            int index = page * height + i;
            rows.add(Toolkit.text(index < wrapped.size() ? wrapped.get(index) : ""));
        }
        rows.add(Toolkit.text("[: previous page  ]: next page | j/k: select | h/l: list/detail"));
        rows.add(Toolkit.text("1 observations | 2 decisions | Space choose | 3 review/history | r replan"));
        rows.add(Toolkit.text(notice.length() > width ? notice.substring(0, width) : notice).green());
        return Toolkit.column(rows.toArray(Element[]::new)).id("prototype").focusable().onKeyEvent(this::key);
    }

    EventResult key(KeyEvent key) {
        keys++;
        if (!field.isEmpty()) {
            if (key.isKey(dev.tamboui.tui.event.KeyCode.ESCAPE)) field = "";
            else if (key.isKey(dev.tamboui.tui.event.KeyCode.ENTER)) {
                if (!input.startsWith("/") || input.contains("..")) notice = "Use an absolute path without '..'.";
                else { switch (field) { case "source root" -> root = input; case "target root" -> targetRoot = input; case "config" -> configPath = input; default -> listPath = input; } field = ""; }
            } else if (key.isKey(dev.tamboui.tui.event.KeyCode.BACKSPACE)) input = input.isEmpty() ? "" : input.substring(0, input.length() - 1);
            else if (key.character() >= 32) input += Character.toString(key.character());
            return EventResult.HANDLED;
        }
        if (key.isChar(']')) page++;
        else if (key.isChar('[')) page = Math.max(0, page - 1);
        else if (running) {
            if (key.isChar('j') || key.isDown()) inspected = Math.min(actionCount() - 1, inspected + 1);
            if (key.isChar('k') || key.isUp()) inspected = Math.max(0, inspected - 1);
        } else if (key.isChar('q')) quit();
        else if (key.isChar('v')) { tabs = !tabs; reset(); }
        else if (key.isChar('x')) { scenario = (scenario + 1) % scenarios.length; reset(); }
        else if (key.isChar('z')) reset();
        else if (view.equals("setup")) setupKey(key);
        else if (view.equals("save")) {
            if (key.isChar('n')) view = "setup";
            if (key.isChar('y')) { configured = saved = true; view = "work"; notice = "Saved selected relocations in memory. Applied: none."; }
        } else if (!configured) { if (key.isChar('m')) { view = "setup"; page = 0; } }
        else if (saved) { if (key.isChar('f') || key.isChar('u')) { available = !available; notice = "Shared list changed; configured selections unchanged."; } }
        else if (view.equals("choice")) {
            if (key.isChar('j') || key.isDown()) { choice = (choice + 1) % 3; page = 0; }
            if (key.isChar('k') || key.isUp()) { choice = (choice + 2) % 3; page = 0; }
            if (key.isChar(' ')) { draft = choice; revision++; view = returnView; page = 0; notice = "Draft selected. Saved policy unchanged."; }
            if (key.isChar('n')) { view = returnView; page = 0; }
        } else if (view.equals("review")) {
            if (key.isChar('n')) { view = returnView; page = 0; if (tabs) { if (item != 0 || detail) lostContext++; item = 0; detail = false; } notice = "Review cancelled; draft retained."; }
            if (key.isChar('y') && revision == reviewedRevision) {
                view = "history"; page = 0; inspected = 0; active = 0;
                if (scenario == 1) { finished = true; result = "PREFLIGHT REJECTED: 0 completed, 0 failed, " + actionCount() + " not run"; }
                else { running = true; nextStep = System.currentTimeMillis() + 1800; }
            }
        } else {
            if (key.isChar('j') || key.isDown()) { if (view.equals("history")) inspected = Math.min(actionCount() - 1, inspected + 1); else item = (item + 1) % 6; navigation++; page = 0; }
            if (key.isChar('k') || key.isUp()) { if (view.equals("history")) inspected = Math.max(0, inspected - 1); else item = (item + 5) % 6; navigation++; page = 0; }
            if (key.isChar('l')) { detail = true; page = 0; navigation++; }
            if (key.isChar('h')) { detail = false; page = 0; navigation++; }
            if (key.isChar('1') || key.isChar('2')) {
                if (tabs) { if (item != 0 || detail) lostContext++; item = 0; detail = false; }
                view = tabs ? key.isChar('1') ? "status" : "plan" : "work"; page = 0; navigation++;
            }
            if (key.isChar(' ') && item == 0 && detail && !finished && !applied && !recoveryBlocked && !(tabs && view.equals("status"))) { returnView = view; view = "choice"; page = 0; }
            if (key.isChar('3')) {
                if (finished) { view = "history"; page = 0; }
                else if (recoveryBlocked) notice = "Fresh plan blocked. Inspect observations; z resets fixture.";
                else if (replanned && applied) notice = "No changes to apply.";
                else if (draft < 0) notice = "Resolve conflict-cache first: l, Space, j/k, Space.";
                else { returnView = view; reviewedRevision = revision; view = "review"; page = 0; }
            }
            if (key.isChar('r')) {
                if (finished && !applied) { notice = "Fresh observations retained; new plan blocked. See recovery detail."; recoveryBlocked = true; }
                else notice = applied ? "Fresh plan: no changes. History cleared explicitly." : "Fresh plan created.";
                finished = false; result = "Cleared by explicit replan"; replanned = true; revision++; view = tabs ? "plan" : "work"; page = 0;
            }
        }
        return EventResult.HANDLED;
    }

    void setupKey(KeyEvent key) {
        if (key.isChar('j') || key.isDown()) selectedCandidate = (selectedCandidate + 1) % 3;
        if (key.isChar('k') || key.isUp()) selectedCandidate = (selectedCandidate + 2) % 3;
        if (key.isChar(' ') && (selectedCandidate != 2 || shared && available || selected[2])) selected[selectedCandidate] = !selected[selectedCandidate];
        if (key.isChar('u')) available = !available;
        if (key.isChar('e')) shared = !shared;
        if (key.isChar('f')) { available = !available; notice = "Suggestions refreshed; draft selections unchanged."; }
        if (key.isChar('n')) { selected = new boolean[3]; view = "work"; notice = "Cancelled. Nothing saved."; }
        if (key.isChar('s')) {
            if (!selected[0] && !selected[1] && !selected[2]) notice = "Select at least one candidate before save.";
            else if (root.equals(targetRoot)) notice = "Source and target roots must differ.";
            else { view = "save"; page = 0; }
        }
        if (key.isChar('c')) { field = "config"; input = configPath; }
        if (key.isChar('o')) { field = "source root"; input = root; }
        if (key.isChar('t')) { field = "target root"; input = targetRoot; }
        if (key.isChar('l')) { field = "shared list"; input = listPath; }
    }
}
