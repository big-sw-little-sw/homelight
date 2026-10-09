package io.github.bigswlittlesw.lighten.reconcile

import java.io.IOException
import java.nio.file.AccessDeniedException
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
 * generic one. The first failure is rethrown instead, so the reported failure names the entry.
 */
internal fun deleteTree(root: Path) {
    try {
        @OptIn(ExperimentalPathApi::class)
        root.deleteRecursively()
    } catch (failure: FileSystemException) {
        val first = failure.suppressed.firstOrNull() ?: throw failure
        throw entryFailure(first).apply { initCause(failure) }
    }
}

/**
 * With `SecureDirectoryStream`, an entry's exception carries only its name, so `deleteRecursively`
 * wraps it in one that holds the full path. Rejoin that path with the cause's reason, keeping a denied
 * access recognizable as one, to give the failure the entry's own exception would have had.
 */
private fun entryFailure(failure: Throwable): IOException {
    val cause = failure.cause
    return when {
        failure !is FileSystemException || cause !is FileSystemException -> IOException(failure.message)
        cause is AccessDeniedException -> AccessDeniedException(failure.file, cause.otherFile, cause.reason)
        else -> FileSystemException(failure.file, cause.otherFile, cause.reason)
    }
}

/**
 * Copies a tree, giving each copied directory the nine permission bits of its source.
 *
 * Directories start owner-only, so the copy is never more open to group or others than the
 * source, and stay owner-writable until their entries are copied. Each gets its final mode
 * after its contents, which lets a read-only source directory (`0500`) still receive children.
 * `destination` must not exist yet.
 *
 * Sockets are skipped: a socket can't be copied, and programs recreate theirs. A named pipe or device file stops the
 * copy. Planning does not look for them: that would walk every source tree on every check (decision 2026-10-08).
 */
internal fun copyVisitor(source: Path, destination: Path): FileVisitor<Path> = fileVisitor {
    onPreVisitDirectory { directory, _ ->
        Files.createDirectory(copiedPath(source, destination, directory), OWNER_ONLY_DIRECTORY)
        FileVisitResult.CONTINUE
    }
    onVisitFile { file, attributes ->
        when (val kind = if (attributes.isOther) specialFileKind(file) else null) {
            null -> Files.copy(file, copiedPath(source, destination, file), LinkOption.NOFOLLOW_LINKS)
            SpecialFileKind.SOCKET -> {}
            // Opening a named pipe waits for a writer and a device can be endless, so neither is ever opened.
            SpecialFileKind.NAMED_PIPE, SpecialFileKind.DEVICE -> throw unmovable(file, kind)
        }
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

/**
 * Checks [copy] against [source] by [copyVisitor]'s rules and returns the source's sockets, which the copy skipped.
 * Walks depth-first, directories before their entries, never following links.
 */
internal fun verifyCopy(source: Path, copy: Path): List<Path> = buildList {
    for (entry in source.walk(PathWalkOption.INCLUDE_DIRECTORIES)) {
        val copied = copiedPath(source, copy, entry)
        if (entry.isDirectory(LinkOption.NOFOLLOW_LINKS)) {
            if (!copied.isDirectory(LinkOption.NOFOLLOW_LINKS)) {
                throw copyChanged(entry, CopyDifference.MISSING_FOLDER, "copied directory is missing: $copied")
            }
            if (directoryPermissions(entry) != directoryPermissions(copied)) {
                throw EnvironmentException(ActionFailure.PermissionsNotKept(entry), "copied directory permissions differ: $copied")
            }
        } else if (entry.isSymbolicLink()) {
            if (!copied.isSymbolicLink() || entry.readSymbolicLink() != copied.readSymbolicLink()) {
                throw copyChanged(entry, CopyDifference.LINK_DIFFERS, "copied symlink differs: $copied")
            }
        } else if (entry.isRegularFile(LinkOption.NOFOLLOW_LINKS)) {
            if (!copied.isRegularFile(LinkOption.NOFOLLOW_LINKS) || entry.fileSize() != copied.fileSize()) {
                throw copyChanged(entry, CopyDifference.FILE_DIFFERS, "copied file differs: $copied")
            }
        } else when (val kind = specialFileKind(entry)) {
            // Something copied at a socket's name was another kind of file when the copy ran.
            SpecialFileKind.SOCKET ->
                if (copied.notExists(LinkOption.NOFOLLOW_LINKS)) add(entry)
                else throw copyChanged(entry, CopyDifference.FILE_DIFFERS, "copied file differs: $copied")
            SpecialFileKind.NAMED_PIPE, SpecialFileKind.DEVICE -> throw unmovable(entry, kind)
            null -> throw copyChanged(entry, CopyDifference.FILE_DIFFERS, "copied file differs: $copied")
        }
    }
    for (copied in copy.walk(PathWalkOption.INCLUDE_DIRECTORIES)) {
        val entry = copiedPath(copy, source, copied)
        if (entry.notExists(LinkOption.NOFOLLOW_LINKS)) {
            throw copyChanged(entry, CopyDifference.EXTRA_ENTRY, "copied directory has an unexpected entry: $copied")
        }
    }
}

/** The copy of [entry], a path under the source, does not match it: the source most likely changed during the copy. */
private fun copyChanged(entry: Path, difference: CopyDifference, message: String) =
    EnvironmentException(ActionFailure.CopyChanged(entry, difference), message)

private fun unmovable(entry: Path, kind: SpecialFileKind) =
    EnvironmentException(
        ActionFailure.Unmovable(entry, kind), "cannot copy ${kind.name.lowercase().replace('_', ' ')}: $entry",
    )

private fun copiedPath(source: Path, copy: Path, entry: Path): Path = copy.resolve(source.relativize(entry))

/** The kinds of file the copy can't make as it makes files, links and folders. */
enum class SpecialFileKind { SOCKET, NAMED_PIPE, DEVICE }

/**
 * The [SpecialFileKind] of [path], without following a link, or null for any other kind of file.
 *
 * Java's basic attributes call all of these "other", so the kind comes from the Unix mode, which the `unix` view reads
 * on Linux and macOS.
 */
internal fun specialFileKind(path: Path): SpecialFileKind? =
    when ((Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS) as Int) and S_IFMT) {
        S_IFSOCK -> SpecialFileKind.SOCKET
        S_IFIFO -> SpecialFileKind.NAMED_PIPE
        S_IFCHR, S_IFBLK -> SpecialFileKind.DEVICE
        else -> null
    }

// The file type bits of a Unix mode (POSIX <sys/stat.h>), the same on Linux and macOS.
private const val S_IFMT = 0xF000
private const val S_IFSOCK = 0xC000
private const val S_IFIFO = 0x1000
private const val S_IFCHR = 0x2000
private const val S_IFBLK = 0x6000

