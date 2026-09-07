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

        assertEquals(PathState.ABSENT, inspector.inspect(root.resolve("absent"), expected));

        var directory = root.resolve("directory");
        Files.createDirectory(directory);
        assertEquals(PathState.DIRECTORY, inspector.inspect(directory, expected));

        var file = root.resolve("file");
        Files.createFile(file);
        assertEquals(PathState.FILE, inspector.inspect(file, expected));

        var correct = root.resolve("correct");
        Files.createSymbolicLink(correct, expected);
        assertEquals(PathState.CORRECT_SYMLINK, inspector.inspect(correct, expected));

        var wrong = root.resolve("wrong");
        var other = Files.createDirectory(root.resolve("other"));
        Files.createSymbolicLink(wrong, other);
        assertEquals(PathState.WRONG_SYMLINK, inspector.inspect(wrong, expected));

        var broken = root.resolve("broken");
        Files.createSymbolicLink(broken, root.resolve("missing"));
        assertEquals(PathState.BROKEN_SYMLINK, inspector.inspect(broken, expected));
    }
}
