package io.github.bigswlittlesw.homelight.application

import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationExecutor.ExecutionResult
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlan
import io.github.bigswlittlesw.homelight.reconcile.RelocationPlan
import java.util.Objects
import java.util.Optional

/** Immutable snapshots of one reviewed plan's confirmation and execution lifecycle. */
sealed interface ApplyModel {
    // Idle, Running and Result are plain classes rather than `@JvmRecord data class`es: a Kotlin data
    // class needs a component, and Running and Result copy their lists. Equality and `toString` match
    // the records they replace.

    class Idle : ApplyModel {
        override fun equals(other: Any?): Boolean = other is Idle

        override fun hashCode(): Int = 0

        override fun toString(): String = "Idle[]"
    }

    @JvmRecord
    data class Confirmation(val plan: ReconciliationPlan) : ApplyModel

    class Running(plan: ReconciliationPlan, steps: List<Step>) : ApplyModel {
        @get:JvmName("plan")
        val plan: ReconciliationPlan = plan

        @get:JvmName("steps")
        val steps: List<Step> = java.util.List.copyOf(steps)

        override fun equals(other: Any?): Boolean = other is Running
                && plan == other.plan
                && steps == other.steps

        override fun hashCode(): Int = Objects.hash(plan, steps)

        override fun toString(): String = "Running[plan=$plan, steps=$steps]"
    }

    /** A result remains retained until explicit re-planning. Preflight failures have no execution. */
    class Result(
        plan: ReconciliationPlan, steps: List<Step>, execution: Optional<ExecutionResult>,
        diagnostics: List<String>, stale: Boolean,
    ) : ApplyModel {
        @get:JvmName("plan")
        val plan: ReconciliationPlan = plan

        @get:JvmName("steps")
        val steps: List<Step> = java.util.List.copyOf(steps)

        @get:JvmName("execution")
        val execution: Optional<ExecutionResult> = execution

        @get:JvmName("diagnostics")
        val diagnostics: List<String> = java.util.List.copyOf(diagnostics)

        @get:JvmName("stale")
        val stale: Boolean = stale

        fun succeeded(): Boolean = execution.map(ExecutionResult::succeeded).orElse(false)

        override fun equals(other: Any?): Boolean = other is Result
                && plan == other.plan
                && steps == other.steps
                && execution == other.execution
                && diagnostics == other.diagnostics
                && stale == other.stale

        override fun hashCode(): Int = Objects.hash(plan, steps, execution, diagnostics, stale)

        override fun toString(): String =
            "Result[plan=$plan, steps=$steps, execution=$execution, diagnostics=$diagnostics, stale=$stale]"
    }

    @JvmRecord
    data class Step(
        val relocation: RelocationPlan, val action: ReconciliationAction, val status: StepStatus,
        val message: String,
    )

    enum class StepStatus { PENDING, RUNNING, COMPLETED, FAILED }
}
