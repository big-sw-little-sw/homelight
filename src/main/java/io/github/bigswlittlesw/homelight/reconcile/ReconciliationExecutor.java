package io.github.bigswlittlesw.homelight.reconcile;

import io.github.bigswlittlesw.homelight.fs.PathInspector;
import io.github.bigswlittlesw.homelight.fs.PathState;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/// Applies a fully resolved plan, stopping when the filesystem no longer matches its guards.
public final class ReconciliationExecutor {
    private final PathInspector inspector = new PathInspector();
    private final AtomicMover atomicMover;

    public ReconciliationExecutor() {
        this((source, target) -> Files.move(source, target, StandardCopyOption.ATOMIC_MOVE));
    }

    ReconciliationExecutor(AtomicMover atomicMover) {
        this.atomicMover = atomicMover;
    }

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
                    apply(action);
                    var execution = new ActionExecution(action, ActionStatus.COMPLETED, "completed");
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

    private void apply(ReconciliationAction action) throws IOException {
        switch (action) {
            case ReconciliationAction.CreateDirectory directory -> createDirectory(directory);
            case ReconciliationAction.EnsureDirectory directory -> ensureDirectory(directory);
            case ReconciliationAction.Move move -> move(move);
            case ReconciliationAction.DeleteDirectory directory -> deleteDirectory(directory);
            case ReconciliationAction.CreateSymlink link -> createSymlink(link);
            case ReconciliationAction.ReplaceSymlink link -> replaceSymlink(link);
            case ReconciliationAction.NoOp _ -> { }
            case ReconciliationAction.Skip _ -> { }
            case ReconciliationAction.Blocked blocked -> throw new IllegalStateException(blocked.reason());
        }
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

    private void move(ReconciliationAction.Move action) throws IOException {
        requireState(action.path(), action.expectedSourceState());
        requireState(action.target(), action.expectedTargetState());
        try {
            atomicMover.move(action.path(), action.target());
        } catch (AtomicMoveNotSupportedException exception) {
            copyThenRemove(action.path(), action.target());
        }
    }

    private void deleteDirectory(ReconciliationAction.DeleteDirectory action) throws IOException {
        requireState(action.path(), action.expectedPathState());
        if (action.expectedEmpty() && !inspector.inspect(action.path()).emptyDirectory()) {
            throw new IllegalStateException("expected empty directory at " + action.path());
        }
        deleteTree(action.path());
    }

    private void createSymlink(ReconciliationAction.CreateSymlink action) throws IOException {
        requireState(action.path(), action.expectedSourceState());
        requireState(action.target(), action.expectedTargetState());
        replaceWithLink(action.path(), action.target(), false);
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
        var temporary = Files.createTempFile(path.getParent(), ".homelight-", ".link");
        Files.delete(temporary);
        try {
            Files.createSymbolicLink(temporary, target);
            if (replaceExisting) {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void copyThenRemove(Path source, Path target) throws IOException {
        var temporary = Files.createTempDirectory(target.getParent(), ".homelight-move-");
        var copied = temporary.resolve(target.getFileName());
        try {
            Files.walkFileTree(source, new CopyVisitor(source, copied));
            verifyCopy(source, copied);
            Files.move(copied, target, StandardCopyOption.ATOMIC_MOVE);
            deleteTree(source);
        } finally {
            Files.deleteIfExists(temporary);
        }
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

    @FunctionalInterface
    interface AtomicMover {
        void move(Path source, Path target) throws IOException;
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
