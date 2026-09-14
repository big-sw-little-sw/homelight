package io.github.bigswlittlesw.homelight.tui;

import dev.tamboui.toolkit.Toolkit;
import dev.tamboui.toolkit.app.ToolkitApp;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.elements.Column;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import io.github.bigswlittlesw.homelight.application.DecisionChoice;
import io.github.bigswlittlesw.homelight.application.ApplyModel;
import io.github.bigswlittlesw.homelight.application.HomeLightSession;
import io.github.bigswlittlesw.homelight.application.PlanModel;
import io.github.bigswlittlesw.homelight.application.PlanRelocationItem;
import io.github.bigswlittlesw.homelight.application.PlanWorkflow;
import io.github.bigswlittlesw.homelight.application.Screen;
import io.github.bigswlittlesw.homelight.application.StatusModel;
import io.github.bigswlittlesw.homelight.application.StatusWorkflow;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction;

import java.nio.file.Path;
import java.util.Objects;

/// Full-screen TamboUI application for HomeLight interactive terminal workflows.
@SuppressWarnings("deprecation")
public final class HomeLightApp extends ToolkitApp {
    private final HomeLightSession session;
    private final TuiConfig customTuiConfig;

    // THROWAWAY overlay: the original handlers below remain the baseline.
    private boolean workspace;
    private String overlay = "";
    private int overlayPage, detailScroll = -1, returnIndex, setupSelection;
    private PaneFocus returnFocus = PaneFocus.MASTER;
    private boolean saveReview;
    private String editField = "", editValue = "";
    private int selectedIndex;
    private PaneFocus paneFocus = PaneFocus.MASTER;
    private int detailSelectedIndex = 0;
    private boolean showInSync;
    private Boolean userShowInSync;
    private int spinnerFrame;
    private ReconciliationAction followedAction;
    private ApplyModel.Result displayedResult;

    public HomeLightApp(Path configPath) {
        this(configPath, Screen.STATUS);
    }

    public HomeLightApp(Path configPath, Screen initialScreen) {
        this(new HomeLightSession(configPath, initialScreen), null);
    }

    public HomeLightApp(Path configPath, StatusWorkflow statusWorkflow) {
        this(new HomeLightSession(configPath, Screen.STATUS, statusWorkflow, new PlanWorkflow(), new io.github.bigswlittlesw.homelight.config.ConfigurationLoader()), null);
    }

    public HomeLightApp(Path configPath, PlanWorkflow planWorkflow) {
        this(new HomeLightSession(configPath, Screen.PLAN, new StatusWorkflow(), planWorkflow, new io.github.bigswlittlesw.homelight.config.ConfigurationLoader()), null);
    }

    public HomeLightApp(HomeLightSession session) {
        this(session, null);
    }

    public HomeLightApp(HomeLightSession session, TuiConfig customTuiConfig) {
        this.session = Objects.requireNonNull(session, "session");
        this.customTuiConfig = customTuiConfig;
        syncInSyncSetting();
    }

    @Override
    protected TuiConfig configure() {
        if (customTuiConfig != null) {
            return customTuiConfig;
        }
        return super.configure();
    }

    @Override
    protected void onStart() {
        runner().scheduleRepeating(session::advance, java.time.Duration.ofMillis(100));
    }

    @Override
    protected void onStop() {
        session.awaitExecution();
    }

    @Override
    protected Element render() {
        Element view = !overlay.isEmpty() ? renderOverlay()
                : session.activeScreen() == Screen.CONFIG ? renderSetup()
                : workspace && session.activeScreen() != Screen.APPLY
                ? WorkspaceView.render(session, selectedIndex, showInSync, paneFocus, detailSelectedIndex, detailScroll, runner().tuiRunner().terminal().size().width())
                : switch (session.activeScreen()) {
            case STATUS -> StatusView.render(session.statusModel(), selectedIndex, showInSync);
            case PLAN -> PlanView.render(session.planModel(), selectedIndex, showInSync, paneFocus, detailSelectedIndex);
            case APPLY -> renderApply();
            case CONFIG -> renderSetup();
        };
        if (workspace && view instanceof Column column) column.fill();
        return Toolkit.column(Toolkit.column(view).fill(),
                Toolkit.text("DEMO " + (workspace ? "B workspace" : "A existing tabs") + " | v Compare | ? Help | ! Scenarios | i Inspect").yellow())
                .id("homelight-screen").onKeyEvent(this::handleKeyEvent).focusable();
    }

