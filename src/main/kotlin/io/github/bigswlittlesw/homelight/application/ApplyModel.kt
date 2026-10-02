package io.github.bigswlittlesw.homelight.application

import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationExecutor.ExecutionResult
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlan
import io.github.bigswlittlesw.homelight.reconcile.RelocationPlan

/**
 * Immutable snapshots of one reviewed plan's confirmation and execution lifecycle.
 *
 * Snapshots pass from the execution worker to the presentation thread, so their lists are
 * unmodifiable JDK copies made by each `of`.
 */
sealed interface ApplyModel {
    data object Idle : ApplyModel

    data class Confirmation(val plan: ReconciliationPlan) : ApplyModel

    @ConsistentCopyVisibility
    data class Running private constructor(val plan: ReconciliationPlan, val steps: List<Step>) : ApplyModel {
        companion object {
            fun of(plan: ReconciliationPlan, steps: List<Step>): Running = Running(plan, steps.toList())
        }
    }

    /** A result remains retained until explicit re-planning. Preflight failures have no execution. */
    @ConsistentCopyVisibility
    data class Result private constructor(
        val plan: ReconciliationPlan, val steps: List<Step>, val execution: ExecutionResult?,
        val diagnostics: List<String>, val stale: Boolean,
    ) : ApplyModel {
        fun succeeded(): Boolean = execution?.succeeded() ?: false

        companion object {
            fun of(
                plan: ReconciliationPlan, steps: List<Step>, execution: ExecutionResult?,
                diagnostics: List<String>, stale: Boolean,
            ): Result = Result(plan, steps.toList(), execution, diagnostics.toList(), stale)
        }
    }

    data class Step(
        val relocation: RelocationPlan, val action: ReconciliationAction, val status: StepStatus,
        val message: String,
    )

    enum class StepStatus { PENDING, RUNNING, COMPLETED, FAILED }
}
