package io.github.bigswlittlesw.lighten.reconcile

import io.github.bigswlittlesw.lighten.config.WhenAdoptingTarget
import io.github.bigswlittlesw.lighten.config.WhenOnlyTargetExists
import io.github.bigswlittlesw.lighten.config.WhenSourceAndTargetDirectoriesExist
import io.github.bigswlittlesw.lighten.config.intersects
import io.github.bigswlittlesw.lighten.domain.RelocationSourceState
import io.github.bigswlittlesw.lighten.fs.PathState
import io.github.bigswlittlesw.lighten.fs.PathText
import io.github.bigswlittlesw.lighten.fs.SymlinkTargetAvailability
import java.nio.file.Path

/** Computes safe filesystem actions from observations and never mutates the filesystem. */
class ReconciliationPlanner {
    /**
     * One plan per state, in order. Relocations that overlap block only themselves, each naming the other
     * ([overlapReason]); the rest plan as usual.
     */
    fun plan(states: List<RelocationState>): ReconciliationPlan = ReconciliationPlan(
        states.mapIndexed { i, state ->
            overlapReason(i, states)?.let { blocked(state, it) }
                ?: blockedByStagingElsewhere(state, blockedByNotAFolder(state, plan(state)))
        },
        listOf(), states,
    )

    private fun plan(state: RelocationState): RelocationPlan {
        val relocation = state.relocation
        val source = relocation.sourcePath
        val target = relocation.targetPath
        val sourceState = state.source.sourceStateForTarget(target)
        val replacedSourceLeft = state.replacedSource?.state == PathState.DIRECTORY
        if (replacedSourceLeft && sourceState == RelocationSourceState.DIRECTORY) {
            return blocked(state, PathText("an interrupted replacement left the original source at ", replacedSource(state)))
        }
        return when (sourceState) {
            RelocationSourceState.CORRECT_SYMLINK -> when {
                state.target.state != PathState.DIRECTORY -> unsupportedTarget(state)
                replacedSourceLeft -> deleteReplacedSource(state)
                else -> outcome(state, listOf(ReconciliationAction.NoOp(source)))
            }
            RelocationSourceState.ABSENT -> when (state.target.state) {
                PathState.ABSENT -> outcome(
                    state, listOf(
                        ReconciliationAction.EnsureDirectory(target.parent),
                        ReconciliationAction.CreateDirectory(target), ReconciliationAction.EnsureDirectory(source.parent),
                        ReconciliationAction.CreateSymlink(source, target),
                    ),
                )
                PathState.DIRECTORY -> onlyTargetExists(state)
                PathState.FILE, PathState.SYMLINK, PathState.INACCESSIBLE, PathState.OTHER -> unsupportedTarget(state)
            }
            RelocationSourceState.DIRECTORY -> when (state.target.state) {
                PathState.ABSENT -> migrateSourceForPublication(state)
                PathState.DIRECTORY -> bothDirectoriesExist(state)
                PathState.FILE, PathState.SYMLINK, PathState.INACCESSIBLE, PathState.OTHER -> unsupportedTarget(state)
            }
            RelocationSourceState.FILE -> blocked(state, PathText("source is a file; relocations require directories"))
            RelocationSourceState.WRONG_SYMLINK -> blocked(state, wrongLinkReason(state))
            RelocationSourceState.BROKEN_SYMLINK -> when (state.target.state) {
                PathState.DIRECTORY -> outcome(state, listOf(replacementLink(state)))
                PathState.ABSENT -> blocked(state, PathText("broken source link has no target directory"))
                PathState.FILE, PathState.SYMLINK, PathState.INACCESSIBLE, PathState.OTHER -> unsupportedTarget(state)
            }
            RelocationSourceState.INACCESSIBLE -> blocked(state, PathText("source cannot be inspected"))
            RelocationSourceState.OTHER -> blocked(state, PathText("source has an unsupported filesystem state"))
        }
    }
}

/**
 * Why relocation [i] of [states] overlaps, or null: its source and target overlap, or one of its paths overlaps a
 * path of another relocation, which it names. One inside the other counts, and so does the same path; a shared
 * target is one case. Paths compare as written first, then by their real spellings, so `/home/u/x` and
 * `/var/home/u/x` overlap when `/home` links to `/var/home`. Only the first overlap is named, in configuration order.
 */
private fun overlapReason(i: Int, states: List<RelocationState>): PathText? =
    selfOverlap(states[i]) ?: states.withIndex().filter { it.index != i }
        .firstNotNullOfOrNull { (_, other) -> pairOverlap(states[i], other) }

