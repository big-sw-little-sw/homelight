package io.github.bigswlittlesw.lighten.application

import io.github.bigswlittlesw.lighten.config.Relocation
import io.github.bigswlittlesw.lighten.config.WhenAdoptingTarget
import io.github.bigswlittlesw.lighten.config.WhenOnlyTargetExists
import io.github.bigswlittlesw.lighten.config.WhenSourceAndTargetDirectoriesExist
import io.github.bigswlittlesw.lighten.fs.PathState

/**
 * What decides one relocation in the case observed now: the saved rule that governs the case, and the one-time
 * choice that replaces it for the next apply, if any.
 *
 * [inForce] is always one of [offered], so a screen can mark it among them.
 */
data class RelocationDecision(val rule: GoverningRule, val oneTimeChoice: DecisionChoice?) {
    init {
        require(oneTimeChoice == null || oneTimeChoice in rule.offered) { "$oneTimeChoice is not offered under $rule" }
    }

    /** The one-time choices for this case, in screen order. */
    val offered: List<DecisionChoice> get() = rule.offered

    /** The one-time choice, else the one the rule makes; null while the rule asks each time. */
    val inForce: DecisionChoice? get() = oneTimeChoice ?: rule.choice
}

/**
 * The decision for a relocation whose source and target are observed in these states, or null when no rule governs
 * that case (a move, a link, anything blocked by what is there). `rules` are the saved rules; `choice` is the
 * one-time choice, which must be one this case offers.
 */
fun relocationDecision(source: PathState, target: PathState, rules: Relocation, choice: DecisionChoice?): RelocationDecision? {
    if (target != PathState.DIRECTORY) return null
    val rule = when (source) {
        PathState.ABSENT -> GoverningRule.OnlyTarget(rules.whenOnlyTargetExists)
        PathState.DIRECTORY -> GoverningRule.BothExist(BothExistRule.of(rules.whenSourceAndTargetDirectoriesExist, rules.whenAdoptingTarget))
        PathState.FILE, PathState.SYMLINK, PathState.INACCESSIBLE, PathState.OTHER -> return null
    }
    return RelocationDecision(rule, choice)
}

/** The saved rule for one case, with the one-time choices that case offers. */
sealed interface GoverningRule {
    /** The choice this rule makes, or null while it asks. */
    val choice: DecisionChoice?
    val offered: List<DecisionChoice>

    /** `when-only-target-exists`: the source is missing and the target is a directory. */
    data class OnlyTarget(val value: WhenOnlyTargetExists) : GoverningRule {
        override val choice: DecisionChoice? get() = when (value) {
            WhenOnlyTargetExists.ADOPT_TARGET -> DecisionChoice.ADOPT_TARGET
            WhenOnlyTargetExists.PROMPT -> null
        }
        override val offered: List<DecisionChoice> get() = listOf(DecisionChoice.ADOPT_TARGET)
    }

    /** The **Both exist** rule: source and target are both directories. */
    data class BothExist(val value: BothExistRule) : GoverningRule {
        override val choice: DecisionChoice? get() = value.choice
        override val offered: List<DecisionChoice> get() = BothExistRule.entries.mapNotNull { it.choice }
    }
}

/**
 * The **Both exist** rule as one value, in screen order. The file keeps it in two fields:
 * `when-adopting-target` means something only with `adopt`, so `adopting` is null for the other values, which
 * leave that field as it is.
 */
enum class BothExistRule(
    val both: WhenSourceAndTargetDirectoriesExist, val adopting: WhenAdoptingTarget?, val choice: DecisionChoice?,
) {
    ASK_EACH_TIME(WhenSourceAndTargetDirectoriesExist.PROMPT, null, null),
    KEEP_TARGET_DELETE_SOURCE(WhenSourceAndTargetDirectoriesExist.ADOPT, WhenAdoptingTarget.DISCARD_SOURCE, DecisionChoice.ADOPT_AND_DISCARD_SOURCE),
    KEEP_TARGET_ARCHIVE_SOURCE(WhenSourceAndTargetDirectoriesExist.ADOPT, WhenAdoptingTarget.ARCHIVE_SOURCE, DecisionChoice.ADOPT_AND_ARCHIVE_SOURCE),
    KEEP_TARGET_ASK_ABOUT_SOURCE(WhenSourceAndTargetDirectoriesExist.ADOPT, WhenAdoptingTarget.PROMPT, null),
    LEAVE_BOTH(WhenSourceAndTargetDirectoriesExist.LEAVE_UNCHANGED, null, DecisionChoice.LEAVE_UNCHANGED),
    DELETE_BOTH(WhenSourceAndTargetDirectoriesExist.DISCARD, null, DecisionChoice.DISCARD_BOTH);

    companion object {
        /** The value the file's two fields make. */
        fun of(both: WhenSourceAndTargetDirectoriesExist, adopting: WhenAdoptingTarget): BothExistRule =
            entries.single { it.both == both && (it.adopting == null || it.adopting == adopting) }
    }
}