    private Element renderApply() {
        var model = session.applyModel();
        switch (model) {
            case ApplyModel.Running running -> {
                for (int i = 0; i < running.steps().size(); i++) {
                    var step = running.steps().get(i);
                    if (step.status() == ApplyModel.StepStatus.RUNNING && step.action() != followedAction) {
                        // Follow action transitions, while allowing inspection between transitions.
                        selectedIndex = i;
                        followedAction = step.action();
                        break;
                    }
                }
            }
            case ApplyModel.Result result -> {
                if (result != displayedResult) {
                    for (int i = 0; i < result.steps().size(); i++) {
                        var status = result.steps().get(i).status();
                        if (status == ApplyModel.StepStatus.COMPLETED || status == ApplyModel.StepStatus.FAILED) {
                            selectedIndex = i;
                        }
                        if (status == ApplyModel.StepStatus.FAILED) {
                            break;
                        }
                    }
                    displayedResult = result;
                }
            }
            case ApplyModel.Idle _, ApplyModel.Confirmation _ -> {
                followedAction = null;
                displayedResult = null;
            }
        }
        return ApplyView.render(session.configPath(), model, selectedIndex, spinnerFrame++);
    }

    private Element renderConfigPlaceholder() {
        return Toolkit.column(
                Toolkit.row(
                        Toolkit.text("⌂ HOMELIGHT  ").cyan().bold(),
                        Toolkit.text("[1: Status]").gray().dim(),
                        Toolkit.text("  [2: Plan]").gray().dim(),
                        Toolkit.text("  [3: Apply]").gray().dim(),
                        Toolkit.text("  [4: Config]").cyan().bold()
                ),
                Toolkit.text(""),
                Toolkit.panel("Configuration",
                        Toolkit.column(
                                Toolkit.text("Configuration path: " + session.configPath()).bold()
                        )
                ).fill(),
                Toolkit.text(""),
                Toolkit.row(Toolkit.text("1: Status  ·  2: Plan  ·  q: Quit").gray().dim())
        );
    }

    public EventResult handleKeyEvent(KeyEvent key) {
        var intercepted = prototypeKey(key);
        if (intercepted != null) return intercepted;
        if (session.activeScreen() == Screen.APPLY) {
            return handleApplyKeyEvent(key);
        }
        if (session.activeScreen() == Screen.PLAN && paneFocus == PaneFocus.DETAIL) {
            return handleDetailKeyEvent(key);
        }
        return handleMasterKeyEvent(key);
    }

    private EventResult handleApplyKeyEvent(KeyEvent key) {
        var model = session.applyModel();
        if (key.isUp() || key.isCharIgnoreCase('k')) {
            selectPrevious();
        } else if (key.isDown() || key.isCharIgnoreCase('j')) {
            selectNext();
        } else if (model instanceof ApplyModel.Running) {
            // Mutation has no safe cancellation contract yet, including terminal quit shortcuts.
            return EventResult.HANDLED;
        } else if (model instanceof ApplyModel.Result
                || model instanceof ApplyModel.Confirmation confirmation && !confirmation.plan().hasChanges()) {
            if (key.isKey(KeyCode.ENTER) || key.isChar('1') || key.isCharIgnoreCase('s')) {
                switchScreen(Screen.STATUS);
            } else if (key.isCharIgnoreCase('r') || key.isChar('2') || key.isCharIgnoreCase('p')) {
                refresh();
            } else if (key.isQuit() || key.isCharIgnoreCase('q') || key.isKey(KeyCode.ESCAPE)) {
                quit();
            }
        } else if (model instanceof ApplyModel.Confirmation) {
            if (key.isChar('y')) {
                session.confirmApply();
            } else if (key.isCharIgnoreCase('n') || key.isKey(KeyCode.ESCAPE) || key.isQuit() || key.isCharIgnoreCase('q')) {
                session.cancelApply();
            }
        } else {
            return handleMasterKeyEvent(key);
        }
        return EventResult.HANDLED;
    }

