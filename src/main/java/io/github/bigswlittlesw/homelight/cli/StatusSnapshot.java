package io.github.bigswlittlesw.homelight.cli;

import io.github.bigswlittlesw.homelight.fs.PathState;

import java.nio.file.Path;

record StatusSnapshot(Path relocationPath, Path localTarget, PathState state) {
}
