package io.github.bigswlittlesw.homelight.reconcile;

import io.github.bigswlittlesw.homelight.fs.PathState;

import java.nio.file.Path;

/// A concrete, inspectable step in a reconciliation plan.
public sealed interface ReconciliationAction {
    Path path();

    boolean destructive();

    /// Ensures that `path` and its missing parent directories exist.
    record CreateDirectory(Path path, PathState expectedPathState) implements ReconciliationAction {
        public CreateDirectory(Path path) {
            this(path, PathState.ABSENT);
        }

        @Override
        public boolean destructive() {
            return false;
        }
    }

    /// Ensures a prerequisite directory exists without accepting files or symlinks at that path.
    record EnsureDirectory(Path path, PathState expectedPathState) implements ReconciliationAction {
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

    record Blocked(Path path, String reason) implements ReconciliationAction {
        @Override
        public boolean destructive() {
            return false;
        }
    }
}
