package io.github.bigswlittlesw.lighten.fs

import io.github.bigswlittlesw.lighten.domain.RelocationSourceState
import java.nio.file.Path

/**
 * A no-follow observation of one filesystem path.
 *
 * Only a symlink has a target and an availability; only a directory can be empty.
 */
data class PathObservation(
    val state: PathState,
    val symlinkTarget: Path? = null,
    val symlinkTargetAvailability: SymlinkTargetAvailability = SymlinkTargetAvailability.NOT_A_SYMLINK,
    val emptyDirectory: Boolean = false,
) {
    init {
        val symlink = state == PathState.SYMLINK
        val hasTarget = symlinkTarget != null || symlinkTargetAvailability != SymlinkTargetAvailability.NOT_A_SYMLINK
        require(symlink || !hasTarget) { "Only symlink observations may have a symlink target" }
        require(!symlink || (symlinkTarget != null && symlinkTargetAvailability != SymlinkTargetAvailability.NOT_A_SYMLINK)) {
            "A symlink observation needs its target and availability"
        }
        require(!emptyDirectory || state == PathState.DIRECTORY) { "Only directory observations may be empty" }
    }

    fun sourceStateForTarget(expectedTarget: Path): RelocationSourceState = when (state) {
        PathState.ABSENT -> RelocationSourceState.ABSENT
        PathState.FILE -> RelocationSourceState.FILE
        PathState.DIRECTORY -> RelocationSourceState.DIRECTORY
        PathState.INACCESSIBLE -> RelocationSourceState.INACCESSIBLE
        PathState.OTHER -> RelocationSourceState.OTHER
        PathState.SYMLINK -> when (symlinkTargetAvailability) {
            SymlinkTargetAvailability.EXISTS ->
                if (symlinkTarget == expectedTarget.toAbsolutePath().normalize())
                    RelocationSourceState.CORRECT_SYMLINK
                else
                    RelocationSourceState.WRONG_SYMLINK
            SymlinkTargetAvailability.ABSENT -> RelocationSourceState.BROKEN_SYMLINK
            SymlinkTargetAvailability.INACCESSIBLE -> RelocationSourceState.INACCESSIBLE
            SymlinkTargetAvailability.NOT_A_SYMLINK -> error("Invalid symlink observation")
        }
    }
}
