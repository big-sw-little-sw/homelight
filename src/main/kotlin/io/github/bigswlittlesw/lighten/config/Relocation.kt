package io.github.bigswlittlesw.lighten.config

import java.nio.file.Path

/**
 * A source location, storage destination, and state-specific reconciliation rules. A rule left unset is
 * [WhenSourceAndTargetDirectoriesExist.PROMPT] (or its peer): the user decides each time.
 *
 * `archiveRoot` is where archive-source moves the source, under the source's name (see `inspectArchiveDestinations`).
 */
data class Relocation(
    val sourcePath: Path,
    val targetPath: Path,
    val whenSourceAndTargetDirectoriesExist: WhenSourceAndTargetDirectoriesExist = WhenSourceAndTargetDirectoriesExist.PROMPT,
    val whenOnlyTargetExists: WhenOnlyTargetExists = WhenOnlyTargetExists.PROMPT,
    val whenAdoptingTarget: WhenAdoptingTarget = WhenAdoptingTarget.PROMPT,
    val archiveRoot: Path = defaultArchiveRoot(sourcePath),
    val stagingRoot: Path? = null,
)

/**
 * `.lighten-archive` beside the source. Archiving is an atomic rename, so the root must be on the source's
 * filesystem; beside the source it almost always is.
 */
fun defaultArchiveRoot(sourcePath: Path): Path = sourcePath.toAbsolutePath().normalize().resolveSibling(".lighten-archive")
