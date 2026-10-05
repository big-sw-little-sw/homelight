package io.github.bigswlittlesw.homelight.application

import io.github.bigswlittlesw.homelight.config.ConfigurationException
import io.github.bigswlittlesw.homelight.config.WhenAdoptingTarget
import io.github.bigswlittlesw.homelight.config.WhenSourceAndTargetDirectoriesExist
import io.github.bigswlittlesw.homelight.domain.RelocationSourceState
import io.github.bigswlittlesw.homelight.fs.PathState
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlanner
import io.github.bigswlittlesw.homelight.reconcile.RelocationOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.nio.file.Files
import java.nio.file.Path

class ConfigurationEvaluationTest {
    @TempDir lateinit var temporary: Path
    private lateinit var root: Path
    private lateinit var config: Path
    private val evaluator = ConfigurationEvaluation()

    @BeforeEach
    fun setUp() {
        root = temporary.toRealPath()
        config = root.resolve("config.json")
    }

    @ParameterizedTest
    @EnumSource(DecisionChoice::class)
    fun choicesPreserveSavedPolicyAndMatchPlannerWithoutWrites(choice: DecisionChoice) {
        val source = root.resolve("source")
        val target = Files.createDirectory(root.resolve("target"))
        if (choice != DecisionChoice.ADOPT_TARGET) {
            Files.createDirectory(source)
            Files.writeString(source.resolve("content"), "source")
        }
        Files.writeString(target.resolve("content"), "target")
        write(entry("source", "target", mapOf(
                "when-source-and-target-directories-exist" to "prompt",
                "when-only-target-exists" to "prompt",
                "archive-root" to archive())))
        val json = Files.readString(config)
        val loaded = loaded()
        val selected = evaluator.choose(loaded, root.resolve("child/../source"), choice)

        assertTrue(loaded.draft.isEmpty())
        assertTrue(selected.savedPlan.hasConflicts())
        assertFalse(selected.plan.hasConflicts())
        assertFalse(selected.plan.hasBlockedActions())
        assertEquals(mapOf(source to choice), selected.draft)
        assertEquals(loaded.savedConfiguration, selected.savedConfiguration)
        assertSame(loaded.observations.first(), selected.observations.first())
        assertEquals(WhenSourceAndTargetDirectoriesExist.PROMPT,
                selected.savedConfiguration.relocations.first().whenSourceAndTargetDirectoriesExist)

        // Compare with the established loader + pure planner path using saved policies.
        val properties = when (choice) {
            DecisionChoice.ADOPT_TARGET -> mapOf("when-only-target-exists" to "adopt-target")
            DecisionChoice.ADOPT_AND_DISCARD_SOURCE -> mapOf("when-source-and-target-directories-exist" to "adopt",
                    "when-adopting-target" to "discard-source")
            DecisionChoice.ADOPT_AND_ARCHIVE_SOURCE -> mapOf("when-source-and-target-directories-exist" to "adopt",
                    "when-adopting-target" to "archive-source")
            DecisionChoice.LEAVE_UNCHANGED -> mapOf("when-source-and-target-directories-exist" to "leave-unchanged")
            DecisionChoice.DISCARD_BOTH -> mapOf("when-source-and-target-directories-exist" to "discard")
        }
        val policies = LinkedHashMap<String, String>()
        policies.put("when-source-and-target-directories-exist", "prompt")
        policies.put("when-only-target-exists", "prompt")
        policies.put("archive-root", archive())
        policies.putAll(properties)
        val saved = root.resolve("saved.json")
        Files.writeString(saved, document(entry("source", "target", policies)))
        val expected = evaluator.loadRequired(saved)
        assertEquals(expected.plan, selected.plan)
        assertEquals(ReconciliationPlanner().plan(selected.plan.expectedStates), selected.plan)
        assertEquals(json, Files.readString(config))
        assertEquals("target", Files.readString(target.resolve("content")))
        if (choice == DecisionChoice.ADOPT_TARGET) {
            assertFalse(Files.exists(source))
        } else {
            assertEquals("source", Files.readString(source.resolve("content")))
        }
        assertFalse(Files.exists(root.resolve("archive")))
    }

