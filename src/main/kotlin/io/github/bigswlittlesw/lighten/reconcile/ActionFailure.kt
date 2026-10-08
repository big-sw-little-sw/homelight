package io.github.bigswlittlesw.lighten.reconcile

import io.github.bigswlittlesw.lighten.fs.PathState
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/**
 * Why an action failed, for screens to put in plain words. The executor's own text stays in
 * [ReconciliationExecutor.ActionExecution.message], for `apply --json` and bug reports.
 */
sealed interface ActionFailure {
    /** A guard found [found] at [path] where the plan expected [expected]. A real folder is expected as `DIRECTORY`. */
    data class Drift(val path: Path, val expected: PathState, val found: PathState) : ActionFailure

    /** The link at [path] now points to [found] instead of [expected]. */
    data class LinkChanged(val path: Path, val expected: Path, val found: Path?) : ActionFailure

    /** [stagingRoot] is not on the filesystem of [target], so the copy cannot be moved there in one step. */
    data class StagingElsewhere(val stagingRoot: Path, val target: Path) : ActionFailure

    /** The filesystem of [path] cannot read or set Unix permission bits. */
    data class NoPosixPermissions(val path: Path) : ActionFailure

    /** Another publication to [target] holds its staging lock: in this process when [here], else in another one. */
    data class Busy(val target: Path, val here: Boolean) : ActionFailure

    /** The staged copy of [entry], a path under the source, did not match it. Nothing was published. */
    data class CopyChanged(val entry: Path) : ActionFailure

    /** The staged copy of the folder [entry] has other permission bits than it. Nothing was published. */
    data class PermissionsNotKept(val entry: Path) : ActionFailure

    /** The copy was published at [target], but its permissions could not be restored. */
    data class PermissionsNotRestored(val target: Path) : ActionFailure

    /** [from] could not be renamed to [to] because they are on different filesystems. */
    data class DifferentFilesystems(val from: Path, val to: Path) : ActionFailure

    data class AccessDenied(val path: Path) : ActionFailure

    /** [path] disappeared between a guard and the change that needed it. */
    data class Gone(val path: Path) : ActionFailure

    /** Something appeared at [path] between a guard and the change that needed it absent. */
    data class AlreadyExists(val path: Path) : ActionFailure

    /** Any other I/O failure; [path] and [reason] are whatever the exception knew. */
    data class Io(val path: Path?, val reason: String?) : ActionFailure
}

/** An I/O failure recognized by its exception type, never by its text. */
internal fun ioFailure(exception: IOException): ActionFailure {
    if (exception is PartlyPublishedException) return ActionFailure.PermissionsNotRestored(exception.target)
    if (exception !is FileSystemException) return ActionFailure.Io(null, exception.message)
    val file = exception.file?.let { Path.of(it) } ?: return ActionFailure.Io(null, exception.message)
    return when (exception) {
        is AtomicMoveNotSupportedException ->
            exception.otherFile?.let { ActionFailure.DifferentFilesystems(file, Path.of(it)) } ?: ActionFailure.Io(file, exception.reason)
        is AccessDeniedException -> ActionFailure.AccessDenied(file)
        is NoSuchFileException -> ActionFailure.Gone(file)
        is FileAlreadyExistsException -> ActionFailure.AlreadyExists(file)
        else -> ActionFailure.Io(file, exception.reason)
    }
}