    private EventResult handleMasterKeyEvent(KeyEvent key) {
        if (key.isQuit() || key.isCharIgnoreCase('q') || key.isKey(KeyCode.ESCAPE)) {
            quit();
            return EventResult.HANDLED;
        }
        if (key.isCharIgnoreCase('r')) {
            refresh();
            return EventResult.HANDLED;
        }
        if (key.isChar('1') || key.isCharIgnoreCase('s')) {
            switchScreen(Screen.STATUS);
            return EventResult.HANDLED;
        }
        if (key.isChar('2') || key.isCharIgnoreCase('p')) {
            switchScreen(Screen.PLAN);
            return EventResult.HANDLED;
        }
        if (key.isChar('3')) {
            switchScreen(Screen.APPLY);
            return EventResult.HANDLED;
        }
        if (key.isCharIgnoreCase('a')) {
            if (session.activeScreen() == Screen.PLAN && !session.isPlanReady()) {
                return EventResult.HANDLED;
            }
            switchScreen(Screen.APPLY);
            return EventResult.HANDLED;
        }
        if (key.isCharIgnoreCase('c')) {
            toggleInSync();
            return EventResult.HANDLED;
        }
        boolean isTabOrRight = key.isKey(KeyCode.TAB) || key.isChar('\t') || key.isFocusNext() || key.isRight() || key.isCharIgnoreCase('l');
        if (session.activeScreen() == Screen.PLAN && isTabOrRight) {
            var item = selectedPlanItem();
            if (item != null && !item.availableResolutions().isEmpty()) {
                enterDetailPane(item);
                return EventResult.HANDLED;
            }
        }
        if (key.isChar(' ')) {
            handleSpaceInMaster();
            return EventResult.HANDLED;
        }
        if (key.isKey(KeyCode.ENTER)) {
            handleEnterInMaster();
            return EventResult.HANDLED;
        }
        if (key.isUp() || key.isCharIgnoreCase('k')) {
            selectPrevious();
            return EventResult.HANDLED;
        }
        if (key.isDown() || key.isCharIgnoreCase('j')) {
            selectNext();
            return EventResult.HANDLED;
        }
        if (key.isHome() || key.isChar('g')) {
            selectFirst();
            return EventResult.HANDLED;
        }
        if (key.isEnd() || key.isChar('G')) {
            selectLast();
            return EventResult.HANDLED;
        }
        return EventResult.UNHANDLED;
    }

    private EventResult handleDetailKeyEvent(KeyEvent key) {
        if (key.isChar('1') || key.isChar('2') || key.isChar('3')) {
            if (key.isChar('3') && !session.isPlanReady()) {
                return EventResult.HANDLED;
            }
            return handleMasterKeyEvent(key);
        }
        if (key.isQuit() || key.isCharIgnoreCase('q')) {
            quit();
            return EventResult.HANDLED;
        }
        boolean isBack = key.isKey(KeyCode.ESCAPE) || key.isLeft() || key.isCharIgnoreCase('h')
                || key.isKey(KeyCode.TAB) || key.isChar('\t') || key.isFocusPrevious();
        if (isBack) {
            paneFocus = PaneFocus.MASTER;
            return EventResult.HANDLED;
        }
        if (key.isCharIgnoreCase('r')) {
            refresh();
            return EventResult.HANDLED;
        }
        var item = selectedPlanItem();
        if (item == null || item.availableResolutions().isEmpty()) {
            paneFocus = PaneFocus.MASTER;
            return EventResult.HANDLED;
        }
        int maxIndex = item.availableResolutions().size() - 1;
        if (key.isUp() || key.isCharIgnoreCase('k')) {
            if (detailSelectedIndex > 0) {
                detailSelectedIndex--;
            }
            return EventResult.HANDLED;
        }
        if (key.isDown() || key.isCharIgnoreCase('j')) {
            if (detailSelectedIndex < maxIndex) {
                detailSelectedIndex++;
            }
            return EventResult.HANDLED;
        }
        if (key.isHome() || key.isChar('g')) {
            detailSelectedIndex = 0;
            return EventResult.HANDLED;
        }
        if (key.isEnd() || key.isChar('G')) {
            detailSelectedIndex = maxIndex;
            return EventResult.HANDLED;
        }
        if (key.isChar(' ') || key.isKey(KeyCode.ENTER)) {
            if (detailSelectedIndex >= 0 && detailSelectedIndex <= maxIndex) {
                var choice = item.availableResolutions().get(detailSelectedIndex);
                var sourcePath = item.relocation().sourcePath();
                session.resolveDecision(item.relocation(), choice);
                restoreSelectionBySourcePath(sourcePath, choice);
            }
            return EventResult.HANDLED;
        }
        return EventResult.UNHANDLED;
    }