private fun selfOverlap(state: RelocationState): PathText? {
    val (source, target) = places(state)
    val overlap = overlap(source, target) ?: return null
    val link = overlap.link()
    return when (overlap.relation) {
        Relation.SAME -> PathText("the source ", source.path, " and target ", target.path, " are the same folder$link")
        Relation.CONTAINS -> PathText("the target ", target.path, " is inside the source ", source.path, link)
        Relation.INSIDE -> PathText("the source ", source.path, " is inside the target ", target.path, link)
    }
}

/**
 * The first overlap of [state]'s source or target with [other]'s, naming [other]'s path and what it is: a
 * relocation (its source), or the target of the relocation whose source it names.
 */
private fun pairOverlap(state: RelocationState, other: RelocationState): PathText? {
    val theirs = places(other)
    for (mine in places(state)) {
        for ((j, their) in theirs.withIndex()) {
            val overlap = overlap(mine, their) ?: continue
            val isSource = j == 0
            val what: List<Any> =
                if (isSource) listOf(", which is also a relocation") else listOf(", the target of ", other.relocation.sourcePath)
            val words: List<Any> = when (overlap.relation) {
                Relation.CONTAINS -> listOf(mine.path, " contains ", their.path, overlap.link()) + what
                Relation.INSIDE -> listOf(mine.path, " is inside ", their.path, overlap.link()) + what
                Relation.SAME -> when {
                    overlap.throughLink -> listOf(mine.path, " is ", their.path, overlap.link()) + what
                    isSource -> listOf(mine.path, " is also the source of another relocation")
                    else -> listOf(mine.path, " is also the target of ", other.relocation.sourcePath)
                }
            }
            return PathText(*words.toTypedArray())
        }
    }
    return null
}

/** A relocation path as written and as its real spelling. */
private data class Place(val path: Path, val real: Path)

/** The source, then the target. */
private fun places(state: RelocationState): List<Place> =
    listOf(state.relocation.sourcePath, state.relocation.targetPath).map { Place(it, state.realSpellings[it] ?: it) }

/** How the first path relates to the second: the same, containing it, or inside it. */
private enum class Relation { SAME, CONTAINS, INSIDE }

private data class Overlap(val relation: Relation, val throughLink: Boolean) {
    fun link(): String = if (throughLink) " through a link" else ""
}

/** As written first; only when that finds none, by real spelling. */
private fun overlap(left: Place, right: Place): Overlap? =
    relation(left.path, right.path)?.let { Overlap(it, throughLink = false) }
        ?: relation(left.real, right.real)?.let { Overlap(it, throughLink = true) }

private fun relation(left: Path, right: Path): Relation? {
    val first = left.toAbsolutePath().normalize()
    val second = right.toAbsolutePath().normalize()
    return when {
        first == second -> Relation.SAME
        second.startsWith(first) -> Relation.CONTAINS
        first.startsWith(second) -> Relation.INSIDE
        else -> null
    }
}

/**
 * Blocks [planned] when it copies a folder through a staging root on another filesystem than the target, as
 * inspection found ([stagingElsewhere]): the copy could not be moved into place in one step. The executor checks
 * again before it copies.
 */
private fun blockedByStagingElsewhere(state: RelocationState, planned: RelocationPlan): RelocationPlan {
    if (!state.stagingElsewhere) return planned
    val migration = planned.actions.filterIsInstance<ReconciliationAction.MigrateDirectoryForPublication>().firstOrNull()
        ?: return planned
    return blocked(
        state,
        PathText(
            "the staging folder ", migration.effectiveStagingRoot, " is on another filesystem than ", migration.target,
            ", so Lighten can't move the copy there in one step. Set staging-root to a folder on the target's " +
                "filesystem, or remove staging-root to stage beside each target",
        ),
    )
}

/**
 * Blocks [planned] when a folder one of its actions needs is in the way (see [inspectFolders]), so the apply does not
 * stop at that step. The reason names the first such path, in action order.
 */
