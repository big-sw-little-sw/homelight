package io.github.bigswlittlesw.homelight.config;

import java.nio.file.Path;
import java.util.List;

/// Resolved paths used by the status and reconciliation adapters.
public record HomeLightConfiguration(Path targetRoot, List<Relocation> relocations) {
}
