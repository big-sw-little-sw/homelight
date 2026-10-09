package io.github.bigswlittlesw.lighten.application

import io.github.bigswlittlesw.lighten.fs.PathText
import io.github.bigswlittlesw.lighten.reconcile.ActionFailure
import io.github.bigswlittlesw.lighten.reconcile.ReconciliationAction
import io.github.bigswlittlesw.lighten.reconcile.ReconciliationExecutor.ExecutionResult
import io.github.bigswlittlesw.lighten.reconcile.ReconciliationPlan
import io.github.bigswlittlesw.lighten.reconcile.RelocationPlan
import java.nio.file.Path

/**
 * Read-only snapshots of one reviewed plan, from confirmation through apply to its result.
 *
 * The apply worker hands snapshots to the UI thread. Each `of` copies its collections, so a snapshot never changes
 * after it is made.
 */
sealed interface ApplyModel {
    data object Idle : ApplyModel

    /** Every state after a plan has been put up for review. */
    sealed interface Reviewed : ApplyModel {
        val plan: ReconciliationPlan

        /**
         * The one-time choices `plan` was made with, by normalized source path. Applying makes the session forget
         * them, so the snapshot keeps them for Results.
         */
        val choices: Map<Path, DecisionChoice>

        /** The one-time choice for the relocation at `source`, or null when its saved rule decided. */
        fun choice(source: Path): DecisionChoice? = choices[source.toAbsolutePath().normalize()]
    }

    @ConsistentCopyVisibility
    data class Confirmation private constructor(
        override val plan: ReconciliationPlan, override val choices: Map<Path, DecisionChoice>,
    ) : Reviewed {
        companion object {
            fun of(plan: ReconciliationPlan, choices: Map<Path, DecisionChoice> = mapOf()): Confirmation =
                Confirmation(plan, choices.toMap())
        }
    }

    @ConsistentCopyVisibility
    data class Running private constructor(
        override val plan: ReconciliationPlan, val steps: List<Step>, override val choices: Map<Path, DecisionChoice>,
    ) : Reviewed {
        companion object {
            fun of(plan: ReconciliationPlan, steps: List<Step>, choices: Map<Path, DecisionChoice> = mapOf()): Running =
                Running(plan, steps.toList(), choices.toMap())
        }
    }

    /** A result stays until the user checks again. When preflight fails, nothing runs, so `execution` is null. */
    @ConsistentCopyVisibility
    data class Result private constructor(
        override val plan: ReconciliationPlan, val steps: List<Step>, val execution: ExecutionResult?,
        val diagnostics: List<PathText>, val stale: Boolean, override val choices: Map<Path, DecisionChoice>,
    ) : Reviewed {
        fun succeeded(): Boolean = execution?.succeeded() ?: false

        companion object {
            fun of(
                plan: ReconciliationPlan, steps: List<Step>, execution: ExecutionResult?,
                diagnostics: List<PathText>, stale: Boolean, choices: Map<Path, DecisionChoice> = mapOf(),
            ): Result = Result(plan, steps.toList(), execution, diagnostics.toList(), stale, choices.toMap())
        }
    }

    /**
     * [failure] says why a failed step failed, when the executor knows; [message] is then its own text. A completed
     * copy step lists the sockets it did not copy in [skippedSockets].
     */
    data class Step(
        val relocation: RelocationPlan, val action: ReconciliationAction, val status: StepStatus,
        val message: String, val failure: ActionFailure? = null, val skippedSockets: List<Path> = listOf(),
    )

    enum class StepStatus { PENDING, RUNNING, COMPLETED, FAILED }
}