    @Test
    fun choosingUsesRetainedConfigSourceTargetAndArchiveObservations() {
        Files.createDirectory(root.resolve("source"))
        Files.createDirectory(root.resolve("target"))
        write(entry("source", "target", mapOf("archive-root" to archive())))
        val session = HomeLightSession(config)
        val original = assertInstanceOf(ConfigurationEvaluation.Loaded::class.java, session.evaluation())
        val observation = original.observations.first()
        val archive = checkNotNull(observation.archiveDestination)
        assertEquals(root.resolve("archive").resolve(root.root.relativize(root.resolve("source"))), archive.path)
        assertEquals(PathState.ABSENT, archive.observation.state)

        Files.delete(root.resolve("source"))
        Files.createSymbolicLink(root.resolve("source"), root.resolve("target"))
        Files.writeString(root.resolve("target/content"), "changed")
        Files.createDirectories(archive.path)
        Files.writeString(config, "{\"malformed\": [")
        session.choose(root.resolve("source"), DecisionChoice.ADOPT_AND_ARCHIVE_SOURCE)

        val selected = assertInstanceOf(ConfigurationEvaluation.Loaded::class.java, session.evaluation())
        val plan = selected.items.first()
        assertSame(original.savedPlan, selected.savedPlan)
        assertSame(observation.source, plan.sourceObservation)
        assertEquals(RelocationSourceState.DIRECTORY, plan.sourceState)
        assertTrue(selected.plan.actions().any { it is ReconciliationAction.ArchiveDirectory })
        assertFalse(selected.plan.hasBlockedActions())
        assertEquals("{\"malformed\": [", Files.readString(config))
        assertTrue(Files.isSymbolicLink(root.resolve("source")))
        assertTrue(Files.isDirectory(archive.path))
        assertEquals("changed", Files.readString(root.resolve("target/content")))
    }

    @Test
    fun replacingChoiceCancelsReviewAndRecheckClearsIt() {
        bothDirectories("source", "target")
        write(entry("source", "target", mapOf("archive-root" to archive())))
        val session = HomeLightSession(config)
        session.choose(root.resolve("source"), DecisionChoice.ADOPT_AND_ARCHIVE_SOURCE)
        assertTrue(session.requestApply())
        session.choose(root.resolve("source"), DecisionChoice.LEAVE_UNCHANGED)
        assertInstanceOf(ApplyModel.Idle::class.java, session.applyModel())
        val selected = assertInstanceOf(ConfigurationEvaluation.Loaded::class.java, session.evaluation())
        assertEquals(1, selected.draft.size)
        assertEquals(WhenAdoptingTarget.PROMPT, selected.plan.relocations.first().relocation.whenAdoptingTarget)
        assertTrue(session.requestApply())
        session.refresh()
        assertInstanceOf(ApplyModel.Idle::class.java, session.applyModel())
        // A re-check clears the choice even though nothing on disk changed.
        val rechecked = assertInstanceOf(ConfigurationEvaluation.Loaded::class.java, session.evaluation())
        assertTrue(rechecked.draft.isEmpty())
        assertEquals(rechecked.savedPlan, rechecked.plan)
    }

    @Test
    fun rejectsUnknownAndUnavailableChoicesWithoutChangingReview() {
        Files.createDirectory(root.resolve("target"))
        write(entry("source", "target"))
        val session = HomeLightSession(config)
        session.choose(root.resolve("source"), DecisionChoice.ADOPT_TARGET)
        assertTrue(session.requestApply())
        val review = session.applyModel()
        val before = session.evaluation()
        assertThrows<IllegalArgumentException> { session.choose(root.resolve("unknown"), DecisionChoice.ADOPT_TARGET) }
        assertThrows<IllegalArgumentException> { session.choose(root.resolve("source"), DecisionChoice.DISCARD_BOTH) }
        assertSame(before, session.evaluation())
        assertSame(review, session.applyModel())

        bothDirectories("other-source", "other-target")
        write(entry("other-source", "other-target"))
        // Archiving is always offered; without an archive-root it goes beside the source.
        val archived = evaluator.choose(loaded(), root.resolve("other-source"), DecisionChoice.ADOPT_AND_ARCHIVE_SOURCE)
        val archive = archived.plan.actions().filterIsInstance<ReconciliationAction.ArchiveDirectory>().single()
        assertTrue(archive.target.startsWith(root.resolve(".homelight-archive")), archive.target.toString())
    }

