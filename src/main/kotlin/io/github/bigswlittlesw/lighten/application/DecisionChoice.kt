package io.github.bigswlittlesw.homelight.application

import io.github.bigswlittlesw.homelight.config.Relocation
import io.github.bigswlittlesw.homelight.config.WhenAdoptingTarget
import io.github.bigswlittlesw.homelight.config.WhenOnlyTargetExists
import io.github.bigswlittlesw.homelight.config.WhenSourceAndTargetDirectoriesExist

/** Typed reconciliation decisions for unresolved conflicts. */
enum class DecisionChoice {
    ADOPT_TARGET,
    ADOPT_AND_DISCARD_SOURCE,
    ADOPT_AND_ARCHIVE_SOURCE,
    LEAVE_UNCHANGED,
    DISCARD_BOTH;

    internal fun applyTo(saved: Relocation): Relocation {
        val both = when (this) {
            ADOPT_TARGET -> saved.whenSourceAndTargetDirectoriesExist
            ADOPT_AND_DISCARD_SOURCE, ADOPT_AND_ARCHIVE_SOURCE -> WhenSourceAndTargetDirectoriesExist.ADOPT
            LEAVE_UNCHANGED -> WhenSourceAndTargetDirectoriesExist.LEAVE_UNCHANGED
            DISCARD_BOTH -> WhenSourceAndTargetDirectoriesExist.DISCARD
        }
        val adopting = when (this) {
            ADOPT_AND_DISCARD_SOURCE -> WhenAdoptingTarget.DISCARD_SOURCE
            ADOPT_AND_ARCHIVE_SOURCE -> WhenAdoptingTarget.ARCHIVE_SOURCE
            ADOPT_TARGET, LEAVE_UNCHANGED, DISCARD_BOTH -> saved.whenAdoptingTarget
        }
        return saved.copy(
            whenSourceAndTargetDirectoriesExist = both,
            whenOnlyTargetExists =
                if (this == ADOPT_TARGET) WhenOnlyTargetExists.ADOPT_TARGET else saved.whenOnlyTargetExists,
            whenAdoptingTarget = adopting,
        )
    }
}
