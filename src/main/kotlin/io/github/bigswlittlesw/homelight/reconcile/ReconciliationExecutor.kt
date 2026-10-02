package io.github.bigswlittlesw.homelight.reconcile

import io.github.bigswlittlesw.homelight.config.isJavaBlank
import io.github.bigswlittlesw.homelight.fs.PathInspector
import io.github.bigswlittlesw.homelight.fs.PathObservation
import io.github.bigswlittlesw.homelight.fs.PathState
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.FileStore
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import java.util.UUID

/** Applies a fully resolved plan, stopping when the filesystem no longer matches its guards. */
class ReconciliationExecutor {
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

    fun execute(plan: ReconciliationPlan, progress: ProgressListener = ProgressListener.NONE): ExecutionResult {
        require(!plan.hasBlockedActions() && !plan.hasConflicts()) { "Only fully resolved plans can be executed" }
        val relocations = ArrayList<RelocationExecution>()
        var halted = false
        for (relocation in plan.relocations) {
            val actions = ArrayList<ActionExecution>()
            for (action in relocation.actions) {
                if (halted) {
                    actions.add(ActionExecution(action, ActionStatus.PENDING, "not run after a previous failure"))
                    continue
                }
                fun fail(exception: Exception) {
                    val execution = ActionExecution(
                        action, ActionStatus.FAILED, exception.message ?: exception.toString(),
                        exception is StateDriftException,
                    )
                    actions.add(execution)
                    progress.finished(relocation, execution)
                    halted = true
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
            relocations.add(RelocationExecution(relocation, actions))
        }
        return ExecutionResult(relocations)
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
        is ReconciliationAction.Blocked -> throw IllegalStateException(action.reason)
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
                "expected absent or directory at " + action.path
                        + " but found " + state.name.lowercase(Locale.ROOT),
            )
        }
    }

    private fun migrateDirectoryForPublication(action: ReconciliationAction.MigrateDirectoryForPublication): String {
        requireState(action.path, PathState.DIRECTORY)
        requireState(action.target, PathState.ABSENT)
        val targetParent: Path = action.target.parent
            ?: throw IllegalStateException("target has no parent directory: " + action.target)
        val stagingRoot = action.stagingRoot ?: targetParent.resolve(".homelight-staging")
        if (fileStoreOfExistingAncestor(stagingRoot) != fileStoreOfExistingAncestor(targetParent)) {
            throw IllegalStateException("staging root is not on the target filesystem: $stagingRoot")
        }
        ensureRealDirectories(targetParent)
        ensureRealDirectories(stagingRoot)
        cleanStaleStaging(stagingRoot)
        probeAtomicMove(stagingRoot)

        val operation = Files.createDirectory(stagingRoot.resolve(OPERATION_PREFIX + UUID.randomUUID()))
        val marker = operation.resolve("target")
        val lockPath = operation.resolve("lock")
        Files.writeString(marker, MARKER_HEADER + action.target.toAbsolutePath().normalize() + "\n", StandardCharsets.UTF_8)
        var lockSupported = true
        try {
            FileChannel.open(lockPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                acquireLock(channel).use { lock ->
                    lockSupported = lock != null
                    val copy = operation.resolve("copy")
                    Files.createDirectory(copy)
                    Files.walkFileTree(action.path, CopyVisitor(action.path, copy))
                    verifyCopy(action.path, copy)
                    requireState(action.path, PathState.DIRECTORY)
                    requireState(action.target, PathState.ABSENT)
                    Files.move(copy, action.target, StandardCopyOption.ATOMIC_MOVE)
                }
            }
        } finally {
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
            ?: throw StateDriftException("expected symlink at " + action.path)
        if (actualTarget != action.expectedSourceTarget) {
            throw StateDriftException("expected symlink target " + action.expectedSourceTarget + " at " + action.path)
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
                "expected " + expected.name.lowercase(Locale.ROOT) + " at " + path
                        + " but found " + actual.name.lowercase(Locale.ROOT),
            )
        }
    }

