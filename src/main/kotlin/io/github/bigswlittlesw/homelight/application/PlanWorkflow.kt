package io.github.bigswlittlesw.homelight.application

import io.github.bigswlittlesw.homelight.config.ConfigurationLoader
import io.github.bigswlittlesw.homelight.fs.PathInspector
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlanner
import java.nio.file.Path

/** Adapts shared evaluation to the existing plan presentation. */
class PlanWorkflow(
    loader: ConfigurationLoader = ConfigurationLoader(),
    inspector: PathInspector = PathInspector(),
    planner: ReconciliationPlanner = ReconciliationPlanner(),
) {
    private val evaluator = ConfigurationEvaluation(loader, inspector, planner)

    fun loadPlan(configPath: Path): PlanModel = from(evaluator.load(configPath))

    companion object {
        internal fun from(evaluation: ConfigurationEvaluation.Evaluation): PlanModel = when (evaluation) {
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
                        // Evaluation records choices, possibly none, for every configured source.
                        evaluation.availableChoices.getValue(relocation.sourcePath.toAbsolutePath().normalize()),
                    )
                }
                PlanModel.Configured.of(
                    evaluation.configPath, evaluation.savedConfiguration.targetRoot,
                    plan, items, PlanSummary.from(items),
                )
            }
        }
    }
}
