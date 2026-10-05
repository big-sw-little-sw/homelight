package io.github.bigswlittlesw.homelight.tui

import io.github.bigswlittlesw.homelight.config.WhenAdoptingTarget
import io.github.bigswlittlesw.homelight.config.WhenOnlyTargetExists
import io.github.bigswlittlesw.homelight.config.WhenSourceAndTargetDirectoriesExist

// The one label each policy value has in every view. A missing rule is Prompt, so it shows as Prompt too.

internal fun bothLabel(value: WhenSourceAndTargetDirectoriesExist): String = when (value) {
    WhenSourceAndTargetDirectoriesExist.PROMPT -> "Prompt"
    WhenSourceAndTargetDirectoriesExist.ADOPT -> "Adopt target"
    WhenSourceAndTargetDirectoriesExist.LEAVE_UNCHANGED -> "Leave unchanged"
    WhenSourceAndTargetDirectoriesExist.DISCARD -> "Discard both"
}

internal fun onlyTargetLabel(value: WhenOnlyTargetExists): String = when (value) {
    WhenOnlyTargetExists.PROMPT -> "Prompt"
    WhenOnlyTargetExists.ADOPT_TARGET -> "Adopt target"
}

internal fun adoptingLabel(value: WhenAdoptingTarget): String = when (value) {
    WhenAdoptingTarget.PROMPT -> "Prompt"
    WhenAdoptingTarget.DISCARD_SOURCE -> "Discard source"
    WhenAdoptingTarget.ARCHIVE_SOURCE -> "Archive source"
}
