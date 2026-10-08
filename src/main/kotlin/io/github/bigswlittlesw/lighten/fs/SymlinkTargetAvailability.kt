package io.github.bigswlittlesw.lighten.fs

/** The availability of the destination reached by a symlink. */
enum class SymlinkTargetAvailability {
    EXISTS,
    ABSENT,
    INACCESSIBLE,
    NOT_A_SYMLINK,
}
