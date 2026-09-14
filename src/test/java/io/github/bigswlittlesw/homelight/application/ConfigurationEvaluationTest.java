package io.github.bigswlittlesw.homelight.application;

import io.github.bigswlittlesw.homelight.config.ConfigurationLoader;
import io.github.bigswlittlesw.homelight.config.WhenSourceAndTargetDirectoriesExist;
import io.github.bigswlittlesw.homelight.domain.RelocationSourceState;
import io.github.bigswlittlesw.homelight.fs.PathState;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConfigurationEvaluationTest {
    @TempDir Path temporary;
    private Path root;
    private Path config;
    private final ConfigurationEvaluation evaluator = new ConfigurationEvaluation();

    @BeforeEach
    void setUp() throws Exception {
        root = temporary.toRealPath();
        config = root.resolve("config.yaml");
    }

    @ParameterizedTest
    @EnumSource(DecisionChoice.class)
    void choicesPreserveSavedPolicyAndMatchPlannerWithoutWrites(DecisionChoice choice) throws Exception {
        var source = root.resolve("source");
        var target = Files.createDirectory(root.resolve("target"));
        if (choice != DecisionChoice.ADOPT_TARGET) {
            Files.createDirectory(source);
            Files.writeString(source.resolve("content"), "source");
        }
        Files.writeString(target.resolve("content"), "target");
        write(entry("source", "target", """
                      when-source-and-target-directories-exist: prompt
                      when-only-target-exists: prompt
                      source-archive-root: %s
                """.formatted(root.resolve("archive"))));
        var yaml = Files.readString(config);
        var loaded = loaded();
        var selected = evaluator.choose(loaded, root.resolve("child/../source"), choice);

        assertTrue(loaded.draft().isEmpty());
        assertTrue(selected.savedPlan().hasConflicts());
        assertFalse(selected.plan().hasConflicts());
        assertFalse(selected.plan().hasBlockedActions());
        assertEquals(Map.of(source, choice), selected.draft());
        assertEquals(loaded.savedConfiguration(), selected.savedConfiguration());
        assertSame(loaded.observations().getFirst(), selected.observations().getFirst());
        assertEquals(WhenSourceAndTargetDirectoriesExist.PROMPT,
                selected.savedConfiguration().relocations().getFirst().whenSourceAndTargetDirectoriesExist().orElseThrow());

        // Compare with the established loader + pure planner path using saved YAML policies.
        var properties = switch (choice) {
            case ADOPT_TARGET -> Map.of("when-only-target-exists", "adopt-target");
            case ADOPT_AND_DISCARD_SOURCE -> Map.of("when-source-and-target-directories-exist", "adopt",
                    "when-adopting-target", "discard-source");
            case ADOPT_AND_ARCHIVE_SOURCE -> Map.of("when-source-and-target-directories-exist", "adopt",
                    "when-adopting-target", "archive-source");
            case LEAVE_UNCHANGED -> Map.of("when-source-and-target-directories-exist", "leave-unchanged");
            case DISCARD_BOTH -> Map.of("when-source-and-target-directories-exist", "discard");
        };
        var overrides = new java.util.HashMap<String, String>();
        properties.forEach((key, value) -> overrides.put("homelight.relocations[0]." + key, value));
        var expected = evaluator.loadRequired(config, overrides);
        assertEquals(expected.plan(), selected.plan());
        assertEquals(new ReconciliationPlanner().plan(selected.plan().expectedStates()), selected.plan());
        assertEquals(yaml, Files.readString(config));
        assertEquals("target", Files.readString(target.resolve("content")));
        if (choice == DecisionChoice.ADOPT_TARGET) {
            assertFalse(Files.exists(source));
        } else {
            assertEquals("source", Files.readString(source.resolve("content")));
        }
        assertFalse(Files.exists(root.resolve("archive")));
        assertThrows(UnsupportedOperationException.class, () -> selected.draft().clear());
        assertThrows(UnsupportedOperationException.class, () -> selected.observations().clear());
        assertThrows(UnsupportedOperationException.class, () -> selected.savedConfiguration().relocations().clear());
        assertThrows(UnsupportedOperationException.class, () -> selected.availableChoices().get(source).clear());
    }

    @Test
    void choosingUsesRetainedConfigSourceTargetAndArchiveObservations() throws Exception {
        Files.createDirectory(root.resolve("source"));
        Files.createDirectory(root.resolve("target"));
        write(entry("source", "target", "      source-archive-root: " + root.resolve("archive") + "\n"));
        var session = new HomeLightSession(config);
        var original = assertInstanceOf(ConfigurationEvaluation.Loaded.class, session.evaluation());
        var observation = original.observations().getFirst();
        var archive = observation.archiveDestination().orElseThrow();
        assertEquals(root.resolve("archive").resolve(root.getRoot().relativize(root.resolve("source"))), archive.path());
        assertEquals(PathState.ABSENT, archive.observation().state());

        Files.delete(root.resolve("source"));
        Files.createSymbolicLink(root.resolve("source"), root.resolve("target"));
        Files.writeString(root.resolve("target/content"), "changed");
        Files.createDirectories(archive.path());
        Files.writeString(config, "malformed: [");
        session.choose(root.resolve("source"), DecisionChoice.ADOPT_AND_ARCHIVE_SOURCE);

        var selected = assertInstanceOf(ConfigurationEvaluation.Loaded.class, session.evaluation());
        var plan = assertInstanceOf(PlanModel.Configured.class, session.planModel()).items().getFirst();
        assertSame(original.savedPlan(), selected.savedPlan());
        assertSame(observation.source(), plan.sourceObservation());
        assertEquals(RelocationSourceState.DIRECTORY, plan.sourceState());
        assertTrue(selected.plan().actions().stream().anyMatch(ReconciliationAction.ArchiveDirectory.class::isInstance));
        assertFalse(selected.plan().hasBlockedActions());
        assertEquals("malformed: [", Files.readString(config));
        assertTrue(Files.isSymbolicLink(root.resolve("source")));
        assertTrue(Files.isDirectory(archive.path()));
        assertEquals("changed", Files.readString(root.resolve("target/content")));
    }

    @Test
    void replacingChoiceStartsFromSavedPolicyAndCancelsReview() throws Exception {
        bothDirectories("source", "target");
        write(entry("source", "target", "      source-archive-root: " + root.resolve("archive") + "\n"));
        var session = new HomeLightSession(config);
        session.choose(root.resolve("source"), DecisionChoice.ADOPT_AND_ARCHIVE_SOURCE);
        assertTrue(session.requestApply());
        session.choose(root.resolve("source"), DecisionChoice.LEAVE_UNCHANGED);
        assertInstanceOf(ApplyModel.Idle.class, session.applyModel());
        var selected = assertInstanceOf(ConfigurationEvaluation.Loaded.class, session.evaluation());
        assertEquals(1, selected.draft().size());
        assertTrue(selected.plan().relocations().getFirst().relocation().whenAdoptingTarget().isEmpty());
        assertTrue(session.requestApply());
        session.refresh();
        assertInstanceOf(ApplyModel.Idle.class, session.applyModel());
        assertEquals(selected.draft(), assertInstanceOf(ConfigurationEvaluation.Loaded.class, session.evaluation()).draft());
    }

    @Test
    void rejectsUnknownAndUnavailableChoicesWithoutChangingReview() throws Exception {
        Files.createDirectory(root.resolve("target"));
        write(entry("source", "target", ""));
        var session = new HomeLightSession(config);
        session.choose(root.resolve("source"), DecisionChoice.ADOPT_TARGET);
        assertTrue(session.requestApply());
        var review = session.applyModel();
        var before = session.evaluation();
        assertThrows(IllegalArgumentException.class, () -> session.choose(root.resolve("unknown"), DecisionChoice.ADOPT_TARGET));
        assertThrows(IllegalArgumentException.class, () -> session.choose(root.resolve("source"), DecisionChoice.DISCARD_BOTH));
        assertSame(before, session.evaluation());
        assertSame(review, session.applyModel());

        bothDirectories("other-source", "other-target");
        write(entry("other-source", "other-target", ""));
        var noArchive = loaded();
        assertThrows(IllegalArgumentException.class, () ->
                evaluator.choose(noArchive, root.resolve("other-source"), DecisionChoice.ADOPT_AND_ARCHIVE_SOURCE));
    }

    @Test
    void retainsChoicesBySourceAcrossReorderAndReportsRemoval() throws Exception {
        bothDirectories("first", "first-target");
        bothDirectories("second", "second-target");
        var first = entry("first", "first-target", "");
        var second = entry("second", "second-target", "");
        write(first + second);
        var selected = evaluator.choose(loaded(), root.resolve("first"), DecisionChoice.LEAVE_UNCHANGED);
        selected = evaluator.choose(selected, root.resolve("second"), DecisionChoice.DISCARD_BOTH);
        write(second + first);
        var reordered = evaluator.replan(selected);
        assertTrue(reordered.discardedChoices().isEmpty());
        var next = assertInstanceOf(ConfigurationEvaluation.Loaded.class, reordered.evaluation());
        assertEquals(selected.draft(), next.draft());
        assertEquals(root.resolve("second"), next.plan().relocations().getFirst().relocation().sourcePath());
        assertEquals(WhenSourceAndTargetDirectoriesExist.DISCARD,
                next.plan().relocations().getFirst().relocation().whenSourceAndTargetDirectoriesExist().orElseThrow());
        write(second);
        var removed = evaluator.replan(next);
        assertEquals(List.of(new ConfigurationEvaluation.DiscardedChoice(root.resolve("first"),
                DecisionChoice.LEAVE_UNCHANGED, ConfigurationEvaluation.DiscardReason.REMOVED)), removed.discardedChoices());
        assertEquals(Map.of(root.resolve("second"), DecisionChoice.DISCARD_BOTH),
                assertInstanceOf(ConfigurationEvaluation.Loaded.class, removed.evaluation()).draft());
    }

    @Test
    void changedDefinitionsNeverInheritDrafts() throws Exception {
        bothDirectories("source", "target");
        write(entry("source", "target", ""));
        var selected = evaluator.choose(loaded(), root.resolve("source"), DecisionChoice.DISCARD_BOTH);
        for (var changed : List.of(
                entry("source", "other-target", ""),
                entry("source", "target", "      when-source-and-target-directories-exist: leave-unchanged\n"),
                entry("source", "target", "      source-archive-root: " + root.resolve("archive") + "\n"))) {
            write(changed);
            var result = evaluator.replan(selected);
            assertEquals(ConfigurationEvaluation.DiscardReason.DEFINITION_CHANGED, result.discardedChoices().getFirst().reason());
            assertTrue(assertInstanceOf(ConfigurationEvaluation.Loaded.class, result.evaluation()).draft().isEmpty());
        }
        write(entry("new-source", "target", ""));
        var moved = evaluator.replan(selected);
        assertEquals(ConfigurationEvaluation.DiscardReason.REMOVED, moved.discardedChoices().getFirst().reason());
        assertTrue(assertInstanceOf(ConfigurationEvaluation.Loaded.class, moved.evaluation()).draft().isEmpty());
    }

    @Test
    void duplicateSourceCannotReceiveOrRetainAnAmbiguousDraft() throws Exception {
        bothDirectories("source", "target");
        write(entry("source", "target", ""));
        var selected = evaluator.choose(loaded(), root.resolve("source"), DecisionChoice.LEAVE_UNCHANGED);
        write(entry("source", "target", "") + entry("source", "target", ""));
        var replanned = evaluator.replan(selected);
        var duplicate = assertInstanceOf(ConfigurationEvaluation.Loaded.class, replanned.evaluation());
        assertTrue(duplicate.plan().hasBlockedActions());
        assertFalse(duplicate.plan().diagnostics().isEmpty());
        assertTrue(duplicate.draft().isEmpty());
        assertEquals(ConfigurationEvaluation.DiscardReason.UNAVAILABLE, replanned.discardedChoices().getFirst().reason());
        assertThrows(IllegalArgumentException.class, () -> evaluator.choose(duplicate, root.resolve("source"), DecisionChoice.DISCARD_BOTH));
    }

    @Test
    void reinspectionDiscardsUnavailableChoiceAndClassifiesCurrentSource() throws Exception {
        Files.createDirectory(root.resolve("target"));
        write(entry("source", "target", ""));
        var selected = evaluator.choose(loaded(), root.resolve("source"), DecisionChoice.ADOPT_TARGET);
        Files.createSymbolicLink(root.resolve("source"), root.resolve("target"));
        var result = evaluator.replan(selected);
        assertEquals(ConfigurationEvaluation.DiscardReason.UNAVAILABLE, result.discardedChoices().getFirst().reason());
        var next = assertInstanceOf(ConfigurationEvaluation.Loaded.class, result.evaluation());
        assertTrue(next.draft().isEmpty());
        assertEquals(RelocationSourceState.CORRECT_SYMLINK,
                assertInstanceOf(PlanModel.Configured.class, PlanWorkflow.from(next)).items().getFirst().sourceState());
    }

    @Test
    void missingAndMalformedConfigDiscardDraftAndRequireFreshReview() throws Exception {
        Files.createDirectory(root.resolve("target"));
        write(entry("source", "target", ""));
        var session = new HomeLightSession(config);
        session.choose(root.resolve("source"), DecisionChoice.ADOPT_TARGET);
        assertTrue(session.requestApply());
        Files.writeString(config, "homelight: [");
        session.refresh();
        assertInstanceOf(ConfigurationEvaluation.Invalid.class, session.evaluation());
        assertInstanceOf(ApplyModel.Idle.class, session.applyModel());
        assertFalse(session.requestApply());
        assertEquals(ConfigurationEvaluation.DiscardReason.CONFIGURATION_UNAVAILABLE,
                session.discardedChoices().getFirst().reason());
        Files.delete(config);
        assertInstanceOf(ConfigurationEvaluation.Missing.class, evaluator.load(config));
        assertInstanceOf(PlanModel.Invalid.class, PlanWorkflow.from(evaluator.load(config)));
        assertThrows(ConfigurationLoader.ConfigurationException.class, () -> evaluator.loadRequired(config, Map.of()));
        Files.createDirectory(config);
        assertInstanceOf(ConfigurationEvaluation.Invalid.class, evaluator.load(config));
    }

    @Test
    void runningAndRetainedResultsRejectEditsUntilExplicitReplan() throws Exception {
        Files.createDirectory(root.resolve("target"));
        write(entry("source", "target", ""));
        var session = new HomeLightSession(config);
        session.choose(root.resolve("source"), DecisionChoice.ADOPT_TARGET);
        assertTrue(session.requestApply());
        var tasks = new ArrayList<Runnable>();
        session.confirmApply(tasks::add);
        var before = session.evaluation();
        assertThrows(IllegalStateException.class, () -> session.choose(root.resolve("source"), DecisionChoice.ADOPT_TARGET));
        session.refresh();
        assertSame(before, session.evaluation());
        tasks.getFirst().run();
        assertInstanceOf(ApplyModel.Result.class, session.applyModel());
        assertThrows(IllegalStateException.class, () -> session.choose(root.resolve("source"), DecisionChoice.ADOPT_TARGET));
        session.refresh();
        assertInstanceOf(ApplyModel.Idle.class, session.applyModel());
        assertEquals(ConfigurationEvaluation.DiscardReason.UNAVAILABLE, session.discardedChoices().getFirst().reason());
        assertTrue(session.isPlanReady());
    }

    private ConfigurationEvaluation.Loaded loaded() {
        return assertInstanceOf(ConfigurationEvaluation.Loaded.class, evaluator.load(config));
    }

    private void bothDirectories(String source, String target) throws Exception {
        Files.createDirectory(root.resolve(source));
        Files.createDirectory(root.resolve(target));
    }

    private void write(String entries) throws Exception {
        Files.writeString(config, "homelight:\n  target-root: " + root + "\n  relocations:\n" + entries);
    }

    private String entry(String source, String target, String policies) {
        return "    - source-path: " + root.resolve(source) + "\n      target-path: " + root.resolve(target) + "\n" + policies;
    }
}
