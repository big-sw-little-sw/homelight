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
            case CreateDirectory _ -> "create-directory";
            case EnsureDirectory _ -> "ensure-directory";
            case CopyDirectory _ -> "copy-directory";
            case MigrateDirectoryForPublication _ -> "migrate-directory-for-publication";
            case ArchiveDirectory _ -> "archive-directory";
            case DeleteDirectory _ -> "delete-directory";
            case CreateSymlink _ -> "create-symlink";
            case ReplaceDirectoryWithSymlink _ -> "replace-directory-with-symlink";
            case ReplaceSymlink _ -> "replace-symlink";
            case NoOp _ -> "no-op";
            case LeaveUnchanged _ -> "leave-unchanged";
            case Blocked _ -> "blocked";
        };
    }

    /// Whether executing this action can change the filesystem.
    default boolean mutatesFilesystem() {
        return switch (this) {
            case NoOp _ -> false;
            case LeaveUnchanged _ -> false;
            case Blocked _ -> false;
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

    /// Copies a source tree into an exclusively created target, leaving the source intact on failure.
    record CopyDirectory(Path path, Path target, PathState expectedSourceState, PathState expectedTargetState)
            implements ReconciliationAction {
        public CopyDirectory(Path path, Path target) {
            this(path, target, PathState.DIRECTORY, PathState.ABSENT);
        }

        @Override
        public boolean destructive() {
            return false;
        }
    }

    /// Migrates a verified source copy for target-local atomic publication.
    record MigrateDirectoryForPublication(Path path, Path target, java.util.Optional<Path> stagingRoot)
            implements ReconciliationAction {
        public MigrateDirectoryForPublication(Path path, Path target) {
            this(path, target, java.util.Optional.empty());
        }

        @Override
        public boolean destructive() {
            return false;
        }
    }

    /// Moves a source directory into an unoccupied deterministic archive location.
    record ArchiveDirectory(Path path, Path target) implements ReconciliationAction {
        @Override
        public boolean destructive() {
            return false;
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

    /// Prepares a replacement link before removing an accepted source directory.
    record ReplaceDirectoryWithSymlink(Path path, Path target, PathState expectedTargetState)
            implements ReconciliationAction {
        public ReplaceDirectoryWithSymlink(Path path, Path target) {
            this(path, target, PathState.DIRECTORY);
        }

        @Override
        public boolean destructive() {
            return true;
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

    /// Records an explicit decision to leave source and target directories unmanaged.
    record LeaveUnchanged(Path path) implements ReconciliationAction {
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
