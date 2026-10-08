package io.github.bigswlittlesw.lighten.reconcile

import io.github.bigswlittlesw.lighten.config.Relocation
import io.github.bigswlittlesw.lighten.config.realSpelling
import io.github.bigswlittlesw.lighten.fs.PathObservation
import io.github.bigswlittlesw.lighten.fs.PathState
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Filesystem observations used to plan one relocation without touching disk.
 * `archiveDestination` is null when it was not observed; archive-source is then blocked.
 * `replacedSource` is the observation at [replacedSourcePath]; null when it was not observed, which plans as absent.
 * `notFolders` maps each folder an action may create or work in to what is in its way (see [inspectFolders]); a
 * folder with no entry plans as usable.
 */
data class RelocationState(
    val relocation: Relocation,
    val source: PathObservation,
    val target: PathObservation,
    val archiveDestination: ArchiveDestination? = null,
    val replacedSource: PathObservation? = null,
    val notFolders: Map<Path, NotAFolder> = mapOf(),
) {
    /** The no-follow observation of the source's archive destination, chosen by [inspectArchiveDestinations]. */
    data class ArchiveDestination(val path: Path, val observation: PathObservation)

    /** What exists at [path], at or above a folder an action needs, where the action needs a folder. */
    data class NotAFolder(val path: Path, val observation: PathObservation)
}

/** Observes everything the planner reads for [relocations], in the order given. */
internal fun inspectRelocations(
    relocations: List<Relocation>, inspect: (Path) -> PathObservation,
): List<RelocationState> =
    relocations.zip(inspectArchiveDestinations(relocations, inspect)) { relocation, archive ->
        RelocationState(
            relocation, inspect(relocation.sourcePath), inspect(relocation.targetPath), archive,
            inspect(replacedSourcePath(relocation.sourcePath, relocation.targetPath)),
            inspectFolders(relocation, archive.path, inspect),
        )
    }

/**
 * Finds what would stop the executor from making or using each folder a plan may need: the parents of
 * the source, target and [archive] destination, which `EnsureDirectory` creates, and the staging root.
 *
 * This is the executor's rule (see `ensureDirectories`): walking down from the filesystem root, every path that
 * exists must be a folder, through links, until one is missing. The staging root itself must be a real folder, not a
 * link to one. An existing path where the walk stops is in the way.
 *
 * When the staging root is also one of the parents, the parents' rule wins, so a plan that needs only the parent is
 * never blocked by the stricter rule. A migration then still finds a linked staging root when it runs.
 */
internal fun inspectFolders(
    relocation: Relocation, archive: Path, inspect: (Path) -> PathObservation,
): Map<Path, RelocationState.NotAFolder> {
    val stagingRoot = effectiveStagingRoot(relocation.targetPath, relocation.stagingRoot)
    val parents = listOfNotNull(relocation.sourcePath.parent, relocation.targetPath.parent, archive.parent)
    val folders = mapOf(stagingRoot to notAFolder(stagingRoot, real = true, inspect)) +
        parents.associateWith { parent -> notAFolder(parent, real = false, inspect) }
    return folders.mapNotNull { (folder, inTheWay) -> inTheWay?.let { folder to it } }.toMap()
}

/** The walk [inspectFolders] describes, for one folder; [real] refuses a link at the folder itself. */
private fun notAFolder(folder: Path, real: Boolean, inspect: (Path) -> PathObservation): RelocationState.NotAFolder? {
    val absolute = folder.toAbsolutePath().normalize()
    var current = absolute.root
    for (name in absolute) {
        current = current.resolve(name)
        val options = if (real && current == absolute) arrayOf(LinkOption.NOFOLLOW_LINKS) else arrayOf()
        if (Files.isDirectory(current, *options)) continue
        val observation = inspect(current)
        return if (observation.state == PathState.ABSENT) null else RelocationState.NotAFolder(current, observation)
    }
    return null
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
 * `<source parent>/.lighten-replaced-<source name>-<SHA-256 of the target's absolute normalized path>`.
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
        ".lighten-replaced-${absolute.fileName}-${sha256Hex(target.toAbsolutePath().normalize().toString())}",
    )
}
