package io.github.bigswlittlesw.homelight.config;

import io.smallrye.config.ConfigMapping;

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

@ConfigMapping(prefix = "homelight")
interface HomeLightMapping {
    String targetRoot();

    Optional<String> stagingRoot();

    List<RelocationMapping> relocations();

    Optional<List<String>> ignoredSourcePaths();

    DiscoveryMapping discovery();
}

interface DiscoveryMapping {
    Optional<String> sharedList();
}

interface RelocationMapping {
    String sourcePath();

    Optional<String> targetPath();

    Optional<WhenSourceAndTargetDirectoriesExist> whenSourceAndTargetDirectoriesExist();

    Optional<WhenOnlyTargetExists> whenOnlyTargetExists();

    Optional<WhenAdoptingTarget> whenAdoptingTarget();

    Optional<String> sourceArchiveRoot();
}
