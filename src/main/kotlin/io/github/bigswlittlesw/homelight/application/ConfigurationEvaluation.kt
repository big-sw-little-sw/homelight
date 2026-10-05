package io.github.bigswlittlesw.homelight.application

import io.github.bigswlittlesw.homelight.config.ConfigurationException
import io.github.bigswlittlesw.homelight.config.ConfigurationLoader
import io.github.bigswlittlesw.homelight.config.HomeLightConfiguration
import io.github.bigswlittlesw.homelight.config.WhenOnlyTargetExists
import io.github.bigswlittlesw.homelight.fs.PathInspector
import io.github.bigswlittlesw.homelight.fs.PathObservation
import io.github.bigswlittlesw.homelight.fs.PathState
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlan
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlanner
import io.github.bigswlittlesw.homelight.reconcile.RelocationPlan
import io.github.bigswlittlesw.homelight.reconcile.RelocationState
import io.github.bigswlittlesw.homelight.reconcile.inspectArchiveDestinations
import io.github.bigswlittlesw.homelight.reconcile.replacedSourcePath
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

    data class Missing(override val configPath: Path, val message: String) : Evaluation

    /** Legacy default-path behavior: no regular configuration file, including a directory at that path. */
    data class Unconfigured(override val configPath: Path) : Evaluation

    data class Invalid(override val configPath: Path, val message: String) : Evaluation

    /** Observations and saved plan retain saved policy; `plan` contains the effective draft policy. */
    @ConsistentCopyVisibility
    data class Loaded private constructor(
        override val configPath: Path, val savedConfiguration: HomeLightConfiguration,
        val observations: List<RelocationState>, val savedPlan: ReconciliationPlan,
        val draft: Map<Path, DecisionChoice>, val availableChoices: Map<Path, List<DecisionChoice>>,
        val plan: ReconciliationPlan,
    ) : Evaluation {
        companion object {
            /** Copies the collections, including each list of choices. */
            fun of(
                configPath: Path, savedConfiguration: HomeLightConfiguration,
                observations: List<RelocationState>, savedPlan: ReconciliationPlan,
                draft: Map<Path, DecisionChoice>, availableChoices: Map<Path, List<DecisionChoice>>,
                plan: ReconciliationPlan,
            ): Loaded = Loaded(
                configPath, savedConfiguration, observations.toList(), savedPlan,
                draft.toMap(),
                availableChoices.mapValues { it.value.toList() },
                plan,
            )
        }

        /** One row per configured relocation under the effective draft plan, most urgent first, then by source path. */
        val items: List<PlanRelocationItem> = observations.mapIndexed { i, state ->
            val relocationPlan = plan.relocations[i]
            val relocation = relocationPlan.relocation
            PlanRelocationItem(
                relocation, state.source, state.target, relocationPlan,
                state.source.sourceStateForTarget(relocation.targetPath),
                choicesFor(relocation.sourcePath),
            )
        }.sortedWith(compareBy({ it.badge().priority }, { it.relocation.sourcePath.toString() }))

        /** Evaluation records choices, possibly none, for every configured source. */
        fun choicesFor(sourcePath: Path): List<DecisionChoice> = availableChoices.getValue(normalize(sourcePath))
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
            val message = exception.message ?: exception.toString()
            if (Files.notExists(configPath)) Missing(configPath, message) else Invalid(configPath, message)
        }
    }

    /** Preserves loader exceptions for existing CLI error handling. The override is an input, never draft storage. */
    fun loadRequired(configPath: Path, override: ConfigurationLoader.PathOverride? = null): Loaded {
        val configuration = loader.load(configPath, override)
        val archives = inspectArchiveDestinations(configuration.relocations, inspect)
        val observations = configuration.relocations.zip(archives) { relocation, archive ->
            RelocationState(
                relocation,
                inspect(relocation.sourcePath), inspect(relocation.targetPath), archive,
                inspect(replacedSourcePath(relocation.sourcePath, relocation.targetPath)),
            )
        }
        val savedPlan = plan(observations)
        // Invalid duplicate sources have no unambiguous draft identity, so they get no choices. The planner retains
        // their diagnostics. groupBy keeps sources in first-seen order.
        val choices = observations.zip(savedPlan.relocations)
            .groupBy({ (state, _) -> normalize(state.relocation.sourcePath) }) { (state, plan) ->
                availableChoices(state, plan)
            }
            .mapValues { (_, choices) -> choices.singleOrNull() ?: listOf() }
        return Loaded.of(configPath, configuration, observations, savedPlan, mapOf(), choices, savedPlan)
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
        return Loaded.of(
            current.configPath, current.savedConfiguration, current.observations,
            current.savedPlan, draft, current.availableChoices, plan(effective),
        )
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
