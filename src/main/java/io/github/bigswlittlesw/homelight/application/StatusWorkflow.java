package io.github.bigswlittlesw.homelight.application;

import io.github.bigswlittlesw.homelight.config.ConfigurationLoader;
import io.github.bigswlittlesw.homelight.config.HomeLightConfiguration;
import io.github.bigswlittlesw.homelight.fs.PathInspector;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlanner;
import io.github.bigswlittlesw.homelight.reconcile.RelocationState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// Coordinates status inspection and domain planning.
public class StatusWorkflow {
    private final ConfigurationLoader configurationLoader;
    private final PathInspector pathInspector;
    private final ReconciliationPlanner reconciliationPlanner;

    public StatusWorkflow() {
        this(new ConfigurationLoader(), new PathInspector(), new ReconciliationPlanner());
    }

    public StatusWorkflow(ConfigurationLoader configurationLoader, PathInspector pathInspector,
            ReconciliationPlanner reconciliationPlanner) {
        this.configurationLoader = configurationLoader;
        this.pathInspector = pathInspector;
        this.reconciliationPlanner = reconciliationPlanner;
    }

    public StatusModel loadStatus(Path configPath) {
        if (isMissingDefaultConfig(configPath)) {
            return new StatusModel.Unconfigured(configPath);
        }
        try {
            var configuration = configurationLoader.load(configPath);
            var states = buildRelocationStates(configuration);
            var plan = reconciliationPlanner.plan(states);
            var items = new ArrayList<RelocationStatusItem>(configuration.relocations().size());

            for (int i = 0; i < configuration.relocations().size(); i++) {
                var relocation = configuration.relocations().get(i);
                var state = states.get(i);
                var relocationPlan = plan.relocations().get(i);
                var sourceState = pathInspector.inspectRelocationSource(relocation.sourcePath(), relocation.targetPath());
                items.add(new RelocationStatusItem(relocation, state.source(), state.target(), relocationPlan, sourceState));
            }

            var summary = StatusSummary.from(items);
            return new StatusModel.Configured(configPath, configuration.targetRoot(), plan, items, summary);
        } catch (Exception exception) {
            return new StatusModel.Invalid(configPath, exception.getMessage() != null ? exception.getMessage() : exception.toString());
        }
    }

    private List<RelocationState> buildRelocationStates(HomeLightConfiguration configuration) {
        return configuration.relocations().stream()
                .map(relocation -> new RelocationState(relocation,
                        pathInspector.inspect(relocation.sourcePath()),
                        pathInspector.inspect(relocation.targetPath()),
                        relocation.sourceArchiveRoot().map(root -> {
                            var source = relocation.sourcePath().toAbsolutePath().normalize();
                            var path = root.resolve(source.getRoot().relativize(source)).normalize();
                            return new RelocationState.ArchiveDestination(path, pathInspector.inspect(path));
                        })))
                .toList();
    }

    private static boolean isMissingDefaultConfig(Path config) {
        return config.toAbsolutePath().normalize().equals(ConfigurationLoader.DEFAULT_PATH.toAbsolutePath().normalize())
                && !Files.isRegularFile(config);
    }
}
