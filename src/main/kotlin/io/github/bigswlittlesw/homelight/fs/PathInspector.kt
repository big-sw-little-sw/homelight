package io.github.bigswlittlesw.homelight.fs

import io.github.bigswlittlesw.homelight.domain.RelocationSourceState
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.Optional

/** Inspects configured paths while treating symlinks as filesystem objects. */
class PathInspector {
    fun inspect(path: Path): PathObservation {
        try {
            if (Files.isSymbolicLink(path)) {
                val resolvedTarget = resolveLinkTarget(path)
                return PathObservation(PathState.SYMLINK, Optional.of(resolvedTarget), targetAvailability(resolvedTarget))
            }
            return inspectNonLink(path)
        } catch (exception: PathInspectionException) {
            return PathObservation(PathState.INACCESSIBLE, Optional.empty(), false)
        }
    }

    fun inspectRelocationSource(path: Path, expectedTarget: Path): RelocationSourceState =
        inspect(path).sourceStateForTarget(expectedTarget)

    private fun resolveLinkTarget(path: Path): Path {
        val linkTarget = readLink(path)
        val absolutePath = path.toAbsolutePath()
        return absolutePath.parent.resolve(linkTarget).normalize()
    }

    private fun inspectNonLink(path: Path): PathObservation {
        try {
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (attributes.isDirectory) {
                return PathObservation(
                    PathState.DIRECTORY, Optional.empty(),
                    SymlinkTargetAvailability.NOT_A_SYMLINK, isEmptyDirectory(path),
                )
            }
            if (attributes.isRegularFile) {
                return PathObservation(PathState.FILE, Optional.empty(), false)
            }
            return PathObservation(PathState.OTHER, Optional.empty(), false)
        } catch (exception: NoSuchFileException) {
            return PathObservation(PathState.ABSENT, Optional.empty(), false)
        } catch (exception: IOException) {
            return PathObservation(PathState.INACCESSIBLE, Optional.empty(), false)
        }
    }

    class PathInspectionException(message: String, cause: Throwable) : RuntimeException(message, cause)

    private companion object {
        fun isEmptyDirectory(path: Path): Boolean =
            Files.list(path).use { entries -> entries.findAny().isEmpty }

        fun targetAvailability(path: Path): SymlinkTargetAvailability {
            try {
                Files.readAttributes(path, BasicFileAttributes::class.java)
                return SymlinkTargetAvailability.EXISTS
            } catch (exception: NoSuchFileException) {
                return SymlinkTargetAvailability.ABSENT
            } catch (exception: IOException) {
                return SymlinkTargetAvailability.INACCESSIBLE
            }
        }

        fun readLink(path: Path): Path {
            try {
                return Files.readSymbolicLink(path)
            } catch (exception: IOException) {
                throw PathInspectionException("Unable to inspect symbolic link $path", exception)
            }
        }
    }
}
