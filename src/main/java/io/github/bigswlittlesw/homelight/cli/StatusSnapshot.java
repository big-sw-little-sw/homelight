package io.github.bigswlittlesw.homelight.cli;

import io.github.bigswlittlesw.homelight.domain.RelocationSourceState;

import java.nio.file.Path;

record StatusSnapshot(Path sourcePath, Path targetPath, RelocationSourceState state) {
}
