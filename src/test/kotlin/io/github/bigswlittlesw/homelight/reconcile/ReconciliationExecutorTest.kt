package io.github.bigswlittlesw.homelight.reconcile

import io.github.bigswlittlesw.homelight.config.Relocation
import io.github.bigswlittlesw.homelight.config.WhenAdoptingTarget
import io.github.bigswlittlesw.homelight.config.WhenSourceAndTargetDirectoriesExist
import io.github.bigswlittlesw.homelight.fs.PathInspector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

class ReconciliationExecutorTest {
    @Test
    fun rejectsUnresolvedPlans(@TempDir root: Path) {
        val source = Files.createDirectories(root.resolve("source"))
        val target = Files.createDirectories(root.resolve("target"))

        assertThrows<IllegalArgumentException> { ReconciliationExecutor().execute(plan(Relocation(source, target))) }
    }

    @Test
    fun createsAnAbsentSourceLinkAndTargetDirectory(@TempDir root: Path) {
        val source = root.resolve("source")
        val target = root.resolve("target")

        val result = ReconciliationExecutor().execute(plan(Relocation(source, target)))

        assertTrue(result.succeeded())
        assertTrue(Files.isSymbolicLink(source))
    }

    @Test
    fun archivesTheSourceBeforeLinkingToAnAdoptedTarget(@TempDir root: Path) {
        val source = Files.createDirectories(root.resolve("home/cache"))
        Files.writeString(source.resolve("entry"), "source")
        val target = Files.createDirectories(root.resolve("local/cache"))
        val archiveRoot = root.resolve("archive")
        val relocation = Relocation(source, target,
                WhenSourceAndTargetDirectoriesExist.ADOPT, whenAdoptingTarget = WhenAdoptingTarget.ARCHIVE_SOURCE,
                archiveRoot = archiveRoot)

        val result = ReconciliationExecutor().execute(plan(relocation))

        assertTrue(result.succeeded())
        assertTrue(Files.isSymbolicLink(source))
        assertTrue(Files.exists(archiveRoot.resolve(source.toAbsolutePath().root.relativize(source.toAbsolutePath()))
                .resolve("entry")))
    }