    private void enterDetailPane(PlanRelocationItem item) {
        paneFocus = PaneFocus.DETAIL;
        if (item.selectedResolution().isPresent()) {
            int idx = item.availableResolutions().indexOf(item.selectedResolution().get());
            detailSelectedIndex = Math.max(0, idx);
        } else {
            detailSelectedIndex = 0;
        }
    }

    public void switchScreen(Screen screen) {
        if (session.isApplying()) {
            return;
        }
        if (screen == Screen.APPLY && session.applyModel() instanceof ApplyModel.Idle) {
            if (session.activeScreen() != Screen.PLAN || !session.requestApply()) {
                screen = Screen.PLAN;
            }
        }
        if (screen == Screen.PLAN && session.applyModel() instanceof ApplyModel.Result) {
            session.refresh();
        }
        session.setActiveScreen(screen);
        selectedIndex = 0;
        paneFocus = PaneFocus.MASTER;
        detailSelectedIndex = 0;
        syncInSyncSetting();
    }

    public void refresh() {
        session.refresh();
        clampSelectedIndex();
    }

    private void handleSpaceInMaster() {
        if (session.activeScreen() == Screen.PLAN) {
            var selectedItem = selectedPlanItem();
            if (selectedItem != null && !selectedItem.availableResolutions().isEmpty()) {
                enterDetailPane(selectedItem);
                return;
            }
        }
        toggleInSync();
    }

    private void handleEnterInMaster() {
        if (session.activeScreen() == Screen.PLAN) {
            var selectedItem = selectedPlanItem();
            if (selectedItem != null && !selectedItem.availableResolutions().isEmpty() && selectedItem.hasConflict()) {
                enterDetailPane(selectedItem);
                return;
            }
            if (session.isPlanReady()) {
                switchScreen(Screen.APPLY);
                return;
            }
        }
    }

    public void resolveSelected(DecisionChoice choice) {
        if (session.activeScreen() == Screen.PLAN) {
            var selectedItem = selectedPlanItem();
            if (selectedItem != null) {
                var sourcePath = selectedItem.relocation().sourcePath();
                session.resolveDecision(selectedItem.relocation(), choice);
                restoreSelectionBySourcePath(sourcePath, choice);
            }
        }
    }

    private io.github.bigswlittlesw.homelight.application.PlanRelocationItem selectedPlanItem() {
        if (session.planModel() instanceof PlanModel.Configured configured) {
            var visible = PlanView.visibleItems(configured, showInSync);
            if (!visible.isEmpty() && selectedIndex >= 0 && selectedIndex < visible.size()) {
                return visible.get(selectedIndex);
            }
        }
        return null;
    }

    public void toggleInSync() {
        userShowInSync = !showInSync;
        showInSync = userShowInSync;
        clampSelectedIndex();
    }

    public void selectPrevious() {
        if (selectedIndex > 0) {
            selectedIndex--;
            resetDetailSelection();
        }
    }

    public void selectNext() {
        int maxIndex = visibleItemCount() - 1;
        if (selectedIndex < maxIndex) {
            selectedIndex++;
            resetDetailSelection();
        }
    }

    public void selectFirst() {
        selectedIndex = 0;
        resetDetailSelection();
    }

    public void selectLast() {
        int maxIndex = visibleItemCount() - 1;
        if (maxIndex >= 0) {
            selectedIndex = maxIndex;
            resetDetailSelection();
        }
    }

    private void resetDetailSelection() {
        var item = selectedPlanItem();
        if (item != null && item.selectedResolution().isPresent()) {
            int idx = item.availableResolutions().indexOf(item.selectedResolution().get());
            detailSelectedIndex = Math.max(0, idx);
        } else {
            detailSelectedIndex = 0;
        }
    }

    public HomeLightSession session() {
        return session;
    }

    public StatusModel statusModel() {
        return session.statusModel();
    }

    public PlanModel planModel() {
        return session.planModel();
    }

