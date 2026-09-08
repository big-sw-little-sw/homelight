package io.github.bigswlittlesw.homelight.fs;

/// The availability of the destination reached by a symlink.
public enum SymlinkTargetAvailability {
    EXISTS,
    ABSENT,
    INACCESSIBLE,
    NOT_A_SYMLINK
}
