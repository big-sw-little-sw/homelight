package io.github.bigswlittlesw.homelight.application

import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationExecutor
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlan
import io.github.bigswlittlesw.homelight.reconcile.RelocationPlan
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * One captured plan's preflight, execution, immutable progress, and retained result.
 * Capturing performs no I/O; starting never reloads or substitutes the plan.
 */
class ReviewedExecution(plan: ReconciliationPlan, debugStepDelayMillis: Long) {
    private val plan: ReconciliationPlan = plan
    private val debugStepDelayMillis: Long

    // Guarded by this instance's monitor, as in the Java original.
    private var snapshot: ApplyModel
    private var completion: CompletableFuture<Void?>? = null

    init {
        if (plan.hasBlockedActions() || plan.hasConflicts()) {
            throw IllegalArgumentException("Review requires a resolved, unblocked plan")
        }
        if (debugStepDelayMillis < 0 || debugStepDelayMillis > 60_000) {
            throw IllegalArgumentException("debug step delay must be between 0 and 60000 milliseconds")
        }
        this.debugStepDelayMillis = debugStepDelayMillis
        snapshot = ApplyModel.Confirmation(plan)
    }

    constructor(plan: ReconciliationPlan) : this(plan, 0)

    @Synchronized
    fun snapshot(): ApplyModel = snapshot

    /**
     * Schedules at most once, returning the same completion on every subsequent call.
     * The terminal snapshot is published before completion settles, including scheduling rejection.
     */
    @Synchronized
    fun start(worker: Executor): CompletableFuture<Void?> {
        val existing = completion
        if (existing != null) {
            return existing
        }
        val completion = CompletableFuture<Void?>()
        this.completion = completion
        snapshot = ApplyModel.Running(plan, pendingSteps(plan))
        try {
            worker.execute {
                try {
                    executeReviewed(plan)
                    completion.complete(null)
                } catch (error: Error) {
                    finishWithoutExecution(plan, java.util.List.of(message(error)), false)
                    completion.completeExceptionally(error)
                }
            }
        } catch (exception: RuntimeException) {
            finishWithoutExecution(plan, java.util.List.of(message(exception)), false)
            completion.complete(null)
        }
        return completion
    }

    /** Waits for started work without cancelling or interrupting its mutation sequence. */
    fun awaitExecution() {
        val pending: CompletableFuture<Void?>?
        synchronized(this) {
            pending = completion
        }
        pending?.join()
    }

    private fun executeReviewed(plan: ReconciliationPlan) {
        try {
            val executor = ReconciliationExecutor()
            val drift = executor.preflight(plan)
            if (!drift.isEmpty()) {
                finishWithoutExecution(plan, drift.stream().map { diagnostic -> diagnostic.message }.toList(), true)
                return
            }
            val result = executor.execute(plan, object : ReconciliationExecutor.ProgressListener {
                override fun started(relocation: RelocationPlan, action: ReconciliationAction) {
                    updateStep(relocation, action, ApplyModel.StepStatus.RUNNING, "Running")
                    if (action.mutatesFilesystem()) {
                        pauseForVisualTesting()
                    }
                }

                override fun finished(relocation: RelocationPlan, action: ReconciliationExecutor.ActionExecution) {
                    updateStep(relocation, action.action, stepStatus(action.status), action.message)
                }
            })
            val steps = result.relocations.stream().flatMap { relocation ->
                relocation.actions.stream()
                    .map { action ->
                        ApplyModel.Step(
                            relocation.relocation, action.action,
                            stepStatus(action.status), action.message,
                        )
                    }
            }.toList()
            val stale = result.relocations.stream().flatMap { relocation -> relocation.actions.stream() }
                .anyMatch(ReconciliationExecutor.ActionExecution::stateDrift)
            synchronized(this) {
                snapshot = ApplyModel.Result(plan, steps, Optional.of(result), java.util.List.of(), stale)
            }
        } catch (exception: RuntimeException) {
            finishWithoutExecution(plan, java.util.List.of(message(exception)), false)
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
        val running = snapshot
        if (running is ApplyModel.Running) {
            val steps = running.steps.stream().map { step ->
                if (step.relocation === relocation && step.action === action)
                    ApplyModel.Step(relocation, action, status, message) else step
            }.toList()
            snapshot = ApplyModel.Running(running.plan, steps)
        }
    }

    @Synchronized
    private fun finishWithoutExecution(plan: ReconciliationPlan, diagnostics: List<String>, stale: Boolean) {
        val current = snapshot
        var steps = if (current is ApplyModel.Running) current.steps else pendingSteps(plan)
        steps = steps.stream().map { step ->
            if (step.status == ApplyModel.StepStatus.RUNNING)
                ApplyModel.Step(step.relocation, step.action, ApplyModel.StepStatus.FAILED, diagnostics.first())
            else step
        }.toList()
        snapshot = ApplyModel.Result(plan, steps, Optional.empty(), diagnostics, stale)
    }

    private companion object {
        fun pendingSteps(plan: ReconciliationPlan): List<ApplyModel.Step> =
            plan.relocations.stream().flatMap { relocation ->
                relocation.actions.stream()
                    .map { action -> ApplyModel.Step(relocation, action, ApplyModel.StepStatus.PENDING, "Not started") }
            }.toList()

        fun stepStatus(status: ReconciliationExecutor.ActionStatus): ApplyModel.StepStatus = when (status) {
            ReconciliationExecutor.ActionStatus.COMPLETED -> ApplyModel.StepStatus.COMPLETED
            ReconciliationExecutor.ActionStatus.FAILED -> ApplyModel.StepStatus.FAILED
            ReconciliationExecutor.ActionStatus.PENDING -> ApplyModel.StepStatus.PENDING
        }

        fun message(exception: Throwable): String =
            if (exception.message == null) exception.toString() else exception.message!!
    }
}
