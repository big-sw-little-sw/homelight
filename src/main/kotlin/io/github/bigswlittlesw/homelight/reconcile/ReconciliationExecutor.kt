package io.github.bigswlittlesw.homelight.reconcile

import io.github.bigswlittlesw.homelight.concurrent.RELOCATION_CONCURRENCY
import io.github.bigswlittlesw.homelight.concurrent.Outcome
import io.github.bigswlittlesw.homelight.concurrent.mapBounded
import io.github.bigswlittlesw.homelight.fs.PathInspector
import io.github.bigswlittlesw.homelight.fs.PathObservation
import io.github.bigswlittlesw.homelight.fs.PathState
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileStore
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Applies a fully resolved plan, stopping when the filesystem no longer matches its guards. */
class ReconciliationExecutor internal constructor(
    private val concurrency: Int,
    /** A test seam, called as an action reaches each [Step]. */
    private val step: (Step, Path) -> Unit,
) {
    constructor(concurrency: Int = RELOCATION_CONCURRENCY) : this(concurrency, { _, _ -> })

    private val inspector = PathInspector()

    /**
     * Checks all observations retained at review before any action runs. This compares
     * path states, link destinations/availability, and directory emptiness, not tree contents.
     * Per-action guards remain necessary because preflight cannot lock out external writers.
     */
    fun preflight(plan: ReconciliationPlan): List<ReconciliationDiagnostic> {
        require(plan.expectedStates.map { it.relocation } == plan.relocations.map { it.relocation }) {
            "Plan has no complete review snapshot"
        }
        return plan.expectedStates.flatMap { state ->
            listOfNotNull(
                state.relocation.sourcePath to state.source,
                state.relocation.targetPath to state.target,
                state.archiveDestination?.let { it.path to it.observation },
                state.replacedSource?.let {
                    replacedSourcePath(state.relocation.sourcePath, state.relocation.targetPath) to it
                },
            )
        }.mapNotNull { (path, expected) -> stalePlan(path, expected) }
    }

    private fun stalePlan(path: Path, expected: PathObservation): ReconciliationDiagnostic? =
        if (inspector.inspect(path) == expected) null else ReconciliationDiagnostic(
            ReconciliationDiagnostic.Severity.ERROR, path,
            "STALE_PLAN", "Filesystem state changed since review: $path",
        )

    /**
     * Runs the plan's relocations, with at most [concurrency] independent groups at once (see [independentGroups]).
     * A group's relocations, and each relocation's actions, run in plan order.
     *
     * After any action fails, no new relocation starts, but relocations already running finish their actions. The
     * result lists relocations in plan order, whatever order they finished in; those never started are pending.
     * [progress] may be called from several threads at once, but never twice at once for one relocation.
     */
    fun execute(plan: ReconciliationPlan, progress: ProgressListener = ProgressListener.NONE): ExecutionResult {
        require(!plan.hasBlockedActions() && !plan.hasConflicts()) { "Only fully resolved plans can be executed" }
        // Owned by this call and shared with its group threads; mapBounded checks it before starting each group.
        val halted = AtomicBoolean()
        fun run(group: List<Int>): List<Pair<Int, RelocationExecution>> = buildList {
            for (index in group) {
                if (halted.get()) break
                try {
                    add(index to execute(plan.relocations[index], progress, halted))
                } catch (throwable: Throwable) {
                    // An unexpected throwable halts too; it is rethrown below, once running groups finish.
                    halted.set(true)
                    throw throwable
                }
            }
        }
        val groups = independentGroups(plan.relocations)
        // A single group runs on the caller's thread, as before concurrency, so the caller's interrupts still reach it.
        val outcomes = if (groups.size == 1) {
            listOf(Outcome.Completed(run(groups.single())))
        } else {
            mapBounded(groups, concurrency, halted::get, ::run)
        }
        val executions = outcomes.flatMap { outcome ->
            when (outcome) {
                is Outcome.Completed -> outcome.value
                is Outcome.Failed -> throw outcome.error
                Outcome.NotStarted -> listOf()
            }
        }.toMap()
        return ExecutionResult(
            plan.relocations.mapIndexed { index, relocation ->
                executions[index] ?: RelocationExecution(relocation, relocation.actions.map(::notRun))
            },
        )
    }

    /** Runs one relocation's actions in order; a failure leaves its remaining actions pending and sets [halted]. */
    private fun execute(relocation: RelocationPlan, progress: ProgressListener, halted: AtomicBoolean): RelocationExecution {
        val actions = ArrayList<ActionExecution>()
        var failed = false
        for (action in relocation.actions) {
            if (failed) {
                actions.add(notRun(action))
                continue
            }
            fun fail(exception: Exception) {
                val execution = ActionExecution(
                    action, ActionStatus.FAILED, exception.message ?: exception.toString(),
                    stateDrift = exception is StateDriftException, targetPublished = exception is PartlyPublishedException,
                )
                actions.add(execution)
                halted.set(true)
                failed = true
                progress.finished(relocation, execution)
            }
            // Only I/O and environment failures fail the action and halt the plan. Anything else is a bug: it
            // propagates, after `finally` blocks have cleaned up staging.
            try {
                progress.started(relocation, action)
                val execution = ActionExecution(action, ActionStatus.COMPLETED, apply(action))
                actions.add(execution)
                progress.finished(relocation, execution)
            } catch (exception: IOException) {
                fail(exception)
            } catch (exception: EnvironmentException) {
                fail(exception)
            }
        }
        return RelocationExecution(relocation, actions)
    }

    private fun apply(action: ReconciliationAction): String = when (action) {
        is ReconciliationAction.CreateDirectory -> { createDirectory(action); "completed" }
        is ReconciliationAction.EnsureDirectory -> { ensureDirectory(action); "completed" }
        is ReconciliationAction.MigrateDirectoryForPublication -> { migrateDirectoryForPublication(action); "completed" }
        is ReconciliationAction.ArchiveDirectory -> { archiveDirectory(action); "completed" }
        is ReconciliationAction.DeleteDirectory -> { deleteDirectory(action); "completed" }
        is ReconciliationAction.CreateSymlink -> { createSymlink(action); "completed" }
        is ReconciliationAction.ReplaceDirectoryWithSymlink -> { replaceDirectoryWithSymlink(action); "completed" }
        is ReconciliationAction.ReplaceSymlink -> { replaceSymlink(action); "completed" }
        is ReconciliationAction.NoOp -> "completed"
        is ReconciliationAction.LeaveUnchanged -> "completed"
        // Unreachable: execute refuses plans with blocked actions.
        is ReconciliationAction.Blocked -> error("blocked action reached execution: ${action.reason}")
    }

    private fun createDirectory(action: ReconciliationAction.CreateDirectory) {
        requireState(action.path, PathState.ABSENT)
        Files.createDirectory(action.path)
    }

    /** The path is always the parent of a source, target or archive path, so it may be a symlinked ancestor. */
    private fun ensureDirectory(action: ReconciliationAction.EnsureDirectory) {
        ensureDirectories(action.path)
    }

    private fun migrateDirectoryForPublication(action: ReconciliationAction.MigrateDirectoryForPublication) {
        requireState(action.path, PathState.DIRECTORY)
        requireState(action.target, PathState.ABSENT)
        val targetParent: Path = checkNotNull(action.target.parent) { "target has no parent directory: ${action.target}" }
        val stagingRoot = action.effectiveStagingRoot
        requirePosixPermissions(action.path, fileStoreOfExistingAncestor(action.path))
        val targetStore = fileStoreOfExistingAncestor(targetParent)
        requirePosixPermissions(targetParent, targetStore)
        // The same store rules out a cross-device rename, so `publish` fails only before it changes anything.
        if (fileStoreOfExistingAncestor(stagingRoot) != targetStore) {
            throw EnvironmentException("staging root is not on the target filesystem: $stagingRoot")
        }
        ensureDirectories(targetParent)
        ensureRealDirectory(stagingRoot)
        StagingOperation.open(stagingRoot, action.target, step).use { operation ->
            operation.stage(action.path)
            requireState(action.path, PathState.DIRECTORY)
            requireState(action.target, PathState.ABSENT)
            operation.publish(action.target)
        }
    }

    /**
     * Points at which [step] is called, each after the step it names. Staged publication passes its staged copy's
     * path, source replacement the [replacedSourcePath].
     */
    internal enum class Step { LOCKED, COPIED, PUBLISHED, SOURCE_SET_ASIDE, LINKED }

    private fun deleteDirectory(action: ReconciliationAction.DeleteDirectory) {
        requireState(action.path, PathState.DIRECTORY)
        deleteTree(action.path)
    }

    private fun archiveDirectory(action: ReconciliationAction.ArchiveDirectory) {
        requireState(action.path, PathState.DIRECTORY)
        requireState(action.target, PathState.ABSENT)
        Files.move(action.path, action.target, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun createSymlink(action: ReconciliationAction.CreateSymlink) {
        requireState(action.path, PathState.ABSENT)
        requireState(action.target, PathState.DIRECTORY)
        replaceWithLink(action.path, action.target)
    }

    /**
     * Changes the source only in atomic steps (#132): it is renamed aside to its [replacedSourcePath], the link is
     * moved into its place, and only then is the renamed tree deleted. A crash or failure therefore leaves the whole
     * source at its path, or nothing there and the whole source aside, or the link there and the rest of the source
     * aside. None of these is a partial source next to the target. The planner deletes what is left aside only once
     * the link is in place.
     */
    private fun replaceDirectoryWithSymlink(action: ReconciliationAction.ReplaceDirectoryWithSymlink) {
        val aside = replacedSourcePath(action.path, action.target)
        requireState(action.path, PathState.DIRECTORY)
        requireState(action.target, PathState.DIRECTORY)
        requireState(aside, PathState.ABSENT)
        replaceWithLink(action.path, action.target) {
            requireState(action.path, PathState.DIRECTORY)
            requireState(action.target, PathState.DIRECTORY)
            // ATOMIC_MOVE may replace an empty directory at the destination, so check again right before it.
            requireState(aside, PathState.ABSENT)
            Files.move(action.path, aside, StandardCopyOption.ATOMIC_MOVE)
            step(Step.SOURCE_SET_ASIDE, aside)
        }
        step(Step.LINKED, aside)
        deleteTree(aside)
    }

    private fun replaceSymlink(action: ReconciliationAction.ReplaceSymlink) {
        // A symlink observation always carries its target.
        val actualTarget = requireState(action.path, PathState.SYMLINK).symlinkTarget
        requireState(action.target, PathState.DIRECTORY)
        if (actualTarget != action.expectedSourceTarget) {
            throw StateDriftException("expected symlink target ${action.expectedSourceTarget} at ${action.path}")
        }
        replaceWithLink(action.path, action.target, replaceExisting = true)
    }

    /**
     * Creates a link to [target] beside [path] and moves it to [path] in one step. [beforeMove] runs once the link
     * exists, so a failure to create it leaves [path] untouched.
     */
    private fun replaceWithLink(
        path: Path, target: Path, replaceExisting: Boolean = false, beforeMove: () -> Unit = {},
    ) {
        val temporary = Files.createTempFile(path.parent, ".homelight-", ".link")
        Files.delete(temporary)
        Files.createSymbolicLink(temporary, target)
        try {
            beforeMove()
            if (replaceExisting) {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } else {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /** Returns the observation that passed, so a caller can check more of it without inspecting again. */
    private fun requireState(path: Path, expected: PathState): PathObservation {
        val observation = inspector.inspect(path)
        val actual = observation.state
        if (actual != expected) {
            throw StateDriftException(
                "expected ${expected.name.lowercase(Locale.ROOT)} at $path but found ${actual.name.lowercase(Locale.ROOT)}",
            )
        }
        return observation
    }

    enum class ActionStatus { COMPLETED, FAILED, PENDING }

    interface ProgressListener {
        fun started(relocation: RelocationPlan, action: ReconciliationAction) {}

        fun finished(relocation: RelocationPlan, action: ActionExecution) {}

        companion object {
            val NONE: ProgressListener = object : ProgressListener {}
        }
    }

    /** [targetPublished] marks a failed migration that had already moved its copy to the target. */
    data class ActionExecution(
        val action: ReconciliationAction, val status: ActionStatus, val message: String,
        val stateDrift: Boolean = false, val targetPublished: Boolean = false,
    )

    data class RelocationExecution(val relocation: RelocationPlan, val actions: List<ActionExecution>) {
        /** Returns the execution outcome after accounting for an interrupted source replacement. */
        fun outcome(): ExecutionOutcome {
            if (actions.any { action -> action.status == ActionStatus.FAILED }) {
                val targetPublished = actions.any { action ->
                    action.action is ReconciliationAction.MigrateDirectoryForPublication
                        && (action.status == ActionStatus.COMPLETED || action.targetPublished)
                }
                return if (targetPublished) ExecutionOutcome.FAILED_RECOVERY else ExecutionOutcome.UNRESOLVED
            }
            if (actions.any { action -> action.status == ActionStatus.PENDING }) {
                return ExecutionOutcome.UNRESOLVED
            }
            return when (relocation.outcome) {
                RelocationOutcome.CONVERGED -> ExecutionOutcome.CONVERGED
                RelocationOutcome.UNCHANGED -> ExecutionOutcome.UNCHANGED
                RelocationOutcome.UNRESOLVED -> ExecutionOutcome.UNRESOLVED
            }
        }
    }

    /** The observed result of one relocation after its actions have run; `value` is its stable machine-readable name. */
    enum class ExecutionOutcome(val value: String) {
        CONVERGED(RelocationOutcome.CONVERGED.value),
        UNCHANGED(RelocationOutcome.UNCHANGED.value),
        UNRESOLVED(RelocationOutcome.UNRESOLVED.value),
        FAILED_RECOVERY("failed-recovery"),
    }

    data class ExecutionResult(val relocations: List<RelocationExecution>) {
        fun succeeded(): Boolean =
            relocations.none { relocation -> relocation.actions.any { action -> action.status == ActionStatus.FAILED } }
    }
}

/**
 * An expected failure of the environment an action runs in, which [ReconciliationExecutor.execute] reports, like an
 * [IOException], as a failed action. Every other exception from an action is a bug and propagates.
 */
internal open class EnvironmentException(message: String) : Exception(message)

/** The filesystem no longer matches an action's guard; reported as [ReconciliationExecutor.ActionExecution.stateDrift]. */
private class StateDriftException(message: String) : EnvironmentException(message)

/** Publication moved the copy to the target and then failed; see [ReconciliationExecutor.ActionExecution.targetPublished]. */
internal class PartlyPublishedException(message: String, cause: IOException) : IOException(message, cause)

private fun notRun(action: ReconciliationAction) =
    ReconciliationExecutor.ActionExecution(action, ReconciliationExecutor.ActionStatus.PENDING, "not run after a previous failure")

/**
 * Refuses publication where directory permission bits cannot be read or set, rather than letting
 * the copy fall back to provider defaults (decision 2026-09-30). A provider that accepts but ignores
 * the bits is caught later by [verifyCopy]. [store] is the [fileStoreOfExistingAncestor] of [path], which the
 * caller passes so the target's store is looked up once.
 */
private fun requirePosixPermissions(path: Path, store: FileStore) {
    if (!store.supportsFileAttributeView(PosixFileAttributeView::class.java)) {
        throw EnvironmentException("cannot preserve directory permissions: no POSIX permission support at $path")
    }
}

/**
 * Makes [path] a directory, creating its missing components.
 *
 * The symlink rule: an existing ancestor of a path that an action works on may be a symlink to a directory, as
 * `/var` is on macOS and `/home` on Fedora Atomic, so existing components here are followed. Every directory
 * HomeLight creates must be real, and so must the paths that actions work on (source, target, staging root): their
 * guards and [ensureRealDirectory] do not follow links. Once a component is missing, the rest are created too, so a
 * symlink that appears there meanwhile is refused.
 */
private fun ensureDirectories(path: Path) {
    val absolute = path.toAbsolutePath().normalize()
    var current = absolute.root
    var creating = false
    for (name in absolute) {
        current = current.resolve(name)
        if (creating || !Files.isDirectory(current)) {
            creating = true
            createRealDirectory(current)
        }
    }
}

/** Makes [path] a real directory, not a link to one, after [ensureDirectories] for its parent. */
private fun ensureRealDirectory(path: Path) {
    path.toAbsolutePath().normalize().parent?.let(::ensureDirectories)
    createRealDirectory(path)
}

private fun createRealDirectory(path: Path) {
    try {
        Files.createDirectory(path)
    } catch (_: FileAlreadyExistsException) {
        // An independent relocation running concurrently may create a shared ancestor first.
    }
    if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
        throw StateDriftException("expected real directory at $path")
    }
}

/** Follows a symlink at the nearest existing component, as [ensureDirectories] would. */
private fun fileStoreOfExistingAncestor(path: Path): FileStore {
    val existing = generateSequence(path.toAbsolutePath().normalize()) { it.parent }
        .firstOrNull { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
        ?: throw IOException("no existing ancestor for $path")
    if (!Files.isDirectory(existing)) {
        throw StateDriftException("expected real directory at $existing")
    }
    return Files.getFileStore(existing)
}
