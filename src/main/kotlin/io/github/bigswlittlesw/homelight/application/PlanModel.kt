package io.github.bigswlittlesw.homelight.application

import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlan
import java.nio.file.Path

/** State representation for the dry-run plan workflow. */
sealed interface PlanModel {
    val configPath: Path

    /** Indicates that no configuration exists at the default path. */
    data class Unconfigured(override val configPath: Path) : PlanModel

    /** Indicates active configuration with inspected relocations, dry-run actions, and resolutions. */
    @ConsistentCopyVisibility
    data class Configured private constructor(
        override val configPath: Path,
        val targetRoot: Path,
        val plan: ReconciliationPlan,
        val items: List<PlanRelocationItem>,
        val summary: PlanSummary,
    ) : PlanModel {
        companion object {
            /** Orders `items` by urgency, then source path, in an unmodifiable copy. */
            fun of(
                configPath: Path, targetRoot: Path, plan: ReconciliationPlan,
                items: List<PlanRelocationItem>, summary: PlanSummary,
            ): Configured = Configured(
                configPath, targetRoot, plan,
                java.util.List.copyOf(items.sortedWith(BY_URGENCY_AND_PATH)), summary,
            )
        }
    }

    /** Indicates that configuration could not be loaded, parsed, or planned. */
    data class Invalid(override val configPath: Path, val message: String) : PlanModel
}

private val BY_URGENCY_AND_PATH: Comparator<PlanRelocationItem> =
    compareBy({ it.badge().priority }, { it.relocation.sourcePath.toString() })

internal fun planModel(evaluation: ConfigurationEvaluation.Evaluation): PlanModel = when (evaluation) {
    is ConfigurationEvaluation.Unconfigured -> PlanModel.Unconfigured(evaluation.configPath)
    is ConfigurationEvaluation.Missing -> PlanModel.Invalid(evaluation.configPath, evaluation.message)
    is ConfigurationEvaluation.Invalid -> PlanModel.Invalid(evaluation.configPath, evaluation.message)
    is ConfigurationEvaluation.Loaded -> {
        val plan = evaluation.plan
        val items = evaluation.observations.mapIndexed { i, state ->
            val relocationPlan = plan.relocations[i]
            val relocation = relocationPlan.relocation
            PlanRelocationItem(
                relocation, state.source, state.target, relocationPlan,
                state.source.sourceStateForTarget(relocation.targetPath),
                evaluation.choicesFor(relocation.sourcePath),
            )
        }
        PlanModel.Configured.of(
            evaluation.configPath, evaluation.savedConfiguration.targetRoot,
            plan, items, PlanSummary.from(items),
        )
    }
}
