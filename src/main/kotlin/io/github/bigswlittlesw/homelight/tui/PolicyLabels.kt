package io.github.bigswlittlesw.homelight.tui

import io.github.bigswlittlesw.homelight.config.WhenAdoptingTarget
import io.github.bigswlittlesw.homelight.config.WhenOnlyTargetExists
import io.github.bigswlittlesw.homelight.config.WhenSourceAndTargetDirectoriesExist

// The one label each policy value has in every view. Null is an omitted policy, which behaves as
// Prompt but is shown apart from an explicit Prompt.

internal const val DEFAULT_POLICY_LABEL = "Default (prompt)"

internal fun bothLabel(value: WhenSourceAndTargetDirectoriesExist?): String = when (value) {
    WhenSourceAndTargetDirectoriesExist.PROMPT -> "Prompt"
    WhenSourceAndTargetDirectoriesExist.ADOPT -> "Adopt target"
    WhenSourceAndTargetDirectoriesExist.LEAVE_UNCHANGED -> "Leave unchanged"
    WhenSourceAndTargetDirectoriesExist.DISCARD -> "Discard both"
    null -> DEFAULT_POLICY_LABEL
}

internal fun onlyTargetLabel(value: WhenOnlyTargetExists?): String = when (value) {
    WhenOnlyTargetExists.PROMPT -> "Prompt"
    WhenOnlyTargetExists.ADOPT_TARGET -> "Adopt target"
    null -> DEFAULT_POLICY_LABEL
}

internal fun adoptingLabel(value: WhenAdoptingTarget?): String = when (value) {
    WhenAdoptingTarget.PROMPT -> "Prompt"
    WhenAdoptingTarget.DISCARD_SOURCE -> "Discard source"
    WhenAdoptingTarget.ARCHIVE_SOURCE -> "Archive source"
    null -> DEFAULT_POLICY_LABEL
}
