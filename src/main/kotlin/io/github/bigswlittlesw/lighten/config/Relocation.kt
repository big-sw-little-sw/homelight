package io.github.bigswlittlesw.lighten.config

import java.nio.file.Path

/**
 * One directory Lighten manages: its source, its target, and a rule for each state it can find them in. A rule left
 * unset is `PROMPT`, "Ask each time".
 *
 * `archiveRoot` is where archive-source moves the source, under the source's name (see `inspectArchiveDestinations`).
 *
 * Every path is normalized, and all but `archiveRoot` are absolute, so callers compare and key paths as given. The
 * loader resolves paths this way; a caller with paths from elsewhere normalizes them first.
 */
data class Relocation(
    val sourcePath: Path,
    val targetPath: Path,
    val whenSourceAndTargetDirectoriesExist: WhenSourceAndTargetDirectoriesExist = WhenSourceAndTargetDirectoriesExist.PROMPT,
    val whenOnlyTargetExists: WhenOnlyTargetExists = WhenOnlyTargetExists.PROMPT,
    val whenAdoptingTarget: WhenAdoptingTarget = WhenAdoptingTarget.PROMPT,
    val archiveRoot: Path = defaultArchiveRoot(sourcePath),
    val stagingRoot: Path? = null,
) {
    init {
        for (path in listOfNotNull(sourcePath, targetPath, stagingRoot)) {
            require(path.isAbsolute && path == path.normalize()) { "Relocation path must be absolute and normalized: $path" }
        }
        // A filesystem-root source has no sibling, so its default archive root is relative. The loader builds that
        // relocation and then refuses it as overlapping its target, with a message naming both.
        require(archiveRoot == archiveRoot.normalize()) { "Relocation archive root must be normalized: $archiveRoot" }
    }
}

/**
 * `.lighten-archive` beside the source. Archiving is an atomic rename, so the root must be on the source's
 * filesystem; beside the source it almost always is.
 */
fun defaultArchiveRoot(sourcePath: Path): Path = sourcePath.resolveSibling(".lighten-archive")
