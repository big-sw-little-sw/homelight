package io.github.bigswlittlesw.homelight.config;

import java.nio.file.Path;

/// A source location and its resolved storage destination.
public record Relocation(Path sourcePath, Path targetPath) {
}
