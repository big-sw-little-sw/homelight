package io.github.bigswlittlesw.homelight.reconcile;

import io.github.bigswlittlesw.homelight.config.Relocation;
import io.github.bigswlittlesw.homelight.fs.PathState;

/// Filesystem observations used to plan one relocation without touching disk.
public record RelocationState(Relocation relocation, PathState sourceState, PathState targetState) {
}
