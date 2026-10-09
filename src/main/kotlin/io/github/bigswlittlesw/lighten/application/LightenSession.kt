package io.github.bigswlittlesw.lighten.application

import io.github.bigswlittlesw.lighten.config.ConfigurationPublisher
import io.github.bigswlittlesw.lighten.config.LightenFile
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * The state of one Lighten session, for both the CLI and the TUI: the current plan, the one-time choices, and the
 * apply in progress.
 */
class LightenSession(
    val configPath: Path,
    private val debugStepDelayMillis: Long = 0,
    private val evaluator: ConfigurationEvaluation = ConfigurationEvaluation(),
) {
    // This instance's monitor guards these fields. The apply worker changes them only through
    // `refreshObservationsAfterExecution`, which takes the monitor.
    private var evaluation: ConfigurationEvaluation.Evaluation = evaluator.load(configPath)
    private var reviewedExecution: ReviewedExecution? = null
    private var execution: CompletableFuture<Void?> = CompletableFuture.completedFuture(null)

    @Synchronized
    fun evaluation(): ConfigurationEvaluation.Evaluation = evaluation

    /** Checks again: reloads from disk and forgets the one-time choices, which hold for one apply only. */
    @Synchronized
    fun refresh() {
        if (isApplying()) {
            return
        }
        reviewedExecution = null
        evaluation = evaluator.load(configPath)
    }

    @Synchronized
    fun choose(sourcePath: Path, choice: DecisionChoice) {
        check(!isApplying() && applyModel() !is ApplyModel.Result) { "Replan before editing a running or retained result" }
        val loaded = checkNotNull(evaluation as? ConfigurationEvaluation.Loaded) { "No loaded configuration" }
        val chosen = evaluator.choose(loaded, sourcePath, choice)
        reviewedExecution = null
        evaluation = chosen
    }

    /**
     * Saves the one-time choice for `sourcePath` as its relocation's rule, replacing the file only while it still
     * holds the bytes this evaluation read (see [ConfigurationPublisher.replace]). The draft keeps the choice until
     * the caller checks again ([refresh]), after which the rule decides. On a throw nothing is written.
     */
    @Synchronized
    fun saveChoice(sourcePath: Path) = replace("No choice to save as a rule: $sourcePath") { it.ruleFile(sourcePath) }

    /** Moves the relocation for `sourcePath` to the ignored paths, as [saveChoice] saves: the caller checks again. */
    @Synchronized
    fun ignore(sourcePath: Path) = replace("No relocation to ignore: $sourcePath") { it.ignoredFile(sourcePath) }

    /** Takes `path` out of the ignored paths, as [saveChoice] saves: the caller checks again. */
    @Synchronized
    fun stopIgnoring(path: Path) = replace("Not ignored: $path") { it.unignoredFile(path) }

    /** Writes `edit`'s file over the one this evaluation read, only while the file still holds the bytes read. */
    private fun replace(missing: String, edit: (ConfigurationEvaluation.Loaded) -> LightenFile?) {
        check(!isApplying() && applyModel() !is ApplyModel.Result) { "Replan before editing a running or retained result" }
        val loaded = checkNotNull(evaluation as? ConfigurationEvaluation.Loaded) { "No loaded configuration" }
        val file = checkNotNull(edit(loaded)) { missing }
        // Each edit is non-null only with the file as read.
        ConfigurationPublisher().replace(configPath, file, checkNotNull(loaded.file).bytes)
    }

    @Synchronized
    fun isPlanReady(): Boolean {
        val loaded = evaluation
        return applyModel() !is ApplyModel.Running && applyModel() !is ApplyModel.Result
                && loaded is ConfigurationEvaluation.Loaded
                && !loaded.plan.hasBlockedActions()
                && !loaded.plan.hasConflicts()
    }

    @Synchronized
    fun hasConflicts(): Boolean {
        val loaded = evaluation
        return loaded is ConfigurationEvaluation.Loaded && loaded.plan.hasConflicts()
    }

    @Synchronized
    fun applyModel(): ApplyModel = reviewedExecution?.snapshot() ?: ApplyModel.Idle

    @Synchronized
    fun isApplying(): Boolean = applyModel() is ApplyModel.Running

    /** True when the apply and the reload after it are done, or when the apply ended with a bug and no reload ran. */
    @Synchronized
    fun executionSettled(): Boolean = execution.isDone

    /** An apply is running or has not settled yet ([executionSettled]); nothing else may start until it has. */
    @Synchronized
    fun isBusy(): Boolean = isApplying() || !executionSettled()

    /** Captures the current plan without reloading it or touching the filesystem. */
    @Synchronized
    fun requestApply(): Boolean {
        val loaded = evaluation
        if (!isPlanReady() || loaded !is ConfigurationEvaluation.Loaded) {
            return false
        }
        reviewedExecution = ReviewedExecution(loaded.plan, debugStepDelayMillis, loaded.draft)
        return true
    }

    @Synchronized
    fun cancelApply() {
        if (applyModel() is ApplyModel.Confirmation) {
            reviewedExecution = null
        }
    }

    /**
     * The worker never touches the terminal. Confirming again returns the same apply and does not start a second one.
     */
    @Synchronized
    fun confirmApply(
        worker: Executor = Executor { task -> Thread.ofPlatform().name("lighten-apply").start(task) },
    ): CompletableFuture<Void?> {
        if (applyModel() !is ApplyModel.Confirmation) {
            return execution
        }
        // A confirmation snapshot only comes from a captured review.
        execution = reviewedExecution!!.start(worker).thenRun(this::refreshObservationsAfterExecution)
        return execution
    }

    /** Applying forgets the one-time choices: the next plan starts from the saved rules and what is on disk now. */
    @Synchronized
    private fun refreshObservationsAfterExecution() {
        evaluation = evaluator.load(configPath)
    }

    /** On exit, waits for a running apply to finish. Interrupting it could stop it in the middle of a step. */
    fun awaitExecution() {
        val pending = synchronized(this) { execution }
        pending.join()
    }
}
