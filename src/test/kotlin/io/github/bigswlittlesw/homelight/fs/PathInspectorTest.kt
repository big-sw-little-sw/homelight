package io.github.bigswlittlesw.homelight.fs

import io.github.bigswlittlesw.homelight.domain.RelocationSourceState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files

class PathInspectorTest {
    @Test
    fun reportsFilesystemStatesWithoutFollowingLinks() {
        val root = Files.createTempDirectory("homelight")
        val expected = root.resolve("local")
        Files.createDirectories(expected)
        val inspector = PathInspector()

        assertEquals(PathState.ABSENT, inspector.inspect(root.resolve("absent")).state)

        val directory = root.resolve("directory")
        Files.createDirectory(directory)
        val directoryObservation = inspector.inspect(directory)
        assertEquals(PathState.DIRECTORY, directoryObservation.state)
        assertEquals(true, directoryObservation.emptyDirectory)
        Files.createFile(directory.resolve("entry"))
        assertEquals(false, inspector.inspect(directory).emptyDirectory)

        val file = root.resolve("file")
        Files.createFile(file)
        assertEquals(PathState.FILE, inspector.inspect(file).state)

        val correct = root.resolve("correct")
        Files.createSymbolicLink(correct, expected)
        assertEquals(RelocationSourceState.CORRECT_SYMLINK, inspector.inspectRelocationSource(correct, expected))

        val wrong = root.resolve("wrong")
        val other = Files.createDirectory(root.resolve("other"))
        Files.createSymbolicLink(wrong, other)
        assertEquals(RelocationSourceState.WRONG_SYMLINK, inspector.inspectRelocationSource(wrong, expected))

        val broken = root.resolve("broken")
        Files.createSymbolicLink(broken, root.resolve("missing"))
        assertEquals(RelocationSourceState.BROKEN_SYMLINK, inspector.inspectRelocationSource(broken, expected))
    }

    @Test
    fun treatsAnInaccessibleSymlinkDestinationAsInaccessible() {
        val root = Files.createTempDirectory("homelight")
        val expected = root.resolve("local")
        val observation = PathObservation(PathState.SYMLINK, java.util.Optional.of(expected),
                SymlinkTargetAvailability.INACCESSIBLE)

        assertEquals(RelocationSourceState.INACCESSIBLE, observation.sourceStateForTarget(expected))
    }
}
