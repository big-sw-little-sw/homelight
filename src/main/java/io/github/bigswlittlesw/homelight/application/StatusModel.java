package io.github.bigswlittlesw.homelight.application;

import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlan;

import java.nio.file.Path;
import java.util.List;

/// State representation for the status workflow.
public sealed interface StatusModel permits StatusModel.Unconfigured, StatusModel.Configured, StatusModel.Invalid {

    Path configPath();

    /// Indicates that no configuration exists at the default path.
    record Unconfigured(Path configPath) implements StatusModel {
    }

    /// Indicates active configuration with inspected relocations and plan.
    record Configured(
            Path configPath,
            Path targetRoot,
            ReconciliationPlan plan,
            List<RelocationStatusItem> items,
            StatusSummary summary
    ) implements StatusModel {
        public Configured {
            items = List.copyOf(items);
        }
    }

    /// Indicates that configuration could not be loaded or parsed.
    record Invalid(Path configPath, String message) implements StatusModel {
    }
}
