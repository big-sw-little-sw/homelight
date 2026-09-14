package io.github.bigswlittlesw.homelight.tui;

import dev.tamboui.toolkit.Toolkit;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import io.github.bigswlittlesw.homelight.application.ApplyModel;
import io.github.bigswlittlesw.homelight.application.DecisionChoice;
import io.github.bigswlittlesw.homelight.application.HomeLightSession;
import io.github.bigswlittlesw.homelight.application.PlanModel;
import io.github.bigswlittlesw.homelight.application.PlanRelocationItem;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/// Owns navigation and inspection; the session owns decisions and guarded execution.
public final class HomeLightApp {
    private final HomeLightSession session;
    private final TuiConfig customTuiConfig;
    private Screen activeScreen = Screen.WORKSPACE;
    private int selectedIndex;
    private int actionIndex;
    private Path reviewedSource;
    private PaneFocus paneFocus = PaneFocus.MASTER;
    private PaneFocus actionFocus = PaneFocus.MASTER;
    private int detailSelectedIndex;
    private boolean showInSync;
    private Boolean userShowInSync;
    private int spinnerFrame;
    private ReconciliationAction followedAction;
    private ApplyModel.Result displayedResult;
    private final DetailViewport workspaceDetails = new DetailViewport();
    private final DetailViewport actionDetails = new DetailViewport();
    private ExitIntent exitIntent = ExitIntent.STAY;
    private enum ExitIntent { STAY, CONFIRM_KEEP, CONFIRM_EXIT, AFTER_EXECUTION, EXIT }

    public HomeLightApp(Path configPath) { this(new HomeLightSession(configPath)); }
    public HomeLightApp(HomeLightSession session) { this(session, null); }
    public HomeLightApp(HomeLightSession session, TuiConfig customTuiConfig) {
        this.session = Objects.requireNonNull(session);
        this.customTuiConfig = customTuiConfig;
        syncInSyncSetting();
    }
    protected TuiConfig configure() { return customTuiConfig == null ? TuiConfig.defaults() : customTuiConfig; }
    public void run() throws Exception { TuiLauncher.run(this); }
    boolean exitRequested() { return exitIntent == ExitIntent.EXIT; }
    Screen activeScreen() { return activeScreen; }

    protected Element render() {
        settleDeferredExit();
        var view = activeScreen == Screen.APPLY ? renderApply()
                : WorkspaceView.render(session, selectedIndex, showInSync, paneFocus, detailSelectedIndex, workspaceDetails);
        var content = view instanceof dev.tamboui.toolkit.elements.Column column ? column.fill() : Toolkit.column(view).fill();
        if (exitIntent == ExitIntent.CONFIRM_KEEP || exitIntent == ExitIntent.CONFIRM_EXIT) {
            content = Toolkit.column(content, Toolkit.panel("Quit HomeLight?", Toolkit.column(
                    Toolkit.text("Filesystem operations will finish, including on failure."),
                    Toolkit.text("Results are session-local and won't remain available after exit."),
                    Toolkit.text((exitIntent == ExitIntent.CONFIRM_KEEP ? "❯ " : "  ") + "Keep running"),
                    Toolkit.text((exitIntent == ExitIntent.CONFIRM_EXIT ? "❯ " : "  ") + "Exit when execution finishes"),
                    Toolkit.text("↑/↓ or Tab: Choose  ·  Enter: Confirm  ·  Esc: Cancel").gray()
            )).length(7));
        } else if (exitIntent == ExitIntent.AFTER_EXECUTION) {
            content = Toolkit.column(content, Toolkit.text("Will exit after execution settles, including failure.").yellow(),
                    Toolkit.text("Session-local results won't remain available after exit.").gray());
        }
        return content.id("homelight-screen").onKeyEvent(this::handleKeyEvent).focusable();
    }

