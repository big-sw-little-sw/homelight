package io.github.bigswlittlesw.homelight.fs;

import io.github.bigswlittlesw.homelight.domain.RelocationSourceState;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/// A no-follow observation of one filesystem path.
public record PathObservation(
        FilesystemKind kind,
        Optional<Path> symlinkTarget,
        boolean symlinkTargetExists) {
    public PathObservation {
        symlinkTarget = Objects.requireNonNullElse(symlinkTarget, Optional.empty());
        if (kind != FilesystemKind.SYMLINK && (symlinkTarget.isPresent() || symlinkTargetExists)) {
            throw new IllegalArgumentException("Only symlink observations may have a symlink target");
        }
    }

    public RelocationSourceState sourceStateForTarget(Path expectedTarget) {
        return switch (kind) {
            case ABSENT -> RelocationSourceState.ABSENT;
            case FILE -> RelocationSourceState.FILE;
            case DIRECTORY -> RelocationSourceState.DIRECTORY;
            case OTHER -> RelocationSourceState.OTHER;
            case SYMLINK -> symlinkTargetExists
                    ? symlinkTarget.orElseThrow().equals(expectedTarget.toAbsolutePath().normalize())
                            ? RelocationSourceState.CORRECT_SYMLINK
                            : RelocationSourceState.WRONG_SYMLINK
                    : RelocationSourceState.BROKEN_SYMLINK;
        };
    }
}
