package io.github.bigswlittlesw.homelight.reconcile

import io.github.bigswlittlesw.homelight.config.intersects
import io.github.bigswlittlesw.homelight.config.realSpelling
import io.github.bigswlittlesw.homelight.config.relocationProblem
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Splits relocations into groups that can run at the same time, each listing plan indices in order. The groups are
 * ordered by their first index.
 *
 * Two relocations are independent when no path one claims overlaps a path the other claims, by the same
 * [intersects] rule that [relocationProblem] applies to sources and targets. A relocation claims its source and
 * target, every action destination (such as an archive path) and each migration's staging root. It also claims the
 * parent of each of these that is not yet a directory, since its actions may create it. Relocations not proven
 * independent share a group, so a relocation that depends on two groups merges them. Siblings under an existing
 * parent can therefore run together; siblings whose parent is missing share a group.
 *
 * Parents are checked here, when [ReconciliationExecutor.execute] starts, not at plan time: the review snapshot does
 * not observe parents, and this is the latest state before any action runs.
 *
 * Claims are compared by [realSpelling], because an existing ancestor may be a symlink: `/home/u/x` and
 * `/var/home/u/x` can be one place. They are resolved here for the same reasons as parents.
 *
 * Relocations may share a staging root, because a [StagingOperation] only opens its own target's names there. A
 * shared root still overlaps every other path it intersects, including a different staging root nested in it.
 */
internal fun independentGroups(relocations: List<RelocationPlan>): List<List<Int>> {
    val claims = relocations.map(::claimedPaths)
    var groups = listOf<List<Int>>()
    for (index in relocations.indices) {
        val (dependent, independent) = groups.partition { group ->
            group.any { other -> claims[other].any { left -> claims[index].any { right -> left.overlaps(right) } } }
        }
        groups = independent + listOf((dependent.flatten() + index).sorted())
    }
    return groups.sortedBy { group -> group.first() }
}

/** A path a relocation claims. A staging root does not overlap the same staging root of another relocation. */
private data class Claim(val path: Path, val stagingRoot: Boolean = false) {
    fun overlaps(other: Claim): Boolean =
        intersects(path, other.path) && !(stagingRoot && other.stagingRoot && path == other.path)
}

private fun claimedPaths(relocation: RelocationPlan): List<Claim> {
    val own = (listOf(relocation.relocation.sourcePath, relocation.relocation.targetPath) +
        relocation.actions.mapNotNull { action -> action.destination }).map(::realSpelling)
    val stagingRoots = relocation.actions.filterIsInstance<ReconciliationAction.MigrateDirectoryForPublication>()
        .map { migration -> realSpelling(migration.effectiveStagingRoot) }
    // An existing parent is not claimed. On POSIX, creating, renaming, linking or deleting different names in one
    // directory is safe, and each action's guards check only its own paths. A parent another relocation changes is
    // inside one of that relocation's paths, so this relocation's path overlaps it anyway. A missing parent stays
    // claimed. Its missing ancestors may still be created concurrently, which `ensureDirectories` tolerates before
    // checking for a real directory. Paths are real spellings, so an existing parent is a real directory here.
    val parents = (own + stagingRoots).mapNotNull { path -> path.parent }
        .filterNot { parent -> Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) }
    return (own + parents).map(::Claim) + stagingRoots.map { root -> Claim(root, stagingRoot = true) }
}
