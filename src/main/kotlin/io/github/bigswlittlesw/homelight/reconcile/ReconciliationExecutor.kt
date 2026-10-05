package io.github.bigswlittlesw.homelight.reconcile

import io.github.bigswlittlesw.homelight.concurrent.RELOCATION_CONCURRENCY
import io.github.bigswlittlesw.homelight.concurrent.Outcome
import io.github.bigswlittlesw.homelight.concurrent.mapBounded
import io.github.bigswlittlesw.homelight.config.intersects
import io.github.bigswlittlesw.homelight.config.realSpelling
import io.github.bigswlittlesw.homelight.config.relocationProblem
import io.github.bigswlittlesw.homelight.fs.PathInspector
import io.github.bigswlittlesw.homelight.fs.PathObservation
import io.github.bigswlittlesw.homelight.fs.PathState
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileStore
import java.nio.file.FileSystemException
import java.nio.file.FileVisitResult
import java.nio.file.FileVisitor
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.PathWalkOption
import kotlin.io.path.deleteRecursively
import kotlin.io.path.fileVisitor
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.notExists
import kotlin.io.path.readSymbolicLink
import kotlin.io.path.walk

/** Applies a fully resolved plan, stopping when the filesystem no longer matches its guards. */
class ReconciliationExecutor internal constructor(
    private val concurrency: Int,
    /** A test seam, called with the staged copy's path as staged publication reaches each [StagingStep]. */
    private val stagingStep: (StagingStep, Path) -> Unit,
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
        val diagnostics = ArrayList<ReconciliationDiagnostic>()
        for (state in plan.expectedStates) {
            checkObservation(state.relocation.sourcePath, state.source, diagnostics)
            checkObservation(state.relocation.targetPath, state.target, diagnostics)
            state.archiveDestination?.let { archive -> checkObservation(archive.path, archive.observation, diagnostics) }
        }
        return diagnostics
    }

    private fun checkObservation(
        path: Path, expected: PathObservation,
        diagnostics: MutableList<ReconciliationDiagnostic>,
    ) {
        if (inspector.inspect(path) != expected) {
            diagnostics.add(
                ReconciliationDiagnostic(
                    ReconciliationDiagnostic.Severity.ERROR, path,
                    "STALE_PLAN", "Filesystem state changed since review: $path",
                ),
            )
        }
    }

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
        StagingOperation.open(stagingRoot, action.target, stagingStep).use { operation ->
            operation.stage(action.path)
            requireState(action.path, PathState.DIRECTORY)
            requireState(action.target, PathState.ABSENT)
            operation.publish(action.target)
        }
    }

    /** Points in staged publication at which [stagingStep] is called; each one is after the step it names. */
    internal enum class StagingStep { LOCKED, COPIED, PUBLISHED }

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

    private fun replaceDirectoryWithSymlink(action: ReconciliationAction.ReplaceDirectoryWithSymlink) {
        requireState(action.path, PathState.DIRECTORY)
        requireState(action.target, PathState.DIRECTORY)
        replaceWithLink(action.path, action.target) {
            requireState(action.path, PathState.DIRECTORY)
            requireState(action.target, PathState.DIRECTORY)
            deleteTree(action.path)
        }
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
private open class EnvironmentException(message: String) : Exception(message)

/** The filesystem no longer matches an action's guard; reported as [ReconciliationExecutor.ActionExecution.stateDrift]. */
private class StateDriftException(message: String) : EnvironmentException(message)

/** Publication moved the copy to the target and then failed; see [ReconciliationExecutor.ActionExecution.targetPublished]. */
private class PartlyPublishedException(message: String, cause: IOException) : IOException(message, cause)

private fun notRun(action: ReconciliationAction) =
    ReconciliationExecutor.ActionExecution(action, ReconciliationExecutor.ActionStatus.PENDING, "not run after a previous failure")

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

/**
 * Keys of the targets this process is staging. Closing any channel to a file releases every lock this process holds
 * on it (see [FileLock]), so a second operation for a target in this process must fail before it opens the lock file.
 * The set is process-wide because file locks are; [StagingOperation] adds and removes its own key.
 */
private val stagingKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()

/**
 * Publication of one target through its staging root. The staged copy is `operation-<key>` and its lock file
 * `operation-<key>.lock`, where the key is the SHA-256 of the target's [realSpelling]. Every spelling of a target, in
 * any process, uses the same two names, and different targets never open each other's.
 *
 * [open] claims the key in [stagingKeys], then takes the lock without waiting, then clears whatever is at the copy's
 * name: under the lock, that can only be left by an earlier run that failed or was killed. [close] deletes the copy
 * under the lock, then releases the lock, then the key. The lock file is never deleted: another process may have it
 * open already, and a new file at the same name would let two processes each hold a lock for one target.
 */
private class StagingOperation private constructor(
    private val key: String, private val copy: Path, private val channel: FileChannel,
    private val stagingStep: (ReconciliationExecutor.StagingStep, Path) -> Unit,
) : AutoCloseable {
    fun stage(source: Path) {
        Files.walkFileTree(source, copyVisitor(source, copy))
        verifyCopy(source, copy)
        stagingStep(ReconciliationExecutor.StagingStep.COPIED, copy)
    }

    /**
     * Moves the staged copy to [target] in one step. rename(2) needs write permission on a directory that changes
     * parent, so a root without owner write gets it for the move only. A failure after the move is a
     * [PartlyPublishedException].
     */
    fun publish(target: Path) {
        val permissions = directoryPermissions(copy)
        val writable = PosixFilePermission.OWNER_WRITE in permissions
        if (!writable) {
            Files.setPosixFilePermissions(copy, permissions + PosixFilePermission.OWNER_WRITE)
        }
        Files.move(copy, target, StandardCopyOption.ATOMIC_MOVE)
        try {
            stagingStep(ReconciliationExecutor.StagingStep.PUBLISHED, copy)
            if (!writable) {
                Files.setPosixFilePermissions(target, permissions)
            }
        } catch (exception: IOException) {
            throw PartlyPublishedException(
                "published $target but could not restore its permissions: ${exception.message}", exception,
            )
        }
    }

    /** Through `use`, a failure here is added to the block's own failure rather than replacing it. */
    override fun close() {
        try {
            clearCopy(copy)
        } finally {
            try {
                channel.close()
            } finally {
                stagingKeys.remove(key)
            }
        }
    }

    companion object {
        fun open(
            stagingRoot: Path, target: Path, stagingStep: (ReconciliationExecutor.StagingStep, Path) -> Unit,
        ): StagingOperation {
            val key = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(realSpelling(target).toString().toByteArray()),
            )
            if (!stagingKeys.add(key)) {
                throw EnvironmentException("this HomeLight is already publishing $target")
            }
            try {
                val copy = stagingRoot.resolve("operation-$key")
                val channel = FileChannel.open(copy.resolveSibling("operation-$key.lock"), LOCK_OPTIONS, OWNER_ONLY_FILE)
                try {
                    if (channel.tryLock() == null) {
                        throw EnvironmentException("another HomeLight is publishing $target")
                    }
                    stagingStep(ReconciliationExecutor.StagingStep.LOCKED, copy)
                    clearCopy(copy)
                    return StagingOperation(key, copy, channel, stagingStep)
                } catch (throwable: Throwable) {
                    channel.close()
                    throw throwable
                }
            } catch (throwable: Throwable) {
                stagingKeys.remove(key)
                throw throwable
            }
        }
    }
}

