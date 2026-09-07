package io.github.bigswlittlesw.homelight.config;

import java.nio.file.Path;

/// Resolved paths used by the status and reconciliation adapters.
public record HomeLightConfiguration(Path sourcePath, Path targetPath) {
}
