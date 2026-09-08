package io.github.bigswlittlesw.homelight.fs;

import io.github.bigswlittlesw.homelight.domain.RelocationSourceState;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PathInspectorTest {
    @Test
    void reportsFilesystemStatesWithoutFollowingLinks() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var expected = root.resolve("local");
        Files.createDirectories(expected);
        var inspector = new PathInspector();

        assertEquals(PathState.ABSENT, inspector.inspect(root.resolve("absent")).state());

        var directory = root.resolve("directory");
        Files.createDirectory(directory);
        var directoryObservation = inspector.inspect(directory);
        assertEquals(PathState.DIRECTORY, directoryObservation.state());
        assertEquals(true, directoryObservation.emptyDirectory());
        Files.createFile(directory.resolve("entry"));
        assertEquals(false, inspector.inspect(directory).emptyDirectory());

        var file = root.resolve("file");
        Files.createFile(file);
        assertEquals(PathState.FILE, inspector.inspect(file).state());

        var correct = root.resolve("correct");
        Files.createSymbolicLink(correct, expected);
        assertEquals(RelocationSourceState.CORRECT_SYMLINK, inspector.inspectRelocationSource(correct, expected));

        var wrong = root.resolve("wrong");
        var other = Files.createDirectory(root.resolve("other"));
        Files.createSymbolicLink(wrong, other);
        assertEquals(RelocationSourceState.WRONG_SYMLINK, inspector.inspectRelocationSource(wrong, expected));

        var broken = root.resolve("broken");
        Files.createSymbolicLink(broken, root.resolve("missing"));
        assertEquals(RelocationSourceState.BROKEN_SYMLINK, inspector.inspectRelocationSource(broken, expected));
    }

    @Test
    void treatsAnInaccessibleSymlinkDestinationAsInaccessible() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var expected = root.resolve("local");
        var observation = new PathObservation(PathState.SYMLINK, java.util.Optional.of(expected),
                SymlinkTargetAvailability.INACCESSIBLE);

        assertEquals(RelocationSourceState.INACCESSIBLE, observation.sourceStateForTarget(expected));
    }
}