private val LOCK_OPTIONS = setOf<OpenOption>(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)

private val OWNER_ACCESS = setOf(
    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
)
private val OWNER_ONLY_DIRECTORY = PosixFilePermissions.asFileAttribute(OWNER_ACCESS)
private val OWNER_ONLY_FILE =
    PosixFilePermissions.asFileAttribute(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))

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

private fun directoryPermissions(directory: Path): Set<PosixFilePermission> =
    Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS)

/**
 * Deletes a staged copy, or whatever else is at its name, without following links. A failed or killed copy can leave
 * directories with their source's restrictive mode (`0500`), and deleting their entries needs owner write.
 */
private fun clearCopy(copy: Path) {
    if (Files.isDirectory(copy, LinkOption.NOFOLLOW_LINKS)) {
        restoreOwnerAccess(copy)
    }
    deleteTree(copy)
}

/** Gives the owner full access to every directory under [root], without following links. */
private fun restoreOwnerAccess(root: Path) {
    val permissions = directoryPermissions(root)
    if (!permissions.containsAll(OWNER_ACCESS)) {
        Files.setPosixFilePermissions(root, permissions + OWNER_ACCESS)
    }
    root.listDirectoryEntries()
        .filter { entry -> Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) }
        .forEach(::restoreOwnerAccess)
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
    var current: Path? = path.toAbsolutePath().normalize()
    while (current != null) {
        if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(current)) {
                throw StateDriftException("expected real directory at $current")
            }
            return Files.getFileStore(current)
        }
        current = current.parent
    }
    throw IOException("no existing ancestor for $path")
}

