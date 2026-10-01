package io.github.bigswlittlesw.homelight.application

import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction
import io.github.bigswlittlesw.homelight.reconcile.RelocationOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

// Java text blocks end with the newline before the closing delimiter, and `trimIndent` drops it,
// so each block appends "\n" to keep the text byte-identical.
class PlanWorkflowTest {

    @Test
    fun returnsUnconfiguredWhenDefaultConfigMissing() {
        val workflow = PlanWorkflow()
        val model = workflow.loadPlan(Path.of(System.getProperty("user.home"), ".homelight.json"))

        if (!Files.exists(Path.of(System.getProperty("user.home"), ".homelight.json"))) {
            assertInstanceOf(PlanModel.Unconfigured::class.java, model)
        }
    }

    @Test
    fun returnsInvalidWhenConfigFileDoesNotExist() {
        val workflow = PlanWorkflow()
        val model = workflow.loadPlan(Path.of("/nonexistent/path/homelight.json"))

        assertInstanceOf(PlanModel.Invalid::class.java, model)
        assertTrue((model as PlanModel.Invalid).message.contains("does not exist"))
    }

    @Test
    fun plansStagedPublicationForExistingSource() {
        val root = Files.createTempDirectory("homelight-plan-test").toRealPath()
        val source = Files.createDirectories(root.resolve("home/cache"))
        val target = root.resolve("local/cache")
        Files.writeString(source.resolve("file.txt"), "hello")

        val config = Files.createTempFile("homelight", ".json")
        Files.writeString(config, ("""
                {
                  "homelight": {
                    "target-root": "%s",
                    "relocations": [
                      {"source-path": "%s", "target-path": "%s"}
                    ]
                  }
                }
                """.trimIndent() + "\n").format(root, source, target))

        val workflow = PlanWorkflow()
        val model = workflow.loadPlan(config)

        assertInstanceOf(PlanModel.Configured::class.java, model)
        val configured = model as PlanModel.Configured

        assertEquals(1, configured.items.size)
        val item = configured.items.first()

        assertEquals(PlanBadge.MIGRATE, item.badge())
        assertEquals(RelocationOutcome.CONVERGED, item.plan.outcome)
        assertTrue(item.plan.actions.stream().anyMatch(ReconciliationAction.MigrateDirectoryForPublication::class.java::isInstance))
        assertTrue(item.plan.actions.stream().anyMatch(ReconciliationAction.ReplaceDirectoryWithSymlink::class.java::isInstance))
        assertTrue(item.hasDestructiveActions())
        assertFalse(item.hasConflict())

        // Filesystem is completely unmodified
        assertTrue(Files.isDirectory(source))
        assertFalse(Files.isSymbolicLink(source))
        assertFalse(Files.exists(target))
    }

    @Test
    fun detectsConflictWhenOnlyTargetExistsAndProvidesTypedResolutions() {
        val root = Files.createTempDirectory("homelight-plan-test").toRealPath()
        val source = root.resolve("home/cache")
        val target = Files.createDirectories(root.resolve("local/cache"))
        Files.writeString(target.resolve("file.txt"), "target content")

        val config = Files.createTempFile("homelight", ".json")
        Files.writeString(config, ("""
                {
                  "homelight": {
                    "target-root": "%s",
                    "relocations": [
                      {"source-path": "%s", "target-path": "%s"}
                    ]
                  }
                }
                """.trimIndent() + "\n").format(root, source, target))

        val workflow = PlanWorkflow()
        val model = workflow.loadPlan(config)

        assertInstanceOf(PlanModel.Configured::class.java, model)
        val configured = model as PlanModel.Configured

        val item = configured.items.first()
        assertEquals(PlanBadge.CONFLICT, item.badge())
        assertTrue(item.hasConflict())
        assertEquals(1, item.availableResolutions.size)
        assertEquals(DecisionChoice.ADOPT_TARGET, item.availableResolutions.first())

        // Test resolving through HomeLightSession
        val session = HomeLightSession(config)
        assertTrue(session.hasConflicts())
        assertFalse(session.isPlanReady())

        session.resolveDecision(item.relocation, DecisionChoice.ADOPT_TARGET)

        assertFalse(session.hasConflicts())
        assertTrue(session.isPlanReady())
        assertInstanceOf(PlanModel.Configured::class.java, session.planModel())
        val resolvedModel = session.planModel() as PlanModel.Configured
        val resolvedItem = resolvedModel.items.first()

        assertEquals(PlanBadge.LINK, resolvedItem.badge())
        assertEquals(RelocationOutcome.CONVERGED, resolvedItem.plan.outcome)
        assertTrue(resolvedItem.plan.actions.stream().anyMatch(ReconciliationAction.CreateSymlink::class.java::isInstance))

        // Filesystem still untouched
        assertFalse(Files.exists(source))
        assertTrue(Files.isDirectory(target))
    }

