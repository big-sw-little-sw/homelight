package io.github.bigswlittlesw.homelight.fs;

/// The filesystem object found at a path, without interpreting its ownership or destination.
public enum FilesystemKind {
    ABSENT,
    FILE,
    DIRECTORY,
    SYMLINK,
    OTHER
}
