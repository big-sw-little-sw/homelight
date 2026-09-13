package io.github.bigswlittlesw.homelight.tui;

import dev.tamboui.toolkit.app.ToolkitApp;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.elements.Column;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import io.github.bigswlittlesw.homelight.application.StatusModel;
import io.github.bigswlittlesw.homelight.application.StatusWorkflow;

import java.nio.file.Path;
import java.util.Objects;

/// Full-screen TamboUI application for HomeLight interactive terminal workflows.
public final class HomeLightApp extends ToolkitApp {
    private final Path configPath;
    private final StatusWorkflow statusWorkflow;
    private final TuiConfig customTuiConfig;

    private StatusModel statusModel;
    private int selectedIndex;
    private boolean showConverged;
    private Boolean userShowConverged;

    public HomeLightApp(Path configPath) {
        this(configPath, new StatusWorkflow(), null);
    }

    public HomeLightApp(Path configPath, StatusWorkflow statusWorkflow) {
        this(configPath, statusWorkflow, null);
    }

    public HomeLightApp(Path configPath, StatusWorkflow statusWorkflow, TuiConfig customTuiConfig) {
        this.configPath = Objects.requireNonNull(configPath, "configPath");
        this.statusWorkflow = Objects.requireNonNull(statusWorkflow, "statusWorkflow");
        this.customTuiConfig = customTuiConfig;
        refreshStatus();
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
        var view = StatusView.render(statusModel, selectedIndex, showConverged);
        if (view instanceof Column col) {
            return col.onKeyEvent(this::handleKeyEvent).focusable();
        }
        return view;
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
        if (key.isCharIgnoreCase('c') || key.isChar(' ')) {
            toggleConverged();
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

    public void refresh() {
        refreshStatus();
    }

    public void toggleConverged() {
        userShowConverged = !showConverged;
        showConverged = userShowConverged;
        if (statusModel instanceof StatusModel.Configured configured) {
            var visible = StatusView.visibleItems(configured, showConverged);
            if (visible.isEmpty()) {
                selectedIndex = 0;
            } else {
                selectedIndex = Math.clamp(selectedIndex, 0, visible.size() - 1);
            }
        }
    }

    public void selectPrevious() {
        if (selectedIndex > 0) {
            selectedIndex--;
        }
    }

    public void selectNext() {
        if (statusModel instanceof StatusModel.Configured configured) {
            var visible = StatusView.visibleItems(configured, showConverged);
            if (!visible.isEmpty() && selectedIndex < visible.size() - 1) {
                selectedIndex++;
            }
        }
    }

    public void selectFirst() {
        selectedIndex = 0;
    }

    public void selectLast() {
        if (statusModel instanceof StatusModel.Configured configured) {
            var visible = StatusView.visibleItems(configured, showConverged);
            if (!visible.isEmpty()) {
                selectedIndex = visible.size() - 1;
            }
        }
    }

    public StatusModel statusModel() {
        return statusModel;
    }

    public int selectedIndex() {
        return selectedIndex;
    }

    public boolean showConverged() {
        return showConverged;
    }

    private void refreshStatus() {
        this.statusModel = statusWorkflow.loadStatus(configPath);
        if (this.statusModel instanceof StatusModel.Configured configured) {
            if (userShowConverged != null) {
                this.showConverged = userShowConverged;
            } else {
                this.showConverged = configured.summary().converged() == configured.summary().total();
            }
            var visible = StatusView.visibleItems(configured, showConverged);
            if (visible.isEmpty()) {
                this.selectedIndex = 0;
            } else {
                this.selectedIndex = Math.clamp(this.selectedIndex, 0, visible.size() - 1);
            }
        } else {
            this.selectedIndex = 0;
        }
    }
}
