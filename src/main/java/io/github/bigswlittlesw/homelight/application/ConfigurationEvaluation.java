package io.github.bigswlittlesw.homelight.application;

import io.github.bigswlittlesw.homelight.config.ConfigurationLoader;
import io.github.bigswlittlesw.homelight.config.HomeLightConfiguration;
import io.github.bigswlittlesw.homelight.config.Relocation;
import io.github.bigswlittlesw.homelight.fs.PathInspector;
import io.github.bigswlittlesw.homelight.fs.PathState;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlan;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlanner;
import io.github.bigswlittlesw.homelight.reconcile.RelocationPlan;
import io.github.bigswlittlesw.homelight.reconcile.RelocationState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/// Loads and inspects once; draft choices replan solely from the retained observations.
/// This bounded inspection pass is not an atomic filesystem snapshot.
public final class ConfigurationEvaluation {
    private final ConfigurationLoader loader;
    private final PathInspector inspector;
    private final ReconciliationPlanner planner;

    public ConfigurationEvaluation() {
        this(new ConfigurationLoader(), new PathInspector(), new ReconciliationPlanner());
    }

    public ConfigurationEvaluation(ConfigurationLoader loader, PathInspector inspector, ReconciliationPlanner planner) {
        this.loader = Objects.requireNonNull(loader);
        this.inspector = Objects.requireNonNull(inspector);
        this.planner = Objects.requireNonNull(planner);
    }

    public sealed interface Evaluation {
        Path configPath();
    }

    public record Missing(Path configPath, String message) implements Evaluation { }

    /// Legacy default-path behavior: no regular configuration file, including a directory at that path.
    public record Unconfigured(Path configPath) implements Evaluation { }

    public record Invalid(Path configPath, String message) implements Evaluation { }

    /// Observations and saved plan retain saved policy; `plan` contains the effective draft policy.
    public record Loaded(Path configPath, HomeLightConfiguration savedConfiguration,
            List<RelocationState> observations, ReconciliationPlan savedPlan,
            Map<Path, DecisionChoice> draft, Map<Path, List<DecisionChoice>> availableChoices,
            ReconciliationPlan plan) implements Evaluation {
        public Loaded {
            savedConfiguration = new HomeLightConfiguration(savedConfiguration.targetRoot(),
                    List.copyOf(savedConfiguration.relocations()), List.copyOf(savedConfiguration.ignoredSourcePaths()),
                    savedConfiguration.sharedList());
            observations = List.copyOf(observations);
            draft = Map.copyOf(draft);
            var choices = new LinkedHashMap<Path, List<DecisionChoice>>();
            availableChoices.forEach((path, values) -> choices.put(path, List.copyOf(values)));
            availableChoices = Map.copyOf(choices);
        }
    }

    public enum DiscardReason { REMOVED, DEFINITION_CHANGED, UNAVAILABLE, CONFIGURATION_UNAVAILABLE }

    public record DiscardedChoice(Path sourcePath, DecisionChoice choice, DiscardReason reason) { }

    public record Replanned(Evaluation evaluation, List<DiscardedChoice> discardedChoices) {
        public Replanned {
            discardedChoices = List.copyOf(discardedChoices);
        }
    }

    public Evaluation load(Path configPath) {
        if (isUnconfiguredDefault(configPath)) {
            return new Unconfigured(configPath);
        }
        try {
            return loadRequired(configPath);
        } catch (RuntimeException exception) {
            var message = exception.getMessage() == null ? exception.toString() : exception.getMessage();
            return Files.notExists(configPath) ? new Missing(configPath, message) : new Invalid(configPath, message);
        }
    }

    /// Shared by evaluation and the legacy JSON empty responses; explicit non-default paths still require a file.
    public static boolean isUnconfiguredDefault(Path configPath) {
        return normalize(configPath).equals(normalize(ConfigurationLoader.DEFAULT_PATH))
                && !Files.isRegularFile(configPath);
    }

    public Loaded loadRequired(Path configPath) {
        return loadRequired(configPath, Optional.empty());
    }

