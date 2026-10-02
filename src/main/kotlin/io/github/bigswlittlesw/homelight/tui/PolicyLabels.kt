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

/** The archive root, when there is one, is shown apart from the label. */
internal fun adoptingLabel(value: WhenAdoptingTarget.Kind?): String = when (value) {
    WhenAdoptingTarget.Kind.PROMPT -> "Prompt"
    WhenAdoptingTarget.Kind.DISCARD_SOURCE -> "Discard source"
    WhenAdoptingTarget.Kind.ARCHIVE_SOURCE -> "Archive source"
    null -> DEFAULT_POLICY_LABEL
}
