package io.github.bigswlittlesw.homelight.fs;

/// The filesystem shape at a configured path, observed without following a symlink.
public enum PathState {
    ABSENT,
    FILE,
    DIRECTORY,
    CORRECT_SYMLINK,
    WRONG_SYMLINK,
    BROKEN_SYMLINK,
    OTHER
}
