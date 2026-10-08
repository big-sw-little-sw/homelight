package io.github.bigswlittlesw.lighten.reconcile

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class ActionFailureTest {
    private val path = Path.of("/scratch/a")
    private val other = Path.of("/data/b")

    @Test
    fun recognizesIoFailuresByTheirType() {
        for ((exception, expected) in listOf(
            AccessDeniedException("$path") to ActionFailure.AccessDenied(path),
            NoSuchFileException("$path") to ActionFailure.Gone(path),
            FileAlreadyExistsException("$path") to ActionFailure.AlreadyExists(path),
            AtomicMoveNotSupportedException("$path", "$other", "Invalid cross-device link") to
                ActionFailure.DifferentFilesystems(path, other),
            FileSystemException("$path", null, "No space left on device") to
                ActionFailure.Io(path, "No space left on device"),
            FileSystemException(null, null, "Read-only file system") to ActionFailure.Io(null, "Read-only file system"),
            InterruptedIOException("Interrupted during visual-test delay") to
                ActionFailure.Io(null, "Interrupted during visual-test delay"),
            PartlyPublishedException(path, "published $path but could not restore its permissions", IOException()) to
                ActionFailure.PermissionsNotRestored(path),
        )) {
            assertEquals(expected, ioFailure(exception), exception.toString())
        }
    }

    @Test
    fun aCopyThatDiffersFromItsSourceNamesTheSourceEntry(@TempDir root: Path) {
        val source = Files.createDirectories(root.resolve("source/sub"))
        Files.writeString(source.resolve("file"), "source")
        val copy = Files.createDirectories(root.resolve("copy/sub"))
        Files.writeString(copy.resolve("file"), "changed!")
        Files.setPosixFilePermissions(copy, Files.getPosixFilePermissions(source))
        Files.setPosixFilePermissions(copy.parent, Files.getPosixFilePermissions(source.parent))

        val differs = assertThrows<EnvironmentException> { verifyCopy(source.parent, copy.parent) }
        assertEquals(ActionFailure.CopyChanged(source.resolve("file")), differs.failure)
        assertEquals("copied file differs: ${copy.resolve("file")}", differs.message)

        Files.writeString(copy.resolve("file"), "source")
        Files.writeString(copy.resolve("extra"), "")
        val extra = assertThrows<EnvironmentException> { verifyCopy(source.parent, copy.parent) }
        assertEquals(ActionFailure.CopyChanged(source.resolve("extra")), extra.failure)

        Files.delete(copy.resolve("extra"))
        Files.setPosixFilePermissions(copy, PosixFilePermissions.fromString("rwx------"))
        Files.setPosixFilePermissions(source, PosixFilePermissions.fromString("rwxr-xr-x"))
        val permissions = assertThrows<EnvironmentException> { verifyCopy(source.parent, copy.parent) }
        assertEquals(ActionFailure.PermissionsNotKept(source), permissions.failure)
    }

    /** A denied delete stays recognizable after [deleteTree] rejoins the entry's full path. */
    @Test
    fun aDeniedDeleteNamesTheEntryAndStaysADeniedAccess(@TempDir root: Path) {
        assumeFalse(System.getProperty("user.name") == "root", "root may delete in a read-only directory")
        val locked = Files.createDirectories(root.resolve("tree/locked"))
        val entry = Files.writeString(locked.resolve("entry"), "keep")
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-x------"))
        try {
            val failure = assertThrows<IOException> { deleteTree(root.resolve("tree")) }
            assertEquals(ActionFailure.AccessDenied(entry), ioFailure(failure))
            assertEquals(entry.toString(), failure.message)
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"))
        }
    }
}