    private Element renderApply() {
        var model = session.applyModel();
        switch (model) {
            case ApplyModel.Running running -> {
                for (int i = 0; i < running.steps().size(); i++) {
                    var step = running.steps().get(i);
                    if (step.status() == ApplyModel.StepStatus.RUNNING && step.action() != followedAction) {
                        // Manual inspection lasts until the next action transition.
                        actionIndex = i;
                        actionDetails.reset();
                        followedAction = step.action();
                        break;
                    }
                }
            }
            case ApplyModel.Result result -> {
                if (result != displayedResult) {
                    for (int i = 0; i < result.steps().size(); i++) {
                        var status = result.steps().get(i).status();
                        if (status == ApplyModel.StepStatus.COMPLETED || status == ApplyModel.StepStatus.FAILED) actionIndex = i;
                        if (status == ApplyModel.StepStatus.FAILED) break;
                    }
                    actionDetails.reset();
                    displayedResult = result;
                }
            }
            case ApplyModel.Idle _, ApplyModel.Confirmation _ -> {
                followedAction = null;
                displayedResult = null;
            }
        }
        return ApplyView.render(session.configPath(), model, actionIndex, spinnerFrame++, actionFocus, actionDetails);
    }

    public EventResult handleKeyEvent(KeyEvent key) {
        if (exitIntent == ExitIntent.CONFIRM_KEEP || exitIntent == ExitIntent.CONFIRM_EXIT) return handleExitDialog(key);
        if (key.isKey(KeyCode.ESCAPE)) {
            if (activeScreen == Screen.APPLY && session.applyModel() instanceof ApplyModel.Confirmation) {
                session.cancelApply();
                activeScreen = Screen.WORKSPACE;
            } else if (activeScreen == Screen.APPLY) actionFocus = PaneFocus.MASTER;
            else paneFocus = PaneFocus.MASTER;
            return EventResult.HANDLED;
        }
        if (key.isQuit() || key.isCharIgnoreCase('q')) {
            if (exitIntent == ExitIntent.STAY) exitIntent = session.isApplying() || !session.executionSettled()
                    ? ExitIntent.CONFIRM_KEEP : ExitIntent.EXIT;
            return EventResult.HANDLED;
        }
        if (exitIntent == ExitIntent.EXIT) return EventResult.HANDLED;
        if (key.isChar('1')) { switchScreen(Screen.WORKSPACE); return EventResult.HANDLED; }
        if (key.isChar('2')) { switchScreen(Screen.APPLY); return EventResult.HANDLED; }
        if (activeScreen == Screen.APPLY) return handleApplyKeyEvent(key);
        if (key.isCharIgnoreCase('r')) { refresh(); return EventResult.HANDLED; }
        if (key.isCharIgnoreCase('a')) { switchScreen(Screen.APPLY); return EventResult.HANDLED; }
        if (key.isCharIgnoreCase('c')) { toggleInSync(); return EventResult.HANDLED; }
        if (key.isChar('[') || key.isChar(']')) {
            workspaceDetails.scroll(key.isChar(']') ? 1 : -1);
            return EventResult.HANDLED;
        }
        if (isTab(key) || key.isRight() || key.isCharIgnoreCase('l')) {
            paneFocus = isTab(key) && paneFocus == PaneFocus.DETAIL ? PaneFocus.MASTER : PaneFocus.DETAIL;
            if (paneFocus == PaneFocus.DETAIL) workspaceDetails.followChoice();
            return EventResult.HANDLED;
        }
        if (key.isLeft() || key.isCharIgnoreCase('h')) { paneFocus = PaneFocus.MASTER; return EventResult.HANDLED; }
        var item = selectedPlanItem();
        boolean choices = paneFocus == PaneFocus.DETAIL && item != null && !item.availableResolutions().isEmpty()
                && !(session.applyModel() instanceof ApplyModel.Result);
        if (key.isUp() || key.isCharIgnoreCase('k') || key.isDown() || key.isCharIgnoreCase('j')) {
            int delta = key.isUp() || key.isCharIgnoreCase('k') ? -1 : 1;
            if (choices) {
                detailSelectedIndex = Math.clamp(detailSelectedIndex + delta, 0, item.availableResolutions().size() - 1);
                workspaceDetails.followChoice();
            } else if (paneFocus == PaneFocus.DETAIL) workspaceDetails.scroll(delta);
            else if (delta < 0) selectPrevious(); else selectNext();
        } else if (key.isHome() || key.isChar('g') || key.isEnd() || key.isChar('G')) {
            boolean end = key.isEnd() || key.isChar('G');
            if (choices) {
                detailSelectedIndex = end ? item.availableResolutions().size() - 1 : 0;
                workspaceDetails.followChoice();
            } else if (paneFocus == PaneFocus.DETAIL) workspaceDetails.scroll(end ? Integer.MAX_VALUE : -Integer.MAX_VALUE);
            else if (end) selectLast(); else selectFirst();
        } else if (key.isChar(' ') || key.isKey(KeyCode.ENTER)) {
            if (choices) resolveSelected(item.availableResolutions().get(detailSelectedIndex));
            else { paneFocus = PaneFocus.DETAIL; workspaceDetails.followChoice(); }
        }
        return EventResult.HANDLED;
    }

