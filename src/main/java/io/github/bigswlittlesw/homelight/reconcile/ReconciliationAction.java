package io.github.bigswlittlesw.homelight.reconcile;

import io.github.bigswlittlesw.homelight.fs.PathState;

import java.nio.file.Path;

/// A concrete, inspectable step in a reconciliation plan.
public sealed interface ReconciliationAction {
    Path path();

    boolean destructive();

    /// Returns the stable machine-readable name used by presentation adapters.
    default String type() {
        return switch (this) {
            case CreateDirectory ignored -> "create-directory";
            case EnsureDirectory ignored -> "ensure-directory";
            case Move ignored -> "move";
            case DeleteDirectory ignored -> "delete-directory";
            case CreateSymlink ignored -> "create-symlink";
            case ReplaceSymlink ignored -> "replace-symlink";
            case NoOp ignored -> "no-op";
            case Skip ignored -> "skip";
            case Blocked ignored -> "blocked";
        };
    }

    /// Whether executing this action can change the filesystem.
    default boolean mutatesFilesystem() {
        return switch (this) {
            case NoOp ignored -> false;
            case Skip ignored -> false;
            case Blocked ignored -> false;
            default -> true;
        };
    }

    /// Creates `path` after its parent-directory prerequisites have been satisfied.
    record CreateDirectory(Path path, PathState expectedPathState) implements ReconciliationAction {
        public CreateDirectory(Path path) {
            this(path, PathState.ABSENT);
        }

        @Override
        public boolean destructive() {
            return false;
        }
    }

    /// Creates a prerequisite directory when absent and refuses files or symlinks.
    record EnsureDirectory(Path path) implements ReconciliationAction {
        @Override
        public boolean destructive() {
            return false;
        }
    }

    record Move(Path path, Path target, PathState expectedSourceState, PathState expectedTargetState)
            implements ReconciliationAction {
        public Move(Path path, Path target) {
            this(path, target, PathState.DIRECTORY, PathState.ABSENT);
        }

        @Override
        public boolean destructive() {
            return true;
        }
    }

    /// Removes a real directory tree after verifying its planned state, and optionally emptiness, still hold.
    record DeleteDirectory(Path path, PathState expectedPathState, boolean expectedEmpty) implements ReconciliationAction {
        public DeleteDirectory(Path path) {
            this(path, PathState.DIRECTORY, false);
        }

        public DeleteDirectory(Path path, PathState expectedPathState) {
            this(path, expectedPathState, false);
        }

        @Override
        public boolean destructive() {
            return true;
        }
    }

    record CreateSymlink(Path path, Path target, PathState expectedSourceState, PathState expectedTargetState)
            implements ReconciliationAction {
        public CreateSymlink(Path path, Path target) {
            this(path, target, PathState.ABSENT, PathState.DIRECTORY);
        }

        @Override
        public boolean destructive() {
            return false;
        }
    }

    record ReplaceSymlink(Path path, Path target, Path expectedSourceTarget, PathState expectedTargetState)
            implements ReconciliationAction {
        public ReplaceSymlink(Path path, Path target) {
            this(path, target, null, PathState.DIRECTORY);
        }

        @Override
        public boolean destructive() {
            return true;
        }
    }

    record NoOp(Path path) implements ReconciliationAction {
        @Override
        public boolean destructive() {
            return false;
        }
    }

    /// Records an explicit decision to leave pre-existing content unmanaged.
    record Skip(Path path) implements ReconciliationAction {
        @Override
        public boolean destructive() {
            return false;
        }
    }

    record Blocked(Path path, String reason) implements ReconciliationAction {
        @Override
        public boolean destructive() {
            return false;
        }
    }
}
