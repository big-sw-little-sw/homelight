package io.github.bigswlittlesw.lighten.application

import io.github.bigswlittlesw.lighten.config.ConfigurationException
import io.github.bigswlittlesw.lighten.config.ConfigurationLoader
import io.github.bigswlittlesw.lighten.config.LightenConfiguration
import io.github.bigswlittlesw.lighten.config.LightenFile
import io.github.bigswlittlesw.lighten.config.LoadedFile
import io.github.bigswlittlesw.lighten.config.InvalidConfigurationException
import io.github.bigswlittlesw.lighten.config.WhenOnlyTargetExists
import io.github.bigswlittlesw.lighten.fs.PathInspector
import io.github.bigswlittlesw.lighten.fs.PathObservation
import io.github.bigswlittlesw.lighten.fs.PathState
import io.github.bigswlittlesw.lighten.fs.PathText
import io.github.bigswlittlesw.lighten.reconcile.ReconciliationAction
import io.github.bigswlittlesw.lighten.reconcile.ReconciliationPlan
import io.github.bigswlittlesw.lighten.reconcile.ReconciliationPlanner
import io.github.bigswlittlesw.lighten.reconcile.RelocationPlan
import io.github.bigswlittlesw.lighten.reconcile.RelocationState
import io.github.bigswlittlesw.lighten.reconcile.inspectRelocations
import java.nio.file.Files
import java.nio.file.Path

/**
 * Loads and inspects once; draft choices replan solely from the retained observations.
 * A draft lives only in one [Loaded]: any fresh [load] starts without one.
 * This bounded inspection pass is not an atomic filesystem snapshot.
 *
 * `inspect` and `plan` are functions so tests can inject a bug into either.
 */
