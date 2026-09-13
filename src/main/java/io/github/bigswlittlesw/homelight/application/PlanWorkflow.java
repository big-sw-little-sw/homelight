package io.github.bigswlittlesw.homelight.application;

import io.github.bigswlittlesw.homelight.config.ConfigurationLoader;
import io.github.bigswlittlesw.homelight.config.HomeLightConfiguration;
import io.github.bigswlittlesw.homelight.config.Relocation;
import io.github.bigswlittlesw.homelight.fs.PathInspector;
import io.github.bigswlittlesw.homelight.fs.PathState;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationConflict;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlanner;
import io.github.bigswlittlesw.homelight.reconcile.RelocationPlan;
import io.github.bigswlittlesw.homelight.reconcile.RelocationState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/// Coordinates filesystem inspection, pure planning, and typed decision resolution.
public class PlanWorkflow {
    private final ConfigurationLoader configurationLoader;
    private final PathInspector pathInspector;
    private final ReconciliationPlanner reconciliationPlanner;

    public PlanWorkflow() {
        this(new ConfigurationLoader(), new PathInspector(), new ReconciliationPlanner());
    }

    public PlanWorkflow(ConfigurationLoader configurationLoader, PathInspector pathInspector,
            ReconciliationPlanner reconciliationPlanner) {
        this.configurationLoader = configurationLoader;
        this.pathInspector = pathInspector;
        this.reconciliationPlanner = reconciliationPlanner;
    }

    public PlanModel loadPlan(Path configPath) {
        return loadPlan(configPath, Map.of());
    }

    public PlanModel loadPlan(Path configPath, Map<String, String> overrides) {
        if (isMissingDefaultConfig(configPath)) {
            return new PlanModel.Unconfigured(configPath);
        }
        try {
            var configuration = configurationLoader.load(configPath, overrides);
            var states = buildRelocationStates(configuration);
            var plan = reconciliationPlanner.plan(states);
            var items = new ArrayList<PlanRelocationItem>(configuration.relocations().size());

            for (int i = 0; i < configuration.relocations().size(); i++) {
                var relocation = configuration.relocations().get(i);
                var state = states.get(i);
                var relocationPlan = plan.relocations().get(i);
                var sourceState = pathInspector.inspectRelocationSource(relocation.sourcePath(), relocation.targetPath());
                var resolutions = availableResolutions(relocation, state, relocationPlan);
                items.add(new PlanRelocationItem(relocation, state.source(), state.target(), relocationPlan, sourceState, resolutions));
            }

            var summary = PlanSummary.from(items);
            return new PlanModel.Configured(configPath, configuration.targetRoot(), plan, items, summary);
        } catch (Exception exception) {
            return new PlanModel.Invalid(configPath, exception.getMessage() != null ? exception.getMessage() : exception.toString());
        }
    }

    public List<DecisionChoice> availableResolutions(Relocation relocation, RelocationState state, RelocationPlan plan) {
        var choices = new ArrayList<DecisionChoice>();
        if (state.source().state() == PathState.DIRECTORY && state.target().state() == PathState.DIRECTORY) {
            choices.add(DecisionChoice.ADOPT_AND_DISCARD_SOURCE);
            if (relocation.sourceArchiveRoot().isPresent()) {
                choices.add(DecisionChoice.ADOPT_AND_ARCHIVE_SOURCE);
            }
            choices.add(DecisionChoice.LEAVE_UNCHANGED);
            choices.add(DecisionChoice.DISCARD_BOTH);
        } else if (state.source().state() == PathState.ABSENT && state.target().state() == PathState.DIRECTORY) {
            if (plan.conflict().isPresent() || relocation.whenOnlyTargetExists().isPresent()) {
                choices.add(DecisionChoice.ADOPT_TARGET);
            }
        } else if (plan.conflict().isPresent()) {
            var conflict = plan.conflict().get();
            var resolutions = conflict.resolutions();
            if (resolutions.contains(ReconciliationConflict.Resolution.RESOLVE_EXISTING_CONTENT)) {
                if (state.source().state() == PathState.ABSENT && state.target().state() == PathState.DIRECTORY) {
                    choices.add(DecisionChoice.ADOPT_TARGET);
                } else if (state.source().state() == PathState.DIRECTORY && state.target().state() == PathState.DIRECTORY) {
                    choices.add(DecisionChoice.ADOPT_AND_DISCARD_SOURCE);
                    if (relocation.sourceArchiveRoot().isPresent()) {
                        choices.add(DecisionChoice.ADOPT_AND_ARCHIVE_SOURCE);
                    }
                    choices.add(DecisionChoice.LEAVE_UNCHANGED);
                    choices.add(DecisionChoice.DISCARD_BOTH);
                }
            }
        }
        return List.copyOf(choices);
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