    private EventResult handleApplyKeyEvent(KeyEvent key) {
        var model = session.applyModel();
        if (isTab(key) || key.isRight() || key.isCharIgnoreCase('l')) {
            actionFocus = isTab(key) && actionFocus == PaneFocus.DETAIL ? PaneFocus.MASTER : PaneFocus.DETAIL;
        } else if (key.isLeft() || key.isCharIgnoreCase('h')) actionFocus = PaneFocus.MASTER;
        else if (key.isChar('[') || key.isChar(']')) actionDetails.scroll(key.isChar(']') ? 1 : -1);
        else if (key.isUp() || key.isCharIgnoreCase('k')) {
            if (actionFocus == PaneFocus.DETAIL) actionDetails.scroll(-1); else selectPrevious();
        } else if (key.isDown() || key.isCharIgnoreCase('j')) {
            if (actionFocus == PaneFocus.DETAIL) actionDetails.scroll(1); else selectNext();
        } else if (model instanceof ApplyModel.Running || exitIntent == ExitIntent.AFTER_EXECUTION) return EventResult.HANDLED;
        else if (model instanceof ApplyModel.Confirmation confirmation) {
            if (key.isChar('y') && confirmation.plan().hasChanges()) session.confirmApply();
            else if (key.isCharIgnoreCase('n') || !confirmation.plan().hasChanges() && key.isKey(KeyCode.ENTER)) {
                session.cancelApply();
                activeScreen = Screen.WORKSPACE;
            }
        } else if (model instanceof ApplyModel.Result) {
            if (key.isKey(KeyCode.ENTER) || key.isChar('1')) switchScreen(Screen.WORKSPACE);
            else if (key.isCharIgnoreCase('r')) refresh();
        }
        return EventResult.HANDLED;
    }

