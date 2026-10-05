package io.github.bigswlittlesw.homelight.fs

import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** Inspects configured paths while treating symlinks as filesystem objects. */
class PathInspector {
    fun inspect(path: Path): PathObservation {
        if (!Files.isSymbolicLink(path)) return inspectNonLink(path)
        val target = try {
            path.toAbsolutePath().parent.resolve(Files.readSymbolicLink(path)).normalize()
        } catch (exception: IOException) {
            return PathObservation(PathState.INACCESSIBLE)
        }
        return PathObservation(PathState.SYMLINK, target, targetAvailability(target))
    }

    private fun inspectNonLink(path: Path): PathObservation = try {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        when {
            attributes.isDirectory -> PathObservation(PathState.DIRECTORY, emptyDirectory = isEmptyDirectory(path))
            attributes.isRegularFile -> PathObservation(PathState.FILE)
            else -> PathObservation(PathState.OTHER)
        }
    } catch (exception: NoSuchFileException) {
        PathObservation(PathState.ABSENT)
    } catch (exception: IOException) {
        PathObservation(PathState.INACCESSIBLE)
    } catch (exception: UncheckedIOException) {
        // Reading a directory's entries reports an I/O failure this way; it is not a bug.
        PathObservation(PathState.INACCESSIBLE)
    }

    private fun isEmptyDirectory(path: Path): Boolean =
        Files.list(path).use { entries -> entries.findAny().isEmpty }

    private fun targetAvailability(path: Path): SymlinkTargetAvailability = try {
        Files.readAttributes(path, BasicFileAttributes::class.java)
        SymlinkTargetAvailability.EXISTS
    } catch (exception: NoSuchFileException) {
        SymlinkTargetAvailability.ABSENT
    } catch (exception: IOException) {
        SymlinkTargetAvailability.INACCESSIBLE
    }
}