    public int selectedIndex() {
        return selectedIndex;
    }

    public PaneFocus paneFocus() {
        return paneFocus;
    }

    public int detailSelectedIndex() {
        return detailSelectedIndex;
    }

    public boolean showInSync() {
        return showInSync;
    }

    private int visibleItemCount() {
        return switch (session.activeScreen()) {
            case STATUS -> {
                if (session.statusModel() instanceof StatusModel.Configured configured) {
                    yield StatusView.visibleItems(configured, showInSync).size();
                }
                yield 0;
            }
            case PLAN -> {
                if (session.planModel() instanceof PlanModel.Configured configured) {
                    yield PlanView.visibleItems(configured, showInSync).size();
                }
                yield 0;
            }
            case APPLY -> ApplyView.steps(session.applyModel()).size();
            default -> 0;
        };
    }

    private void syncInSyncSetting() {
        if (userShowInSync != null) {
            this.showInSync = userShowInSync;
        } else {
            if (session.activeScreen() == Screen.STATUS && session.statusModel() instanceof StatusModel.Configured configured) {
                this.showInSync = configured.summary().inSync() == configured.summary().total();
            } else if (session.activeScreen() == Screen.PLAN && session.planModel() instanceof PlanModel.Configured configured) {
                this.showInSync = configured.summary().inSync() == configured.summary().total();
            } else {
                this.showInSync = false;
            }
        }
        clampSelectedIndex();
    }

    private void restoreSelectionBySourcePath(Path sourcePath, DecisionChoice choice) {
        if (session.planModel() instanceof PlanModel.Configured configured) {
            var visible = PlanView.visibleItems(configured, showInSync);
            for (int i = 0; i < visible.size(); i++) {
                if (visible.get(i).relocation().sourcePath().equals(sourcePath)) {
                    selectedIndex = i;
                    var item = visible.get(i);
                    int choiceIdx = item.availableResolutions().indexOf(choice);
                    if (choiceIdx >= 0) {
                        detailSelectedIndex = choiceIdx;
                    }
                    return;
                }
            }
            clampSelectedIndex();
        }
    }

    private void clampSelectedIndex() {
        int count = visibleItemCount();
        if (count == 0) {
            selectedIndex = 0;
        } else {
            selectedIndex = Math.clamp(selectedIndex, 0, count - 1);
        }
        resetDetailSelection();
    }

    public static void main(String[] args) throws Exception {
        var app = new HomeLightApp(Path.of("/demo/never-read.yaml"));
        app.workspace = java.util.List.of(args).contains("--workspace");
        if (app.workspace) app.session.setActiveScreen(Screen.PLAN);
        app.run();
    }

