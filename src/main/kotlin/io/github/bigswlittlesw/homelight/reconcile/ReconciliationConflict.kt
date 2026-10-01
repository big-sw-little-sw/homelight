package io.github.bigswlittlesw.homelight.reconcile

import java.nio.file.Path
import java.util.Objects

/**
 * A state that requires an explicit, state-appropriate user decision before application.
 *
 * Not a `@JvmRecord data class`: the constructor copies `resolutions`, which a Kotlin record cannot do.
 * Accessors keep the record names; equality and `toString` match the record this replaces.
 */
class ReconciliationConflict(path: Path, reason: String, resolutions: List<Resolution>) {
    @get:JvmName("path")
    val path: Path = path

    @get:JvmName("reason")
    val reason: String = reason

    @get:JvmName("resolutions")
    val resolutions: List<Resolution> = java.util.List.copyOf(resolutions)

    init {
        if (this.resolutions.isEmpty()) {
            throw IllegalArgumentException("A conflict needs at least one resolution")
        }
    }

    enum class Resolution {
        REPLACE_SOURCE_LINK,
        RESOLVE_EXISTING_CONTENT,
        LEAVE_UNMANAGED,
        CHOOSE_DIFFERENT_TARGET,
    }

    override fun equals(other: Any?): Boolean = other is ReconciliationConflict
            && path == other.path
            && reason == other.reason
            && resolutions == other.resolutions

    override fun hashCode(): Int = Objects.hash(path, reason, resolutions)

    override fun toString(): String = "ReconciliationConflict[path=$path, reason=$reason, resolutions=$resolutions]"
}
