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
    val stagingRoot: Path? = null,
)