    @Test
    fun duplicateSourceCannotReceiveAnAmbiguousDraft() {
        bothDirectories("source", "target")
        write(entry("source", "target"), entry("source", "target"))
        val duplicate = loaded()
        assertTrue(duplicate.plan.hasBlockedActions())
        assertFalse(duplicate.plan.diagnostics.isEmpty())
        assertThrows<IllegalArgumentException> { evaluator.choose(duplicate, root.resolve("source"), DecisionChoice.DISCARD_BOTH) }
    }

    @Test
    fun recheckClassifiesCurrentSourceWithoutTheEarlierChoice() {
        Files.createDirectory(root.resolve("target"))
        write(entry("source", "target"))
        val session = HomeLightSession(config)
        session.choose(root.resolve("source"), DecisionChoice.ADOPT_TARGET)
        Files.createSymbolicLink(root.resolve("source"), root.resolve("target"))
        session.refresh()
        val next = assertInstanceOf(ConfigurationEvaluation.Loaded::class.java, session.evaluation())
        assertTrue(next.draft.isEmpty())
        assertEquals(RelocationSourceState.CORRECT_SYMLINK, next.items.first().sourceState)
    }

    @Test
    fun missingAndMalformedConfigClearDraftAndRequireFreshReview() {
        Files.createDirectory(root.resolve("target"))
        write(entry("source", "target"))
        val session = HomeLightSession(config)
        session.choose(root.resolve("source"), DecisionChoice.ADOPT_TARGET)
        assertTrue(session.requestApply())
        Files.writeString(config, "{\"homelight\": [")
        session.refresh()
        assertInstanceOf(ConfigurationEvaluation.Invalid::class.java, session.evaluation())
        assertInstanceOf(ApplyModel.Idle::class.java, session.applyModel())
        assertFalse(session.requestApply())
        Files.delete(config)
        val missing = assertInstanceOf(ConfigurationEvaluation.Missing::class.java, evaluator.load(config))
        assertTrue(missing.message.contains("does not exist"), missing.message)
        assertThrows<ConfigurationException> { evaluator.loadRequired(config) }
        Files.createDirectory(config)
        assertInstanceOf(ConfigurationEvaluation.Invalid::class.java, evaluator.load(config))
    }

    @Test
    fun runningAndRetainedResultsRejectEditsUntilExplicitReplan() {
        Files.createDirectory(root.resolve("target"))
        write(entry("source", "target"))
        val session = HomeLightSession(config)
        session.choose(root.resolve("source"), DecisionChoice.ADOPT_TARGET)
        assertTrue(session.requestApply())
        val tasks = mutableListOf<Runnable>()
        session.confirmApply(tasks::add)
        val before = session.evaluation()
        assertThrows<IllegalStateException> { session.choose(root.resolve("source"), DecisionChoice.ADOPT_TARGET) }
        session.refresh()
        assertSame(before, session.evaluation())
        tasks.first().run()
        assertInstanceOf(ApplyModel.Result::class.java, session.applyModel())
        // Applying clears the draft and re-inspects, so Workspace shows the applied state.
        val applied = assertInstanceOf(ConfigurationEvaluation.Loaded::class.java, session.evaluation())
        assertTrue(applied.draft.isEmpty())
        assertEquals(PlanBadge.IN_SYNC, applied.items.single().badge())
        assertThrows<IllegalStateException> { session.choose(root.resolve("source"), DecisionChoice.ADOPT_TARGET) }
        session.refresh()
        assertInstanceOf(ApplyModel.Idle::class.java, session.applyModel())
        assertTrue(session.isPlanReady())
    }

    @Test
    fun itemsDescribeStagedPublicationWithoutTouchingTheFilesystem() {
        Files.writeString(Files.createDirectories(root.resolve("home/cache")).resolve("file.txt"), "hello")
        write(entry("home/cache", "local/cache"))
        val item = loaded().items.single()
        assertEquals(PlanBadge.MIGRATE, item.badge())
        assertEquals(RelocationOutcome.CONVERGED, item.plan.outcome)
        assertTrue(item.plan.actions.any { it is ReconciliationAction.MigrateDirectoryForPublication })
        assertTrue(item.plan.actions.any { it is ReconciliationAction.ReplaceDirectoryWithSymlink })
        assertTrue(item.hasDestructiveActions())
        assertFalse(item.hasConflict())
        assertFalse(Files.isSymbolicLink(root.resolve("home/cache")))
        assertFalse(Files.exists(root.resolve("local/cache")))
    }