    private EventResult prototypeKey(KeyEvent key) {
        if (!editField.isEmpty()) {
            if (key.isKey(KeyCode.ESCAPE)) editField = "";
            else if (key.isKey(KeyCode.ENTER)) {
                if (!editValue.startsWith("/") || editValue.contains("..")) session.notice = "Enter an absolute path without '..'.";
                else { switch (editField) { case "Source root" -> session.root = editValue; case "Target root" -> session.target = editValue; default -> session.sharedPath = editValue; } editField = ""; }
            } else if (key.isKey(KeyCode.BACKSPACE)) editValue = editValue.isEmpty() ? "" : editValue.substring(0, editValue.length() - 1);
            else if (key.character() >= 32) editValue += Character.toString(key.character());
            return EventResult.HANDLED;
        }
        if (!overlay.isEmpty()) {
            if (overlay.equals("scenarios")) {
                for (int i = 1; i <= 5; i++) if (key.isChar((char) ('0' + i))) {
                    session.reset(i - 1); selectedIndex = 0; paneFocus = PaneFocus.MASTER; detailScroll = -1; overlay = "";
                    if (workspace) session.setActiveScreen(Screen.PLAN);
                    syncInSyncSetting();
                    return EventResult.HANDLED;
                }
            }
            if (key.isChar(']') || key.isDown() || key.isChar('j')) overlayPage++;
            if (key.isChar('[') || key.isUp() || key.isChar('k')) overlayPage = Math.max(0, overlayPage - 1);
            if (key.isKey(KeyCode.ESCAPE) || key.isChar('n') || key.isChar('i') || key.isChar('?')) overlay = "";
            return EventResult.HANDLED;
        }
        if (!session.isApplying() && (key.isChar('?') || key.isChar('!') || key.isChar('i'))) {
            overlay = key.isChar('?') ? "help" : key.isChar('!') ? "scenarios" : "inspect";
            overlayPage = 0; return EventResult.HANDLED;
        }
        if (session.isApplying()) return null;
        if (session.activeScreen() == Screen.CONFIG) { setupKey(key); return EventResult.HANDLED; }
        if (key.isChar('4')) { session.setActiveScreen(Screen.CONFIG); saveReview = false; return EventResult.HANDLED; }
        if (key.isChar('v') && session.activeScreen() != Screen.APPLY) {
            Path selected = null;
            if (session.activeScreen() == Screen.STATUS && session.statusModel() instanceof StatusModel.Configured m) {
                var rows = StatusView.visibleItems(m, showInSync);
                if (selectedIndex < rows.size()) selected = rows.get(selectedIndex).relocation().sourcePath();
            } else if (selectedPlanItem() != null) selected = selectedPlanItem().relocation().sourcePath();
            workspace = !workspace; session.setActiveScreen(workspace ? Screen.PLAN : Screen.STATUS);
            selectedIndex = 0; paneFocus = PaneFocus.MASTER; detailScroll = -1;
            if (workspace && selected != null) restoreSelectionBySourcePath(selected, session.draft(selected).orElse(DecisionChoice.ADOPT_AND_DISCARD_SOURCE));
            else if (selected != null && session.statusModel() instanceof StatusModel.Configured m) {
                var rows = StatusView.visibleItems(m, showInSync);
                for (int i = 0; i < rows.size(); i++) if (rows.get(i).relocation().sourcePath().equals(selected)) selectedIndex = i;
            }
            return EventResult.HANDLED;
        }
        if (!workspace) return null;
        if (session.activeScreen() == Screen.APPLY) {
            if (session.applyModel() instanceof ApplyModel.Confirmation && (key.isChar('n') || key.isKey(KeyCode.ESCAPE))) {
                session.cancelApply(); selectedIndex = returnIndex; paneFocus = returnFocus; return EventResult.HANDLED;
            }
            if (session.applyModel() instanceof ApplyModel.Result && (key.isChar('1') || key.isKey(KeyCode.ENTER))) {
                session.setActiveScreen(Screen.PLAN); selectedIndex = returnIndex; paneFocus = returnFocus; clampSelectedIndex(); return EventResult.HANDLED;
            }
            return null;
        }
        if (key.isChar('3') || key.isChar('a')) {
            returnIndex = selectedIndex; returnFocus = paneFocus;
            if (session.applyModel() instanceof ApplyModel.Result) session.setActiveScreen(Screen.APPLY);
            else session.requestApply();
            return EventResult.HANDLED;
        }
        if (key.isChar('1') || key.isChar('2')) return EventResult.HANDLED;
        if (key.isChar(']') || key.isChar('[')) {
            detailScroll = Math.max(0, detailScroll + (key.isChar(']') ? 3 : -3)); return EventResult.HANDLED;
        }
        if (key.isChar('j') || key.isChar('k') || key.isUp() || key.isDown() || key.isChar('l') || key.isChar('h')) detailScroll = -1;
        return null;
    }

