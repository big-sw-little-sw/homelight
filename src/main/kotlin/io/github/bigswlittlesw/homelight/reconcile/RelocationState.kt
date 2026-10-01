package io.github.bigswlittlesw.homelight.reconcile

import io.github.bigswlittlesw.homelight.config.Relocation
import io.github.bigswlittlesw.homelight.fs.PathObservation
import java.nio.file.Path

/**
 * Filesystem observations used to plan one relocation without touching disk.
 * `archiveDestination` is null when the relocation has no source archive root.
 */
data class RelocationState(
    val relocation: Relocation,
    val source: PathObservation,
    val target: PathObservation,
    val archiveDestination: ArchiveDestination? = null,
) {
    /** The no-follow observation of a deterministic source archive destination. */
    data class ArchiveDestination(val path: Path, val observation: PathObservation)
}