private fun blockedByNotAFolder(state: RelocationState, planned: RelocationPlan): RelocationPlan {
    val folder = planned.actions.asSequence().flatMap(::neededFolders).firstOrNull(state.notFolders::containsKey)
        ?: return planned
    val inTheWay = state.notFolders.getValue(folder)
    val stagingRoot = effectiveStagingRoot(state.relocation.targetPath, state.relocation.stagingRoot)
    // Only the staging root must not be a link at all. Elsewhere a link to a folder is fine, so one in the way there
    // leads to something else and gets the general reason.
    val linkedStagingRoot = folder == stagingRoot && inTheWay.path == folder && inTheWay.observation.state == PathState.SYMLINK
        && inTheWay.observation.symlinkTargetAvailability != SymlinkTargetAvailability.ABSENT
    return blocked(
        state,
        if (linkedStagingRoot) PathText("the staging folder must be a real folder, not a link: ", folder)
        else notAFolderReason(inTheWay),
    )
}

/**
 * The folders the executor makes or works in for an action. Every other path an action uses is a source, target,
 * archive destination or replaced source, whose observations the planner has already checked.
 */
private fun neededFolders(action: ReconciliationAction): List<Path> = when (action) {
    is ReconciliationAction.EnsureDirectory -> listOf(action.path)
    is ReconciliationAction.MigrateDirectoryForPublication -> listOfNotNull(action.target.parent, action.effectiveStagingRoot)
    is ReconciliationAction.CreateDirectory, is ReconciliationAction.ArchiveDirectory,
    is ReconciliationAction.DeleteDirectory, is ReconciliationAction.CreateSymlink,
    is ReconciliationAction.ReplaceDirectoryWithSymlink, is ReconciliationAction.ReplaceSymlink,
    is ReconciliationAction.NoOp, is ReconciliationAction.LeaveUnchanged, is ReconciliationAction.Blocked -> listOf()
}

private fun notAFolderReason(inTheWay: RelocationState.NotAFolder): PathText {
    val observation = inTheWay.observation
    return PathText(
        inTheWay.path,
        when (observation.state) {
            PathState.FILE -> " is a file, not a folder"
            PathState.SYMLINK ->
                if (observation.symlinkTargetAvailability == SymlinkTargetAvailability.ABSENT) " is a broken link, not a folder"
                else " is a link, not a folder"
            PathState.INACCESSIBLE -> " can't be read, so Lighten can't tell if it is a folder"
            PathState.ABSENT, PathState.DIRECTORY, PathState.OTHER -> " is not a folder"
        },
    )
}

/**
 * A source link to somewhere else is blocked, never replaced: it may belong to another tool, and no rule or choice
 * replaces it. The reason names both paths and both fixes.
 *
 * `[skipped: naming which tool owns the link, add with #5]`
 */
private fun wrongLinkReason(state: RelocationState): PathText = PathText(
    // A wrong link is a symlink observation, which always has a link target.
    state.relocation.sourcePath, " links to ", state.source.symlinkTarget!!, ", not to ", state.relocation.targetPath,
    ". Remove the link, or set target-path to where it points",
)

private fun migrateSourceForPublication(state: RelocationState): RelocationPlan {
    val relocation = state.relocation
    return outcome(
        state, listOf(
            ReconciliationAction.MigrateDirectoryForPublication(
                relocation.sourcePath, relocation.targetPath, relocation.stagingRoot,
            ),
            ReconciliationAction.ReplaceDirectoryWithSymlink(relocation.sourcePath, relocation.targetPath),
        ),
    )
}

private fun replacedSource(state: RelocationState): Path =
    replacedSourcePath(state.relocation.sourcePath, state.relocation.targetPath)

/** See [replacedSourcePath] for why deleting it is safe. */
private fun deleteReplacedSource(state: RelocationState): RelocationPlan {
    val path = replacedSource(state)
    val warning = ReconciliationDiagnostic(
        ReconciliationDiagnostic.Severity.WARNING, path,
        "REPLACED_SOURCE_LEFT", PathText("an interrupted replacement left the original source here; it will be deleted"),
    )
    val actions = listOf(ReconciliationAction.DeleteDirectory(path))
    return RelocationPlan(state.relocation, RelocationOutcome.CONVERGED, actions, listOf(warning))
}

private fun onlyTargetExists(state: RelocationState): RelocationPlan =
    if (state.relocation.whenOnlyTargetExists == WhenOnlyTargetExists.ADOPT_TARGET)
        outcome(
            state, listOf(
                ReconciliationAction.EnsureDirectory(state.relocation.sourcePath.parent),
                ReconciliationAction.CreateSymlink(state.relocation.sourcePath, state.relocation.targetPath),
            ),
        )
    else conflict(state, state.relocation.targetPath, "a real target directory requires an adopt-target decision")

