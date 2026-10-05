package io.github.bigswlittlesw.homelight.reconcile

import io.github.bigswlittlesw.homelight.config.Relocation
import io.github.bigswlittlesw.homelight.fs.PathObservation
import java.nio.file.Path

/**
 * Filesystem observations used to plan one relocation without touching disk.
 * `archiveDestination` is null when it was not observed; archive-source is then blocked.
 * `replacedSource` is the observation at [replacedSourcePath]; null when it was not observed, which plans as absent.
 */
data class RelocationState(
    val relocation: Relocation,
    val source: PathObservation,
    val target: PathObservation,
    val archiveDestination: ArchiveDestination? = null,
    val replacedSource: PathObservation? = null,
) {
    /** The no-follow observation of a deterministic source archive destination. */
    data class ArchiveDestination(val path: Path, val observation: PathObservation)
}

/**
 * Where replacing [source] with a link to [target] sets the source aside before deleting it:
 * `<source parent>/.homelight-replaced-<source name>-<SHA-256 of the target's absolute normalized path>`.
 *
 * The recognition rule: a directory at exactly this name is the remains of an interrupted replacement only while
 * [source] is a link to [target] and [target] is a directory. The planner then deletes it, and never treats it as a
 * source. That is safe because the replacement started only after [target] held the source's content, and the hash
 * ties the name to that one target: after the configuration moves the relocation to another target, the name no
 * longer matches. While [source] is absent the remains are kept, since the link that would make them redundant does
 * not exist yet. While [source] is a directory again (an application may recreate it), the relocation is blocked,
 * so a both-exist rule such as `discard` cannot act on a fresh source while the original one is still aside.
 */
internal fun replacedSourcePath(source: Path, target: Path): Path {
    val absolute = source.toAbsolutePath().normalize()
    return absolute.resolveSibling(
        ".homelight-replaced-${absolute.fileName}-${sha256Hex(target.toAbsolutePath().normalize().toString())}",
    )
}
