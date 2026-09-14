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
public final class HomeLightApp extends ToolkitApp {
    private final HomeLightSession session;
    private final TuiConfig customTuiConfig;

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
    }

    @Override
    protected void onStop() {
        session.awaitExecution();
    }

    @Override
    protected Element render() {
        var view = switch (session.activeScreen()) {
            case STATUS -> StatusView.render(session.statusModel(), selectedIndex, showInSync);
            case PLAN -> PlanView.render(session.planModel(), selectedIndex, showInSync, paneFocus, detailSelectedIndex);
            case APPLY -> renderApply();
            case CONFIG -> renderConfigPlaceholder();
        };
        if (view instanceof Column col) {
            return col.id("homelight-screen").onKeyEvent(this::handleKeyEvent).focusable();
        }
        return view;
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
}
