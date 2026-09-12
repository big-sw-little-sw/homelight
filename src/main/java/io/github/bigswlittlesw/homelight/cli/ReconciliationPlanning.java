package io.github.bigswlittlesw.homelight.cli;

import io.github.bigswlittlesw.homelight.config.ConfigurationLoader;
import io.github.bigswlittlesw.homelight.fs.PathInspector;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlan;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlanner;
import io.github.bigswlittlesw.homelight.reconcile.RelocationState;

import java.nio.file.Path;
import java.util.Map;

/// Produces a current reconciliation plan from desired configuration and filesystem state.
final class ReconciliationPlanning {
    ReconciliationPlan plan(Path config, Map<String, String> overrides) {
        var configuration = new ConfigurationLoader().load(config, overrides);
        var inspector = new PathInspector();
        var states = configuration.relocations().stream()
                .map(relocation -> new RelocationState(relocation,
                        inspector.inspect(relocation.sourcePath()), inspector.inspect(relocation.targetPath()),
                        relocation.sourceArchiveRoot().map(root -> {
                            var source = relocation.sourcePath().toAbsolutePath().normalize();
                            var path = root.resolve(source.getRoot().relativize(source)).normalize();
                            return new RelocationState.ArchiveDestination(path, inspector.inspect(path));
                        })))
                .toList();
        return new ReconciliationPlanner().plan(states);
    }
}