    @Test
    fun onlyTargetConflictOffersAdoptTargetAndChoosingLinks() {
        Files.writeString(Files.createDirectories(root.resolve("local/cache")).resolve("file.txt"), "target")
        write(entry("home/cache", "local/cache"))
        val session = HomeLightSession(config)
        val item = assertInstanceOf(ConfigurationEvaluation.Loaded::class.java, session.evaluation()).items.single()
        assertEquals(PlanBadge.CONFLICT, item.badge())
        assertTrue(item.hasConflict())
        assertEquals(listOf(DecisionChoice.ADOPT_TARGET), item.availableResolutions)
        assertTrue(session.hasConflicts())
        assertFalse(session.isPlanReady())

        session.choose(item.relocation.sourcePath, DecisionChoice.ADOPT_TARGET)
        assertFalse(session.hasConflicts())
        assertTrue(session.isPlanReady())
        val resolved = assertInstanceOf(ConfigurationEvaluation.Loaded::class.java, session.evaluation()).items.single()
        assertEquals(PlanBadge.LINK, resolved.badge())
        assertEquals(RelocationOutcome.CONVERGED, resolved.plan.outcome)
        assertTrue(resolved.plan.actions.any { it is ReconciliationAction.CreateSymlink })
        assertFalse(Files.exists(root.resolve("home/cache")))
    }

    @Test
    fun bothDirectoriesOfferEveryChoiceAndEachChangesTheBadge() {
        bothDirectories("source", "target")
        write(entry("source", "target", mapOf("archive-root" to archive())))
        val item = loaded().items.single()
        assertEquals(PlanBadge.CONFLICT, item.badge())
        assertEquals(
            listOf(DecisionChoice.ADOPT_AND_DISCARD_SOURCE, DecisionChoice.ADOPT_AND_ARCHIVE_SOURCE,
                DecisionChoice.LEAVE_UNCHANGED, DecisionChoice.DISCARD_BOTH),
            item.availableResolutions,
        )
        val archived = evaluator.choose(loaded(), item.relocation.sourcePath, DecisionChoice.ADOPT_AND_ARCHIVE_SOURCE)
        assertEquals(PlanBadge.BACKUP, archived.items.single().badge())
        assertTrue(archived.items.single().plan.actions.any { it is ReconciliationAction.ArchiveDirectory })
        val discarded = evaluator.choose(loaded(), item.relocation.sourcePath, DecisionChoice.DISCARD_BOTH).items.single()
        assertEquals(PlanBadge.DISCARD, discarded.badge())
        assertTrue(discarded.hasDestructiveActions())
        assertFalse(discarded.plan.diagnostics.isEmpty())
    }

    @Test
    fun correctLinkIsInSyncWithoutDestructiveActions() {
        Files.createDirectories(root.resolve("home"))
        Files.createSymbolicLink(root.resolve("home/cache"), Files.createDirectories(root.resolve("local/cache")))
        write(entry("home/cache", "local/cache"))
        val item = loaded().items.single()
        assertEquals(PlanBadge.IN_SYNC, item.badge())
        assertEquals(RelocationOutcome.CONVERGED, item.plan.outcome)
        assertFalse(item.hasDestructiveActions())
    }

    private fun loaded(): ConfigurationEvaluation.Loaded {
        return assertInstanceOf(ConfigurationEvaluation.Loaded::class.java, evaluator.load(config))
    }

    private fun bothDirectories(source: String, target: String) {
        Files.createDirectory(root.resolve(source))
        Files.createDirectory(root.resolve(target))
    }

    private fun write(vararg entries: String) {
        Files.writeString(config, document(*entries))
    }

    private fun document(vararg entries: String): String =
        "{\"homelight\": {\"target-root\": \"$root\", \"relocations\": [\n" + entries.joinToString(",\n") + "\n]}}\n"

    private fun entry(source: String, target: String, policies: Map<String, String> = mapOf()): String {
        val fields = mapOf("source-path" to root.resolve(source).toString(), "target-path" to root.resolve(target).toString()) + policies
        return fields.entries.joinToString(", ", "  {", "}") { (key, value) -> "\"$key\": \"$value\"" }
    }

    private fun archive(): String = root.resolve("archive").toString()
}
