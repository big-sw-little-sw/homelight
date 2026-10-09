package io.github.bigswlittlesw.lighten.reconcile

import io.github.bigswlittlesw.lighten.fs.PathState
import io.github.bigswlittlesw.lighten.fs.systemReason
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
    /** A guard found [found] at [path] where the plan expected [expected]. A real directory is expected as `DIRECTORY`. */
    data class Drift(val path: Path, val expected: PathState, val found: PathState) : ActionFailure

    /** The link at [path] now points to [found] instead of [expected]. */
    data class LinkChanged(val path: Path, val expected: Path, val found: Path?) : ActionFailure

    /** [stagingRoot] is not on the filesystem of [target], so the copy cannot be moved there in one step. */
    data class StagingElsewhere(val stagingRoot: Path, val target: Path) : ActionFailure

    /** The filesystem of [path] cannot read or set Unix permission bits. */
    data class NoPosixPermissions(val path: Path) : ActionFailure

    /** Another publication to [target] holds its staging lock: in this process when [here], else in another one. */
    data class Busy(val target: Path, val here: Boolean) : ActionFailure

    /** The staged copy of [entry], a path under the source, did not match it as [difference] says. Nothing was published. */
    data class CopyChanged(val entry: Path, val difference: CopyDifference) : ActionFailure

    /** [entry], a path under the source, is a named pipe or device file, which the copy can't make. Nothing was published. */
    data class Unmovable(val entry: Path, val kind: SpecialFileKind) : ActionFailure

    /** The staged copy of the directory [entry] has other permission bits than it. Nothing was published. */
    data class PermissionsNotKept(val entry: Path) : ActionFailure

    /** The copy was published at [target], but its permissions could not be restored, for [reason]. */
    data class PermissionsNotRestored(val target: Path, val reason: String) : ActionFailure

    /** [from] could not be renamed to [to] because they are on different filesystems. */
    data class DifferentFilesystems(val from: Path, val to: Path) : ActionFailure

    data class AccessDenied(val path: Path) : ActionFailure

    /** [path] disappeared between a guard and the change that needed it. */
    data class Gone(val path: Path) : ActionFailure

    /** Something appeared at [path] between a guard and the change that needed it absent. */
    data class AlreadyExists(val path: Path) : ActionFailure

    /**
     * Any other I/O failure: the [path] and [other] path the exception named, if any, and its [reason], the system's
     * words or, without a path, the exception's message.
     */
    data class Io(val path: Path?, val other: Path?, val reason: String) : ActionFailure
}

/** How a staged copy differed from its source. */
enum class CopyDifference { MISSING_DIRECTORY, FILE_DIFFERS, LINK_DIFFERS, EXTRA_ENTRY }

/** An I/O failure recognized by its exception type, never by its text. */
internal fun ioFailure(exception: IOException): ActionFailure {
    if (exception is PartlyPublishedException) return ActionFailure.PermissionsNotRestored(exception.target, systemReason(exception.failure))
    val file = (exception as? FileSystemException)?.file?.let { Path.of(it) }
        ?: return ActionFailure.Io(null, null, systemReason(exception))
    val other = exception.otherFile?.let { Path.of(it) }
    return when (exception) {
        is AtomicMoveNotSupportedException ->
            other?.let { ActionFailure.DifferentFilesystems(file, it) } ?: ActionFailure.Io(file, null, systemReason(exception))
        is AccessDeniedException -> ActionFailure.AccessDenied(file)
        is NoSuchFileException -> ActionFailure.Gone(file)
        is FileAlreadyExistsException -> ActionFailure.AlreadyExists(file)
        else -> ActionFailure.Io(file, other, systemReason(exception))
    }
}