    /// Preserves loader exceptions for existing CLI error handling. The override is an input, never draft storage.
    public Loaded loadRequired(Path configPath, Optional<ConfigurationLoader.PathOverride> override) {
        var configuration = loader.load(configPath, override);
        var observations = configuration.relocations().stream().map(relocation -> new RelocationState(relocation,
                inspector.inspect(relocation.sourcePath()), inspector.inspect(relocation.targetPath()),
                relocation.sourceArchiveRoot().map(root -> {
                    var source = normalize(relocation.sourcePath());
                    var path = root.resolve(source.getRoot().relativize(source)).normalize();
                    return new RelocationState.ArchiveDestination(path, inspector.inspect(path));
                }))).toList();
        var savedPlan = planner.plan(observations);
        var choices = new LinkedHashMap<Path, List<DecisionChoice>>();
        for (int i = 0; i < observations.size(); i++) {
            var state = observations.get(i);
            // Invalid duplicate sources have no unambiguous draft identity. The planner retains their diagnostics.
            choices.merge(normalize(state.relocation().sourcePath()), availableChoices(state, savedPlan.relocations().get(i)),
                    (existing, duplicate) -> List.of());
        }
        return new Loaded(configPath, configuration, observations, savedPlan, Map.of(), choices, savedPlan);
    }

    public Loaded choose(Loaded current, Path sourcePath, DecisionChoice choice) {
        var source = normalize(sourcePath);
        var available = current.availableChoices().get(source);
        if (available == null) {
            throw new IllegalArgumentException("Unknown relocation source: " + source);
        }
        if (!available.contains(Objects.requireNonNull(choice, "choice"))) {
            throw new IllegalArgumentException("Unavailable choice " + choice + " for " + source);
        }
        var draft = new LinkedHashMap<>(current.draft());
        draft.put(source, choice);
        return withDraft(current, draft);
    }

    /// Retention compares complete resolved definitions, never positions in the configuration list.
    public Replanned replan(Evaluation previous) {
        var next = load(previous.configPath());
        if (!(previous instanceof Loaded old)) {
            return new Replanned(next, List.of());
        }
        var discarded = new ArrayList<DiscardedChoice>();
        var retained = new LinkedHashMap<Path, DecisionChoice>();
        var oldDefinitions = definitions(old);
        if (next instanceof Loaded loaded) {
            var newDefinitions = definitions(loaded);
            old.draft().forEach((source, choice) -> {
                DiscardReason reason = null;
                if (!newDefinitions.containsKey(source)) {
                    reason = DiscardReason.REMOVED;
                } else if (!oldDefinitions.get(source).equals(newDefinitions.get(source))) {
                    reason = DiscardReason.DEFINITION_CHANGED;
                } else if (!loaded.availableChoices().get(source).contains(choice)) {
                    reason = DiscardReason.UNAVAILABLE;
                }
                if (reason == null) {
                    retained.put(source, choice);
                } else {
                    discarded.add(new DiscardedChoice(source, choice, reason));
                }
            });
            return new Replanned(withDraft(loaded, retained), discarded);
        }
        old.draft().forEach((source, choice) -> discarded.add(
                new DiscardedChoice(source, choice, DiscardReason.CONFIGURATION_UNAVAILABLE)));
        return new Replanned(next, discarded);
    }

    private Loaded withDraft(Loaded current, Map<Path, DecisionChoice> draft) {
        var effective = current.observations().stream().map(state -> {
            var choice = draft.get(normalize(state.relocation().sourcePath()));
            return choice == null ? state : new RelocationState(choice.applyTo(state.relocation()),
                    state.source(), state.target(), state.archiveDestination());
        }).toList();
        return new Loaded(current.configPath(), current.savedConfiguration(), current.observations(),
                current.savedPlan(), draft, current.availableChoices(), planner.plan(effective));
    }

    private static Map<Path, Relocation> definitions(Loaded evaluation) {
        var definitions = new LinkedHashMap<Path, Relocation>();
        evaluation.savedConfiguration().relocations().forEach(relocation ->
                definitions.put(normalize(relocation.sourcePath()), relocation));
        return definitions;
    }

    private static List<DecisionChoice> availableChoices(RelocationState state, RelocationPlan plan) {
        if (state.source().state() == PathState.DIRECTORY && state.target().state() == PathState.DIRECTORY) {
            var choices = new ArrayList<DecisionChoice>();
            choices.add(DecisionChoice.ADOPT_AND_DISCARD_SOURCE);
            if (state.relocation().sourceArchiveRoot().isPresent()) {
                choices.add(DecisionChoice.ADOPT_AND_ARCHIVE_SOURCE);
            }
            choices.add(DecisionChoice.LEAVE_UNCHANGED);
            choices.add(DecisionChoice.DISCARD_BOTH);
            return List.copyOf(choices);
        }
        if (state.source().state() == PathState.ABSENT && state.target().state() == PathState.DIRECTORY
                && (plan.conflict().isPresent() || state.relocation().whenOnlyTargetExists().isPresent())) {
            return List.of(DecisionChoice.ADOPT_TARGET);
        }
        return List.of();
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }
}