/**
 * Deletes [root] and everything under it without following links; a missing root is a no-op.
 *
 * `deleteRecursively` continues past failures and reports them only as suppressed exceptions of a
 * generic one. The first failure's message is rethrown, so the reported message names the entry.
 */
private fun deleteTree(root: Path) {
    try {
        @OptIn(ExperimentalPathApi::class)
        root.deleteRecursively()
    } catch (failure: FileSystemException) {
        val first = failure.suppressed.firstOrNull() ?: throw failure
        throw IOException(deleteFailureMessage(first), failure)
    }
}

/**
 * With `SecureDirectoryStream`, an entry's exception carries only its name, so `deleteRecursively`
 * wraps it in one that holds the full path. Rejoin that path with the cause's reason to give the
 * message the entry's own exception would have had.
 */
private fun deleteFailureMessage(failure: Throwable): String? {
    val cause = failure.cause
    return if (failure is FileSystemException && cause is FileSystemException) {
        FileSystemException(failure.file, cause.otherFile, cause.reason).message
    } else {
        failure.message
    }
}

/**
 * Copies a tree, giving each copied directory the nine permission bits of its source.
 *
 * Directories start owner-only, so the copy is never more open to group or others than the
 * source, and stay owner-writable until their entries are copied. Each gets its final mode
 * after its contents, which lets a read-only source directory (`0500`) still receive children.
 * `destination` must not exist yet.
 */
internal fun copyVisitor(source: Path, destination: Path): FileVisitor<Path> = fileVisitor {
    onPreVisitDirectory { directory, _ ->
        Files.createDirectory(copiedPath(source, destination, directory), OWNER_ONLY_DIRECTORY)
        FileVisitResult.CONTINUE
    }
    onVisitFile { file, _ ->
        Files.copy(file, copiedPath(source, destination, file), LinkOption.NOFOLLOW_LINKS)
        FileVisitResult.CONTINUE
    }
    onPostVisitDirectory { directory, exception ->
        if (exception != null) {
            throw exception
        }
        Files.setPosixFilePermissions(copiedPath(source, destination, directory), directoryPermissions(directory))
        FileVisitResult.CONTINUE
    }
}

/** Walks depth-first, directories before their entries, never following links. */
private fun verifyCopy(source: Path, copy: Path) {
    for (entry in source.walk(PathWalkOption.INCLUDE_DIRECTORIES)) {
        val copied = copiedPath(source, copy, entry)
        if (entry.isDirectory(LinkOption.NOFOLLOW_LINKS)) {
            if (!copied.isDirectory(LinkOption.NOFOLLOW_LINKS)) {
                throw IOException("copied directory is missing: $copied")
            }
            if (directoryPermissions(entry) != directoryPermissions(copied)) {
                throw IOException("copied directory permissions differ: $copied")
            }
        } else if (entry.isSymbolicLink()) {
            if (!copied.isSymbolicLink() || entry.readSymbolicLink() != copied.readSymbolicLink()) {
                throw IOException("copied symlink differs: $copied")
            }
        } else if (!copied.isRegularFile(LinkOption.NOFOLLOW_LINKS) || entry.fileSize() != copied.fileSize()) {
            throw IOException("copied file differs: $copied")
        }
    }
    for (copied in copy.walk(PathWalkOption.INCLUDE_DIRECTORIES)) {
        if (copiedPath(copy, source, copied).notExists(LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("copied directory has an unexpected entry: $copied")
        }
    }
}

private fun copiedPath(source: Path, copy: Path, entry: Path): Path = copy.resolve(source.relativize(entry))