    private class CopyVisitor(private val source: Path, private val destination: Path) : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
            Files.createDirectories(copiedPath(source, destination, directory))
            return FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
            Files.copy(file, copiedPath(source, destination, file), LinkOption.NOFOLLOW_LINKS)
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

private const val OPERATION_PREFIX = "operation-"
private const val MARKER_HEADER = "homelight-staging-v1\n"

private fun ensureRealDirectories(path: Path) {
    val absolute = path.toAbsolutePath().normalize()
    var current = absolute.root
    for (name in absolute) {
        current = current.resolve(name)
        if (Files.notExists(current, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(current)
        } else if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
            throw StateDriftException("expected real directory at $current")
        }
    }
}

private fun acquireLock(channel: FileChannel): FileLock? {
    try {
        return channel.lock()
    } catch (exception: UnsupportedOperationException) {
        return null
    }
}

private fun probeAtomicMove(stagingRoot: Path) {
    val probe = Files.createTempDirectory(stagingRoot, "atomic-probe-")
    val published = probe.resolveSibling(probe.fileName.toString() + ".published")
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
    Files.list(stagingRoot).use { entries ->
        for (entry in entries.toList()) {
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
            if (target == null || !hasOnlyOperationEntries(entry) || containsSymlink(entry)) {
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
                            deleteTree(entry)
                        }
                    }
                }
            } catch (ignored: UnsupportedOperationException) {
                return
            }
        }
    }
}

private fun isOwnedOperation(entry: Path): Boolean {
    val name = entry.fileName.toString()
    if (!name.startsWith(OPERATION_PREFIX) || !Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(entry)
    ) {
        return false
    }
    try {
        UUID.fromString(name.substring(OPERATION_PREFIX.length))
        return true
    } catch (exception: IllegalArgumentException) {
        return false
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
    try {
        return Path.of(value).toAbsolutePath().normalize()
    } catch (exception: InvalidPathException) {
        return null
    }
}

private fun containsSymlink(root: Path): Boolean {
    var symlink = false
    Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
            if (Files.isSymbolicLink(file)) {
                symlink = true
                return FileVisitResult.TERMINATE
            }
            return FileVisitResult.CONTINUE
        }
    })
    return symlink
}

private fun hasOnlyOperationEntries(operation: Path): Boolean =
    Files.list(operation).use { entries ->
        entries.allMatch { entry ->
            when (entry.fileName.toString()) {
                "target", "lock" -> Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
                "copy" -> Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)
                else -> false
            }
        }
    }

private fun sameFileStore(left: Path, right: Path): Boolean {
    try {
        return Files.getFileStore(left) == Files.getFileStore(right)
    } catch (exception: IOException) {
        return false
    }
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

private fun tryAcquireLock(channel: FileChannel): FileLock? {
    try {
        return channel.tryLock()
    } catch (exception: OverlappingFileLockException) {
        return null
    }
}

private fun prepareLink(path: Path, target: Path): Path {
    val temporary = Files.createTempFile(path.parent, ".homelight-", ".link")
    Files.delete(temporary)
    Files.createSymbolicLink(temporary, target)
    return temporary
}

private fun deleteTree(root: Path) {
    Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
            Files.delete(file)
            return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(directory: Path, exception: IOException?): FileVisitResult {
            if (exception != null) {
                throw exception
            }
            Files.delete(directory)
            return FileVisitResult.CONTINUE
        }
    })
}

private fun verifyCopy(source: Path, copy: Path) {
    Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
            val copiedDirectory = copiedPath(source, copy, directory)
            if (!Files.isDirectory(copiedDirectory, LinkOption.NOFOLLOW_LINKS)) {
                throw IOException("copied directory is missing: $copiedDirectory")
            }
            return FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
            val copiedFile = copiedPath(source, copy, file)
            if (Files.isSymbolicLink(file)) {
                if (!Files.isSymbolicLink(copiedFile)
                    || Files.readSymbolicLink(file) != Files.readSymbolicLink(copiedFile)
                ) {
                    throw IOException("copied symlink differs: $copiedFile")
                }
            } else if (!Files.isRegularFile(copiedFile, LinkOption.NOFOLLOW_LINKS)
                || Files.size(file) != Files.size(copiedFile)
            ) {
                throw IOException("copied file differs: $copiedFile")
            }
            return FileVisitResult.CONTINUE
        }
    })
    Files.walkFileTree(copy, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
            verifySourceEntry(source, copy, directory)
            return FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
            verifySourceEntry(source, copy, file)
            return FileVisitResult.CONTINUE
        }
    })
}

private fun verifySourceEntry(source: Path, copy: Path, copiedEntry: Path) {
    val sourceEntry = copiedPath(copy, source, copiedEntry)
    if (Files.notExists(sourceEntry, LinkOption.NOFOLLOW_LINKS)) {
        throw IOException("copied directory has an unexpected entry: $copiedEntry")
    }
}

private fun copiedPath(source: Path, copy: Path, entry: Path): Path = copy.resolve(source.relativize(entry))
