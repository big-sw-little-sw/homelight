package io.github.bigswlittlesw.homelight.reconcile;

import java.nio.file.Path;

/// A concrete, inspectable step in a reconciliation plan.
public sealed interface ReconciliationAction {
    Path path();

    boolean destructive();

    /// Ensures that `path` and its missing parent directories exist.
    record CreateDirectory(Path path) implements ReconciliationAction {
        @Override
        public boolean destructive() {
            return false;
        }
    }

    record Move(Path path, Path target) implements ReconciliationAction {
        @Override
        public boolean destructive() {
            return true;
        }
    }

    record CreateSymlink(Path path, Path target) implements ReconciliationAction {
        @Override
        public boolean destructive() {
            return false;
        }
    }

    record ReplaceSymlink(Path path, Path target) implements ReconciliationAction {
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
