package io.github.bigswlittlesw.homelight.fs;

import io.github.bigswlittlesw.homelight.domain.RelocationSourceState;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/// Inspects configured paths while treating symlinks as filesystem objects.
public final class PathInspector {
    public PathObservation inspect(Path path) {
        try {
            if (Files.isSymbolicLink(path)) {
                var resolvedTarget = resolveLinkTarget(path);
                return new PathObservation(PathState.SYMLINK, java.util.Optional.of(resolvedTarget),
                        targetAvailability(resolvedTarget));
            }
            return inspectNonLink(path);
        } catch (PathInspectionException exception) {
            return new PathObservation(PathState.INACCESSIBLE, java.util.Optional.empty(), false);
        }
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
                return new PathObservation(PathState.DIRECTORY, java.util.Optional.empty(),
                        SymlinkTargetAvailability.NOT_A_SYMLINK, isEmptyDirectory(path));
            }
            if (attributes.isRegularFile()) {
                return new PathObservation(PathState.FILE, java.util.Optional.empty(), false);
            }
            return new PathObservation(PathState.OTHER, java.util.Optional.empty(), false);
        } catch (NoSuchFileException exception) {
            return new PathObservation(PathState.ABSENT, java.util.Optional.empty(), false);
        } catch (IOException exception) {
            return new PathObservation(PathState.INACCESSIBLE, java.util.Optional.empty(), false);
        }
    }

    private static boolean isEmptyDirectory(Path path) throws IOException {
        try (var entries = Files.list(path)) {
            return entries.findAny().isEmpty();
        }
    }

    private static SymlinkTargetAvailability targetAvailability(Path path) {
        try {
            Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class);
            return SymlinkTargetAvailability.EXISTS;
        } catch (NoSuchFileException exception) {
            return SymlinkTargetAvailability.ABSENT;
        } catch (IOException exception) {
            return SymlinkTargetAvailability.INACCESSIBLE;
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
