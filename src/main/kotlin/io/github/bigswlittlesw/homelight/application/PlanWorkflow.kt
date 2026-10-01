package io.github.bigswlittlesw.homelight.application

import io.github.bigswlittlesw.homelight.config.ConfigurationLoader
import io.github.bigswlittlesw.homelight.fs.PathInspector
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlanner
import java.nio.file.Path

/** Adapts shared evaluation to the existing plan presentation. */
// Open, like the non-final Java class.
open class PlanWorkflow(loader: ConfigurationLoader, inspector: PathInspector, planner: ReconciliationPlanner) {
    private val evaluator: ConfigurationEvaluation = ConfigurationEvaluation(loader, inspector, planner)

    constructor() : this(ConfigurationLoader(), PathInspector(), ReconciliationPlanner())

    open fun loadPlan(configPath: Path): PlanModel = from(evaluator.load(configPath))

    companion object {
        // Package-private in Java; the Java tests call it, so it stays static and unmangled.
        @JvmStatic
        fun from(evaluation: ConfigurationEvaluation.Evaluation): PlanModel = when (evaluation) {
            is ConfigurationEvaluation.Unconfigured -> PlanModel.Unconfigured(evaluation.configPath)
            is ConfigurationEvaluation.Missing -> PlanModel.Invalid(evaluation.configPath, evaluation.message)
            is ConfigurationEvaluation.Invalid -> PlanModel.Invalid(evaluation.configPath, evaluation.message)
            is ConfigurationEvaluation.Loaded -> {
                val items = ArrayList<PlanRelocationItem>()
                val plan = evaluation.plan
                for (i in evaluation.observations.indices) {
                    val state = evaluation.observations[i]
                    val relocationPlan = plan.relocations[i]
                    val relocation = relocationPlan.relocation
                    items.add(
                        PlanRelocationItem(
                            relocation, state.source, state.target, relocationPlan,
                            state.source.sourceStateForTarget(relocation.targetPath),
                            evaluation.availableChoices[relocation.sourcePath.toAbsolutePath().normalize()]!!,
                        ),
                    )
                }
                PlanModel.Configured(
                    evaluation.configPath, evaluation.savedConfiguration.targetRoot,
                    plan, items, PlanSummary.from(items),
                )
            }
        }
    }
}
