package io.github.bigswlittlesw.lighten.reconcile

import java.nio.file.Path

/** A state that requires an explicit, state-appropriate user decision before application. */
data class ReconciliationConflict(val path: Path, val reason: String, val resolutions: List<Resolution>) {
    init {
        require(resolutions.isNotEmpty()) { "A conflict needs at least one resolution" }
    }

    enum class Resolution {
        REPLACE_SOURCE_LINK,
        RESOLVE_EXISTING_CONTENT,
        LEAVE_UNMANAGED,
        CHOOSE_DIFFERENT_TARGET,
    }
}
