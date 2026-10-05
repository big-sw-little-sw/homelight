package io.github.bigswlittlesw.homelight.reconcile

import io.github.bigswlittlesw.homelight.config.WhenAdoptingTarget
import io.github.bigswlittlesw.homelight.config.WhenOnlyTargetExists
import io.github.bigswlittlesw.homelight.config.WhenSourceAndTargetDirectoriesExist
import io.github.bigswlittlesw.homelight.config.intersects
import io.github.bigswlittlesw.homelight.config.relocationProblem
import io.github.bigswlittlesw.homelight.domain.RelocationSourceState
import io.github.bigswlittlesw.homelight.fs.PathState
import java.nio.file.Path

/** Computes safe filesystem actions from observations and never mutates the filesystem. */
class ReconciliationPlanner {
    fun plan(states: List<RelocationState>): ReconciliationPlan {
        val problem = relocationProblem(states.map { it.relocation })
        if (problem != null) {
            return ReconciliationPlan(
                states.map { state -> blocked(state, "relocation configuration is invalid") },
                listOf(
                    ReconciliationDiagnostic(
                        ReconciliationDiagnostic.Severity.ERROR, problem.source, "INVALID_RELOCATION", problem.message,
                    ),
                ),
                states,
            )
        }
        return ReconciliationPlan(states.map { plan(it) }, listOf(), states)
    }

    private fun plan(state: RelocationState): RelocationPlan {
        val relocation = state.relocation
        val source = relocation.sourcePath
        val target = relocation.targetPath
        return when (state.source.sourceStateForTarget(target)) {
            RelocationSourceState.CORRECT_SYMLINK -> if (state.target.state == PathState.DIRECTORY)
                outcome(state, listOf(ReconciliationAction.NoOp(source))) else unsupportedTarget(state)
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
            RelocationSourceState.FILE -> blocked(state, "source is a file; relocations require directories")
            RelocationSourceState.WRONG_SYMLINK -> conflict(
                state, source, "source points to a live, non-configured destination",
                ReconciliationConflict.Resolution.REPLACE_SOURCE_LINK, ReconciliationConflict.Resolution.LEAVE_UNMANAGED,
            )
            RelocationSourceState.BROKEN_SYMLINK -> when (state.target.state) {
                PathState.DIRECTORY -> outcome(state, listOf(replacementLink(state)))
                PathState.ABSENT -> blocked(state, "broken source link has no target directory")
                PathState.FILE, PathState.SYMLINK, PathState.INACCESSIBLE, PathState.OTHER -> unsupportedTarget(state)
            }
            RelocationSourceState.INACCESSIBLE -> blocked(state, "source cannot be inspected")
            RelocationSourceState.OTHER -> blocked(state, "source has an unsupported filesystem state")
        }
    }
}

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

private fun onlyTargetExists(state: RelocationState): RelocationPlan =
    if (state.relocation.whenOnlyTargetExists == WhenOnlyTargetExists.ADOPT_TARGET)
        outcome(
            state, listOf(
                ReconciliationAction.EnsureDirectory(state.relocation.sourcePath.parent),
                ReconciliationAction.CreateSymlink(state.relocation.sourcePath, state.relocation.targetPath),
            ),
        )
    else unresolved(state, state.relocation.targetPath, "a real target directory requires an adopt-target decision")

private fun bothDirectoriesExist(state: RelocationState): RelocationPlan =
    when (state.relocation.whenSourceAndTargetDirectoriesExist) {
        WhenSourceAndTargetDirectoriesExist.PROMPT -> unresolved(
            state, state.relocation.sourcePath,
            "both source and target directories exist; choose which directory is authoritative",
        )
        WhenSourceAndTargetDirectoriesExist.ADOPT -> adoptTarget(state)
        WhenSourceAndTargetDirectoriesExist.LEAVE_UNCHANGED -> unchanged(state)
        WhenSourceAndTargetDirectoriesExist.DISCARD -> discardDirectories(state)
    }

private fun adoptTarget(state: RelocationState): RelocationPlan =
    when (state.relocation.whenAdoptingTarget) {
        WhenAdoptingTarget.PROMPT -> unresolved(
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
    val archivePath = relocation.archiveRoot.resolve(sourceRelativePath(relocation.sourcePath)).normalize()
    if (intersects(archivePath, relocation.sourcePath) || intersects(archivePath, relocation.targetPath)) {
        return blocked(state, "source archive path overlaps a relocation path")
    }
    if (state.archiveDestination?.observation?.state != PathState.ABSENT) {
        return blocked(state, "source archive destination already exists")
    }
    return outcome(
        state, listOf(
            ReconciliationAction.EnsureDirectory(archivePath.parent),
            ReconciliationAction.ArchiveDirectory(relocation.sourcePath, archivePath),
            ReconciliationAction.CreateSymlink(relocation.sourcePath, relocation.targetPath),
        ),
    )
}

private fun sourceRelativePath(source: Path): Path {
    val absolute = source.toAbsolutePath().normalize()
    return absolute.root.relativize(absolute)
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
        "DIRECTORIES_DISCARDED", "discard will permanently remove both directory trees",
    )
    return RelocationPlan(relocation, RelocationOutcome.CONVERGED, actions, listOf(warning))
}

private fun unsupportedTarget(state: RelocationState): RelocationPlan =
    blocked(state, "target is not a real directory or an absent path")

private fun outcome(state: RelocationState, actions: List<ReconciliationAction>): RelocationPlan =
    RelocationPlan(state.relocation, RelocationOutcome.CONVERGED, actions, listOf())

/** Only planned for a broken symlink with a directory target, so the source observation has a link target. */
private fun replacementLink(state: RelocationState): ReconciliationAction.ReplaceSymlink =
    ReconciliationAction.ReplaceSymlink(
        state.relocation.sourcePath, state.relocation.targetPath, state.source.symlinkTarget!!,
    )

private fun unresolved(state: RelocationState, path: Path, reason: String): RelocationPlan = conflict(
    state, path, reason, ReconciliationConflict.Resolution.RESOLVE_EXISTING_CONTENT,
    ReconciliationConflict.Resolution.LEAVE_UNMANAGED, ReconciliationConflict.Resolution.CHOOSE_DIFFERENT_TARGET,
)

private fun conflict(
    state: RelocationState, path: Path, reason: String, vararg resolutions: ReconciliationConflict.Resolution,
): RelocationPlan = RelocationPlan(
    state.relocation, RelocationOutcome.UNRESOLVED, listOf(), listOf(),
    ReconciliationConflict(path, reason, resolutions.toList()),
)

private fun blocked(state: RelocationState, reason: String): RelocationPlan = RelocationPlan(
    state.relocation, RelocationOutcome.UNRESOLVED,
    listOf(ReconciliationAction.Blocked(state.relocation.sourcePath, reason)), listOf(),
)
