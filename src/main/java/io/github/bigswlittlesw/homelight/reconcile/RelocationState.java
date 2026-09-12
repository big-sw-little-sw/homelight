package io.github.bigswlittlesw.homelight.reconcile;

import io.github.bigswlittlesw.homelight.config.Relocation;
import io.github.bigswlittlesw.homelight.fs.PathObservation;

import java.nio.file.Path;
import java.util.Optional;

/// Filesystem observations used to plan one relocation without touching disk.
public record RelocationState(
        Relocation relocation,
        PathObservation source,
        PathObservation target,
        Optional<ArchiveDestination> archiveDestination) {
    public RelocationState(Relocation relocation, PathObservation source, PathObservation target) {
        this(relocation, source, target, Optional.empty());
    }

    /// The no-follow observation of a deterministic source archive destination.
    public record ArchiveDestination(Path path, PathObservation observation) { }
}
