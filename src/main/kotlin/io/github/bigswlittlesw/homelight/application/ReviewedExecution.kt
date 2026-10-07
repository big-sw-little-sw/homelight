package io.github.bigswlittlesw.homelight.application

import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationExecutor
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlan
import io.github.bigswlittlesw.homelight.reconcile.RelocationPlan
import java.io.InterruptedIOException
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/** The accepted range of the visual-test delay that holds each action in its running state. */
internal val DEBUG_STEP_DELAY_MILLIS: LongRange = 0L..60_000L

/**
 * One captured plan's preflight, execution, immutable progress, and retained result.
 * Capturing performs no I/O; starting never reloads or substitutes the plan.
 *
 * `choices` are the one-time choices the plan was made with; every snapshot carries them (see [ApplyModel.Reviewed]).
 */
class ReviewedExecution(
    private val plan: ReconciliationPlan, private val debugStepDelayMillis: Long = 0,
    private val choices: Map<Path, DecisionChoice> = mapOf(),
) {
    // Guarded by this instance's monitor. The worker thread, and the executor's relocation threads when independent
    // relocations run at once, publish progress through it, so steps change in one serial order.
    private var snapshot: ApplyModel
    private var completion: CompletableFuture<Void?>? = null

    init {
        require(!plan.hasBlockedActions() && !plan.hasConflicts()) { "Review requires a resolved, unblocked plan" }
        require(debugStepDelayMillis in DEBUG_STEP_DELAY_MILLIS) {
            "debug step delay must be between ${DEBUG_STEP_DELAY_MILLIS.first} and ${DEBUG_STEP_DELAY_MILLIS.last} milliseconds"
        }
        snapshot = ApplyModel.Confirmation.of(plan, choices)
    }

    @Synchronized
    fun snapshot(): ApplyModel = snapshot

    /**
     * Schedules at most once, returning the same completion on every subsequent call.
     * The terminal snapshot is published before completion settles, including scheduling rejection.
     *
     * The executor reports I/O and environment failures as failed actions, so anything that escapes it is a bug:
     * the result shows [internalErrorMessage] and completion fails with the bug, so callers report it too.
     */
    fun start(worker: Executor): CompletableFuture<Void?> {
        val completion = synchronized(this) {
            this.completion?.let { return it }
            snapshot = ApplyModel.Running.of(plan, pendingSteps(plan), choices)
            CompletableFuture<Void?>().also { this.completion = it }
        }
        // Scheduled outside the monitor: a direct executor (the CLI's) runs the plan on this thread, and relocation
        // threads publish progress through the monitor, so holding it here would deadlock a concurrent plan.
        try {
            worker.execute {
                try {
                    executeReviewed(plan)
                    completion.complete(null)
                } catch (bug: Throwable) {
                    finishWithoutExecution(plan, listOf(internalErrorMessage(bug)), false)
                    completion.completeExceptionally(bug)
                }
            }
        } catch (exception: RuntimeException) {
            // Scheduling rejection: nothing ran, and it is an environment failure rather than a bug.
            finishWithoutExecution(plan, listOf(exception.message ?: exception.toString()), false)
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
            snapshot = ApplyModel.Result.of(plan, steps, result, listOf(), stale, choices)
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
            // An I/O failure, so the executor reports it as the running action's failure rather than a bug.
            throw InterruptedIOException("Interrupted during visual-test delay").apply { initCause(exception) }
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
        snapshot = ApplyModel.Running.of(running.plan, steps, choices)
    }

    @Synchronized
    private fun finishWithoutExecution(plan: ReconciliationPlan, diagnostics: List<String>, stale: Boolean) {
        val current = snapshot
        val steps = (if (current is ApplyModel.Running) current.steps else pendingSteps(plan)).map { step ->
            if (step.status == ApplyModel.StepStatus.RUNNING) {
                step.copy(status = ApplyModel.StepStatus.FAILED, message = diagnostics.first())
            } else step
        }
        snapshot = ApplyModel.Result.of(plan, steps, null, diagnostics, stale, choices)
    }
}

/** Every action of `plan`, not started: what Review shows before confirmation and what execution starts from. */
internal fun pendingSteps(plan: ReconciliationPlan): List<ApplyModel.Step> =
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