    @Test
    fun reportsFailedRecoveryWhenPublicationSucceedsButSourceReplacementCannotStart(@TempDir root: Path) {
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
                                } catch (exception: IOException) {
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
    fun reportsAnUnresolvedResultWhenPlannedStateIsStale(@TempDir root: Path) {
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
    fun reportsTypedDriftAtAnActionBoundaryAfterSuccessfulPreflight(@TempDir root: Path) {
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
                    } catch (exception: IOException) {
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

    /** A failed internal precondition is a bug: it propagates, and staging is cleaned up as on an I/O failure. */
    @ParameterizedTest
    @EnumSource(ReconciliationExecutor.StagingStep::class, names = ["CREATED", "LOCKED", "MARKED", "COPIED"])
    internal fun aFailedPreconditionPropagatesAfterStagingCleanup(step: ReconciliationExecutor.StagingStep, @TempDir root: Path) {
        val source = Files.createDirectories(root.resolve("home/cache"))
        Files.writeString(source.resolve("entry"), "source")
        val target = root.resolve("local/cache")
        val plan = plan(Relocation(source, target))
        val executor = ReconciliationExecutor(1) { at, _ -> check(at != step) { "injected bug" } }

        val bug = assertThrows<IllegalStateException> { executor.execute(plan) }

        assertEquals("injected bug", bug.message)
        assertEquals("source", Files.readString(source.resolve("entry")))
        assertTrue(Files.notExists(target))
        Files.list(target.resolveSibling(".homelight-staging")).use { entries -> assertEquals(listOf<Path>(), entries.toList()) }
        assertTrue(ReconciliationExecutor().execute(plan).succeeded())
    }

    /**
     * Existing ancestors may be symlinks (#128). The fixture is never resolved with `toRealPath()`: on macOS `@TempDir`
     * is already under `/var -> /private/var`, and `link` adds a symlinked ancestor on every platform. The first
     * target's immediate parent is the link itself, as `EnsureDirectory` and the default staging root see it.
     */
    @Test
    fun migratesAndLinksUnderSymlinkedAncestors(@TempDir root: Path) {
        val real = Files.createDirectory(root.resolve("real"))
        val link = Files.createSymbolicLink(root.resolve("link"), real)
        val source = Files.createDirectories(link.resolve("home/cache"))
        Files.writeString(source.resolve("entry"), "source")
        val target = link.resolve("cache")
        val absentSource = link.resolve("home/new")
        val newTarget = link.resolve("store/new")
        val explicitStaging = Relocation(Files.createDirectories(link.resolve("home/other")), link.resolve("store/other"),
                stagingRoot = link.resolve("store/.staging"))

        for (relocation in listOf(Relocation(source, target), Relocation(absentSource, newTarget), explicitStaging)) {
            val result = ReconciliationExecutor().execute(plan(relocation))
            assertTrue(result.succeeded(), result.toString())
        }

        assertEquals(target, Files.readSymbolicLink(source))
        assertEquals("source", Files.readString(real.resolve("cache/entry")))
        assertEquals(newTarget, Files.readSymbolicLink(absentSource))
        assertEquals(explicitStaging.targetPath, Files.readSymbolicLink(explicitStaging.sourcePath))
        for (created in listOf("cache", ".homelight-staging", "store", "store/new", "store/other", "store/.staging")) {
            assertTrue(Files.isDirectory(real.resolve(created), LinkOption.NOFOLLOW_LINKS), created)
        }
    }

    /** A symlink where HomeLight needs a real directory, the staging root itself or one it would create, is refused. */
    @Test
    fun refusesASymlinkedStagingRootOrADanglingLinkWhereADirectoryWouldBeCreated(@TempDir root: Path) {
        val elsewhere = Files.createDirectory(root.resolve("elsewhere"))
        val store = Files.createDirectory(root.resolve("store"))
        val linkedStaging = Files.createSymbolicLink(store.resolve(".staging"), elsewhere)
        val dangling = Files.createSymbolicLink(root.resolve("dangling"), root.resolve("missing"))

        for ((relocation, refused) in listOf(
            Relocation(Files.createDirectories(root.resolve("home/a")), store.resolve("a"), stagingRoot = linkedStaging)
                to linkedStaging,
            Relocation(Files.createDirectories(root.resolve("home/b")), dangling.resolve("b")) to dangling,
            Relocation(root.resolve("home/c"), dangling.resolve("c")) to dangling,
        )) {
            val actions = ReconciliationExecutor().execute(plan(relocation)).relocations.single().actions
            val failed = actions.single { action -> action.status == ReconciliationExecutor.ActionStatus.FAILED }

            assertEquals("expected real directory at $refused", failed.message, relocation.toString())
            assertTrue(failed.stateDrift)
            assertTrue(Files.notExists(relocation.targetPath, LinkOption.NOFOLLOW_LINKS))
        }
        assertEquals(listOf<Path>(), Files.list(elsewhere).use { it.toList() })
        assertTrue(Files.notExists(root.resolve("missing"), LinkOption.NOFOLLOW_LINKS))
    }

    companion object {
        private fun plan(relocation: Relocation): ReconciliationPlan {
            val inspector = PathInspector()
            val source = relocation.sourcePath.toAbsolutePath()
            val path = relocation.archiveRoot.resolve(source.root.relativize(source))
            val archive = RelocationState.ArchiveDestination(path, inspector.inspect(path))
            return ReconciliationPlanner().plan(listOf(RelocationState(relocation,
                    inspector.inspect(relocation.sourcePath), inspector.inspect(relocation.targetPath), archive)))
        }
    }
}
