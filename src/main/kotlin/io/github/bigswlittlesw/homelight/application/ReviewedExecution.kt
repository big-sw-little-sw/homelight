package io.github.bigswlittlesw.homelight.application

import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationExecutor
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlan
import io.github.bigswlittlesw.homelight.reconcile.RelocationPlan
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * One captured plan's preflight, execution, immutable progress, and retained result.
 * Capturing performs no I/O; starting never reloads or substitutes the plan.
 */
class ReviewedExecution(private val plan: ReconciliationPlan, private val debugStepDelayMillis: Long = 0) {
    // Guarded by this instance's monitor. The worker thread publishes progress through it.
    private var snapshot: ApplyModel
    private var completion: CompletableFuture<Void?>? = null

    init {
        require(!plan.hasBlockedActions() && !plan.hasConflicts()) { "Review requires a resolved, unblocked plan" }
        require(debugStepDelayMillis in 0L..60_000L) { "debug step delay must be between 0 and 60000 milliseconds" }
        snapshot = ApplyModel.Confirmation(plan)
    }

    @Synchronized
    fun snapshot(): ApplyModel = snapshot

    /**
     * Schedules at most once, returning the same completion on every subsequent call.
     * The terminal snapshot is published before completion settles, including scheduling rejection.
     */
    @Synchronized
    fun start(worker: Executor): CompletableFuture<Void?> {
        completion?.let { return it }
        val completion = CompletableFuture<Void?>()
        this.completion = completion
        snapshot = ApplyModel.Running.of(plan, pendingSteps(plan))
        try {
            worker.execute {
                try {
                    executeReviewed(plan)
                    completion.complete(null)
                } catch (error: Error) {
                    finishWithoutExecution(plan, listOf(message(error)), false)
                    completion.completeExceptionally(error)
                }
            }
        } catch (exception: RuntimeException) {
            finishWithoutExecution(plan, listOf(message(exception)), false)
            completion.complete(null)
        }
        return completion
    }

    /** Waits for started work without cancelling or interrupting its mutation sequence. */
    fun awaitExecution() {
        val pending = synchronized(this) { completion }
        pending?.join()
    }

    private fun executeReviewed(plan: ReconciliationPlan) {
        try {
            val executor = ReconciliationExecutor()
            val drift = executor.preflight(plan)
            if (drift.isNotEmpty()) {
                finishWithoutExecution(plan, drift.map { it.message }, true)
                return
            }
            val result = executor.execute(plan, object : ReconciliationExecutor.ProgressListener {
                override fun started(relocation: RelocationPlan, action: ReconciliationAction) {
                    updateStep(relocation, action, ApplyModel.StepStatus.RUNNING, "Running")
                    if (action.mutatesFilesystem) {
                        pauseForVisualTesting()
                    }
                }

                override fun finished(relocation: RelocationPlan, action: ReconciliationExecutor.ActionExecution) {
                    updateStep(relocation, action.action, stepStatus(action.status), action.message)
                }
            })
            val steps = result.relocations.flatMap { relocation ->
                relocation.actions.map { action ->
                    ApplyModel.Step(relocation.relocation, action.action, stepStatus(action.status), action.message)
                }
            }
            val stale = result.relocations.any { relocation -> relocation.actions.any { it.stateDrift } }
            synchronized(this) {
                snapshot = ApplyModel.Result.of(plan, steps, result, listOf(), stale)
            }
        } catch (exception: RuntimeException) {
            finishWithoutExecution(plan, listOf(message(exception)), false)
        }
    }

    private fun pauseForVisualTesting() {
        if (debugStepDelayMillis == 0L) {
            return
        }
        try {
            // Delay only the execution worker after publishing RUNNING, so the TUI keeps animating.
            Thread.sleep(debugStepDelayMillis)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted during visual-test delay", exception)
        }
    }

    @Synchronized
    private fun updateStep(
        relocation: RelocationPlan, action: ReconciliationAction,
        status: ApplyModel.StepStatus, message: String,
    ) {
        val running = snapshot as? ApplyModel.Running ?: return
        val steps = running.steps.map { step ->
            if (step.relocation === relocation && step.action === action) {
                ApplyModel.Step(relocation, action, status, message)
            } else step
        }
        snapshot = ApplyModel.Running.of(running.plan, steps)
    }

    @Synchronized
    private fun finishWithoutExecution(plan: ReconciliationPlan, diagnostics: List<String>, stale: Boolean) {
        val current = snapshot
        val steps = (if (current is ApplyModel.Running) current.steps else pendingSteps(plan)).map { step ->
            if (step.status == ApplyModel.StepStatus.RUNNING) {
                step.copy(status = ApplyModel.StepStatus.FAILED, message = diagnostics.first())
            } else step
        }
        snapshot = ApplyModel.Result.of(plan, steps, null, diagnostics, stale)
    }
}

private fun pendingSteps(plan: ReconciliationPlan): List<ApplyModel.Step> =
    plan.relocations.flatMap { relocation ->
        relocation.actions.map { action ->
            ApplyModel.Step(relocation, action, ApplyModel.StepStatus.PENDING, "Not started")
        }
    }

private fun stepStatus(status: ReconciliationExecutor.ActionStatus): ApplyModel.StepStatus = when (status) {
    ReconciliationExecutor.ActionStatus.COMPLETED -> ApplyModel.StepStatus.COMPLETED
    ReconciliationExecutor.ActionStatus.FAILED -> ApplyModel.StepStatus.FAILED
    ReconciliationExecutor.ActionStatus.PENDING -> ApplyModel.StepStatus.PENDING
}

private fun message(exception: Throwable): String = exception.message ?: exception.toString()
