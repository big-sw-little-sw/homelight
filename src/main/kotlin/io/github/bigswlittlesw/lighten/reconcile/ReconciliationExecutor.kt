package io.github.bigswlittlesw.lighten.reconcile

import io.github.bigswlittlesw.lighten.concurrent.RELOCATION_CONCURRENCY
import io.github.bigswlittlesw.lighten.concurrent.Outcome
import io.github.bigswlittlesw.lighten.concurrent.mapBounded
import io.github.bigswlittlesw.lighten.fs.PathInspector
import io.github.bigswlittlesw.lighten.fs.PathText
import io.github.bigswlittlesw.lighten.fs.PathObservation
import io.github.bigswlittlesw.lighten.fs.PathState
import io.github.bigswlittlesw.lighten.fs.systemReason
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileStore
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Applies a plan with no blocked actions and no open choices. It stops when the filesystem no longer matches an
 * action's guards.
 */
class ReconciliationExecutor internal constructor(
    private val concurrency: Int,
    /** A test seam, called as an action reaches each [Step]. */
    private val step: (Step, Path) -> Unit,
) {
    constructor(concurrency: Int = RELOCATION_CONCURRENCY) : this(concurrency, { _, _ -> })

    private val inspector = PathInspector()

    /**
     * Compares the disk with every observation kept at review, before any action runs. It compares path states, link
     * destinations and whether they exist, and whether directories are empty. It does not compare tree contents.
     * Each action still checks its own guards, because other programs can change the disk after preflight.
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
            "STALE_PLAN", PathText("Filesystem state changed since review: ", path),
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
        // A single group runs on the caller's thread, so the caller's interrupts still reach it. mapBounded never
        // passes interrupts to its threads.
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
        var failed = false
        val actions = relocation.actions.map { action ->
            if (failed) {
                notRun(action)
            } else {
                runAction(relocation, action, progress, halted).also { failed = it.status == ActionStatus.FAILED }
            }
        }
        return RelocationExecution(relocation, actions)
    }

    /**
     * Runs one action and reports it to [progress] exactly once. A failure sets [halted] before it is reported:
     * a listener that sees the failure can rely on no new relocation starting.
     *
     * Only I/O and environment failures, from the action or from [ProgressListener.started], fail the action. Anything
     * else is a bug: it propagates, after `finally` blocks have cleaned up staging. [ProgressListener.finished] runs
     * outside that handling, so a listener that throws there propagates too, rather than recording the action again
     * as failed.
     */
    private fun runAction(
        relocation: RelocationPlan, action: ReconciliationAction, progress: ProgressListener, halted: AtomicBoolean,
    ): ActionExecution {
        val execution = try {
            progress.started(relocation, action)
            completed(action, apply(action))
        } catch (exception: IOException) {
            failure(action, ioMessage(exception), ioFailure(exception))
        } catch (exception: EnvironmentException) {
            failure(action, exception.message.orEmpty(), exception.failure)
        }
        if (execution.status == ActionStatus.FAILED) halted.set(true)
        progress.finished(relocation, execution)
        return execution
    }

    /** Runs [action] and returns the sockets it skipped; only a migration copies, so only it can skip any. */
    private fun apply(action: ReconciliationAction): List<Path> = when (action) {
        is ReconciliationAction.CreateDirectory -> { createDirectory(action); listOf() }
        is ReconciliationAction.EnsureDirectory -> { ensureDirectory(action); listOf() }
        is ReconciliationAction.MigrateDirectoryForPublication -> migrateDirectoryForPublication(action)
        is ReconciliationAction.ArchiveDirectory -> { archiveDirectory(action); listOf() }
        is ReconciliationAction.DeleteDirectory -> { deleteDirectory(action); listOf() }
        is ReconciliationAction.CreateSymlink -> { createSymlink(action); listOf() }
        is ReconciliationAction.ReplaceDirectoryWithSymlink -> { replaceDirectoryWithSymlink(action); listOf() }
        is ReconciliationAction.ReplaceSymlink -> { replaceSymlink(action); listOf() }
        is ReconciliationAction.NoOp -> listOf()
        is ReconciliationAction.LeaveUnchanged -> listOf()
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

    private fun migrateDirectoryForPublication(action: ReconciliationAction.MigrateDirectoryForPublication): List<Path> {
        requireState(action.path, PathState.DIRECTORY)
        requireState(action.target, PathState.ABSENT)
        val targetParent: Path = checkNotNull(action.target.parent) { "target has no parent directory: ${action.target}" }
        val stagingRoot = action.effectiveStagingRoot
        requirePosixPermissions(action.path, fileStoreOfExistingAncestor(action.path))
        val targetStore = fileStoreOfExistingAncestor(targetParent)
        requirePosixPermissions(targetParent, targetStore)
        // The same store rules out a cross-device rename, so `publish` fails only before it changes anything.
        if (fileStoreOfExistingAncestor(stagingRoot) != targetStore) {
            throw EnvironmentException(
                ActionFailure.StagingElsewhere(stagingRoot, action.target),
                "staging root is not on the target filesystem: $stagingRoot",
            )
        }
        ensureDirectories(targetParent)
        ensureRealDirectory(stagingRoot)
        return StagingOperation.open(stagingRoot, action.target, step).use { operation ->
            val skippedSockets = operation.stage(action.path)
            requireState(action.path, PathState.DIRECTORY)
            requireState(action.target, PathState.ABSENT)
            operation.publish(action.target)
            skippedSockets
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
     * Changes the source only in atomic steps. The source is renamed aside to its [replacedSourcePath], then the link
     * moves into its place, and only then is the renamed tree deleted. After a crash or failure, one of these is true:
     * - The whole source is at its path.
     * - Nothing is at its path, and the whole source is aside.
     * - The link is at its path, and the rest of the source is aside.
     *
     * None of these is a partial source next to the target. A partial source would plan as both directories existing,
     * and a saved `discard` rule would then delete the whole target. The planner deletes what is aside only once the
     * link is in place.
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
            throw EnvironmentException(
                ActionFailure.LinkChanged(action.path, action.expectedSourceTarget, actualTarget),
                "expected symlink target ${action.expectedSourceTarget} at ${action.path}",
            )
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
        val temporary = Files.createTempFile(path.parent, ".lighten-", ".link")
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
            throw EnvironmentException(
                ActionFailure.Drift(path, expected, actual),
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

    /**
     * [message] is the executor's own text; a failed action also has its [failure], for plain wording. A completed
     * migration lists the sockets its copy skipped in [skippedSockets], and its message names them.
     */
    data class ActionExecution(
        val action: ReconciliationAction, val status: ActionStatus, val message: String,
        val failure: ActionFailure? = null, val skippedSockets: List<Path> = listOf(),
    ) {
        /** A guard found something other than the plan. */
        val stateDrift: Boolean get() = failure is ActionFailure.Drift || failure is ActionFailure.LinkChanged

        /** A failed migration that had already moved its copy to the target. */
        val targetPublished: Boolean get() = failure is ActionFailure.PermissionsNotRestored
    }

    data class RelocationExecution(val relocation: RelocationPlan, val actions: List<ActionExecution>) {
        /**
         * The outcome after the actions ran. A failure after the migration published the target is
         * [ExecutionOutcome.FAILED_RECOVERY]: the target exists, but the relocation did not finish.
         */
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
internal class EnvironmentException(val failure: ActionFailure, message: String) : Exception(message)

/** Publication moved the copy to [target] and then failed; see [ReconciliationExecutor.ActionExecution.targetPublished]. */
internal class PartlyPublishedException(val target: Path, message: String, val failure: IOException) : IOException(message, failure)

private fun notRun(action: ReconciliationAction) =
    ReconciliationExecutor.ActionExecution(action, ReconciliationExecutor.ActionStatus.PENDING, "not run after a previous failure")

private fun completed(action: ReconciliationAction, skippedSockets: List<Path>) = ReconciliationExecutor.ActionExecution(
    action, ReconciliationExecutor.ActionStatus.COMPLETED,
    if (skippedSockets.isEmpty()) "completed" else "completed; skipped sockets: " + skippedSockets.joinToString(),
    skippedSockets = skippedSockets,
)

private fun failure(action: ReconciliationAction, message: String, failure: ActionFailure) =
    ReconciliationExecutor.ActionExecution(action, ReconciliationExecutor.ActionStatus.FAILED, message, failure)

/**
 * The executor's text for [exception]: the system's reason, then any paths it names, such as
 * `permission denied: /home/me/.lighten-1.link`. The JDK throws the common file exceptions without a reason, so
 * their own message is only a path.
 */
internal fun ioMessage(exception: IOException): String {
    val reason = systemReason(exception)
    val paths = (exception as? FileSystemException)?.let { listOfNotNull(it.file, it.otherFile) }.orEmpty()
    return if (paths.isEmpty()) reason else reason + ": " + paths.joinToString(" -> ")
}

/**
 * Refuses publication where directory permission bits cannot be read or set. Otherwise the copy would get the
 * filesystem's default permissions. Relocated directories often go to shared storage, where wider permissions would
 * expose private data. [verifyCopy] later finds a filesystem that accepts the bits but ignores them.
 *
 * [store] is the [fileStoreOfExistingAncestor] of [path]. The caller passes it so the target's store is looked up once.
 */
private fun requirePosixPermissions(path: Path, store: FileStore) {
    if (!store.supportsFileAttributeView(PosixFileAttributeView::class.java)) {
        throw EnvironmentException(
            ActionFailure.NoPosixPermissions(path), "cannot preserve directory permissions: no POSIX permission support at $path",
        )
    }
}

/**
 * Makes [path] a directory, creating its missing components.
 *
 * The symlink rule: an existing ancestor of a path that an action works on may be a symlink to a directory, as
 * `/var` is on macOS and `/home` on Fedora Atomic. So this follows existing components. Every directory Lighten
 * creates must be real. So must the paths that actions work on: the source, target and staging root. Their guards and
 * [ensureRealDirectory] do not follow links. Once a component is missing, this creates the rest too, so it refuses a
 * symlink that appears there meanwhile.
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
    if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw notRealDirectory(path)
}

/**
 * The file store of the nearest existing ancestor of [path], following a symlink there as [ensureDirectories] does.
 * Inspection uses it too, so the plan-time staging check ([stagingElsewhere]) and the copy-time check compare the
 * same stores.
 */
internal fun fileStoreOfExistingAncestor(path: Path): FileStore {
    val existing = generateSequence(path.toAbsolutePath().normalize()) { it.parent }
        .firstOrNull { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
        ?: throw IOException("no existing ancestor for $path")
    if (!Files.isDirectory(existing)) throw notRealDirectory(existing)
    return Files.getFileStore(existing)
}

private fun notRealDirectory(path: Path) = EnvironmentException(
    ActionFailure.Drift(path, PathState.DIRECTORY, PathInspector().inspect(path).state), "expected real directory at $path",
)
