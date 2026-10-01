package io.github.bigswlittlesw.homelight.config

import java.nio.file.Path
import java.util.Optional

/** A source location, storage destination, and state-specific reconciliation decisions. */
@JvmRecord
data class Relocation(
    val sourcePath: Path,
    val targetPath: Path,
    val whenSourceAndTargetDirectoriesExist: Optional<WhenSourceAndTargetDirectoriesExist>,
    val whenOnlyTargetExists: Optional<WhenOnlyTargetExists>,
    val whenAdoptingTarget: Optional<WhenAdoptingTarget>,
    val sourceArchiveRoot: Optional<Path>,
    val stagingRoot: Optional<Path>,
) {
    constructor(sourcePath: Path, targetPath: Path) : this(
        sourcePath, targetPath, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
    )

    constructor(
        sourcePath: Path, targetPath: Path,
        whenSourceAndTargetDirectoriesExist: Optional<WhenSourceAndTargetDirectoriesExist>,
        whenOnlyTargetExists: Optional<WhenOnlyTargetExists>,
        whenAdoptingTarget: Optional<WhenAdoptingTarget>,
        sourceArchiveRoot: Optional<Path>,
    ) : this(
        sourcePath, targetPath, whenSourceAndTargetDirectoriesExist, whenOnlyTargetExists, whenAdoptingTarget,
        sourceArchiveRoot, Optional.empty(),
    )
}
