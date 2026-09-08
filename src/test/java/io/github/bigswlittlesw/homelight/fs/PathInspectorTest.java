package io.github.bigswlittlesw.homelight.fs;

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

        assertEquals(FilesystemKind.ABSENT, inspector.inspect(root.resolve("absent")).kind());

        var directory = root.resolve("directory");
        Files.createDirectory(directory);
        assertEquals(FilesystemKind.DIRECTORY, inspector.inspect(directory).kind());

        var file = root.resolve("file");
        Files.createFile(file);
        assertEquals(FilesystemKind.FILE, inspector.inspect(file).kind());

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
}
