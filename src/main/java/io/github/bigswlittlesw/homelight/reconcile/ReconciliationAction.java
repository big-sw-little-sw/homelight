package io.github.bigswlittlesw.homelight.reconcile;

import java.nio.file.Path;

/// A concrete, inspectable step in a reconciliation plan.
public sealed interface ReconciliationAction
        permits ReconciliationAction.CreateDirectory, ReconciliationAction.Move,
        ReconciliationAction.CreateSymlink, ReconciliationAction.ReplaceSymlink,
        ReconciliationAction.NoOp, ReconciliationAction.Blocked {
    Path path();

    boolean destructive();

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
