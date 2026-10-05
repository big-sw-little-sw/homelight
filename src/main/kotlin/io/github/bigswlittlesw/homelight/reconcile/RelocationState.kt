package io.github.bigswlittlesw.homelight.reconcile

import io.github.bigswlittlesw.homelight.config.Relocation
import io.github.bigswlittlesw.homelight.config.realSpelling
import io.github.bigswlittlesw.homelight.fs.PathObservation
import io.github.bigswlittlesw.homelight.fs.PathState
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
    /** The no-follow observation of the source's archive destination, chosen by [inspectArchiveDestinations]. */
    data class ArchiveDestination(val path: Path, val observation: PathObservation)
}

/**
 * Chooses and inspects where archive-source would move each relocation's source, in the order given.
 *
 * The destination is `<archive root>/<source name>`. When that name is taken, it becomes
 * `<source name>-<first 8 hex digits of the SHA-256 of the source's real spelling>`. A name is taken when anything
 * exists there, or when another of [relocations] has the same plain destination, compared by real spelling. The second
 * rule depends only on the configuration, so two relocations with the same source name always get different names,
 * whichever of them archives first. The suffix depends only on the source, so the same filesystem state always gives
 * the same destination. A suffixed name that is taken too is not varied further: the planner blocks archiving.
 *
 * This is the only place that decides the name; the planner uses the path it is given.
 */
internal fun inspectArchiveDestinations(
    relocations: List<Relocation>, inspect: (Path) -> PathObservation,
): List<RelocationState.ArchiveDestination> {
    val plain = relocations.map { it.archiveRoot.resolve(sourceName(it.sourcePath)).normalize() }
    val spellings = plain.map(::realSpelling)
    val claims = spellings.groupingBy { it }.eachCount()
    return relocations.indices.map { i ->
        val path = plain[i]
        val observation = if (claims.getValue(spellings[i]) == 1) inspect(path) else null
        if (observation?.state == PathState.ABSENT) {
            RelocationState.ArchiveDestination(path, observation)
        } else {
            val suffix = sha256Hex(realSpelling(relocations[i].sourcePath).toString()).take(8)
            val suffixed = path.resolveSibling("${path.fileName}-$suffix")
            RelocationState.ArchiveDestination(suffixed, inspect(suffixed))
        }
    }
}

private fun sourceName(source: Path): Path =
    // A filesystem root overlaps every target, so the loader refuses it as a source.
    checkNotNull(source.toAbsolutePath().normalize().fileName) { "source has no name: $source" }

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
