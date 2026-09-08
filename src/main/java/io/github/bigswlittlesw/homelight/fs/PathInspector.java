package io.github.bigswlittlesw.homelight.fs;

import io.github.bigswlittlesw.homelight.domain.RelocationSourceState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/// Inspects configured paths while treating symlinks as filesystem objects.
public final class PathInspector {
    public PathObservation inspect(Path path) {
        if (Files.isSymbolicLink(path)) {
            var resolvedTarget = resolveLinkTarget(path);
            return new PathObservation(FilesystemKind.SYMLINK, java.util.Optional.of(resolvedTarget),
                    Files.exists(resolvedTarget));
        }
        return inspectNonLink(path);
    }

    public RelocationSourceState inspectRelocationSource(Path path, Path expectedTarget) {
        return inspect(path).sourceStateForTarget(expectedTarget);
    }

    private Path resolveLinkTarget(Path path) {
        var linkTarget = readLink(path);
        var absolutePath = path.toAbsolutePath();
        return absolutePath.getParent().resolve(linkTarget).normalize();
    }

    private PathObservation inspectNonLink(Path path) {
        try {
            var attributes = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (attributes.isDirectory()) {
                return new PathObservation(FilesystemKind.DIRECTORY, java.util.Optional.empty(), false);
            }
            if (attributes.isRegularFile()) {
                return new PathObservation(FilesystemKind.FILE, java.util.Optional.empty(), false);
            }
            return new PathObservation(FilesystemKind.OTHER, java.util.Optional.empty(), false);
        } catch (IOException exception) {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                return new PathObservation(FilesystemKind.ABSENT, java.util.Optional.empty(), false);
            }
            return new PathObservation(FilesystemKind.OTHER, java.util.Optional.empty(), false);
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
