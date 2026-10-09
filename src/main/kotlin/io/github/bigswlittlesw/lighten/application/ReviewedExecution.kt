package io.github.bigswlittlesw.lighten.application

import io.github.bigswlittlesw.lighten.fs.PathText
import io.github.bigswlittlesw.lighten.reconcile.ReconciliationAction
import io.github.bigswlittlesw.lighten.reconcile.ReconciliationExecutor
import io.github.bigswlittlesw.lighten.reconcile.ReconciliationPlan
import io.github.bigswlittlesw.lighten.reconcile.RelocationPlan
import java.io.InterruptedIOException
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/** The allowed range of the debug delay that holds each step in its running state, so a person can watch it. */
internal val DEBUG_STEP_DELAY_MILLIS: LongRange = 0L..60_000L

/**
 * Applies one reviewed plan: preflight, the steps, read-only progress snapshots, and the result, which it keeps.
 * Creating it reads nothing from disk. Starting it never reloads the plan or replaces it with another.
 *
 * `choices` are the one-time choices the plan was made with; every snapshot carries them (see [ApplyModel.Reviewed]).
 */
class ReviewedExecution(
    private val plan: ReconciliationPlan, private val debugStepDelayMillis: Long = 0,
    private val choices: Map<Path, DecisionChoice> = mapOf(),
) {
    // This instance's monitor guards these fields. The worker thread publishes progress through it. When independent
    // relocations run at once, the executor's relocation threads do too. So steps change in one order.
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
     * Starts the apply at most once. Each later call returns the same completion.
     * The final snapshot is published before the completion finishes, also when the worker refuses the task.
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
                    finishWithoutExecution(plan, listOf(PathText(internalErrorMessage(bug))), false)
                    completion.completeExceptionally(bug)
                }
            }
        } catch (exception: RuntimeException) {
            // The worker refused the task: nothing ran. This is an environment failure, not a bug.
            finishWithoutExecution(plan, listOf(PathText(exception.message ?: exception.toString())), false)
            completion.complete(null)
        }
        return completion
    }

    /** Waits for a started apply to finish. It does not cancel or interrupt it, so no step stops halfway. */
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
                updateStep(ApplyModel.Step(relocation, action, ApplyModel.StepStatus.RUNNING, "Running"))
                if (action.mutatesFilesystem) {
                    pauseForVisualTesting()
                }
            }

            override fun finished(relocation: RelocationPlan, action: ReconciliationExecutor.ActionExecution) {
                updateStep(executedStep(relocation, action))
            }
        })
        val steps = result.relocations.flatMap { relocation ->
            relocation.actions.map { action -> executedStep(relocation.relocation, action) }
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
            // Delay only the apply worker, after it publishes RUNNING, so the TUI keeps animating.
            Thread.sleep(debugStepDelayMillis)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            // Throw an I/O failure, so the executor reports it as the running step's failure, not as a bug.
            throw InterruptedIOException("Interrupted during visual-test delay").apply { initCause(exception) }
        }
    }

    @Synchronized
    private fun updateStep(updated: ApplyModel.Step) {
        val running = snapshot as? ApplyModel.Running ?: return
        val steps = running.steps.map { step ->
            if (step.relocation === updated.relocation && step.action === updated.action) updated else step
        }
        snapshot = ApplyModel.Running.of(running.plan, steps, choices)
    }

    @Synchronized
    private fun finishWithoutExecution(plan: ReconciliationPlan, diagnostics: List<PathText>, stale: Boolean) {
        val current = snapshot
        val steps = (if (current is ApplyModel.Running) current.steps else pendingSteps(plan)).map { step ->
            if (step.status == ApplyModel.StepStatus.RUNNING) {
                step.copy(status = ApplyModel.StepStatus.FAILED, message = diagnostics.first().toString())
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

private fun executedStep(relocation: RelocationPlan, execution: ReconciliationExecutor.ActionExecution) = ApplyModel.Step(
    relocation, execution.action, stepStatus(execution.status), execution.message, execution.failure,
    execution.skippedSockets,
)

private fun stepStatus(status: ReconciliationExecutor.ActionStatus): ApplyModel.StepStatus = when (status) {
    ReconciliationExecutor.ActionStatus.COMPLETED -> ApplyModel.StepStatus.COMPLETED
    ReconciliationExecutor.ActionStatus.FAILED -> ApplyModel.StepStatus.FAILED
    ReconciliationExecutor.ActionStatus.PENDING -> ApplyModel.StepStatus.PENDING
}