class ConfigurationEvaluation(
    private val loader: ConfigurationLoader = ConfigurationLoader(),
    private val inspect: (Path) -> PathObservation = PathInspector()::inspect,
    private val plan: (List<RelocationState>) -> ReconciliationPlan = ReconciliationPlanner()::plan,
) {
    sealed interface Evaluation {
        val configPath: Path
    }

    data class Missing(override val configPath: Path, val message: PathText) : Evaluation

    /** Legacy default-path behavior: no regular configuration file, including a directory at that path. */
    data class Unconfigured(override val configPath: Path) : Evaluation

    /** `line` is the line at fault, or 0 when no one line is (see [InvalidConfigurationException]). */
    data class Invalid(override val configPath: Path, val message: PathText, val line: Int) : Evaluation

    /**
     * Observations and saved plan retain saved policy; `plan` contains the effective draft policy.
     * `choiceAvoidsFolder` holds the normalized sources whose [PlanRelocationItem.choiceAvoidsFolder] is true.
     * `file` is the file as read, which [ruleFile] edits; null under a command-line override, whose paths are not
     * the file's.
     */
    @ConsistentCopyVisibility
    data class Loaded private constructor(
        override val configPath: Path, val savedConfiguration: LightenConfiguration,
        val observations: List<RelocationState>, val savedPlan: ReconciliationPlan,
        val draft: Map<Path, DecisionChoice>, val availableChoices: Map<Path, List<DecisionChoice>>,
        val plan: ReconciliationPlan, val choiceAvoidsFolder: Set<Path>, internal val file: LoadedFile?,
    ) : Evaluation {
        companion object {
            /** Copies the collections, including each list of choices. */
            internal fun of(
                configPath: Path, savedConfiguration: LightenConfiguration,
                observations: List<RelocationState>, savedPlan: ReconciliationPlan,
                draft: Map<Path, DecisionChoice>, availableChoices: Map<Path, List<DecisionChoice>>,
                plan: ReconciliationPlan, choiceAvoidsFolder: Set<Path>, file: LoadedFile?,
            ): Loaded = Loaded(
                configPath, savedConfiguration, observations.toList(), savedPlan,
                draft.toMap(),
                availableChoices.mapValues { it.value.toList() },
                plan, choiceAvoidsFolder.toSet(), file,
            )
        }

        /**
         * One row per configured relocation under the effective draft plan, most urgent first. Within an urgency
         * group rows keep the configuration file's order, as Configuration's list does; `sortedBy` is stable.
         */
        val items: List<PlanRelocationItem> = observations.mapIndexed { i, state ->
            val relocationPlan = plan.relocations[i]
            val relocation = relocationPlan.relocation
            PlanRelocationItem(
                relocation, state.source, state.target, relocationPlan,
                state.source.sourceStateForTarget(relocation.targetPath),
                choicesFor(relocation.sourcePath),
                normalize(relocation.sourcePath) in choiceAvoidsFolder,
            )
        }.sortedBy { it.badge().priority }

        /** Evaluation records choices, possibly none, for every configured source. */
        fun choicesFor(sourcePath: Path): List<DecisionChoice> = availableChoices.getValue(normalize(sourcePath))

        /**
         * The file as read, with the one-time choice for `sourcePath` as that relocation's rule, or null when there
         * is nothing to save: no choice, a choice the saved rule already makes, or no file. Every choice is a rule
         * value ([DecisionChoice.applyTo]), so these are the only cases.
         */
        internal fun ruleFile(sourcePath: Path): LightenFile? {
            val read = file ?: return null
            val source = normalize(sourcePath)
            val choice = draft[source] ?: return null
            // A source with a choice is configured once.
            val i = fileIndex(source) ?: return null
            val saved = savedConfiguration.relocations[i]
            val rule = choice.applyTo(saved)
            if (rule == saved) return null
            return read.file.copy(
                relocations = read.file.relocations.mapIndexed { j, fields ->
                    if (j != i) fields else fields.copy(
                        whenSourceAndTargetDirectoriesExist = rule.whenSourceAndTargetDirectoriesExist,
                        whenOnlyTargetExists = rule.whenOnlyTargetExists,
                        whenAdoptingTarget = rule.whenAdoptingTarget,
                    )
                },
            )
        }

        /** The sources the configuration ignores, each once, in the file's order. Lighten plans nothing for them. */
        val ignored: List<Path> get() = savedConfiguration.ignoredSourcePaths.distinct()

        /**
         * The file as read, with the relocation for `sourcePath` moved to the ignored paths, its source as written; or
         * null when there is no file or no such relocation.
         */
        internal fun ignoredFile(sourcePath: Path): LightenFile? {
            val read = file ?: return null
            val i = fileIndex(normalize(sourcePath)) ?: return null
            return read.file.copy(
                relocations = read.file.relocations.filterIndexed { j, _ -> j != i },
                ignoredSourcePaths = read.file.ignoredSourcePaths + read.file.relocations[i].sourcePath,
            )
        }

        /** The file as read, without `path` among the ignored paths; or null when there is no file or it isn't ignored. */
        internal fun unignoredFile(path: Path): LightenFile? {
            val read = file ?: return null
            val source = normalize(path)
            // The loader resolves the file's ignored paths in order, one each.
            val kept = read.file.ignoredSourcePaths.filterIndexed { j, _ -> savedConfiguration.ignoredSourcePaths[j] != source }
            return if (kept.size == read.file.ignoredSourcePaths.size) null else read.file.copy(ignoredSourcePaths = kept)
        }

        /** The index of the file's relocation for the normalized `source`: the loader converts them in order, one each. */
        private fun fileIndex(source: Path): Int? =
            savedConfiguration.relocations.indexOfFirst { normalize(it.sourcePath) == source }.takeIf { it >= 0 }
    }

    /**
     * A [ConfigurationException] becomes [Missing] or [Invalid]. Any other exception is a bug in inspection or
     * planning and propagates, so it is never shown as an invalid configuration.
     */
    fun load(configPath: Path): Evaluation {
        if (isUnconfiguredDefault(configPath)) {
            return Unconfigured(configPath)
        }
        return try {
            loadRequired(configPath)
        } catch (exception: ConfigurationException) {
            val message = exception.text
            if (Files.notExists(configPath)) Missing(configPath, message)
            else Invalid(configPath, message, (exception as? InvalidConfigurationException)?.line ?: 0)
        }
    }

    /** Preserves loader exceptions for existing CLI error handling. The override is an input, never draft storage. */
    fun loadRequired(configPath: Path, override: ConfigurationLoader.PathOverride? = null): Loaded {
        val read = loader.read(configPath)
        val configuration = loader.resolve(configPath, read, override)
        val observations = inspectRelocations(configuration.relocations, inspect)
        val savedPlan = plan(observations)
        // Invalid duplicate sources have no unambiguous draft identity, so they get no choices. The planner retains
        // their diagnostics. groupBy keeps sources in first-seen order.
        val choices = observations.zip(savedPlan.relocations)
            .groupBy({ (state, _) -> normalize(state.relocation.sourcePath) }) { (state, plan) ->
                availableChoices(state, plan)
            }
            .mapValues { (_, choices) -> choices.singleOrNull() ?: listOf() }
        return Loaded.of(
            configPath, configuration, observations, savedPlan, mapOf(), choices, savedPlan,
            choiceAvoidsFolder(observations, savedPlan, choices),
            // An override replaces the file's paths, so there is no file to save a rule to.
            read.takeIf { override == null },
        )
    }

    fun choose(current: Loaded, sourcePath: Path, choice: DecisionChoice): Loaded {
        val source = normalize(sourcePath)
        val available = requireNotNull(current.availableChoices[source]) { "Unknown relocation source: $source" }
        require(choice in available) { "Unavailable choice $choice for $source" }
        return withDraft(current, current.draft + (source to choice))
    }

    private fun withDraft(current: Loaded, draft: Map<Path, DecisionChoice>): Loaded {
        val effective = current.observations.map { state ->
            val choice = draft[normalize(state.relocation.sourcePath)]
            if (choice == null) state else state.copy(relocation = choice.applyTo(state.relocation))
        }
        val effectivePlan = plan(effective)
        return Loaded.of(
            current.configPath, current.savedConfiguration, current.observations,
            current.savedPlan, draft, current.availableChoices, effectivePlan,
            choiceAvoidsFolder(current.observations, effectivePlan, current.availableChoices), current.file,
        )
    }

    /**
     * The sources whose relocation [effectivePlan] blocks only because of a folder in the way, and which one of
     * their offered choices plans without a block. A relocation counts as blocked only by a folder when planning it
     * with no folder in the way unblocks it. Each one is planned among the others, so an overlap, which no choice
     * avoids, still blocks it.
     */
    private fun choiceAvoidsFolder(
        observations: List<RelocationState>, effectivePlan: ReconciliationPlan,
        choices: Map<Path, List<DecisionChoice>>,
    ): Set<Path> {
        val effective = observations.zip(effectivePlan.relocations) { saved, relocationPlan ->
            saved.copy(relocation = relocationPlan.relocation)
        }
        fun blocked(i: Int, state: RelocationState) = plan(effective.mapIndexed { j, other -> if (j == i) state else other })
            .relocations[i].actions.any { it is ReconciliationAction.Blocked }
        return observations.indices.filter { i ->
            val saved = observations[i]
            effectivePlan.relocations[i].actions.any { it is ReconciliationAction.Blocked } &&
                !blocked(i, effective[i].copy(notFolders = mapOf())) &&
                choices.getValue(normalize(saved.relocation.sourcePath)).any { choice ->
                    !blocked(i, saved.copy(relocation = choice.applyTo(saved.relocation)))
                }
        }.map { i -> normalize(observations[i].relocation.sourcePath) }.toSet()
    }
}

/** Shared by evaluation and the legacy JSON empty responses; explicit non-default paths still require a file. */
fun isUnconfiguredDefault(configPath: Path): Boolean =
    normalize(configPath) == normalize(ConfigurationLoader.DEFAULT_PATH) && !Files.isRegularFile(configPath)

private fun availableChoices(state: RelocationState, plan: RelocationPlan): List<DecisionChoice> {
    if (state.source.state == PathState.DIRECTORY && state.target.state == PathState.DIRECTORY) {
        return listOf(
            DecisionChoice.ADOPT_AND_DISCARD_SOURCE, DecisionChoice.ADOPT_AND_ARCHIVE_SOURCE,
            DecisionChoice.LEAVE_UNCHANGED, DecisionChoice.DISCARD_BOTH,
        )
    }
    if (state.source.state == PathState.ABSENT && state.target.state == PathState.DIRECTORY
        && (plan.conflict != null || state.relocation.whenOnlyTargetExists == WhenOnlyTargetExists.ADOPT_TARGET)
    ) {
        return listOf(DecisionChoice.ADOPT_TARGET)
    }
    return listOf()
}

private fun normalize(path: Path): Path = path.toAbsolutePath().normalize()
