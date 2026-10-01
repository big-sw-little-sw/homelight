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
import java.util.Objects
import java.util.Optional

/**
 * Loads and inspects once; draft choices replan solely from the retained observations.
 * This bounded inspection pass is not an atomic filesystem snapshot.
 */
class ConfigurationEvaluation(
    private val loader: ConfigurationLoader,
    private val inspector: PathInspector,
    private val planner: ReconciliationPlanner,
) {
    constructor() : this(ConfigurationLoader(), PathInspector(), ReconciliationPlanner())

    sealed interface Evaluation {
        // Java callers use the record-style accessor `configPath()`, which each record's component implements.
        @Suppress("INAPPLICABLE_JVM_NAME")
        @get:JvmName("configPath")
        val configPath: Path
    }

    @JvmRecord
    data class Missing(override val configPath: Path, val message: String) : Evaluation

    /** Legacy default-path behavior: no regular configuration file, including a directory at that path. */
    @JvmRecord
    data class Unconfigured(override val configPath: Path) : Evaluation

    @JvmRecord
    data class Invalid(override val configPath: Path, val message: String) : Evaluation

    /**
     * Observations and saved plan retain saved policy; `plan` contains the effective draft policy.
     *
     * Not a `@JvmRecord data class`: the constructor copies its components, which a Kotlin record cannot
     * do. Accessors keep the record names; equality and `toString` match the record this replaces.
     */
    class Loaded(
        configPath: Path, savedConfiguration: HomeLightConfiguration,
        observations: List<RelocationState>, savedPlan: ReconciliationPlan,
        draft: Map<Path, DecisionChoice>, availableChoices: Map<Path, List<DecisionChoice>>,
        plan: ReconciliationPlan,
    ) : Evaluation {
        @Suppress("INAPPLICABLE_JVM_NAME")
        @get:JvmName("configPath")
        override val configPath: Path = configPath

        @get:JvmName("savedConfiguration")
        val savedConfiguration: HomeLightConfiguration = HomeLightConfiguration.of(
            savedConfiguration.targetRoot,
            java.util.List.copyOf(savedConfiguration.relocations), java.util.List.copyOf(savedConfiguration.ignoredSourcePaths),
            savedConfiguration.sharedList,
        )

        @get:JvmName("observations")
        val observations: List<RelocationState> = java.util.List.copyOf(observations)

        @get:JvmName("savedPlan")
        val savedPlan: ReconciliationPlan = savedPlan

        @get:JvmName("draft")
        val draft: Map<Path, DecisionChoice> = java.util.Map.copyOf(draft)

        @get:JvmName("availableChoices")
        val availableChoices: Map<Path, List<DecisionChoice>>

        @get:JvmName("plan")
        val plan: ReconciliationPlan = plan

        init {
            val choices = LinkedHashMap<Path, List<DecisionChoice>>()
            availableChoices.forEach { path, values -> choices[path] = java.util.List.copyOf(values) }
            this.availableChoices = java.util.Map.copyOf(choices)
        }

        override fun equals(other: Any?): Boolean = other is Loaded
                && configPath == other.configPath
                && savedConfiguration == other.savedConfiguration
                && observations == other.observations
                && savedPlan == other.savedPlan
                && draft == other.draft
                && availableChoices == other.availableChoices
                && plan == other.plan

        override fun hashCode(): Int =
            Objects.hash(configPath, savedConfiguration, observations, savedPlan, draft, availableChoices, plan)

        override fun toString(): String = "Loaded[configPath=$configPath, savedConfiguration=$savedConfiguration, " +
                "observations=$observations, savedPlan=$savedPlan, draft=$draft, availableChoices=$availableChoices, " +
                "plan=$plan]"
    }

    enum class DiscardReason { REMOVED, DEFINITION_CHANGED, UNAVAILABLE, CONFIGURATION_UNAVAILABLE }

    @JvmRecord
    data class DiscardedChoice(val sourcePath: Path, val choice: DecisionChoice, val reason: DiscardReason)

    /**
     * Not a `@JvmRecord data class`: the constructor copies `discardedChoices`, which a Kotlin record cannot
     * do. Accessors keep the record names; equality and `toString` match the record this replaces.
     */
    class Replanned(evaluation: Evaluation, discardedChoices: List<DiscardedChoice>) {
        @get:JvmName("evaluation")
        val evaluation: Evaluation = evaluation

        @get:JvmName("discardedChoices")
        val discardedChoices: List<DiscardedChoice> = java.util.List.copyOf(discardedChoices)

        override fun equals(other: Any?): Boolean = other is Replanned
                && evaluation == other.evaluation
                && discardedChoices == other.discardedChoices

        override fun hashCode(): Int = Objects.hash(evaluation, discardedChoices)

        override fun toString(): String = "Replanned[evaluation=$evaluation, discardedChoices=$discardedChoices]"
    }

    fun load(configPath: Path): Evaluation {
        if (isUnconfiguredDefault(configPath)) {
            return Unconfigured(configPath)
        }
        try {
            return loadRequired(configPath)
        } catch (exception: RuntimeException) {
            val message = if (exception.message == null) exception.toString() else exception.message!!
            return if (Files.notExists(configPath)) Missing(configPath, message) else Invalid(configPath, message)
        }
    }

    fun loadRequired(configPath: Path): Loaded = loadRequired(configPath, Optional.empty())

    /** Preserves loader exceptions for existing CLI error handling. The override is an input, never draft storage. */
    fun loadRequired(configPath: Path, override: Optional<ConfigurationLoader.PathOverride>): Loaded {
        val configuration = loader.load(configPath, override.orElse(null))
        val observations = configuration.relocations.stream().map { relocation ->
            RelocationState(
                relocation,
                inspector.inspect(relocation.sourcePath), inspector.inspect(relocation.targetPath),
                Optional.ofNullable(relocation.sourceArchiveRoot).map { root ->
                    val source = normalize(relocation.sourcePath)
                    val path = root.resolve(source.root.relativize(source)).normalize()
                    RelocationState.ArchiveDestination(path, inspector.inspect(path))
                },
            )
        }.toList()
        val savedPlan = planner.plan(observations)
        val choices = LinkedHashMap<Path, List<DecisionChoice>>()
        for (i in observations.indices) {
            val state = observations[i]
            // Invalid duplicate sources have no unambiguous draft identity. The planner retains their diagnostics.
            choices.merge(
                normalize(state.relocation.sourcePath), availableChoices(state, savedPlan.relocations[i]),
            ) { _, _ -> java.util.List.of() }
        }
        return Loaded(configPath, configuration, observations, savedPlan, java.util.Map.of(), choices, savedPlan)
    }

    fun choose(current: Loaded, sourcePath: Path, choice: DecisionChoice): Loaded {
        val source = normalize(sourcePath)
        val available = current.availableChoices[source]
            ?: throw IllegalArgumentException("Unknown relocation source: $source")
        if (!available.contains(choice)) {
            throw IllegalArgumentException("Unavailable choice $choice for $source")
        }
        val draft = LinkedHashMap(current.draft)
        draft[source] = choice
        return withDraft(current, draft)
    }

    /** Retention compares complete resolved definitions, never positions in the configuration list. */
    fun replan(previous: Evaluation): Replanned {
        val next = load(previous.configPath)
        if (previous !is Loaded) {
            return Replanned(next, java.util.List.of())
        }
        val old = previous
        val discarded = ArrayList<DiscardedChoice>()
        val retained = LinkedHashMap<Path, DecisionChoice>()
        val oldDefinitions = definitions(old)
        if (next is Loaded) {
            val loaded = next
            val newDefinitions = definitions(loaded)
            old.draft.forEach { source, choice ->
                var reason: DiscardReason? = null
                if (!newDefinitions.containsKey(source)) {
                    reason = DiscardReason.REMOVED
                } else if (!oldDefinitions[source]!!.equals(newDefinitions[source])) {
                    reason = DiscardReason.DEFINITION_CHANGED
                } else if (!loaded.availableChoices[source]!!.contains(choice)) {
                    reason = DiscardReason.UNAVAILABLE
                }
                if (reason == null) {
                    retained[source] = choice
                } else {
                    discarded.add(DiscardedChoice(source, choice, reason))
                }
            }
            return Replanned(withDraft(loaded, retained), discarded)
        }
        old.draft.forEach { source, choice ->
            discarded.add(DiscardedChoice(source, choice, DiscardReason.CONFIGURATION_UNAVAILABLE))
        }
        return Replanned(next, discarded)
    }

    private fun withDraft(current: Loaded, draft: Map<Path, DecisionChoice>): Loaded {
        val effective = current.observations.stream().map { state ->
            val choice = draft[normalize(state.relocation.sourcePath)]
            if (choice == null) state else RelocationState(
                choice.applyTo(state.relocation),
                state.source, state.target, state.archiveDestination,
            )
        }.toList()
        return Loaded(
            current.configPath, current.savedConfiguration, current.observations,
            current.savedPlan, draft, current.availableChoices, planner.plan(effective),
        )
    }

    companion object {
        /** Shared by evaluation and the legacy JSON empty responses; explicit non-default paths still require a file. */
        @JvmStatic
        fun isUnconfiguredDefault(configPath: Path): Boolean =
            normalize(configPath) == normalize(ConfigurationLoader.DEFAULT_PATH) && !Files.isRegularFile(configPath)

        private fun definitions(evaluation: Loaded): Map<Path, Relocation> {
            val definitions = LinkedHashMap<Path, Relocation>()
            evaluation.savedConfiguration.relocations.forEach { relocation ->
                definitions[normalize(relocation.sourcePath)] = relocation
            }
            return definitions
        }

        private fun availableChoices(state: RelocationState, plan: RelocationPlan): List<DecisionChoice> {
            if (state.source.state == PathState.DIRECTORY && state.target.state == PathState.DIRECTORY) {
                val choices = ArrayList<DecisionChoice>()
                choices.add(DecisionChoice.ADOPT_AND_DISCARD_SOURCE)
                if (state.relocation.sourceArchiveRoot != null) {
                    choices.add(DecisionChoice.ADOPT_AND_ARCHIVE_SOURCE)
                }
                choices.add(DecisionChoice.LEAVE_UNCHANGED)
                choices.add(DecisionChoice.DISCARD_BOTH)
                return java.util.List.copyOf(choices)
            }
            if (state.source.state == PathState.ABSENT && state.target.state == PathState.DIRECTORY
                && (plan.conflict.isPresent || state.relocation.whenOnlyTargetExists != null)
            ) {
                return java.util.List.of(DecisionChoice.ADOPT_TARGET)
            }
            return java.util.List.of()
        }

        private fun normalize(path: Path): Path = path.toAbsolutePath().normalize()
    }
}
