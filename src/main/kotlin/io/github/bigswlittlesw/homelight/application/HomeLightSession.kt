package io.github.bigswlittlesw.homelight.application

import io.github.bigswlittlesw.homelight.config.Relocation
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/** Presentation-neutral session holding active workflow state, typed draft decisions, and evaluated plans. */
// Open, like the non-final Java class: the TUI tests subclass it to stub individual methods.
open class HomeLightSession(configPath: Path, evaluator: ConfigurationEvaluation, debugStepDelayMillis: Long) {
    private val configPath: Path
    private val evaluator: ConfigurationEvaluation
    private val debugStepDelayMillis: Long

    // Guarded by this instance's monitor, as in the Java original.
    private var evaluation: ConfigurationEvaluation.Evaluation? = null
    private var discardedChoices: List<ConfigurationEvaluation.DiscardedChoice> = java.util.List.of()

    private lateinit var planModel: PlanModel
    private var reviewedExecution: ReviewedExecution? = null
    private var execution: CompletableFuture<Void?> = CompletableFuture.completedFuture(null)

    init {
        if (debugStepDelayMillis < 0 || debugStepDelayMillis > 60_000) {
            throw IllegalArgumentException("debug step delay must be between 0 and 60000 milliseconds")
        }
        this.debugStepDelayMillis = debugStepDelayMillis
        this.configPath = configPath
        this.evaluator = evaluator
        reload()
    }

    constructor(configPath: Path) : this(configPath, 0)

    constructor(configPath: Path, debugStepDelayMillis: Long) :
            this(configPath, ConfigurationEvaluation(), debugStepDelayMillis)

    open fun configPath(): Path = configPath

    @Synchronized
    open fun planModel(): PlanModel = planModel

    @Synchronized
    open fun evaluation(): ConfigurationEvaluation.Evaluation? = evaluation

    /** Choices discarded by the most recent explicit replan, retained for presentation by the caller. */
    @Synchronized
    open fun discardedChoices(): List<ConfigurationEvaluation.DiscardedChoice> = discardedChoices

    @Synchronized
    open fun refresh() {
        if (isApplying()) {
            return
        }
        reviewedExecution = null
        reload()
    }

    @Synchronized
    open fun resolveDecision(relocation: Relocation, choice: DecisionChoice) {
        choose(relocation.sourcePath, choice)
    }

    @Synchronized
    open fun choose(sourcePath: Path, choice: DecisionChoice) {
        if (isApplying() || applyModel() is ApplyModel.Result) {
            throw IllegalStateException("Replan before editing a running or retained result")
        }
        val loaded = evaluation as? ConfigurationEvaluation.Loaded
            ?: throw IllegalStateException("No loaded configuration")
        evaluation = evaluator.choose(loaded, sourcePath, choice)
        reviewedExecution = null
        reloadModels()
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
    open fun hasConflicts(): Boolean {
        val configured = planModel
        return configured is PlanModel.Configured
                && configured.plan.hasConflicts()
    }

    @Synchronized
    open fun applyModel(): ApplyModel {
        val reviewed = reviewedExecution
        return if (reviewed == null) ApplyModel.Idle() else reviewed.snapshot()
    }

    @Synchronized
    open fun isApplying(): Boolean = applyModel() is ApplyModel.Running

    /** Includes post-execution refresh and exceptional settlement, not just result publication. */
    @Synchronized
    open fun executionSettled(): Boolean = execution.isDone

    /** Captures the current plan without reloading it or touching the filesystem. */
    @Synchronized
    open fun requestApply(): Boolean {
        val configured = planModel
        if (!isPlanReady() || configured !is PlanModel.Configured) {
            return false
        }
        reviewedExecution = ReviewedExecution(configured.plan, debugStepDelayMillis)
        return true
    }

    @Synchronized
    open fun cancelApply() {
        if (applyModel() is ApplyModel.Confirmation) {
            reviewedExecution = null
        }
    }

    open fun confirmApply(): CompletableFuture<Void?> =
        confirmApply { task -> Thread.ofPlatform().name("homelight-apply").start(task) }

    /** The worker never accesses terminal state. Repeated confirmation cannot schedule another execution. */
    @Synchronized
    open fun confirmApply(worker: Executor): CompletableFuture<Void?> {
        if (applyModel() !is ApplyModel.Confirmation) {
            return execution
        }
        execution = reviewedExecution!!.start(worker).thenRun(this::refreshObservationsAfterExecution)
        return execution
    }

    @Synchronized
    private fun refreshObservationsAfterExecution() {
        val current = evaluator.load(configPath)
        planModel = PlanWorkflow.from(current)
    }

    /** Terminal shutdown waits for an active mutation sequence rather than interrupting it mid-action. */
    open fun awaitExecution() {
        val pending: CompletableFuture<Void?>
        synchronized(this) {
            pending = execution
        }
        pending.join()
    }

    private fun reload() {
        val current = evaluation
        if (current == null) {
            evaluation = evaluator.load(configPath)
        } else {
            val replanned = evaluator.replan(current)
            evaluation = replanned.evaluation
            discardedChoices = replanned.discardedChoices
        }
        reloadModels()
    }

    private fun reloadModels() {
        this.planModel = PlanWorkflow.from(evaluation!!)
    }
}
