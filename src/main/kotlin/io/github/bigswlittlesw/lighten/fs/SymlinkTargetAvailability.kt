package io.github.bigswlittlesw.lighten.fs

/** Whether the place a symlink points to exists. A path that is not a symlink is [NOT_A_SYMLINK]. */
enum class SymlinkTargetAvailability {
    EXISTS,
    ABSENT,
    INACCESSIBLE,
    NOT_A_SYMLINK,
}