private fun bothDirectoriesExist(state: RelocationState): RelocationPlan =
    when (state.relocation.whenSourceAndTargetDirectoriesExist) {
        WhenSourceAndTargetDirectoriesExist.PROMPT -> conflict(
            state, state.relocation.sourcePath,
            "both source and target directories exist; choose which directory is authoritative",
        )
        WhenSourceAndTargetDirectoriesExist.ADOPT -> adoptTarget(state)
        WhenSourceAndTargetDirectoriesExist.LEAVE_UNCHANGED -> unchanged(state)
        WhenSourceAndTargetDirectoriesExist.DISCARD -> discardDirectories(state)
    }

private fun adoptTarget(state: RelocationState): RelocationPlan =
    when (state.relocation.whenAdoptingTarget) {
        WhenAdoptingTarget.PROMPT -> conflict(
            state, state.relocation.sourcePath, "adopting the target requires a source disposition",
        )
        WhenAdoptingTarget.DISCARD_SOURCE -> outcome(
            state, listOf(
                ReconciliationAction.ReplaceDirectoryWithSymlink(state.relocation.sourcePath, state.relocation.targetPath),
            ),
        )
        WhenAdoptingTarget.ARCHIVE_SOURCE -> archiveSource(state)
    }

private fun archiveSource(state: RelocationState): RelocationPlan {
    val relocation = state.relocation
    val destination = state.archiveDestination ?: return blocked(state, PathText("source archive destination was not inspected"))
    val archivePath = destination.path
    if (intersects(archivePath, relocation.sourcePath) || intersects(archivePath, relocation.targetPath)) {
        return blocked(state, PathText("source archive path overlaps a relocation path"))
    }
    if (destination.observation.state != PathState.ABSENT) {
        return blocked(state, PathText("source archive destination already exists"))
    }
    return outcome(
        state, listOf(
            ReconciliationAction.EnsureDirectory(archivePath.parent),
            ReconciliationAction.ArchiveDirectory(relocation.sourcePath, archivePath),
            ReconciliationAction.CreateSymlink(relocation.sourcePath, relocation.targetPath),
        ),
    )
}

private fun unchanged(state: RelocationState): RelocationPlan = RelocationPlan(
    state.relocation, RelocationOutcome.UNCHANGED,
    listOf(ReconciliationAction.LeaveUnchanged(state.relocation.sourcePath)), listOf(),
)

private fun discardDirectories(state: RelocationState): RelocationPlan {
    val relocation = state.relocation
    val actions = listOf(
        ReconciliationAction.DeleteDirectory(relocation.sourcePath),
        ReconciliationAction.DeleteDirectory(relocation.targetPath),
        ReconciliationAction.EnsureDirectory(relocation.targetPath.parent),
        ReconciliationAction.CreateDirectory(relocation.targetPath),
        ReconciliationAction.EnsureDirectory(relocation.sourcePath.parent),
        ReconciliationAction.CreateSymlink(relocation.sourcePath, relocation.targetPath),
    )
    val warning = ReconciliationDiagnostic(
        ReconciliationDiagnostic.Severity.WARNING, relocation.sourcePath,
        "DIRECTORIES_DISCARDED", PathText("discard will permanently remove both directory trees"),
    )
    return RelocationPlan(relocation, RelocationOutcome.CONVERGED, actions, listOf(warning))
}

private fun unsupportedTarget(state: RelocationState): RelocationPlan =
    blocked(state, PathText("target is not a real directory or an absent path"))

private fun outcome(state: RelocationState, actions: List<ReconciliationAction>): RelocationPlan =
    RelocationPlan(state.relocation, RelocationOutcome.CONVERGED, actions, listOf())

/** Only planned for a broken symlink with a directory target, so the source observation has a link target. */
private fun replacementLink(state: RelocationState): ReconciliationAction.ReplaceSymlink =
    ReconciliationAction.ReplaceSymlink(
        state.relocation.sourcePath, state.relocation.targetPath, state.source.symlinkTarget!!,
    )

private fun conflict(state: RelocationState, path: Path, reason: String): RelocationPlan = RelocationPlan(
    state.relocation, RelocationOutcome.UNRESOLVED, listOf(), listOf(), ReconciliationConflict(path, reason),
)

private fun blocked(state: RelocationState, reason: PathText): RelocationPlan = RelocationPlan(
    state.relocation, RelocationOutcome.UNRESOLVED,
    listOf(ReconciliationAction.Blocked(state.relocation.sourcePath, reason)), listOf(),
)
