package io.github.bigswlittlesw.homelight.cli;

import io.github.bigswlittlesw.homelight.domain.RelocationSourceState;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;

/// Machine-readable JSON renderer for relocation status.
final class StatusRenderer {
    void renderJson(List<StatusSnapshot> snapshots, PrintWriter output) {
        JsonOutput.print(snapshots.stream().map(snapshot -> new StatusJson(snapshot.sourcePath().toString(),
                snapshot.targetPath().toString(), snapshot.state().name().toLowerCase())).toList(), output);
    }

    void renderUnconfiguredJson(Path config, PrintWriter output) {
        JsonOutput.print(new UnconfiguredJson(false, config.toAbsolutePath().normalize().toString(), List.of()), output);
    }

    /// One element of the `status --json` array.
    private record StatusJson(String sourcePath, String targetPath, String state) {
    }

    /// The `status --json` response when no configuration exists.
    private record UnconfiguredJson(boolean configured, String configPath, List<Object> relocations) {
    }
}

record StatusSnapshot(Path sourcePath, Path targetPath, RelocationSourceState state) {
}
