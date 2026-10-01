package io.github.bigswlittlesw.homelight.reconcile

import io.github.bigswlittlesw.homelight.fs.PathState
import java.nio.file.Path
import java.util.Optional

/** A concrete, inspectable step in a reconciliation plan. */
sealed interface ReconciliationAction {
    // Java callers use the record-style accessor `path()`, which each record's component implements.
    @Suppress("INAPPLICABLE_JVM_NAME")
    @get:JvmName("path")
    val path: Path

    fun destructive(): Boolean

    /** Returns the stable machine-readable name used by presentation adapters. */
    fun type(): String = when (this) {
        is CreateDirectory -> "create-directory"
        is EnsureDirectory -> "ensure-directory"
        is CopyDirectory -> "copy-directory"
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

    /** Whether executing this action can change the filesystem. */
    fun mutatesFilesystem(): Boolean = when (this) {
        is NoOp -> false
        is LeaveUnchanged -> false
        is Blocked -> false
        else -> true
    }

    /** Creates `path` after its parent-directory prerequisites have been satisfied. */
    @JvmRecord
    data class CreateDirectory(override val path: Path, val expectedPathState: PathState) : ReconciliationAction {
        constructor(path: Path) : this(path, PathState.ABSENT)

        override fun destructive(): Boolean = false
    }

    /** Creates a prerequisite directory when absent and refuses files or symlinks. */
    @JvmRecord
    data class EnsureDirectory(override val path: Path) : ReconciliationAction {
        override fun destructive(): Boolean = false
    }

    /** Copies a source tree into an exclusively created target, leaving the source intact on failure. */
    @JvmRecord
    data class CopyDirectory(
        override val path: Path, val target: Path, val expectedSourceState: PathState,
        val expectedTargetState: PathState,
    ) : ReconciliationAction {
        constructor(path: Path, target: Path) : this(path, target, PathState.DIRECTORY, PathState.ABSENT)

        override fun destructive(): Boolean = false
    }

    /** Migrates a verified source copy for target-local atomic publication. */
    @JvmRecord
    data class MigrateDirectoryForPublication(override val path: Path, val target: Path, val stagingRoot: Optional<Path>) :
        ReconciliationAction {
        constructor(path: Path, target: Path) : this(path, target, Optional.empty())

        override fun destructive(): Boolean = false
    }

    /** Moves a source directory into an unoccupied deterministic archive location. */
    @JvmRecord
    data class ArchiveDirectory(override val path: Path, val target: Path) : ReconciliationAction {
        override fun destructive(): Boolean = false
    }

    /** Removes a real directory tree after verifying its planned state, and optionally emptiness, still hold. */
    @JvmRecord
    data class DeleteDirectory(override val path: Path, val expectedPathState: PathState, val expectedEmpty: Boolean) :
        ReconciliationAction {
        constructor(path: Path) : this(path, PathState.DIRECTORY, false)

        constructor(path: Path, expectedPathState: PathState) : this(path, expectedPathState, false)

        override fun destructive(): Boolean = true
    }

    @JvmRecord
    data class CreateSymlink(
        override val path: Path, val target: Path, val expectedSourceState: PathState,
        val expectedTargetState: PathState,
    ) : ReconciliationAction {
        constructor(path: Path, target: Path) : this(path, target, PathState.ABSENT, PathState.DIRECTORY)

        override fun destructive(): Boolean = false
    }

    /** Prepares a replacement link before removing an accepted source directory. */
    @JvmRecord
    data class ReplaceDirectoryWithSymlink(override val path: Path, val target: Path, val expectedTargetState: PathState) :
        ReconciliationAction {
        constructor(path: Path, target: Path) : this(path, target, PathState.DIRECTORY)

        override fun destructive(): Boolean = true
    }

    @JvmRecord
    data class ReplaceSymlink(
        override val path: Path, val target: Path, val expectedSourceTarget: Path?,
        val expectedTargetState: PathState,
    ) : ReconciliationAction {
        constructor(path: Path, target: Path) : this(path, target, null, PathState.DIRECTORY)

        override fun destructive(): Boolean = true
    }

    @JvmRecord
    data class NoOp(override val path: Path) : ReconciliationAction {
        override fun destructive(): Boolean = false
    }

    /** Records an explicit decision to leave source and target directories unmanaged. */
    @JvmRecord
    data class LeaveUnchanged(override val path: Path) : ReconciliationAction {
        override fun destructive(): Boolean = false
    }

    @JvmRecord
    data class Blocked(override val path: Path, val reason: String) : ReconciliationAction {
        override fun destructive(): Boolean = false
    }
}
