package io.github.bigswlittlesw.homelight.reconcile

import java.io.IOException
import java.nio.file.FileSystemException
import java.nio.file.FileVisitResult
import java.nio.file.FileVisitor
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.PathWalkOption
import kotlin.io.path.deleteRecursively
import kotlin.io.path.fileVisitor
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.notExists
import kotlin.io.path.readSymbolicLink
import kotlin.io.path.walk

internal val OWNER_ACCESS = setOf(
    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
)
private val OWNER_ONLY_DIRECTORY = PosixFilePermissions.asFileAttribute(OWNER_ACCESS)

internal fun directoryPermissions(directory: Path): Set<PosixFilePermission> =
    Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS)

/**
 * Deletes [root] and everything under it without following links; a missing root is a no-op.
 *
 * `deleteRecursively` continues past failures and reports them only as suppressed exceptions of a
 * generic one. The first failure's message is rethrown, so the reported message names the entry.
 */
internal fun deleteTree(root: Path) {
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
internal fun verifyCopy(source: Path, copy: Path) {
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
