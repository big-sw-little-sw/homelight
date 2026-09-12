package io.github.bigswlittlesw.homelight.reconcile;

import io.github.bigswlittlesw.homelight.fs.PathInspector;
import io.github.bigswlittlesw.homelight.fs.PathState;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

/// Applies a fully resolved plan, stopping when the filesystem no longer matches its guards.
public final class ReconciliationExecutor {
    private static final String OPERATION_PREFIX = "operation-";
    private static final String MARKER_HEADER = "homelight-staging-v1\n";
    private final PathInspector inspector = new PathInspector();

    public ExecutionResult execute(ReconciliationPlan plan) {
        return execute(plan, ProgressListener.NONE);
    }

    public ExecutionResult execute(ReconciliationPlan plan, ProgressListener progress) {
        if (plan.hasBlockedActions() || plan.hasConflicts()) {
            throw new IllegalArgumentException("Only fully resolved plans can be executed");
        }
        var relocations = new ArrayList<RelocationExecution>();
        boolean halted = false;
        for (var relocation : plan.relocations()) {
            var actions = new ArrayList<ActionExecution>();
            for (var action : relocation.actions()) {
                if (halted) {
                    actions.add(new ActionExecution(action, ActionStatus.PENDING, "not run after a previous failure"));
                    continue;
                }
                try {
                    progress.started(relocation, action);
                    var message = apply(action);
                    var execution = new ActionExecution(action, ActionStatus.COMPLETED, message);
                    actions.add(execution);
                    progress.finished(relocation, execution);
                } catch (IOException | IllegalStateException exception) {
                    var execution = new ActionExecution(action, ActionStatus.FAILED, exception.getMessage());
                    actions.add(execution);
                    progress.finished(relocation, execution);
                    halted = true;
                }
            }
            relocations.add(new RelocationExecution(relocation, actions));
        }
        return new ExecutionResult(relocations);
    }

    private String apply(ReconciliationAction action) throws IOException {
        return switch (action) {
            case ReconciliationAction.CreateDirectory directory -> { createDirectory(directory); yield "completed"; }
            case ReconciliationAction.EnsureDirectory directory -> { ensureDirectory(directory); yield "completed"; }
            case ReconciliationAction.CopyDirectory copy -> { copyDirectory(copy); yield "completed"; }
            case ReconciliationAction.StageDirectoryForPublication stage -> stageDirectoryForPublication(stage);
            case ReconciliationAction.ArchiveDirectory archive -> { archiveDirectory(archive); yield "completed"; }
            case ReconciliationAction.DeleteDirectory directory -> { deleteDirectory(directory); yield "completed"; }
            case ReconciliationAction.CreateSymlink link -> { createSymlink(link); yield "completed"; }
            case ReconciliationAction.ReplaceDirectoryWithSymlink link -> { replaceDirectoryWithSymlink(link); yield "completed"; }
            case ReconciliationAction.ReplaceSymlink link -> { replaceSymlink(link); yield "completed"; }
            case ReconciliationAction.NoOp _ -> "completed";
            case ReconciliationAction.LeaveUnchanged _ -> "completed";
            case ReconciliationAction.Blocked blocked -> throw new IllegalStateException(blocked.reason());
        };
    }

    private void createDirectory(ReconciliationAction.CreateDirectory action) throws IOException {
        requireState(action.path(), action.expectedPathState());
        Files.createDirectory(action.path());
    }

    private void ensureDirectory(ReconciliationAction.EnsureDirectory action) throws IOException {
        var state = inspector.inspect(action.path()).state();
        if (state == PathState.ABSENT) {
            Files.createDirectories(action.path());
        } else if (state != PathState.DIRECTORY) {
            throw new IllegalStateException("expected absent or directory at " + action.path()
                    + " but found " + state.name().toLowerCase());
        }
    }

