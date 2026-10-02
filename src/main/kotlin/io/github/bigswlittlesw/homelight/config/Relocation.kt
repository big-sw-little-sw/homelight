package io.github.bigswlittlesw.homelight.config

import java.nio.file.Path

/**
 * A source location, storage destination, and state-specific reconciliation decisions.
 * A null decision has not been made.
 */
data class Relocation(
    val sourcePath: Path,
    val targetPath: Path,
    val whenSourceAndTargetDirectoriesExist: WhenSourceAndTargetDirectoriesExist? = null,
    val whenOnlyTargetExists: WhenOnlyTargetExists? = null,
    val whenAdoptingTarget: WhenAdoptingTarget? = null,
    val sourceArchiveRoot: Path? = null,
    val stagingRoot: Path? = null,
)

/** Archive-source is chosen but has nowhere to archive to. */
internal fun lacksArchiveRoot(whenAdoptingTarget: WhenAdoptingTarget?, sourceArchiveRoot: Path?): Boolean =
    whenAdoptingTarget == WhenAdoptingTarget.ARCHIVE_SOURCE && sourceArchiveRoot == null
