package io.github.bigswlittlesw.lighten.fs

/** What is at the source, compared with the relocation's target. */
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
