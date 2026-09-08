package io.github.bigswlittlesw.homelight.fs;

/// The source path's state relative to its configured relocation target.
public enum RelocationSourceState {
    ABSENT,
    FILE,
    DIRECTORY,
    CORRECT_SYMLINK,
    WRONG_SYMLINK,
    BROKEN_SYMLINK,
    OTHER
}
