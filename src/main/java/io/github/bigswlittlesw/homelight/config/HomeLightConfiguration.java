package io.github.bigswlittlesw.homelight.config;

import io.smallrye.config.ConfigMapping;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/// Resolved paths used by the status and reconciliation adapters.
public record HomeLightConfiguration(
        Path targetRoot,
        List<Relocation> relocations,
        List<Path> ignoredSourcePaths) {

}

@ConfigMapping(prefix = "homelight")
interface HomeLightMapping {
    String targetRoot();

    List<RelocationMapping> relocations();

    Optional<List<String>> ignoredSourcePaths();
}

interface RelocationMapping {
    String sourcePath();

    Optional<String> targetPath();

    Optional<WhenSourceAndTargetDirectoriesExist> whenSourceAndTargetDirectoriesExist();

    Optional<WhenOnlyTargetExists> whenOnlyTargetExists();

    Optional<WhenAdoptingTarget> whenAdoptingTarget();

    Optional<String> sourceArchiveRoot();
}
