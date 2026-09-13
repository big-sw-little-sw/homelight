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
import io.github.bigswlittlesw.homelight.application.HomeLightSession;
import io.github.bigswlittlesw.homelight.application.PlanModel;
import io.github.bigswlittlesw.homelight.application.PlanWorkflow;
import io.github.bigswlittlesw.homelight.application.Screen;
import io.github.bigswlittlesw.homelight.application.StatusModel;
import io.github.bigswlittlesw.homelight.application.StatusWorkflow;

import java.nio.file.Path;
import java.util.Objects;

/// Full-screen TamboUI application for HomeLight interactive terminal workflows.
public final class HomeLightApp extends ToolkitApp {
    private final HomeLightSession session;
    private final TuiConfig customTuiConfig;

    private int selectedIndex;
    private boolean showConverged;
    private Boolean userShowConverged;

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
        syncConvergedSetting();
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
    protected Element render() {
        var view = switch (session.activeScreen()) {
            case STATUS -> StatusView.render(session.statusModel(), selectedIndex, showConverged);
            case PLAN -> PlanView.render(session.planModel(), selectedIndex, showConverged);
            case APPLY -> renderApplyPlaceholder();
            case CONFIG -> renderConfigPlaceholder();
        };
        if (view instanceof Column col) {
            return col.onKeyEvent(this::handleKeyEvent).focusable();
        }
        return view;
    }

    private Element renderApplyPlaceholder() {
        return Toolkit.column(
                Toolkit.row(
                        Toolkit.text("⌂ HOMELIGHT  ").cyan().bold(),
                        Toolkit.text("[1: Status]").gray().dim(),
                        Toolkit.text("  [2: Plan]").gray().dim(),
                        Toolkit.text("  [3: Apply]").cyan().bold()
                ),
                Toolkit.text(""),
                Toolkit.panel("Apply Plan",
                        Toolkit.column(
                                Toolkit.text("Ready to apply reconciliation plan.").bold(),
                                Toolkit.text(""),
                                session.isPlanReady()
                                        ? Toolkit.text("All checks passed. Execution is guarded.").green()
                                        : Toolkit.text("Plan has unresolved conflicts or blocked actions.").yellow()
                        )
                ).fill(),
                Toolkit.text(""),
                Toolkit.row(Toolkit.text("1: Status  ·  2: Plan  ·  r: Refresh  ·  q: Quit").gray().dim())
        );
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
        if (key.isChar('3') || key.isCharIgnoreCase('a')) {
            switchScreen(Screen.APPLY);
            return EventResult.HANDLED;
        }
        if (key.isCharIgnoreCase('c')) {
            toggleConverged();
            return EventResult.HANDLED;
        }
        if (key.isChar(' ')) {
            handleSpace();
            return EventResult.HANDLED;
        }
        if (key.isKey(KeyCode.ENTER)) {
            handleEnter();
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

    public void switchScreen(Screen screen) {
        session.setActiveScreen(screen);
        selectedIndex = 0;
        syncConvergedSetting();
    }

    public void refresh() {
        session.refresh();
        clampSelectedIndex();
    }

    private void handleSpace() {
        if (session.activeScreen() == Screen.PLAN) {
            var selectedItem = selectedPlanItem();
            if (selectedItem != null && selectedItem.hasConflict() && !selectedItem.availableResolutions().isEmpty()) {
                session.resolveDecision(selectedItem.relocation(), selectedItem.availableResolutions().getFirst());
                clampSelectedIndex();
                return;
            }
        }
        toggleConverged();
    }

    private void handleEnter() {
        if (session.activeScreen() == Screen.PLAN) {
            var selectedItem = selectedPlanItem();
            if (selectedItem != null && selectedItem.hasConflict() && !selectedItem.availableResolutions().isEmpty()) {
                session.resolveDecision(selectedItem.relocation(), selectedItem.availableResolutions().getFirst());
                clampSelectedIndex();
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
                session.resolveDecision(selectedItem.relocation(), choice);
                clampSelectedIndex();
            }
        }
    }

    private io.github.bigswlittlesw.homelight.application.PlanRelocationItem selectedPlanItem() {
        if (session.planModel() instanceof PlanModel.Configured configured) {
            var visible = PlanView.visibleItems(configured, showConverged);
            if (!visible.isEmpty() && selectedIndex >= 0 && selectedIndex < visible.size()) {
                return visible.get(selectedIndex);
            }
        }
        return null;
    }

    public void toggleConverged() {
        userShowConverged = !showConverged;
        showConverged = userShowConverged;
        clampSelectedIndex();
    }

    public void selectPrevious() {
        if (selectedIndex > 0) {
            selectedIndex--;
        }
    }

    public void selectNext() {
        int maxIndex = visibleItemCount() - 1;
        if (selectedIndex < maxIndex) {
            selectedIndex++;
        }
    }

    public void selectFirst() {
        selectedIndex = 0;
    }

    public void selectLast() {
        int maxIndex = visibleItemCount() - 1;
        if (maxIndex >= 0) {
            selectedIndex = maxIndex;
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

    public boolean showConverged() {
        return showConverged;
    }

    private int visibleItemCount() {
        return switch (session.activeScreen()) {
            case STATUS -> {
                if (session.statusModel() instanceof StatusModel.Configured configured) {
                    yield StatusView.visibleItems(configured, showConverged).size();
                }
                yield 0;
            }
            case PLAN -> {
                if (session.planModel() instanceof PlanModel.Configured configured) {
                    yield PlanView.visibleItems(configured, showConverged).size();
                }
                yield 0;
            }
            default -> 0;
        };
    }

    private void syncConvergedSetting() {
        if (userShowConverged != null) {
            this.showConverged = userShowConverged;
        } else {
            if (session.activeScreen() == Screen.STATUS && session.statusModel() instanceof StatusModel.Configured configured) {
                this.showConverged = configured.summary().converged() == configured.summary().total();
            } else if (session.activeScreen() == Screen.PLAN && session.planModel() instanceof PlanModel.Configured configured) {
                this.showConverged = (configured.summary().converged() + configured.summary().unchanged()) == configured.summary().total();
            } else {
                this.showConverged = false;
            }
        }
        clampSelectedIndex();
    }

    private void clampSelectedIndex() {
        int count = visibleItemCount();
        if (count == 0) {
            selectedIndex = 0;
        } else {
            selectedIndex = Math.clamp(selectedIndex, 0, count - 1);
        }
    }
}