    private Element renderOverlay() {
        var lines = new java.util.ArrayList<String>();
        if (overlay.equals("help")) {
            lines.add("WHAT ARE WE COMPARING?");
            lines.add("Same HomeLight, same directories, same choices and reviewed actions.");
            lines.add("A: the existing Status / Plan / Apply screens and their existing keys.");
            lines.add("B: replace Status + Plan with one observation-and-decision workspace.");
            lines.add("Review and Apply are the existing screens in both variants.");
            lines.add("");
            lines.add("TRY ONE TASK:");
            lines.add("A: select conflict-cache. 2 opens Plan; l enters choices; Space selects.");
            lines.add("3 reviews. n cancels. Notice where selection and focus return.");
            lines.add("v switches to B without clearing the draft or changing the fixture.");
            lines.add("B: inspect the same relocation; l chooses; j/k move; Space selects.");
            lines.add("3 reviews, n cancels. Compare the amount of navigation and context.");
            lines.add("");
            lines.add("All observations, save and execution are in memory. No paths are read.");
            lines.add("i shows full paths/results, including text clipped in the real baseline.");
            lines.add("! opens optional failure/setup scenarios. 4 opens simulated setup.");
            lines.add("r explicitly replans and clears the retained result. q quits when idle.");
            lines.add("v switches layouts only before/after review, never during execution.");
        } else if (overlay.equals("scenarios")) {
            lines.add("OPTIONAL DEMO SCENARIOS: selecting one resets the in-memory session.");
            lines.add("1 Returning user: conflict, destructive policy, pending and unchanged");
            lines.add("2 Preflight rejection: no simulated changes before rejection");
            lines.add("3 Partial execution: stop after four mutating actions complete");
            lines.add("4 Missing default config: press 4 for manual setup");
            lines.add("5 Missing explicit config: press 4 for manual setup");
            lines.add("");
            lines.add("Current: " + session.scenarioName());
        } else if (session.activeScreen() == Screen.APPLY) {
            var steps = ApplyView.steps(session.applyModel());
            var changes = steps.stream().filter(s -> s.action().mutatesFilesystem()).toList();
            lines.add("REVIEW / EXECUTION DETAIL (same actual plan as the Apply screen)");
            lines.add(changes.stream().filter(s -> s.status() == ApplyModel.StepStatus.COMPLETED).count() + " completed; "
                    + changes.stream().filter(s -> s.status() == ApplyModel.StepStatus.FAILED).count() + " failed; "
                    + changes.stream().filter(s -> s.status() == ApplyModel.StepStatus.PENDING).count() + " not run");
            if (session.applyModel() instanceof ApplyModel.Result r) lines.addAll(r.diagnostics());
            for (var step : steps) {
                lines.add(step.status() + ": " + ApplyView.actionLabel(step.action()));
                lines.add("Source: " + step.relocation().relocation().sourcePath());
                lines.add("Target: " + step.relocation().relocation().targetPath());
                lines.add("Affected action: " + step.action());
                lines.add(step.message());
            }
        } else {
            var selected = selectedPlanItem();
            if (session.activeScreen() == Screen.STATUS && session.statusModel() instanceof StatusModel.Configured m) {
                var row = StatusView.visibleItems(m, showInSync).get(selectedIndex);
                if (session.planModel() instanceof PlanModel.Configured p) selected = p.items().stream().filter(i -> i.relocation().sourcePath().equals(row.relocation().sourcePath())).findFirst().orElse(null);
            }
            if (selected != null) {
                lines.add("Current source: " + selected.sourceObservation());
                lines.add("Current target: " + selected.targetObservation());
                lines.add("Source: " + selected.relocation().sourcePath());
                lines.add("Target: " + selected.relocation().targetPath());
                lines.add("Saved policy: " + session.savedPolicy(selected.relocation().sourcePath()));
                lines.add("Draft: " + session.draft(selected.relocation().sourcePath()).map(DecisionChoice::label).orElse("none"));
                lines.add("Expected outcome with draft: " + selected.plan().outcome());
                for (var c : selected.availableResolutions()) { lines.add(c.label()); lines.add(c.description()); }
                for (var action : selected.plan().actions()) lines.add(action.toString());
            } else lines.add("No configured relocation. Press 4 for setup after closing this view.");
        }
        var size = runner().tuiRunner().terminal().size();
        var wrapped = new java.util.ArrayList<String>();
        for (var line : lines) { if (line.isEmpty()) wrapped.add(""); else WorkspaceView.add(wrapped, line, Math.max(20, size.width() - 4)); }
        int height = Math.max(1, size.height() - 5);
        int pages = Math.max(1, (wrapped.size() + height - 1) / height);
        overlayPage = Math.clamp(overlayPage, 0, pages - 1);
        var rows = new java.util.ArrayList<Element>();
        rows.add(Toolkit.text("HomeLight prototype | " + overlay + " | page " + (overlayPage + 1) + "/" + pages).cyan().bold());
        for (int i = overlayPage * height; i < Math.min(wrapped.size(), (overlayPage + 1) * height); i++) rows.add(Toolkit.text(wrapped.get(i)));
        rows.add(Toolkit.text("[/]: page | n or Esc: return").yellow());
        return Toolkit.column(rows.toArray(Element[]::new));
    }

