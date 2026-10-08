package io.github.bigswlittlesw.homelight.domain

/** The source path's state relative to its configured relocation target. */
enum class RelocationSourceState {
    ABSENT,
    FILE,
    DIRECTORY,
    CORRECT_SYMLINK,
    WRONG_SYMLINK,
    BROKEN_SYMLINK,
    INACCESSIBLE,
    OTHER,
}
