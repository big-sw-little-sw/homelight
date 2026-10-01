package io.github.bigswlittlesw.homelight.fs

import io.github.bigswlittlesw.homelight.domain.RelocationSourceState
import java.nio.file.Path
import java.util.Objects
import java.util.Optional

/**
 * A no-follow observation of one filesystem path.
 *
 * Not a `@JvmRecord data class`: the constructor replaces a null `symlinkTarget`, and a Kotlin record
 * cannot normalize its components. Accessors keep the record names; equality and `toString` match the
 * record this replaces.
 */
class PathObservation(
    state: PathState,
    symlinkTarget: Optional<Path>?,
    symlinkTargetAvailability: SymlinkTargetAvailability,
    emptyDirectory: Boolean,
) {
    @get:JvmName("state")
    val state: PathState = state

    @get:JvmName("symlinkTarget")
    val symlinkTarget: Optional<Path> = symlinkTarget ?: Optional.empty()

    @get:JvmName("symlinkTargetAvailability")
    val symlinkTargetAvailability: SymlinkTargetAvailability = symlinkTargetAvailability

    @get:JvmName("emptyDirectory")
    val emptyDirectory: Boolean = emptyDirectory

    init {
        if (this.state != PathState.SYMLINK
            && (this.symlinkTarget.isPresent || this.symlinkTargetAvailability != SymlinkTargetAvailability.NOT_A_SYMLINK)
        ) {
            throw IllegalArgumentException("Only symlink observations may have a symlink target")
        }
        if (this.state == PathState.SYMLINK
            && (this.symlinkTarget.isEmpty || this.symlinkTargetAvailability == SymlinkTargetAvailability.NOT_A_SYMLINK)
        ) {
            throw IllegalArgumentException("A symlink observation needs its target and availability")
        }
        if (this.emptyDirectory && this.state != PathState.DIRECTORY) {
            throw IllegalArgumentException("Only directory observations may be empty")
        }
    }

    constructor(
        state: PathState, symlinkTarget: Optional<Path>?,
        symlinkTargetAvailability: SymlinkTargetAvailability,
    ) : this(state, symlinkTarget, symlinkTargetAvailability, false)

    constructor(state: PathState, symlinkTarget: Optional<Path>?, symlinkTargetExists: Boolean) : this(
        state, symlinkTarget,
        if (state == PathState.SYMLINK)
            if (symlinkTargetExists) SymlinkTargetAvailability.EXISTS else SymlinkTargetAvailability.ABSENT
        else SymlinkTargetAvailability.NOT_A_SYMLINK,
        false,
    )

    fun sourceStateForTarget(expectedTarget: Path): RelocationSourceState = when (state) {
        PathState.ABSENT -> RelocationSourceState.ABSENT
        PathState.FILE -> RelocationSourceState.FILE
        PathState.DIRECTORY -> RelocationSourceState.DIRECTORY
        PathState.INACCESSIBLE -> RelocationSourceState.INACCESSIBLE
        PathState.OTHER -> RelocationSourceState.OTHER
        PathState.SYMLINK -> when (symlinkTargetAvailability) {
            SymlinkTargetAvailability.EXISTS ->
                if (symlinkTarget.orElseThrow() == expectedTarget.toAbsolutePath().normalize())
                    RelocationSourceState.CORRECT_SYMLINK
                else
                    RelocationSourceState.WRONG_SYMLINK
            SymlinkTargetAvailability.ABSENT -> RelocationSourceState.BROKEN_SYMLINK
            SymlinkTargetAvailability.INACCESSIBLE -> RelocationSourceState.INACCESSIBLE
            SymlinkTargetAvailability.NOT_A_SYMLINK -> throw IllegalStateException("Invalid symlink observation")
        }
    }

    override fun equals(other: Any?): Boolean = other is PathObservation
            && state == other.state
            && symlinkTarget == other.symlinkTarget
            && symlinkTargetAvailability == other.symlinkTargetAvailability
            && emptyDirectory == other.emptyDirectory

    override fun hashCode(): Int = Objects.hash(state, symlinkTarget, symlinkTargetAvailability, emptyDirectory)

    override fun toString(): String = "PathObservation[state=$state, symlinkTarget=$symlinkTarget, " +
            "symlinkTargetAvailability=$symlinkTargetAvailability, emptyDirectory=$emptyDirectory]"
}
