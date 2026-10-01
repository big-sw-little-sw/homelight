package io.github.bigswlittlesw.homelight.reconcile

import io.github.bigswlittlesw.homelight.config.Relocation
import io.github.bigswlittlesw.homelight.config.WhenAdoptingTarget
import io.github.bigswlittlesw.homelight.config.WhenSourceAndTargetDirectoriesExist
import io.github.bigswlittlesw.homelight.fs.PathInspector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

class ReconciliationExecutorTest {
    @Test
    fun rejectsUnresolvedPlans() {
        val root = Files.createTempDirectory("homelight")
        val source = Files.createDirectories(root.resolve("source"))
        val target = Files.createDirectories(root.resolve("target"))

        assertThrows(IllegalArgumentException::class.java) { ReconciliationExecutor().execute(plan(Relocation(source, target))) }
    }

    @Test
    fun createsAnAbsentSourceLinkAndTargetDirectory() {
        val root = Files.createTempDirectory("homelight")
        val source = root.resolve("source")
        val target = root.resolve("target")

        val result = ReconciliationExecutor().execute(plan(Relocation(source, target)))

        assertTrue(result.succeeded())
        assertTrue(Files.isSymbolicLink(source))
    }

    @Test
    fun archivesTheSourceBeforeLinkingToAnAdoptedTarget() {
        val root = Files.createTempDirectory("homelight")
        val source = Files.createDirectories(root.resolve("home/cache"))
        Files.writeString(source.resolve("entry"), "source")
        val target = Files.createDirectories(root.resolve("local/cache"))
        val archiveRoot = root.resolve("archive")
        val relocation = Relocation(source, target,
                java.util.Optional.of(WhenSourceAndTargetDirectoriesExist.ADOPT), java.util.Optional.empty(),
                java.util.Optional.of(WhenAdoptingTarget.ARCHIVE_SOURCE), java.util.Optional.of(archiveRoot))

        val result = ReconciliationExecutor().execute(plan(relocation))

        assertTrue(result.succeeded())
        assertTrue(Files.isSymbolicLink(source))
        assertTrue(Files.exists(archiveRoot.resolve(source.toAbsolutePath().root.relativize(source.toAbsolutePath()))
                .resolve("entry")))
    }

    @Test
    fun reportsFailedRecoveryWhenPublicationSucceedsButSourceReplacementCannotStart() {
        val root = Files.createTempDirectory("homelight").toRealPath()
        val sourceParent = Files.createDirectories(root.resolve("home"))
        val source = Files.createDirectories(sourceParent.resolve("cache"))
        Files.writeString(source.resolve("entry"), "source")
        val target = root.resolve("local/cache")
        val originalPermissions = Files.getPosixFilePermissions(sourceParent)
        var result = ReconciliationExecutor.ExecutionResult(listOf())

        try {
            result = ReconciliationExecutor().execute(plan(Relocation(source, target)),
                    object : ReconciliationExecutor.ProgressListener {
                        override fun finished(relocation: RelocationPlan, action: ReconciliationExecutor.ActionExecution) {
                            if (action.action is ReconciliationAction.MigrateDirectoryForPublication) {
                                try {
                                    Files.setPosixFilePermissions(sourceParent, setOf(
                                            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE))
                                } catch (exception: java.io.IOException) {
                                    throw AssertionError(exception)
                                }
                            }
                        }
                    })
        } finally {
            Files.setPosixFilePermissions(sourceParent, originalPermissions)
        }

        assertEquals(ReconciliationExecutor.ExecutionOutcome.FAILED_RECOVERY, result.relocations.first().outcome())
        assertTrue(Files.isDirectory(source))
        assertTrue(Files.isDirectory(target))
        Files.list(target.parent.resolve(".homelight-staging")).use { stagingEntries ->
            assertTrue(stagingEntries.findAny().isEmpty())
        }
        assertTrue(plan(Relocation(source, target)).hasConflicts())
    }

    @Test
    fun reportsAnUnresolvedResultWhenPlannedStateIsStale() {
        val root = Files.createTempDirectory("homelight")
        val source = root.resolve("source")
        val target = root.resolve("target")
        val relocation = Relocation(source, target)
        val plan = plan(relocation)
        Files.createDirectories(target)

        val result = ReconciliationExecutor().execute(plan)

        assertEquals(ReconciliationExecutor.ExecutionOutcome.UNRESOLVED, result.relocations.first().outcome())
        assertTrue(Files.isDirectory(target))
        assertTrue(Files.notExists(source))
    }

    @Test
    fun reportsTypedDriftAtAnActionBoundaryAfterSuccessfulPreflight() {
        val root = Files.createTempDirectory("homelight").toRealPath()
        val source = root.resolve("home/cache")
        val target = root.resolve("local/cache")
        val plan = plan(Relocation(source, target))
        val executor = ReconciliationExecutor()
        assertTrue(executor.preflight(plan).isEmpty())

        val result = executor.execute(plan, object : ReconciliationExecutor.ProgressListener {
            override fun finished(relocation: RelocationPlan, action: ReconciliationExecutor.ActionExecution) {
                if (action.action is ReconciliationAction.EnsureDirectory && action.action.path.equals(target.parent)) {
                    try {
                        Files.createDirectory(target)
                    } catch (exception: java.io.IOException) {
                        throw AssertionError(exception)
                    }
                }
            }
        })

        val actions = result.relocations.first().actions
        assertEquals(ReconciliationExecutor.ActionStatus.COMPLETED, actions.get(0).status)
        assertEquals(ReconciliationExecutor.ActionStatus.FAILED, actions.get(1).status)
        assertTrue(actions.get(1).stateDrift)
        assertEquals(ReconciliationExecutor.ActionStatus.PENDING, actions.get(2).status)
        assertTrue(Files.notExists(source))
    }

    companion object {
        private fun plan(relocation: Relocation): ReconciliationPlan {
            val inspector = PathInspector()
            val archive = relocation.sourceArchiveRoot.map { root ->
                val source = relocation.sourcePath.toAbsolutePath()
                val path = root.resolve(source.root.relativize(source))
                RelocationState.ArchiveDestination(path, inspector.inspect(path))
            }
            return ReconciliationPlanner().plan(listOf(RelocationState(relocation,
                    inspector.inspect(relocation.sourcePath), inspector.inspect(relocation.targetPath), archive)))
        }
    }
}
