package io.github.bigswlittlesw.homelight.fs;

import io.github.bigswlittlesw.homelight.domain.RelocationSourceState;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/// A no-follow observation of one filesystem path.
public record PathObservation(
        PathState state,
        Optional<Path> symlinkTarget,
        SymlinkTargetAvailability symlinkTargetAvailability,
        boolean emptyDirectory) {
    public PathObservation {
        symlinkTarget = Objects.requireNonNullElse(symlinkTarget, Optional.empty());
        Objects.requireNonNull(symlinkTargetAvailability, "symlinkTargetAvailability");
        if (state != PathState.SYMLINK
                && (symlinkTarget.isPresent() || symlinkTargetAvailability != SymlinkTargetAvailability.NOT_A_SYMLINK)) {
            throw new IllegalArgumentException("Only symlink observations may have a symlink target");
        }
        if (state == PathState.SYMLINK
                && (symlinkTarget.isEmpty() || symlinkTargetAvailability == SymlinkTargetAvailability.NOT_A_SYMLINK)) {
            throw new IllegalArgumentException("A symlink observation needs its target and availability");
        }
        if (emptyDirectory && state != PathState.DIRECTORY) {
            throw new IllegalArgumentException("Only directory observations may be empty");
        }
    }

    public PathObservation(PathState state, Optional<Path> symlinkTarget,
            SymlinkTargetAvailability symlinkTargetAvailability) {
        this(state, symlinkTarget, symlinkTargetAvailability, false);
    }

    public PathObservation(PathState state, Optional<Path> symlinkTarget, boolean symlinkTargetExists) {
        this(state, symlinkTarget, state == PathState.SYMLINK
                ? symlinkTargetExists ? SymlinkTargetAvailability.EXISTS : SymlinkTargetAvailability.ABSENT
                : SymlinkTargetAvailability.NOT_A_SYMLINK, false);
    }

    public RelocationSourceState sourceStateForTarget(Path expectedTarget) {
        return switch (state) {
            case ABSENT -> RelocationSourceState.ABSENT;
            case FILE -> RelocationSourceState.FILE;
            case DIRECTORY -> RelocationSourceState.DIRECTORY;
            case INACCESSIBLE -> RelocationSourceState.INACCESSIBLE;
            case OTHER -> RelocationSourceState.OTHER;
            case SYMLINK -> switch (symlinkTargetAvailability) {
                case EXISTS -> symlinkTarget.orElseThrow().equals(expectedTarget.toAbsolutePath().normalize())
                        ? RelocationSourceState.CORRECT_SYMLINK
                        : RelocationSourceState.WRONG_SYMLINK;
                case ABSENT -> RelocationSourceState.BROKEN_SYMLINK;
                case INACCESSIBLE -> RelocationSourceState.INACCESSIBLE;
                case NOT_A_SYMLINK -> throw new IllegalStateException("Invalid symlink observation");
            };
        };
    }
}
