package io.github.bigswlittlesw.homelight.application;

import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlan;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// State representation for the dry-run plan workflow.
public sealed interface PlanModel permits PlanModel.Unconfigured, PlanModel.Configured, PlanModel.Invalid {

    Path configPath();

    /// Indicates that no configuration exists at the default path.
    record Unconfigured(Path configPath) implements PlanModel {
    }

    /// Indicates active configuration with inspected relocations, dry-run actions, and resolutions.
    record Configured(
            Path configPath,
            Path targetRoot,
            ReconciliationPlan plan,
            List<PlanRelocationItem> items,
            PlanSummary summary
    ) implements PlanModel {
        public Configured {
            var sorted = new ArrayList<>(items);
            sorted.sort(PlanRelocationItem.BY_URGENCY_AND_PATH);
            items = List.copyOf(sorted);
        }
    }

    /// Indicates that configuration could not be loaded, parsed, or planned.
    record Invalid(Path configPath, String message) implements PlanModel {
    }
}
