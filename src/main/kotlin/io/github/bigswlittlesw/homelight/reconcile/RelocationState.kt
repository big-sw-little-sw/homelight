package io.github.bigswlittlesw.homelight.reconcile

import io.github.bigswlittlesw.homelight.config.Relocation
import io.github.bigswlittlesw.homelight.fs.PathObservation
import java.nio.file.Path
import java.util.Optional

/** Filesystem observations used to plan one relocation without touching disk. */
@JvmRecord
data class RelocationState(
    val relocation: Relocation,
    val source: PathObservation,
    val target: PathObservation,
    val archiveDestination: Optional<ArchiveDestination>,
) {
    constructor(relocation: Relocation, source: PathObservation, target: PathObservation) :
            this(relocation, source, target, Optional.empty())

    /** The no-follow observation of a deterministic source archive destination. */
    @JvmRecord
    data class ArchiveDestination(val path: Path, val observation: PathObservation)
}
