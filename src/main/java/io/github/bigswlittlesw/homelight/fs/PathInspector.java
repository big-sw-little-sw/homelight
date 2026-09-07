package io.github.bigswlittlesw.homelight.fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/// Inspects configured paths while treating symlinks as filesystem objects.
public final class PathInspector {
    public PathState inspect(Path path, Path expectedTarget) {
        if (Files.isSymbolicLink(path)) {
            var linkTarget = readLink(path);
            var absolutePath = path.toAbsolutePath();
            var resolvedTarget = absolutePath.getParent().resolve(linkTarget).normalize();
            if (!Files.exists(resolvedTarget)) {
                return PathState.BROKEN_SYMLINK;
            }
            return resolvedTarget.equals(expectedTarget.toAbsolutePath().normalize())
                    ? PathState.CORRECT_SYMLINK
                    : PathState.WRONG_SYMLINK;
        }
        try {
            var attributes = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (attributes.isDirectory()) {
                return PathState.DIRECTORY;
            }
            if (attributes.isRegularFile()) {
                return PathState.FILE;
            }
            return PathState.OTHER;
        } catch (IOException exception) {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                return PathState.ABSENT;
            }
            return PathState.OTHER;
        }
    }

    private static Path readLink(Path path) {
        try {
            return Files.readSymbolicLink(path);
        } catch (IOException exception) {
            throw new PathInspectionException("Unable to inspect symbolic link " + path, exception);
        }
    }

    public static final class PathInspectionException extends RuntimeException {
        public PathInspectionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
