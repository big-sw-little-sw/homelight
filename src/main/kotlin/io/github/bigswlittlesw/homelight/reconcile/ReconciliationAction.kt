package io.github.bigswlittlesw.homelight.reconcile

import java.nio.file.Path

/** A concrete, inspectable step in a reconciliation plan. */
sealed interface ReconciliationAction {
    val path: Path

    /** The stable machine-readable name used by presentation adapters. */
    val type: String
        get() = when (this) {
            is CreateDirectory -> "create-directory"
            is EnsureDirectory -> "ensure-directory"
            is MigrateDirectoryForPublication -> "migrate-directory-for-publication"
            is ArchiveDirectory -> "archive-directory"
            is DeleteDirectory -> "delete-directory"
            is CreateSymlink -> "create-symlink"
            is ReplaceDirectoryWithSymlink -> "replace-directory-with-symlink"
            is ReplaceSymlink -> "replace-symlink"
            is NoOp -> "no-op"
            is LeaveUnchanged -> "leave-unchanged"
            is Blocked -> "blocked"
        }

    /** Whether executing this action removes existing content. */
    val destructive: Boolean
        get() = when (this) {
            is DeleteDirectory, is ReplaceDirectoryWithSymlink, is ReplaceSymlink -> true
            is CreateDirectory, is EnsureDirectory, is MigrateDirectoryForPublication,
            is ArchiveDirectory, is CreateSymlink, is NoOp, is LeaveUnchanged, is Blocked -> false
        }

    /** Whether executing this action can change the filesystem. */
    val mutatesFilesystem: Boolean
        get() = when (this) {
            is NoOp, is LeaveUnchanged, is Blocked -> false
            is CreateDirectory, is EnsureDirectory, is MigrateDirectoryForPublication,
            is ArchiveDirectory, is DeleteDirectory, is CreateSymlink, is ReplaceDirectoryWithSymlink,
            is ReplaceSymlink -> true
        }

    /** Creates `path` after its parent-directory prerequisites have been satisfied. */
    data class CreateDirectory(override val path: Path) : ReconciliationAction

    /** Creates a prerequisite directory when absent and refuses files or symlinks. */
    data class EnsureDirectory(override val path: Path) : ReconciliationAction

    /**
     * Migrates a verified source copy for target-local atomic publication.
     * A null `stagingRoot` stages beside the target.
     */
    data class MigrateDirectoryForPublication(override val path: Path, val target: Path, val stagingRoot: Path? = null) :
        ReconciliationAction

    /** Moves a source directory into an unoccupied deterministic archive location. */
    data class ArchiveDirectory(override val path: Path, val target: Path) : ReconciliationAction

    /** Removes a real directory tree after verifying it is still a directory. */
    data class DeleteDirectory(override val path: Path) : ReconciliationAction

    data class CreateSymlink(override val path: Path, val target: Path) : ReconciliationAction

    /** Prepares a replacement link before removing an accepted source directory. */
    data class ReplaceDirectoryWithSymlink(override val path: Path, val target: Path) : ReconciliationAction

    data class ReplaceSymlink(override val path: Path, val target: Path, val expectedSourceTarget: Path) :
        ReconciliationAction

    data class NoOp(override val path: Path) : ReconciliationAction

    /** Records an explicit decision to leave source and target directories unmanaged. */
    data class LeaveUnchanged(override val path: Path) : ReconciliationAction

    data class Blocked(override val path: Path, val reason: String) : ReconciliationAction
}
