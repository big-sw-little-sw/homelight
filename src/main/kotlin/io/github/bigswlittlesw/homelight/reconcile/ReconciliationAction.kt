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

    /** Where this action publishes, archives or links `path` to; null when it acts on `path` alone. */
    val destination: Path?
        get() = when (this) {
            is MigrateDirectoryForPublication -> target
            is ArchiveDirectory -> target
            is CreateSymlink -> target
            is ReplaceDirectoryWithSymlink -> target
            is ReplaceSymlink -> target
            is CreateDirectory, is EnsureDirectory, is DeleteDirectory, is NoOp, is LeaveUnchanged, is Blocked -> null
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
        ReconciliationAction {
        /** The staging root this migration uses: [stagingRoot], or `.homelight-staging` beside [target]. */
        val effectiveStagingRoot: Path
            get() = stagingRoot ?: target.resolveSibling(DEFAULT_STAGING_NAME)
    }

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

private const val DEFAULT_STAGING_NAME = ".homelight-staging"
