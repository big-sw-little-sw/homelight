package io.github.bigswlittlesw.homelight.application;

import io.github.bigswlittlesw.homelight.config.ConfigurationLoader;
import io.github.bigswlittlesw.homelight.fs.PathInspector;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlanner;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;

/// Adapts shared evaluation to the existing plan presentation.
public class PlanWorkflow {
    private final ConfigurationEvaluation evaluator;

    public PlanWorkflow() {
        this(new ConfigurationLoader(), new PathInspector(), new ReconciliationPlanner());
    }

    public PlanWorkflow(ConfigurationLoader loader, PathInspector inspector, ReconciliationPlanner planner) {
        this.evaluator = new ConfigurationEvaluation(loader, inspector, planner);
    }

    public PlanModel loadPlan(Path configPath) {
        return loadPlan(configPath, Map.of());
    }

    public PlanModel loadPlan(Path configPath, Map<String, String> inputOverrides) {
        return from(evaluator.load(configPath, inputOverrides));
    }

    static PlanModel from(ConfigurationEvaluation.Evaluation evaluation) {
        return switch (evaluation) {
            case ConfigurationEvaluation.Unconfigured unconfigured -> new PlanModel.Unconfigured(unconfigured.configPath());
            case ConfigurationEvaluation.Missing missing -> new PlanModel.Invalid(missing.configPath(), missing.message());
            case ConfigurationEvaluation.Invalid invalid -> new PlanModel.Invalid(invalid.configPath(), invalid.message());
            case ConfigurationEvaluation.Loaded loaded -> {
                var items = new ArrayList<PlanRelocationItem>();
                var plan = loaded.plan();
                for (int i = 0; i < loaded.observations().size(); i++) {
                    var state = loaded.observations().get(i);
                    var relocationPlan = plan.relocations().get(i);
                    var relocation = relocationPlan.relocation();
                    items.add(new PlanRelocationItem(relocation, state.source(), state.target(), relocationPlan,
                            state.source().sourceStateForTarget(relocation.targetPath()),
                            loaded.availableChoices().get(relocation.sourcePath().toAbsolutePath().normalize())));
                }
                yield new PlanModel.Configured(loaded.configPath(), loaded.savedConfiguration().targetRoot(),
                        plan, items, PlanSummary.from(items));
            }
        };
    }

}
