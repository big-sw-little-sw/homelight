package io.github.bigswlittlesw.homelight.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/// Resolved paths used by the status and reconciliation adapters.
public record HomeLightConfiguration(
        Path targetRoot,
        List<Relocation> relocations,
        List<Path> ignoredSourcePaths,
        Optional<Path> sharedList) {

    public HomeLightConfiguration {
        relocations = List.copyOf(relocations);
        ignoredSourcePaths = List.copyOf(ignoredSourcePaths);
        sharedList = sharedList.map(DiscoverySetting::normalize);
    }

    public HomeLightConfiguration(Path targetRoot, List<Relocation> relocations, List<Path> ignoredSourcePaths) {
        this(targetRoot, relocations, ignoredSourcePaths, Optional.empty());
    }
}
