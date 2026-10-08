package io.github.bigswlittlesw.lighten.reconcile

import io.github.bigswlittlesw.lighten.config.realSpelling
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.listDirectoryEntries

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
internal class StagingOperation private constructor(
    private val key: String, private val copy: Path, private val channel: FileChannel,
    private val stagingStep: (ReconciliationExecutor.Step, Path) -> Unit,
) : AutoCloseable {
    fun stage(source: Path) {
        Files.walkFileTree(source, copyVisitor(source, copy))
        verifyCopy(source, copy)
        stagingStep(ReconciliationExecutor.Step.COPIED, copy)
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
            stagingStep(ReconciliationExecutor.Step.PUBLISHED, copy)
            if (!writable) {
                Files.setPosixFilePermissions(target, permissions)
            }
        } catch (exception: IOException) {
            throw PartlyPublishedException(
                target, "published $target but could not restore its permissions: ${exception.message}", exception,
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
            stagingRoot: Path, target: Path, stagingStep: (ReconciliationExecutor.Step, Path) -> Unit,
        ): StagingOperation {
            val key = sha256Hex(realSpelling(target).toString())
            if (!stagingKeys.add(key)) {
                throw EnvironmentException(ActionFailure.Busy(target, here = true), "this Lighten is already publishing $target")
            }
            try {
                val copy = stagingRoot.resolve("operation-$key")
                val channel = FileChannel.open(copy.resolveSibling("operation-$key.lock"), LOCK_OPTIONS, OWNER_ONLY_FILE)
                try {
                    if (channel.tryLock() == null) {
                        throw EnvironmentException(ActionFailure.Busy(target, here = false), "another Lighten is publishing $target")
                    }
                    stagingStep(ReconciliationExecutor.Step.LOCKED, copy)
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

internal fun sha256Hex(text: String): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))

private val LOCK_OPTIONS = setOf<OpenOption>(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
private val OWNER_ONLY_FILE =
    PosixFilePermissions.asFileAttribute(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))

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
