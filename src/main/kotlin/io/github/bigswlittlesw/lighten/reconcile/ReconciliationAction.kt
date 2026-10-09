package io.github.bigswlittlesw.lighten.reconcile

import io.github.bigswlittlesw.lighten.fs.PathText
import java.nio.file.Path

/** One step of a plan. */
sealed interface ReconciliationAction {
    val path: Path

    /** The stable machine-readable name, which JSON output uses. */
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

    /** Creates `path`. An earlier [EnsureDirectory] makes its parent. */
    data class CreateDirectory(override val path: Path) : ReconciliationAction

    /**
     * Makes `path` a directory, with its missing parents. It follows an existing link to a directory, and fails on
     * anything else in the way.
     *
     * [role] says which directory `path` is for, so screens can name it. The path alone cannot say: the source's
     * parent and the target's parent can be the same directory.
     */
    data class EnsureDirectory(override val path: Path, val role: Role) : ReconciliationAction {
        enum class Role { TARGET_PARENT, SOURCE_PARENT, ARCHIVE_ROOT }
    }

    /**
     * Copies the source into a staging root, checks the copy, and moves it to [target] in one rename. A null
     * `stagingRoot` stages beside the target.
     */
    data class MigrateDirectoryForPublication(override val path: Path, val target: Path, val stagingRoot: Path? = null) :
        ReconciliationAction {
        /** The staging root this migration uses: [stagingRoot], or `.lighten-staging` beside [target]. */
        val effectiveStagingRoot: Path
            get() = effectiveStagingRoot(target, stagingRoot)
    }

    /** Moves the source in one rename to [target], its archive path, which must not exist yet. */
    data class ArchiveDirectory(override val path: Path, val target: Path) : ReconciliationAction

    /** Deletes a directory tree after checking that it is still a directory. */
    data class DeleteDirectory(override val path: Path) : ReconciliationAction

    data class CreateSymlink(override val path: Path, val target: Path) : ReconciliationAction

    /** Replaces the source directory with a link to [target]. The link is in place before the old source is deleted. */
    data class ReplaceDirectoryWithSymlink(override val path: Path, val target: Path) : ReconciliationAction

    data class ReplaceSymlink(override val path: Path, val target: Path, val expectedSourceTarget: Path) :
        ReconciliationAction

    data class NoOp(override val path: Path) : ReconciliationAction

    /** Records that the rule or one-time choice leaves both directories as they are. */
    data class LeaveUnchanged(override val path: Path) : ReconciliationAction

    /** [reason] is in the planner's words, which JSON and screens share; only how its paths read differs. */
    data class Blocked(override val path: Path, val reason: PathText) : ReconciliationAction
}

/** A configured [stagingRoot], or `.lighten-staging` beside [target]. */
internal fun effectiveStagingRoot(target: Path, stagingRoot: Path?): Path =
    stagingRoot ?: target.resolveSibling(DEFAULT_STAGING_NAME)

private const val DEFAULT_STAGING_NAME = ".lighten-staging"
