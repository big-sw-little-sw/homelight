package io.github.bigswlittlesw.lighten.application

import io.github.bigswlittlesw.lighten.config.Relocation
import io.github.bigswlittlesw.lighten.config.WhenOnlyTargetExists

/**
 * The one-time choices for a relocation that needs a choice. Each is a value of the rule for its case
 * ([GoverningRule]). `plan --json` names them in kebab case, for example `adopt-and-archive-source`.
 */
enum class DecisionChoice {
    ADOPT_TARGET,
    ADOPT_AND_DISCARD_SOURCE,
    ADOPT_AND_ARCHIVE_SOURCE,
    LEAVE_UNCHANGED,
    DISCARD_BOTH;

    /** `saved` with this choice as the rule for its case; the other case's rule stays as saved. */
    internal fun applyTo(saved: Relocation): Relocation = when (this) {
        ADOPT_TARGET -> saved.copy(whenOnlyTargetExists = WhenOnlyTargetExists.ADOPT_TARGET)
        ADOPT_AND_DISCARD_SOURCE, ADOPT_AND_ARCHIVE_SOURCE, LEAVE_UNCHANGED, DISCARD_BOTH -> {
            val rule = BothExistRule.entries.single { it.choice == this }
            saved.copy(
                whenSourceAndTargetDirectoriesExist = rule.both,
                whenAdoptingTarget = rule.adopting ?: saved.whenAdoptingTarget,
            )
        }
    }
}
