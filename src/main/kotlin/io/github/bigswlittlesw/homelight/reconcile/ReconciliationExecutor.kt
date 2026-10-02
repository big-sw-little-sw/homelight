package io.github.bigswlittlesw.homelight.reconcile

import io.github.bigswlittlesw.homelight.concurrent.RELOCATION_CONCURRENCY
import io.github.bigswlittlesw.homelight.concurrent.forEachBounded
import io.github.bigswlittlesw.homelight.config.intersects
import io.github.bigswlittlesw.homelight.config.isJavaBlank
import io.github.bigswlittlesw.homelight.config.relocationProblem
import io.github.bigswlittlesw.homelight.fs.PathInspector
import io.github.bigswlittlesw.homelight.fs.PathObservation
import io.github.bigswlittlesw.homelight.fs.PathState
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileStore
import java.nio.file.FileSystemException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.PathWalkOption
import kotlin.io.path.deleteRecursively
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.notExists
import kotlin.io.path.readSymbolicLink
import kotlin.io.path.walk

/** Applies a fully resolved plan, stopping when the filesystem no longer matches its guards. */
class ReconciliationExecutor(private val concurrency: Int = RELOCATION_CONCURRENCY) {
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
        // Owned by this call and shared with its group threads. Each slot has one writer; the join in
        // forEachBounded publishes the slots back to this thread.
        val halted = AtomicBoolean()
        val unexpected = AtomicReference<Throwable>()
        val slots = arrayOfNulls<RelocationExecution>(plan.relocations.size)
        fun run(group: List<Int>) {
            for (index in group) {
                if (halted.get()) break
                try {
                    slots[index] = execute(plan.relocations[index], progress, halted)
                } catch (throwable: Throwable) {
                    // Rethrown below, as the sequential loop would have, once running groups finish.
                    unexpected.compareAndSet(null, throwable)
                    halted.set(true)
                }
            }
        }
        val groups = independentGroups(plan.relocations)
        // A single group runs on the caller's thread, as before concurrency, so the caller's interrupts still reach it.
        if (groups.size == 1) run(groups.single()) else forEachBounded(groups, concurrency, halted::get, ::run)
        unexpected.get()?.let { throw it }
        return ExecutionResult(
            plan.relocations.mapIndexed { index, relocation ->
                slots[index] ?: RelocationExecution(relocation, relocation.actions.map(::notRun))
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
                    exception is StateDriftException,
                )
                actions.add(execution)
                halted.set(true)
                failed = true
                progress.finished(relocation, execution)
            }
            // Only I/O and state failures halt the plan; anything else propagates.
            try {
                progress.started(relocation, action)
                val execution = ActionExecution(action, ActionStatus.COMPLETED, apply(action))
                actions.add(execution)
                progress.finished(relocation, execution)
            } catch (exception: IOException) {
                fail(exception)
            } catch (exception: IllegalStateException) {
                fail(exception)
            }
        }
        return RelocationExecution(relocation, actions)
    }

    private fun apply(action: ReconciliationAction): String = when (action) {
        is ReconciliationAction.CreateDirectory -> { createDirectory(action); "completed" }
        is ReconciliationAction.EnsureDirectory -> { ensureDirectory(action); "completed" }
        is ReconciliationAction.MigrateDirectoryForPublication -> migrateDirectoryForPublication(action)
        is ReconciliationAction.ArchiveDirectory -> { archiveDirectory(action); "completed" }
        is ReconciliationAction.DeleteDirectory -> { deleteDirectory(action); "completed" }
        is ReconciliationAction.CreateSymlink -> { createSymlink(action); "completed" }
        is ReconciliationAction.ReplaceDirectoryWithSymlink -> { replaceDirectoryWithSymlink(action); "completed" }
        is ReconciliationAction.ReplaceSymlink -> { replaceSymlink(action); "completed" }
        is ReconciliationAction.NoOp -> "completed"
        is ReconciliationAction.LeaveUnchanged -> "completed"
        is ReconciliationAction.Blocked -> error(action.reason)
    }

    private fun createDirectory(action: ReconciliationAction.CreateDirectory) {
        requireState(action.path, PathState.ABSENT)
        Files.createDirectory(action.path)
    }

    private fun ensureDirectory(action: ReconciliationAction.EnsureDirectory) {
        val state = inspector.inspect(action.path).state
        if (state == PathState.ABSENT) {
            Files.createDirectories(action.path)
        } else if (state != PathState.DIRECTORY) {
            throw StateDriftException(
                "expected absent or directory at ${action.path} but found ${state.name.lowercase(Locale.ROOT)}",
            )
        }
    }

    private fun migrateDirectoryForPublication(action: ReconciliationAction.MigrateDirectoryForPublication): String {
        requireState(action.path, PathState.DIRECTORY)
        requireState(action.target, PathState.ABSENT)
        val targetParent: Path = checkNotNull(action.target.parent) { "target has no parent directory: ${action.target}" }
        val stagingRoot = action.stagingRoot ?: targetParent.resolve(DEFAULT_STAGING_NAME)
        requirePosixPermissions(action.path)
        requirePosixPermissions(targetParent)
        check(fileStoreOfExistingAncestor(stagingRoot) == fileStoreOfExistingAncestor(targetParent)) {
            "staging root is not on the target filesystem: $stagingRoot"
        }
        ensureRealDirectories(targetParent)
        ensureRealDirectories(stagingRoot)
        cleanStaleStaging(stagingRoot)
        probeAtomicMove(stagingRoot)

        val operation = Files.createDirectory(stagingRoot.resolve(OPERATION_PREFIX + UUID.randomUUID()))
        val marker = operation.resolve("target")
        val lockPath = operation.resolve("lock")
        Files.writeString(marker, "$MARKER_HEADER${action.target.toAbsolutePath().normalize()}\n", StandardCharsets.UTF_8)
        val copy = operation.resolve("copy")
        var lockSupported = true
        try {
            FileChannel.open(lockPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                acquireLock(channel).use { lock ->
                    lockSupported = lock != null
                    Files.walkFileTree(action.path, CopyVisitor(action.path, copy))
                    verifyCopy(action.path, copy)
                    requireState(action.path, PathState.DIRECTORY)
                    requireState(action.target, PathState.ABSENT)
                    publish(copy, action.target)
                }
            }
        } finally {
            // A failed copy may hold directories without owner write; deleting their entries needs it.
            if (Files.isDirectory(copy, LinkOption.NOFOLLOW_LINKS)) {
                restoreOwnerAccess(copy)
            }
            deleteTree(operation)
        }
        return if (lockSupported) "completed" else "completed; staging locks unsupported, stale cleanup skipped"
    }

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
        replaceWithLink(action.path, action.target, false)
    }

    private fun replaceDirectoryWithSymlink(action: ReconciliationAction.ReplaceDirectoryWithSymlink) {
        requireState(action.path, PathState.DIRECTORY)
        requireState(action.target, PathState.DIRECTORY)
        val temporary = prepareLink(action.path, action.target)
        try {
            requireState(action.path, PathState.DIRECTORY)
            requireState(action.target, PathState.DIRECTORY)
            deleteTree(action.path)
            Files.move(temporary, action.path, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun replaceSymlink(action: ReconciliationAction.ReplaceSymlink) {
        requireState(action.path, PathState.SYMLINK)
        requireState(action.target, PathState.DIRECTORY)
        val actualTarget = inspector.inspect(action.path).symlinkTarget
            ?: throw StateDriftException("expected symlink at ${action.path}")
        if (actualTarget != action.expectedSourceTarget) {
            throw StateDriftException("expected symlink target ${action.expectedSourceTarget} at ${action.path}")
        }
        replaceWithLink(action.path, action.target, true)
    }

    private fun replaceWithLink(path: Path, target: Path, replaceExisting: Boolean) {
        val temporary = prepareLink(path, target)
        try {
            if (replaceExisting) {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } else {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun requireState(path: Path, expected: PathState) {
        val actual = inspector.inspect(path).state
        if (actual != expected) {
            throw StateDriftException(
                "expected ${expected.name.lowercase(Locale.ROOT)} at $path but found ${actual.name.lowercase(Locale.ROOT)}",
            )
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
    private class CopyVisitor(private val source: Path, private val destination: Path) : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
            Files.createDirectory(copiedPath(source, destination, directory), OWNER_ONLY_DIRECTORY)
            return FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
            Files.copy(file, copiedPath(source, destination, file), LinkOption.NOFOLLOW_LINKS)
            return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(directory: Path, exception: IOException?): FileVisitResult {
            if (exception != null) {
                throw exception
            }
            Files.setPosixFilePermissions(copiedPath(source, destination, directory), directoryPermissions(directory))
            return FileVisitResult.CONTINUE
        }
    }

    enum class ActionStatus { COMPLETED, FAILED, PENDING }

    interface ProgressListener {
        fun started(relocation: RelocationPlan, action: ReconciliationAction) {}

        fun finished(relocation: RelocationPlan, action: ActionExecution) {}

        companion object {
            val NONE: ProgressListener = object : ProgressListener {}
        }
    }

    data class ActionExecution(
        val action: ReconciliationAction, val status: ActionStatus, val message: String,
        val stateDrift: Boolean = false,
    )

    data class RelocationExecution(val relocation: RelocationPlan, val actions: List<ActionExecution>) {
        /** Returns the execution outcome after accounting for an interrupted source replacement. */
        fun outcome(): ExecutionOutcome {
            if (actions.any { action -> action.status == ActionStatus.PENDING }) {
                return ExecutionOutcome.UNRESOLVED
            }
            if (actions.none { action -> action.status == ActionStatus.FAILED }) {
                return when (relocation.outcome) {
                    RelocationOutcome.CONVERGED -> ExecutionOutcome.CONVERGED
                    RelocationOutcome.UNCHANGED -> ExecutionOutcome.UNCHANGED
                    RelocationOutcome.UNRESOLVED -> ExecutionOutcome.UNRESOLVED
                }
            }
            val targetPublished = actions.any { action ->
                action.action is ReconciliationAction.MigrateDirectoryForPublication && action.status == ActionStatus.COMPLETED
            }
            return if (targetPublished) ExecutionOutcome.FAILED_RECOVERY else ExecutionOutcome.UNRESOLVED
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

private class StateDriftException(message: String) : IllegalStateException(message)

private fun notRun(action: ReconciliationAction) =
    ReconciliationExecutor.ActionExecution(action, ReconciliationExecutor.ActionStatus.PENDING, "not run after a previous failure")

/**
 * Splits relocations into groups that can run at the same time, each listing plan indices in order. The groups are
 * ordered by their first index.
 *
 * Two relocations are independent when no path one claims overlaps a path the other claims, by the same
 * [intersects] rule that [relocationProblem] applies to sources and targets. A relocation claims its source and
 * target, every action destination (such as an archive path) and each migration's staging root. It also claims the
 * parent of each of these that is not yet a real directory, since its actions may create it. Relocations not proven
 * independent share a group, so a relocation that depends on two groups merges them. Siblings under an existing
 * parent can therefore run together; siblings whose parent is missing share a group.
 *
 * Parents are checked here, when [ReconciliationExecutor.execute] starts, not at plan time: the review snapshot does
 * not observe parents, and this is the latest state before any action runs.
 */
internal fun independentGroups(relocations: List<RelocationPlan>): List<List<Int>> {
    val claims = relocations.map(::claimedPaths)
    var groups = listOf<List<Int>>()
    for (index in relocations.indices) {
        val (dependent, independent) = groups.partition { group ->
            group.any { other -> claims[other].any { left -> claims[index].any { right -> intersects(left, right) } } }
        }
        groups = independent + listOf((dependent.flatten() + index).sorted())
    }
    return groups.sortedBy { group -> group.first() }
}

private fun claimedPaths(relocation: RelocationPlan): List<Path> {
    val paths = listOf(relocation.relocation.sourcePath, relocation.relocation.targetPath) +
        relocation.actions.mapNotNull { action -> action.destination } +
        relocation.actions.filterIsInstance<ReconciliationAction.MigrateDirectoryForPublication>().map { migration ->
            migration.stagingRoot ?: migration.target.resolveSibling(DEFAULT_STAGING_NAME)
        }
    // An existing parent is not claimed. On POSIX, creating, renaming, linking or deleting different names in one
    // directory is safe, and each action's guards check only its own paths. A parent another relocation changes is
    // inside one of that relocation's paths, so this relocation's path overlaps it anyway. A missing parent stays
    // claimed. Its missing ancestors may still be created concurrently, which `ensureRealDirectories` and
    // `Files.createDirectories` tolerate before checking for a real directory.
    val parents = paths.mapNotNull { path -> path.toAbsolutePath().normalize().parent }
    return paths + parents.filterNot { parent -> Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) }
}

private const val DEFAULT_STAGING_NAME = ".homelight-staging"
private const val OPERATION_PREFIX = "operation-"
private const val MARKER_HEADER = "homelight-staging-v1\n"

private val OWNER_ACCESS = setOf(
    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
)
private val OWNER_ONLY_DIRECTORY = PosixFilePermissions.asFileAttribute(OWNER_ACCESS)

/**
 * Refuses publication where directory permission bits cannot be read or set, rather than letting
 * the copy fall back to provider defaults (decision 2026-09-30). A provider that accepts but ignores
 * the bits is caught later by [verifyCopy].
 */
private fun requirePosixPermissions(path: Path) {
    check(fileStoreOfExistingAncestor(path).supportsFileAttributeView(PosixFileAttributeView::class.java)) {
        "cannot preserve directory permissions: no POSIX permission support at $path"
    }
}

private fun directoryPermissions(directory: Path): Set<PosixFilePermission> =
    Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS)

/**
 * Moves the staged copy to [target] in one step. rename(2) needs write permission on a directory
 * that changes parent, so a root without owner write gets it for the move only.
 */
private fun publish(copy: Path, target: Path) {
    val permissions = directoryPermissions(copy)
    if (PosixFilePermission.OWNER_WRITE in permissions) {
        Files.move(copy, target, StandardCopyOption.ATOMIC_MOVE)
        return
    }
    Files.setPosixFilePermissions(copy, permissions + PosixFilePermission.OWNER_WRITE)
    Files.move(copy, target, StandardCopyOption.ATOMIC_MOVE)
    try {
        Files.setPosixFilePermissions(target, permissions)
    } catch (exception: IOException) {
        throw IOException("published $target but could not restore its permissions: ${exception.message}", exception)
    }
}

/** Gives the owner full access to every directory under [root] so a staged copy can be deleted. */
private fun restoreOwnerAccess(root: Path) {
    val permissions = directoryPermissions(root)
    if (!permissions.containsAll(OWNER_ACCESS)) {
        Files.setPosixFilePermissions(root, permissions + OWNER_ACCESS)
    }
    root.listDirectoryEntries()
        .filter { entry -> Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) }
        .forEach(::restoreOwnerAccess)
}

private fun ensureRealDirectories(path: Path) {
    val absolute = path.toAbsolutePath().normalize()
    var current = absolute.root
    for (name in absolute) {
        current = current.resolve(name)
        if (Files.notExists(current, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createDirectory(current)
            } catch (_: FileAlreadyExistsException) {
                // An independent relocation running concurrently may create a shared ancestor first.
            }
        }
        if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
            throw StateDriftException("expected real directory at $current")
        }
    }
}

private fun acquireLock(channel: FileChannel): FileLock? =
    try {
        channel.lock()
    } catch (_: UnsupportedOperationException) {
        null
    }

private fun probeAtomicMove(stagingRoot: Path) {
    val probe = Files.createTempDirectory(stagingRoot, "atomic-probe-")
    val published = probe.resolveSibling("${probe.fileName}.published")
    try {
        Files.move(probe, published, StandardCopyOption.ATOMIC_MOVE)
    } finally {
        if (Files.exists(published, LinkOption.NOFOLLOW_LINKS)) {
            deleteTree(published)
        } else if (Files.exists(probe, LinkOption.NOFOLLOW_LINKS)) {
            deleteTree(probe)
        }
    }
}

private fun cleanStaleStaging(stagingRoot: Path) {
    for (entry in stagingRoot.listDirectoryEntries()) {
        if (!isOwnedOperation(entry)) {
            continue
        }
        val marker = entry.resolve("target")
        val lock = entry.resolve("lock")
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
            || !Files.isRegularFile(lock, LinkOption.NOFOLLOW_LINKS)
        ) {
            continue
        }
        val target = markedTarget(marker)
        if (target == null || !hasOnlyOperationEntries(entry)) {
            continue
        }
        if (!sameFileStore(stagingRoot, target.parent) || Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            continue
        }
        try {
            FileChannel.open(lock, StandardOpenOption.WRITE).use { channel ->
                val held = tryAcquireLock(channel)
                if (held != null) {
                    held.use {
                        deleteStaleOperation(entry)
                    }
                }
            }
        } catch (_: UnsupportedOperationException) {
            return
        }
    }
}

/**
 * Deletes an operation that passed the ownership checks and whose lock the caller holds.
 *
 * A killed copy can leave directories with their source's restrictive mode (`0500`), so owner access
 * is restored first; this also lets the symlink check walk every directory. The copy goes before the
 * marker and lock: if its delete fails partway, the operation still passes the ownership checks and
 * a later run retries it.
 */
private fun deleteStaleOperation(operation: Path) {
    val copy = operation.resolve("copy")
    if (Files.isDirectory(copy, LinkOption.NOFOLLOW_LINKS)) {
        restoreOwnerAccess(copy)
    }
    if (containsSymlink(operation)) {
        return
    }
    deleteTree(copy)
    deleteTree(operation)
}

private fun isOwnedOperation(entry: Path): Boolean {
    val name = entry.fileName.toString()
    if (!name.startsWith(OPERATION_PREFIX) || !Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(entry)
    ) {
        return false
    }
    return try {
        UUID.fromString(name.substring(OPERATION_PREFIX.length))
        true
    } catch (_: IllegalArgumentException) {
        false
    }
}

private fun markedTarget(marker: Path): Path? {
    val text = Files.readString(marker, StandardCharsets.UTF_8)
    if (!text.startsWith(MARKER_HEADER) || !text.endsWith("\n")) {
        return null
    }
    val value = text.substring(MARKER_HEADER.length, text.length - 1)
    if (value.isJavaBlank() || value.contains("\n")) {
        return null
    }
    return try {
        Path.of(value).toAbsolutePath().normalize()
    } catch (_: InvalidPathException) {
        null
    }
}

private fun containsSymlink(root: Path): Boolean = root.walk().any { entry -> entry.isSymbolicLink() }

private fun hasOnlyOperationEntries(operation: Path): Boolean =
    operation.listDirectoryEntries().all { entry ->
        when (entry.fileName.toString()) {
            "target", "lock" -> Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
            "copy" -> Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)
            else -> false
        }
    }

private fun sameFileStore(left: Path, right: Path): Boolean =
    try {
        Files.getFileStore(left) == Files.getFileStore(right)
    } catch (_: IOException) {
        false
    }

private fun fileStoreOfExistingAncestor(path: Path): FileStore {
    var current: Path? = path.toAbsolutePath().normalize()
    while (current != null) {
        if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw StateDriftException("expected real directory at $current")
            }
            return Files.getFileStore(current)
        }
        current = current.parent
    }
    throw IOException("no existing ancestor for $path")
}

private fun tryAcquireLock(channel: FileChannel): FileLock? =
    try {
        channel.tryLock()
    } catch (_: OverlappingFileLockException) {
        null
    }

private fun prepareLink(path: Path, target: Path): Path {
    val temporary = Files.createTempFile(path.parent, ".homelight-", ".link")
    Files.delete(temporary)
    Files.createSymbolicLink(temporary, target)
    return temporary
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
