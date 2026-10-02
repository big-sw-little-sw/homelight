package io.github.bigswlittlesw.homelight.application

import io.github.bigswlittlesw.homelight.config.ConfigurationLoader
import io.github.bigswlittlesw.homelight.config.HomeLightConfiguration
import io.github.bigswlittlesw.homelight.config.Relocation
import io.github.bigswlittlesw.homelight.fs.PathInspector
import io.github.bigswlittlesw.homelight.fs.PathState
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlan
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlanner
import io.github.bigswlittlesw.homelight.reconcile.RelocationPlan
import io.github.bigswlittlesw.homelight.reconcile.RelocationState
import java.nio.file.Files
import java.nio.file.Path

/**
 * Loads and inspects once; draft choices replan solely from the retained observations.
 * This bounded inspection pass is not an atomic filesystem snapshot.
 */
class ConfigurationEvaluation(
    private val loader: ConfigurationLoader = ConfigurationLoader(),
    private val inspector: PathInspector = PathInspector(),
    private val planner: ReconciliationPlanner = ReconciliationPlanner(),
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

        /** Evaluation records choices, possibly none, for every configured source. */
        fun choicesFor(sourcePath: Path): List<DecisionChoice> = availableChoices.getValue(normalize(sourcePath))
    }

    enum class DiscardReason { REMOVED, DEFINITION_CHANGED, UNAVAILABLE, CONFIGURATION_UNAVAILABLE }

    data class DiscardedChoice(val sourcePath: Path, val choice: DecisionChoice, val reason: DiscardReason)

    data class Replanned(val evaluation: Evaluation, val discardedChoices: List<DiscardedChoice>)

    fun load(configPath: Path): Evaluation {
        if (isUnconfiguredDefault(configPath)) {
            return Unconfigured(configPath)
        }
        return try {
            loadRequired(configPath)
        } catch (exception: RuntimeException) {
            val message = exception.message ?: exception.toString()
            if (Files.notExists(configPath)) Missing(configPath, message) else Invalid(configPath, message)
        }
    }

    /** Preserves loader exceptions for existing CLI error handling. The override is an input, never draft storage. */
    fun loadRequired(configPath: Path, override: ConfigurationLoader.PathOverride? = null): Loaded {
        val configuration = loader.load(configPath, override)
        val observations = configuration.relocations.map { relocation ->
            RelocationState(
                relocation,
                inspector.inspect(relocation.sourcePath), inspector.inspect(relocation.targetPath),
                relocation.sourceArchiveRoot?.let { root ->
                    val source = normalize(relocation.sourcePath)
                    val path = root.resolve(source.root.relativize(source)).normalize()
                    RelocationState.ArchiveDestination(path, inspector.inspect(path))
                },
            )
        }
        val savedPlan = planner.plan(observations)
        val choices = LinkedHashMap<Path, List<DecisionChoice>>()
        observations.forEachIndexed { i, state ->
            // Invalid duplicate sources have no unambiguous draft identity. The planner retains their diagnostics.
            val choicesForSource = availableChoices(state, savedPlan.relocations[i])
            choices.merge(normalize(state.relocation.sourcePath), choicesForSource) { _, _ -> listOf() }
        }
        return Loaded.of(configPath, configuration, observations, savedPlan, mapOf(), choices, savedPlan)
    }

    fun choose(current: Loaded, sourcePath: Path, choice: DecisionChoice): Loaded {
        val source = normalize(sourcePath)
        val available = requireNotNull(current.availableChoices[source]) { "Unknown relocation source: $source" }
        require(choice in available) { "Unavailable choice $choice for $source" }
        return withDraft(current, current.draft + (source to choice))
    }

    /** Retention compares complete resolved definitions, never positions in the configuration list. */
    fun replan(previous: Evaluation): Replanned {
        val next = load(previous.configPath)
        if (previous !is Loaded) {
            return Replanned(next, listOf())
        }
        if (next !is Loaded) {
            return Replanned(
                next,
                previous.draft.map { (source, choice) ->
                    DiscardedChoice(source, choice, DiscardReason.CONFIGURATION_UNAVAILABLE)
                },
            )
        }
        val oldDefinitions = definitions(previous)
        val newDefinitions = definitions(next)
        val discarded = ArrayList<DiscardedChoice>()
        val retained = LinkedHashMap<Path, DecisionChoice>()
        previous.draft.forEach { (source, choice) ->
            val reason = when {
                source !in newDefinitions -> DiscardReason.REMOVED
                oldDefinitions[source] != newDefinitions[source] -> DiscardReason.DEFINITION_CHANGED
                // Every configured source has an entry, even if it is an empty list.
                choice !in next.availableChoices.getValue(source) -> DiscardReason.UNAVAILABLE
                else -> null
            }
            if (reason == null) retained[source] = choice else discarded.add(DiscardedChoice(source, choice, reason))
        }
        return Replanned(withDraft(next, retained), discarded)
    }

    private fun withDraft(current: Loaded, draft: Map<Path, DecisionChoice>): Loaded {
        val effective = current.observations.map { state ->
            val choice = draft[normalize(state.relocation.sourcePath)]
            if (choice == null) state else state.copy(relocation = choice.applyTo(state.relocation))
        }
        return Loaded.of(
            current.configPath, current.savedConfiguration, current.observations,
            current.savedPlan, draft, current.availableChoices, planner.plan(effective),
        )
    }
}

/** Shared by evaluation and the legacy JSON empty responses; explicit non-default paths still require a file. */
fun isUnconfiguredDefault(configPath: Path): Boolean =
    normalize(configPath) == normalize(ConfigurationLoader.DEFAULT_PATH) && !Files.isRegularFile(configPath)

private fun definitions(evaluation: ConfigurationEvaluation.Loaded): Map<Path, Relocation> =
    evaluation.savedConfiguration.relocations.associateBy { normalize(it.sourcePath) }

private fun availableChoices(state: RelocationState, plan: RelocationPlan): List<DecisionChoice> {
    if (state.source.state == PathState.DIRECTORY && state.target.state == PathState.DIRECTORY) {
        return buildList {
            add(DecisionChoice.ADOPT_AND_DISCARD_SOURCE)
            if (state.relocation.sourceArchiveRoot != null) {
                add(DecisionChoice.ADOPT_AND_ARCHIVE_SOURCE)
            }
            add(DecisionChoice.LEAVE_UNCHANGED)
            add(DecisionChoice.DISCARD_BOTH)
        }
    }
    if (state.source.state == PathState.ABSENT && state.target.state == PathState.DIRECTORY
        && (plan.conflict != null || state.relocation.whenOnlyTargetExists != null)
    ) {
        return listOf(DecisionChoice.ADOPT_TARGET)
    }
    return listOf()
}

private fun normalize(path: Path): Path = path.toAbsolutePath().normalize()