    private Element renderSetup() {
        var lines = new java.util.ArrayList<Element>();
        lines.add(Toolkit.text("⌂ HOMELIGHT  Configuration / simulated save").cyan().bold());
        if (!editField.isEmpty()) {
            lines.add(Toolkit.text(editField + ": " + editValue + "_"));
            lines.add(Toolkit.text("Type path, Backspace edits, Enter accepts, Esc cancels."));
        } else if (saveReview) {
            lines.add(Toolkit.text("Save explicitly selected relocations to " + session.configPath() + "?"));
            for (int i = 0; i < 3; i++) if (session.candidates[i]) lines.add(Toolkit.text(session.root + "/" + new String[]{"manual-cache", "build-cache", "team-cache"}[i] + " -> " + session.target));
            lines.add(Toolkit.text("IN MEMORY ONLY. This does not apply relocations. y saves; n cancels."));
        } else {
            lines.add(Toolkit.text("Config: " + session.configPath()));
            lines.add(Toolkit.text("Source root: " + session.root + "  (o edit)"));
            lines.add(Toolkit.text("Target root: " + session.target + "  (t edit)"));
            lines.add(Toolkit.text("Optional shared list: " + session.sharedPath + "  (e edit)"));
            lines.add(Toolkit.text("Shared list: " + (session.sharedAvailable ? "available" : "UNAVAILABLE; manual / built-in choices still work") + "  (u toggle)").yellow());
            lines.add(Toolkit.text("Candidates are suggestions; approximate sizes are simulated."));
            String[] names = {"manual-cache  manual  ~1 GiB", "build-cache  built-in + shared  ~8 GiB", "team-cache  shared  ~3 GiB"};
            for (int i = 0; i < 3; i++) lines.add(Toolkit.text((i == setupSelection ? "> " : "  ") + (session.candidates[i] ? "[x] " : "[ ] ") + names[i]).fg(i == setupSelection ? dev.tamboui.style.Color.CYAN : dev.tamboui.style.Color.WHITE));
            lines.add(Toolkit.text("Configured entries: " + session.savedRelocations().size() + " (refresh never changes these)"));
            lines.add(Toolkit.text("j/k select | Space toggle | f refresh list | s review save | n cancel"));
        }
        lines.add(Toolkit.text(session.notice).yellow());
        return Toolkit.column(lines.toArray(Element[]::new));
    }

    private void setupKey(KeyEvent key) {
        if (key.isChar('q')) { quit(); return; }
        if (saveReview) {
            if (key.isChar('n')) saveReview = false;
            if (key.isChar('y')) { session.saveCandidates(); saveReview = false; selectedIndex = 0; if (workspace) session.setActiveScreen(Screen.PLAN); syncInSyncSetting(); }
            return;
        }
        if (key.isChar('n') || key.isKey(KeyCode.ESCAPE)) { session.candidates = new boolean[3]; session.setActiveScreen(workspace ? Screen.PLAN : Screen.STATUS); session.notice = "Setup cancelled; nothing saved."; }
        if (key.isChar('j') || key.isDown()) setupSelection = Math.min(2, setupSelection + 1);
        if (key.isChar('k') || key.isUp()) setupSelection = Math.max(0, setupSelection - 1);
        if (key.isChar(' ') && (setupSelection != 2 || session.sharedAvailable || session.candidates[2])) session.candidates[setupSelection] = !session.candidates[setupSelection];
        if (key.isChar('u') || key.isChar('f')) { session.sharedAvailable = !session.sharedAvailable; session.notice = "Suggestions refreshed. Draft selections and configured relocations unchanged."; }
        if (key.isChar('s')) {
            if (!session.savedRelocations().isEmpty()) session.notice = "Setup demo only creates missing configuration. Existing relocations preserved.";
            else if (session.root.equals(session.target)) session.notice = "Source and target roots must differ.";
            else if (session.candidates[0] || session.candidates[1] || session.candidates[2]) saveReview = true;
            else session.notice = "Select a candidate first.";
        }
        if (key.isChar('o')) { editField = "Source root"; editValue = session.root; }
        if (key.isChar('t')) { editField = "Target root"; editValue = session.target; }
        if (key.isChar('e')) { editField = "Shared list"; editValue = session.sharedPath; }
    }

}
