package io.github.bigswlittlesw.homelight.config

import java.nio.file.Path

/**
 * A source location, storage destination, and state-specific reconciliation decisions.
 * A null decision has not been made.
 *
 * `archiveRoot` is where archive-source moves the source; the source keeps its full path below it.
 */
data class Relocation(
    val sourcePath: Path,
    val targetPath: Path,
    val whenSourceAndTargetDirectoriesExist: WhenSourceAndTargetDirectoriesExist? = null,
    val whenOnlyTargetExists: WhenOnlyTargetExists? = null,
    val whenAdoptingTarget: WhenAdoptingTarget? = null,
    val archiveRoot: Path = defaultArchiveRoot(sourcePath),
    val stagingRoot: Path? = null,
)

/**
 * `.homelight-archive` beside the source. Archiving is an atomic rename, so the root must be on the source's
 * filesystem; beside the source it almost always is.
 */
fun defaultArchiveRoot(sourcePath: Path): Path = sourcePath.toAbsolutePath().normalize().resolveSibling(".homelight-archive")
