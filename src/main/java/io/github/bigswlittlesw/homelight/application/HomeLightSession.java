package io.github.bigswlittlesw.homelight.application;

import io.github.bigswlittlesw.homelight.config.ConfigurationLoader;
import io.github.bigswlittlesw.homelight.config.Relocation;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Presentation-neutral session holding active workflow state, configuration overrides, and evaluated plans.
public class HomeLightSession {
    private final Path configPath;
    private final StatusWorkflow statusWorkflow;
    private final PlanWorkflow planWorkflow;
    private final ConfigurationLoader configurationLoader;
    private final Map<String, String> overrides = new HashMap<>();

    private Screen activeScreen;
    private StatusModel statusModel;
    private PlanModel planModel;

    public HomeLightSession(Path configPath, Screen initialScreen) {
        this(configPath, initialScreen, new StatusWorkflow(), new PlanWorkflow(), new ConfigurationLoader());
    }

    public HomeLightSession(Path configPath, Screen initialScreen, StatusWorkflow statusWorkflow,
            PlanWorkflow planWorkflow, ConfigurationLoader configurationLoader) {
        this.configPath = Objects.requireNonNull(configPath, "configPath");
        this.activeScreen = Objects.requireNonNull(initialScreen, "initialScreen");
        this.statusWorkflow = Objects.requireNonNull(statusWorkflow, "statusWorkflow");
        this.planWorkflow = Objects.requireNonNull(planWorkflow, "planWorkflow");
        this.configurationLoader = Objects.requireNonNull(configurationLoader, "configurationLoader");
        reload();
    }

    public Screen activeScreen() {
        return activeScreen;
    }

    public void setActiveScreen(Screen activeScreen) {
        this.activeScreen = Objects.requireNonNull(activeScreen, "activeScreen");
    }

    public Path configPath() {
        return configPath;
    }

    public StatusModel statusModel() {
        return statusModel;
    }

    public PlanModel planModel() {
        return planModel;
    }

    public Map<String, String> overrides() {
        return Map.copyOf(overrides);
    }

    public void refresh() {
        reload();
    }

    public void resolveDecision(Relocation relocation, DecisionChoice choice) {
        try {
            var configuration = configurationLoader.load(configPath, overrides);
            int index = -1;
            for (int i = 0; i < configuration.relocations().size(); i++) {
                if (configuration.relocations().get(i).sourcePath().equals(relocation.sourcePath())) {
                    index = i;
                    break;
                }
            }
            if (index >= 0) {
                for (var entry : choice.configurationOverrides().entrySet()) {
                    overrides.put("homelight.relocations[" + index + "]." + entry.getKey(), entry.getValue());
                }
                reloadModels();
            }
        } catch (Exception exception) {
            reloadModels();
        }
    }

    public boolean isPlanReady() {
        return planModel instanceof PlanModel.Configured configured
                && !configured.plan().hasBlockedActions()
                && !configured.plan().hasConflicts();
    }

    public boolean hasConflicts() {
        return planModel instanceof PlanModel.Configured configured
                && configured.plan().hasConflicts();
    }

    private void reload() {
        reloadModels();
    }

    private void reloadModels() {
        this.statusModel = statusWorkflow.loadStatus(configPath);
        this.planModel = planWorkflow.loadPlan(configPath, overrides);
    }
}
