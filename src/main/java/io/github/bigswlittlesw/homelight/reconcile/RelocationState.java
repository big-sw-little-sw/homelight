package io.github.bigswlittlesw.homelight.reconcile;

import io.github.bigswlittlesw.homelight.config.Relocation;
import io.github.bigswlittlesw.homelight.fs.PathObservation;

/// Filesystem observations used to plan one relocation without touching disk.
public record RelocationState(Relocation relocation, PathObservation source, PathObservation target) {
}