    @Test
    fun detectsConflictWhenBothDirectoriesExistAndProvidesAllResolutions() {
        val root = Files.createTempDirectory("homelight-plan-test").toRealPath()
        val source = Files.createDirectories(root.resolve("home/cache"))
        val target = Files.createDirectories(root.resolve("local/cache"))
        val archiveRoot = root.resolve("local/archive")

        val config = Files.createTempFile("homelight", ".json")
        Files.writeString(config, ("""
                {
                  "homelight": {
                    "target-root": "%s",
                    "relocations": [
                      {"source-path": "%s", "target-path": "%s", "source-archive-root": "%s"}
                    ]
                  }
                }
                """.trimIndent() + "\n").format(root, source, target, archiveRoot))

        val session = HomeLightSession(config)
        assertTrue(session.hasConflicts())

        val configured = session.planModel() as PlanModel.Configured
        val item = configured.items.first()
        assertEquals(PlanBadge.CONFLICT, item.badge())

        val resolutions = item.availableResolutions
        assertEquals(4, resolutions.size)
        assertTrue(resolutions.contains(DecisionChoice.ADOPT_AND_DISCARD_SOURCE))
        assertTrue(resolutions.contains(DecisionChoice.ADOPT_AND_ARCHIVE_SOURCE))
        assertTrue(resolutions.contains(DecisionChoice.LEAVE_UNCHANGED))
        assertTrue(resolutions.contains(DecisionChoice.DISCARD_BOTH))

        // Resolve with ADOPT_AND_ARCHIVE_SOURCE
        session.resolveDecision(item.relocation, DecisionChoice.ADOPT_AND_ARCHIVE_SOURCE)
        assertTrue(session.isPlanReady())

        val planModel = session.planModel() as PlanModel.Configured
        val resolvedItem = planModel.items.first()
        assertEquals(PlanBadge.BACKUP, resolvedItem.badge())
        assertTrue(resolvedItem.plan.actions.stream().anyMatch(ReconciliationAction.ArchiveDirectory::class.java::isInstance))
    }

    @Test
    fun resolvesWithDiscardBothProducesDiscardBadgeAndDestructiveWarning() {
        val root = Files.createTempDirectory("homelight-plan-test").toRealPath()
        val source = Files.createDirectories(root.resolve("home/cache"))
        val target = Files.createDirectories(root.resolve("local/cache"))

        val config = Files.createTempFile("homelight", ".json")
        Files.writeString(config, ("""
                {
                  "homelight": {
                    "target-root": "%s",
                    "relocations": [
                      {"source-path": "%s", "target-path": "%s"}
                    ]
                  }
                }
                """.trimIndent() + "\n").format(root, source, target))

        val session = HomeLightSession(config)
        val item = (session.planModel() as PlanModel.Configured).items.first()

        session.resolveDecision(item.relocation, DecisionChoice.DISCARD_BOTH)
        val planModel = session.planModel() as PlanModel.Configured
        val resolvedItem = planModel.items.first()

        assertEquals(PlanBadge.DISCARD, resolvedItem.badge())
        assertTrue(resolvedItem.hasDestructiveActions())
        assertFalse(resolvedItem.plan.diagnostics.isEmpty())
    }

    @Test
    fun handlesConvergedAndNoOpRelocation() {
        val root = Files.createTempDirectory("homelight-plan-test").toRealPath()
        val target = Files.createDirectories(root.resolve("local/cache"))
        val source = root.resolve("home/cache")
        Files.createDirectories(source.parent)
        Files.createSymbolicLink(source, target)

        val config = Files.createTempFile("homelight", ".json")
        Files.writeString(config, ("""
                {
                  "homelight": {
                    "target-root": "%s",
                    "relocations": [
                      {"source-path": "%s", "target-path": "%s"}
                    ]
                  }
                }
                """.trimIndent() + "\n").format(root, source, target))

        val workflow = PlanWorkflow()
        val model = workflow.loadPlan(config)

        assertInstanceOf(PlanModel.Configured::class.java, model)
        val configured = model as PlanModel.Configured
        val item = configured.items.first()

        assertEquals(PlanBadge.IN_SYNC, item.badge())
        assertEquals(RelocationOutcome.CONVERGED, item.plan.outcome)
        assertEquals(1, configured.summary.inSync)
        assertEquals(0, configured.summary.destructive)
    }
}