    private EventResult handleExitDialog(KeyEvent key) {
        if (key.isKey(KeyCode.ESCAPE)) exitIntent = ExitIntent.STAY;
        else if (key.isUp() || key.isCharIgnoreCase('k')) exitIntent = ExitIntent.CONFIRM_KEEP;
        else if (key.isDown() || key.isCharIgnoreCase('j')) exitIntent = ExitIntent.CONFIRM_EXIT;
        else if (isTab(key)) exitIntent = exitIntent == ExitIntent.CONFIRM_KEEP ? ExitIntent.CONFIRM_EXIT : ExitIntent.CONFIRM_KEEP;
        else if (key.isKey(KeyCode.ENTER)) {
            exitIntent = exitIntent == ExitIntent.CONFIRM_KEEP ? ExitIntent.STAY : ExitIntent.AFTER_EXECUTION;
            settleDeferredExit();
        }
        return EventResult.HANDLED;
    }
    private static boolean isTab(KeyEvent key) {
        return key.isKey(KeyCode.TAB) || key.isChar('\t') || key.isFocusNext() || key.isFocusPrevious();
    }
    private void settleDeferredExit() {
        // Result publication alone does not imply that post-execution refresh has settled.
        if (exitIntent == ExitIntent.AFTER_EXECUTION && session.executionSettled()) exitIntent = ExitIntent.EXIT;
    }
    void switchScreen(Screen screen) {
        if (session.isApplying() || !session.executionSettled()) return;
        if (screen == activeScreen) return;
        if (screen == Screen.APPLY) {
            if (session.applyModel() instanceof ApplyModel.Idle && !session.requestApply()) return;
            if (activeScreen == Screen.WORKSPACE && selectedPlanItem() != null) reviewedSource = selectedPlanItem().relocation().sourcePath();
            if (activeScreen != Screen.APPLY && session.applyModel() instanceof ApplyModel.Confirmation) {
                actionIndex = 0;
                actionFocus = PaneFocus.MASTER;
                actionDetails.reset();
            }
        } else { session.cancelApply(); restoreSelection(reviewedSource); }
        activeScreen = screen;
    }
    public void refresh() {
        if (session.isApplying() || !session.executionSettled()) return;
        var source = selectedPlanItem() == null ? null : selectedPlanItem().relocation().sourcePath();
        session.refresh();
        activeScreen = Screen.WORKSPACE;
        syncInSyncSetting();
        restoreSelection(source);
        resetDetailSelection();
    }
    public void resolveSelected(DecisionChoice choice) {
        var item = selectedPlanItem();
        if (item == null || session.applyModel() instanceof ApplyModel.Result) return;
        session.choose(item.relocation().sourcePath(), choice);
        restoreSelection(item.relocation().sourcePath());
        detailSelectedIndex = selectedPlanItem().availableResolutions().indexOf(choice);
        workspaceDetails.followChoice();
    }
    private List<PlanRelocationItem> visibleItems() {
        return session.planModel() instanceof PlanModel.Configured model ? WorkspaceView.visibleItems(model, showInSync) : List.of();
    }
    private PlanRelocationItem selectedPlanItem() {
        var items = visibleItems();
        return items.isEmpty() ? null : items.get(Math.clamp(selectedIndex, 0, items.size() - 1));
    }
    public void toggleInSync() {
        var source = selectedPlanItem() == null ? null : selectedPlanItem().relocation().sourcePath();
        userShowInSync = !showInSync;
        showInSync = userShowInSync;
        restoreSelection(source);
    }
    public void selectPrevious() { select(-1, false); }
    public void selectNext() { select(1, false); }
    public void selectFirst() { select(0, true); }
    public void selectLast() { select(Integer.MAX_VALUE, true); }
    private void select(int value, boolean absolute) {
        if (activeScreen == Screen.APPLY) {
            actionIndex = Math.clamp(absolute ? value : actionIndex + value, 0, Math.max(0, ApplyView.steps(session.applyModel()).size() - 1));
            actionDetails.reset();
        } else {
            selectedIndex = Math.clamp(absolute ? value : selectedIndex + value, 0, Math.max(0, visibleItems().size() - 1));
            resetDetailSelection();
        }
    }
    private void resetDetailSelection() {
        var item = selectedPlanItem();
        detailSelectedIndex = item == null ? 0 : item.selectedResolution()
                .map(choice -> Math.max(0, item.availableResolutions().indexOf(choice))).orElse(0);
        workspaceDetails.reset();
    }
    private void restoreSelection(Path source) {
        var items = visibleItems();
        for (int i = 0; i < items.size(); i++) if (items.get(i).relocation().sourcePath().equals(source)) { selectedIndex = i; return; }
        clampSelection();
    }
    private void clampSelection() { selectedIndex = Math.clamp(selectedIndex, 0, Math.max(0, visibleItems().size() - 1)); }
    private void syncInSyncSetting() {
        showInSync = userShowInSync != null ? userShowInSync : session.planModel() instanceof PlanModel.Configured model
                && model.summary().inSync() == model.summary().total();
        clampSelection();
    }
    public HomeLightSession session() { return session; }
    public PlanModel planModel() { return session.planModel(); }
    public int selectedIndex() { return activeScreen == Screen.APPLY ? actionIndex : selectedIndex; }
    public PaneFocus paneFocus() { return activeScreen == Screen.APPLY ? actionFocus : paneFocus; }
    public int detailSelectedIndex() { return detailSelectedIndex; }
    public boolean showInSync() { return showInSync; }
}
