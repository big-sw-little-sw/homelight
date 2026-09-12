package io.github.bigswlittlesw.homelight.config;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/// A source location, storage destination, and state-specific reconciliation decisions.
public record Relocation(
        Path sourcePath,
        Path targetPath,
        Optional<WhenSourceAndTargetDirectoriesExist> whenSourceAndTargetDirectoriesExist,
        Optional<WhenOnlyTargetExists> whenOnlyTargetExists,
        Optional<WhenAdoptingTarget> whenAdoptingTarget,
        Optional<Path> sourceArchiveRoot,
        Optional<Path> stagingRoot) {
    public Relocation {
        whenSourceAndTargetDirectoriesExist = Objects.requireNonNull(
                whenSourceAndTargetDirectoriesExist, "whenSourceAndTargetDirectoriesExist");
        whenOnlyTargetExists = Objects.requireNonNull(whenOnlyTargetExists, "whenOnlyTargetExists");
        whenAdoptingTarget = Objects.requireNonNull(whenAdoptingTarget, "whenAdoptingTarget");
        sourceArchiveRoot = Objects.requireNonNull(sourceArchiveRoot, "sourceArchiveRoot");
        stagingRoot = Objects.requireNonNull(stagingRoot, "stagingRoot");
    }

    public Relocation(Path sourcePath, Path targetPath) {
        this(sourcePath, targetPath, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    public Relocation(Path sourcePath, Path targetPath,
            Optional<WhenSourceAndTargetDirectoriesExist> whenSourceAndTargetDirectoriesExist,
            Optional<WhenOnlyTargetExists> whenOnlyTargetExists,
            Optional<WhenAdoptingTarget> whenAdoptingTarget,
            Optional<Path> sourceArchiveRoot) {
        this(sourcePath, targetPath, whenSourceAndTargetDirectoriesExist, whenOnlyTargetExists, whenAdoptingTarget,
                sourceArchiveRoot, Optional.empty());
    }
}