    private void copyDirectory(ReconciliationAction.CopyDirectory action) throws IOException {
        requireState(action.path(), action.expectedSourceState());
        requireState(action.target(), action.expectedTargetState());
        Files.createDirectory(action.target());
        Files.walkFileTree(action.path(), new CopyVisitor(action.path(), action.target()));
        verifyCopy(action.path(), action.target());
    }

    private String stageDirectoryForPublication(ReconciliationAction.StageDirectoryForPublication action) throws IOException {
        requireState(action.path(), PathState.DIRECTORY);
        requireState(action.target(), PathState.ABSENT);
        var targetParent = action.target().getParent();
        if (targetParent == null) {
            throw new IllegalStateException("target has no parent directory: " + action.target());
        }
        var stagingRoot = action.stagingRoot().orElseGet(() -> targetParent.resolve(".homelight-staging"));
        if (!fileStoreOfExistingAncestor(stagingRoot).equals(fileStoreOfExistingAncestor(targetParent))) {
            throw new IllegalStateException("staging root is not on the target filesystem: " + stagingRoot);
        }
        ensureRealDirectories(targetParent);
        ensureRealDirectories(stagingRoot);
        cleanStaleStaging(stagingRoot);
        probeAtomicMove(stagingRoot);

        var operation = Files.createDirectory(stagingRoot.resolve(OPERATION_PREFIX + UUID.randomUUID()));
        var marker = operation.resolve("target");
        var lockPath = operation.resolve("lock");
        Files.writeString(marker, MARKER_HEADER + action.target().toAbsolutePath().normalize() + "\n", StandardCharsets.UTF_8);
        var lockSupported = true;
        try (var channel = FileChannel.open(lockPath, java.nio.file.StandardOpenOption.CREATE_NEW,
                java.nio.file.StandardOpenOption.WRITE); var ignored = acquireLock(channel)) {
            lockSupported = ignored != null;
            var copy = operation.resolve("copy");
            Files.createDirectory(copy);
            Files.walkFileTree(action.path(), new CopyVisitor(action.path(), copy));
            verifyCopy(action.path(), copy);
            requireState(action.path(), PathState.DIRECTORY);
            requireState(action.target(), PathState.ABSENT);
            Files.move(copy, action.target(), StandardCopyOption.ATOMIC_MOVE);
        } finally {
            deleteTree(operation);
        }
        return lockSupported ? "completed" : "completed; staging locks unsupported, stale cleanup skipped";
    }

    private static void ensureRealDirectories(Path path) throws IOException {
        var absolute = path.toAbsolutePath().normalize();
        var current = absolute.getRoot();
        for (var name : absolute) {
            current = current.resolve(name);
            if (Files.notExists(current, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(current);
            } else if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("expected real directory at " + current);
            }
        }
    }

    private static FileLock acquireLock(FileChannel channel) throws IOException {
        try {
            return channel.lock();
        } catch (UnsupportedOperationException exception) {
            return null;
        }
    }

