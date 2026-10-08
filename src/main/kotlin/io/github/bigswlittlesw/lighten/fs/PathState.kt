package io.github.bigswlittlesw.homelight.fs

/** The state observed for a path without following symbolic links. */
enum class PathState {
    ABSENT,
    FILE,
    DIRECTORY,
    SYMLINK,
    INACCESSIBLE,
    OTHER,
}
