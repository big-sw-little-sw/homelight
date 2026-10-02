package io.github.bigswlittlesw.homelight.application

import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * Presentation-neutral session holding active workflow state, typed draft decisions, and evaluated plans.
 *
 * Open only for the members TUI tests override to stub session state.
 */
open class HomeLightSession(
    val configPath: Path,
    private val debugStepDelayMillis: Long = 0,
    private val evaluator: ConfigurationEvaluation = ConfigurationEvaluation(),
) {
    // Guarded by this instance's monitor. The apply worker touches them only through
    // `refreshObservationsAfterExecution`, which takes the monitor.
    private var evaluation: ConfigurationEvaluation.Evaluation = evaluator.load(configPath)
    private var discardedChoices: List<ConfigurationEvaluation.DiscardedChoice> = listOf()
    private var planModel: PlanModel = planModel(evaluation)
    private var reviewedExecution: ReviewedExecution? = null
    private var execution: CompletableFuture<Void?> = CompletableFuture.completedFuture(null)

    @Synchronized
    open fun planModel(): PlanModel = planModel

    @Synchronized
    fun evaluation(): ConfigurationEvaluation.Evaluation = evaluation

    /** Choices discarded by the most recent explicit replan, retained for presentation by the caller. */
    @Synchronized
    fun discardedChoices(): List<ConfigurationEvaluation.DiscardedChoice> = discardedChoices

    @Synchronized
    fun refresh() {
        if (isApplying()) {
            return
        }
        reviewedExecution = null
        val replanned = evaluator.replan(evaluation)
        discardedChoices = replanned.discardedChoices
        replaceEvaluation(replanned.evaluation)
    }

    @Synchronized
    fun choose(sourcePath: Path, choice: DecisionChoice) {
        check(!isApplying() && applyModel() !is ApplyModel.Result) { "Replan before editing a running or retained result" }
        val loaded = checkNotNull(evaluation as? ConfigurationEvaluation.Loaded) { "No loaded configuration" }
        val chosen = evaluator.choose(loaded, sourcePath, choice)
        reviewedExecution = null
        replaceEvaluation(chosen)
    }

    @Synchronized
    open fun isPlanReady(): Boolean {
        val configured = planModel
        return applyModel() !is ApplyModel.Running && applyModel() !is ApplyModel.Result
                && configured is PlanModel.Configured
                && !configured.plan.hasBlockedActions()
                && !configured.plan.hasConflicts()
    }

    @Synchronized
    fun hasConflicts(): Boolean {
        val configured = planModel
        return configured is PlanModel.Configured && configured.plan.hasConflicts()
    }

    @Synchronized
    open fun applyModel(): ApplyModel = reviewedExecution?.snapshot() ?: ApplyModel.Idle

    @Synchronized
    fun isApplying(): Boolean = applyModel() is ApplyModel.Running

    /** Includes post-execution refresh and exceptional settlement, not just result publication. */
    @Synchronized
    open fun executionSettled(): Boolean = execution.isDone

    /** Captures the current plan without reloading it or touching the filesystem. */
    @Synchronized
    fun requestApply(): Boolean {
        val configured = planModel
        if (!isPlanReady() || configured !is PlanModel.Configured) {
            return false
        }
        reviewedExecution = ReviewedExecution(configured.plan, debugStepDelayMillis)
        return true
    }

    @Synchronized
    fun cancelApply() {
        if (applyModel() is ApplyModel.Confirmation) {
            reviewedExecution = null
        }
    }

    /** The worker never accesses terminal state. Repeated confirmation cannot schedule another execution. */
    @Synchronized
    fun confirmApply(
        worker: Executor = Executor { task -> Thread.ofPlatform().name("homelight-apply").start(task) },
    ): CompletableFuture<Void?> {
        if (applyModel() !is ApplyModel.Confirmation) {
            return execution
        }
        // A confirmation snapshot only comes from a captured review.
        execution = reviewedExecution!!.start(worker).thenRun(this::refreshObservationsAfterExecution)
        return execution
    }

    @Synchronized
    private fun refreshObservationsAfterExecution() {
        planModel = planModel(evaluator.load(configPath))
    }

    /** Terminal shutdown waits for an active mutation sequence rather than interrupting it mid-action. */
    open fun awaitExecution() {
        val pending = synchronized(this) { execution }
        pending.join()
    }

    private fun replaceEvaluation(next: ConfigurationEvaluation.Evaluation) {
        evaluation = next
        planModel = planModel(next)
    }
}