    private static void probeAtomicMove(Path stagingRoot) throws IOException {
        var probe = Files.createTempDirectory(stagingRoot, "atomic-probe-");
        var published = probe.resolveSibling(probe.getFileName() + ".published");
        try {
            Files.move(probe, published, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            if (Files.exists(published, LinkOption.NOFOLLOW_LINKS)) {
                deleteTree(published);
            } else if (Files.exists(probe, LinkOption.NOFOLLOW_LINKS)) {
                deleteTree(probe);
            }
        }
    }

    private static void cleanStaleStaging(Path stagingRoot) throws IOException {
        try (var entries = Files.list(stagingRoot)) {
            for (var entry : entries.toList()) {
                if (!isOwnedOperation(entry)) {
                    continue;
                }
                var marker = entry.resolve("target");
                var lock = entry.resolve("lock");
                if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                        || !Files.isRegularFile(lock, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                var target = markedTarget(marker);
                if (target == null || !hasOnlyOperationEntries(entry) || containsSymlink(entry)) {
                    continue;
                }
                if (!sameFileStore(stagingRoot, target.getParent()) || Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                try (var channel = FileChannel.open(lock, java.nio.file.StandardOpenOption.WRITE)) {
                    var held = tryAcquireLock(channel);
                    if (held != null) {
                        try (held) {
                            deleteTree(entry);
                        }
                    }
                } catch (UnsupportedOperationException ignored) {
                    return;
                }
            }
        }
    }

    private static boolean isOwnedOperation(Path entry) throws IOException {
        var name = entry.getFileName().toString();
        if (!name.startsWith(OPERATION_PREFIX) || !Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(entry)) {
            return false;
        }
        try {
            UUID.fromString(name.substring(OPERATION_PREFIX.length()));
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static Path markedTarget(Path marker) throws IOException {
        var text = Files.readString(marker, StandardCharsets.UTF_8);
        if (!text.startsWith(MARKER_HEADER) || !text.endsWith("\n")) {
            return null;
        }
        var value = text.substring(MARKER_HEADER.length(), text.length() - 1);
        if (value.isBlank() || value.contains("\n")) {
            return null;
        }
        try {
            return Path.of(value).toAbsolutePath().normalize();
        } catch (java.nio.file.InvalidPathException exception) {
            return null;
        }
    }

    private static boolean containsSymlink(Path root) throws IOException {
        var symlink = new boolean[1];
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (Files.isSymbolicLink(file)) {
                    symlink[0] = true;
                    return FileVisitResult.TERMINATE;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return symlink[0];
    }

    private static boolean hasOnlyOperationEntries(Path operation) throws IOException {
        try (var entries = Files.list(operation)) {
            return entries.allMatch(entry -> switch (entry.getFileName().toString()) {
                case "target", "lock" -> Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS);
                case "copy" -> Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS);
                default -> false;
            });
        }
    }

    private static boolean sameFileStore(Path left, Path right) {
        try {
            return Files.getFileStore(left).equals(Files.getFileStore(right));
        } catch (IOException exception) {
            return false;
        }
    }

    private static java.nio.file.FileStore fileStoreOfExistingAncestor(Path path) throws IOException {
        for (var current = path.toAbsolutePath().normalize(); current != null; current = current.getParent()) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalStateException("expected real directory at " + current);
                }
                return Files.getFileStore(current);
            }
        }
        throw new IOException("no existing ancestor for " + path);
    }

    private static FileLock tryAcquireLock(FileChannel channel) throws IOException {
        try {
            return channel.tryLock();
        } catch (OverlappingFileLockException exception) {
            return null;
        }
    }

    private void deleteDirectory(ReconciliationAction.DeleteDirectory action) throws IOException {
        requireState(action.path(), action.expectedPathState());
        if (action.expectedEmpty() && !inspector.inspect(action.path()).emptyDirectory()) {
            throw new IllegalStateException("expected empty directory at " + action.path());
        }
        deleteTree(action.path());
    }

    private void archiveDirectory(ReconciliationAction.ArchiveDirectory action) throws IOException {
        requireState(action.path(), PathState.DIRECTORY);
        requireState(action.target(), PathState.ABSENT);
        Files.move(action.path(), action.target(), StandardCopyOption.ATOMIC_MOVE);
    }

    private void createSymlink(ReconciliationAction.CreateSymlink action) throws IOException {
        requireState(action.path(), action.expectedSourceState());
        requireState(action.target(), action.expectedTargetState());
        replaceWithLink(action.path(), action.target(), false);
    }

    private void replaceDirectoryWithSymlink(ReconciliationAction.ReplaceDirectoryWithSymlink action) throws IOException {
        requireState(action.path(), PathState.DIRECTORY);
        requireState(action.target(), action.expectedTargetState());
        var temporary = prepareLink(action.path(), action.target());
        try {
            requireState(action.path(), PathState.DIRECTORY);
            requireState(action.target(), action.expectedTargetState());
            deleteTree(action.path());
            Files.move(temporary, action.path(), StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void replaceSymlink(ReconciliationAction.ReplaceSymlink action) throws IOException {
        requireState(action.path(), PathState.SYMLINK);
        requireState(action.target(), action.expectedTargetState());
        var actualTarget = inspector.inspect(action.path()).symlinkTarget().orElseThrow();
        if (action.expectedSourceTarget() != null && !actualTarget.equals(action.expectedSourceTarget())) {
            throw new IllegalStateException("expected symlink target " + action.expectedSourceTarget() + " at " + action.path());
        }
        replaceWithLink(action.path(), action.target(), true);
    }

    private void replaceWithLink(Path path, Path target, boolean replaceExisting) throws IOException {
        var temporary = prepareLink(path, target);
        try {
            if (replaceExisting) {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path prepareLink(Path path, Path target) throws IOException {
        var temporary = Files.createTempFile(path.getParent(), ".homelight-", ".link");
        Files.delete(temporary);
        Files.createSymbolicLink(temporary, target);
        return temporary;
    }

    private static void deleteTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void verifyCopy(Path source, Path copy) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                var copiedDirectory = copiedPath(source, copy, directory);
                if (!Files.isDirectory(copiedDirectory, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("copied directory is missing: " + copiedDirectory);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                var copiedFile = copiedPath(source, copy, file);
                if (Files.isSymbolicLink(file)) {
                    if (!Files.isSymbolicLink(copiedFile)
                            || !Files.readSymbolicLink(file).equals(Files.readSymbolicLink(copiedFile))) {
                        throw new IOException("copied symlink differs: " + copiedFile);
                    }
                } else if (!Files.isRegularFile(copiedFile, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(file) != Files.size(copiedFile)) {
                    throw new IOException("copied file differs: " + copiedFile);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        Files.walkFileTree(copy, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                verifySourceEntry(source, copy, directory);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                verifySourceEntry(source, copy, file);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void verifySourceEntry(Path source, Path copy, Path copiedEntry) throws IOException {
        var sourceEntry = copiedPath(copy, source, copiedEntry);
        if (Files.notExists(sourceEntry, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("copied directory has an unexpected entry: " + copiedEntry);
        }
    }

    private static Path copiedPath(Path source, Path copy, Path entry) {
        return copy.resolve(source.relativize(entry));
    }

    private void requireState(Path path, PathState expected) {
        var actual = inspector.inspect(path).state();
        if (actual != expected) {
            throw new IllegalStateException("expected " + expected.name().toLowerCase() + " at " + path
                    + " but found " + actual.name().toLowerCase());
        }
    }

    private static final class CopyVisitor extends SimpleFileVisitor<Path> {
        private final Path source;
        private final Path destination;

        private CopyVisitor(Path source, Path destination) {
            this.source = source;
            this.destination = destination;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
            Files.createDirectories(copiedPath(source, destination, directory));
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
            Files.copy(file, copiedPath(source, destination, file), LinkOption.NOFOLLOW_LINKS);
            return FileVisitResult.CONTINUE;
        }
    }

    public enum ActionStatus { COMPLETED, FAILED, PENDING }

    public interface ProgressListener {
        ProgressListener NONE = new ProgressListener() { };

        default void started(RelocationPlan relocation, ReconciliationAction action) { }

        default void finished(RelocationPlan relocation, ActionExecution action) { }
    }

    public record ActionExecution(ReconciliationAction action, ActionStatus status, String message) { }

    public record RelocationExecution(RelocationPlan relocation, List<ActionExecution> actions) {
        public RelocationExecution {
            actions = List.copyOf(actions);
        }
    }

    public record ExecutionResult(List<RelocationExecution> relocations) {
        public ExecutionResult {
            relocations = List.copyOf(relocations);
        }

        public boolean succeeded() {
            return relocations.stream().flatMap(relocation -> relocation.actions().stream())
                    .noneMatch(action -> action.status() == ActionStatus.FAILED);
        }
    }
}
