package io.github.bigswlittlesw.lighten.reconcile

import io.github.bigswlittlesw.lighten.config.Relocation
import io.github.bigswlittlesw.lighten.config.WhenAdoptingTarget
import io.github.bigswlittlesw.lighten.config.WhenSourceAndTargetDirectoriesExist
import io.github.bigswlittlesw.lighten.fs.PathInspector
import io.github.bigswlittlesw.lighten.fs.PathState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
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
import java.nio.file.attribute.PosixFilePermissions

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
        assertEquals("source", Files.readString(archiveRoot.resolve("cache/entry")))
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
        assertOnlyLockLeft(target)
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
        assertEquals(ActionFailure.Drift(target, PathState.ABSENT, PathState.DIRECTORY), actions.get(1).failure)
        assertEquals(ReconciliationExecutor.ActionStatus.PENDING, actions.get(2).status)
        assertTrue(Files.notExists(source))
    }

    /**
     * A listener that throws while told an action completed does not also record that action as failed. Each action is
     * reported once, and the listener's exception propagates like any other listener bug.
     */
    @Test
    fun aListenerFailureAfterAnActionCompletesDoesNotRecordTheActionTwice(@TempDir root: Path) {
        val plan = plan(Relocation(root.resolve("source"), root.resolve("target")))
        val reported = mutableListOf<ReconciliationExecutor.ActionExecution>()
        val failure = IOException("listener failed")

        val thrown = assertThrows<IOException> {
            ReconciliationExecutor().execute(plan, object : ReconciliationExecutor.ProgressListener {
                override fun finished(relocation: RelocationPlan, action: ReconciliationExecutor.ActionExecution) {
                    reported.add(action)
                    if (action.status == ReconciliationExecutor.ActionStatus.COMPLETED) throw failure
                }
            })
        }

        assertSame(failure, thrown)
        val first = plan.relocations.single().actions.first()
        assertEquals(listOf(first to ReconciliationExecutor.ActionStatus.COMPLETED), reported.map { it.action to it.status })
    }

    /** A failed internal precondition is a bug: it propagates, and staging is cleaned up as on an I/O failure. */
    @ParameterizedTest
    @EnumSource(ReconciliationExecutor.Step::class, names = ["LOCKED", "COPIED"])
    internal fun aFailedPreconditionPropagatesAfterStagingCleanup(step: ReconciliationExecutor.Step, @TempDir root: Path) {
        val source = Files.createDirectories(root.resolve("home/cache"))
        Files.writeString(source.resolve("entry"), "source")
        val target = root.resolve("local/cache")
        val plan = plan(Relocation(source, target))
        val executor = ReconciliationExecutor(1) { at, _ -> check(at != step) { "injected bug" } }

        val bug = assertThrows<IllegalStateException> { executor.execute(plan) }

        assertEquals("injected bug", bug.message)
        assertEquals("source", Files.readString(source.resolve("entry")))
        assertTrue(Files.notExists(target))
        assertOnlyLockLeft(target)
        assertTrue(ReconciliationExecutor().execute(plan).succeeded())
    }

    /** B4: a failure to clean up staging is added to the action's own failure, which it used to replace. */
    @Test
    fun aStagingCleanupFailureDoesNotHideTheOriginalFailure(@TempDir root: Path) {
        val source = Files.createDirectories(root.resolve("home/cache"))
        Files.writeString(source.resolve("entry"), "source")
        val target = root.resolve("local/cache")
        val staging = target.resolveSibling(".lighten-staging")
        val plan = plan(Relocation(source, target))
        val bug = IllegalStateException("injected bug")
        // The copy's contents can be deleted, but the copy cannot be removed from the read-only staging root.
        val executor = ReconciliationExecutor(1) { at, _ ->
            if (at == ReconciliationExecutor.Step.COPIED) {
                Files.setPosixFilePermissions(staging, PosixFilePermissions.fromString("r-x------"))
                throw bug
            }
        }

        try {
            assertSame(bug, assertThrows<IllegalStateException> { executor.execute(plan) })
            assumeFalse(Files.isWritable(staging), "requires directory write denial, not a privileged process")
            assertEquals(listOf(stagedCopy(staging, target).toString()), bug.suppressed.map { it.message })
        } finally {
            Files.setPosixFilePermissions(staging, PosixFilePermissions.fromString("rwx------"))
        }
        assertTrue(Files.notExists(target))
        // The next run of the same target clears the leftover under its lock.
        assertTrue(ReconciliationExecutor().execute(plan).succeeded())
        assertEquals("source", Files.readString(target.resolve("entry")))
        assertOnlyLockLeft(target)
    }

    /** B5: a failure after the copy was moved to the target leaves both directories, which needs recovery. */
    @Test
    fun aFailureAfterPublicationReportsFailedRecovery(@TempDir root: Path) {
        val source = Files.createDirectories(root.resolve("home/cache"))
        Files.writeString(source.resolve("entry"), "source")
        val target = root.resolve("local/cache")
        val executor = ReconciliationExecutor(1) { at, _ ->
            if (at == ReconciliationExecutor.Step.PUBLISHED) throw IOException("injected failure")
        }

        val relocation = executor.execute(plan(Relocation(source, target))).relocations.single()

        assertEquals(ReconciliationExecutor.ExecutionOutcome.FAILED_RECOVERY, relocation.outcome())
        val failed = relocation.actions.single { it.status == ReconciliationExecutor.ActionStatus.FAILED }
        assertEquals("published $target but could not restore its permissions: injected failure", failed.message)
        assertTrue(failed.targetPublished)
        assertEquals(ActionFailure.PermissionsNotRestored(target, "injected failure"), failed.failure)
        assertEquals("source", Files.readString(source.resolve("entry")))
        assertEquals("source", Files.readString(target.resolve("entry")))
        assertOnlyLockLeft(target)
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
        for (created in listOf("cache", ".lighten-staging", "store", "store/new", "store/other", "store/.staging")) {
            assertTrue(Files.isDirectory(real.resolve(created), LinkOption.NOFOLLOW_LINKS), created)
        }
    }

    /** A symlink where Lighten needs a real directory, the staging root itself or one it would create, is refused. */
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
            assertEquals(ActionFailure.Drift(refused, PathState.DIRECTORY, PathState.SYMLINK), failed.failure)
            assertTrue(Files.notExists(relocation.targetPath, LinkOption.NOFOLLOW_LINKS))
        }
        assertEquals(listOf<Path>(), Files.list(elsewhere).use { it.toList() })
        assertTrue(Files.notExists(root.resolve("missing"), LinkOption.NOFOLLOW_LINKS))
    }

    /** The lock file is opened without following a link, so a link planted at its name is refused. */
    @Test
    fun refusesALinkAtTheLockFilesName(@TempDir root: Path) {
        val source = Files.createDirectories(root.resolve("home/cache"))
        Files.writeString(source.resolve("entry"), "source")
        val target = root.resolve("local/cache")
        val staging = Files.createDirectories(target.resolveSibling(".lighten-staging"))
        val elsewhere = root.resolve("elsewhere")
        Files.createSymbolicLink(lockOf(stagedCopy(staging, target)), elsewhere)

        val actions = ReconciliationExecutor().execute(plan(Relocation(source, target))).relocations.single().actions

        assertEquals(ReconciliationExecutor.ActionStatus.FAILED, actions.first().status)
        assertTrue(Files.notExists(elsewhere, LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.notExists(target, LinkOption.NOFOLLOW_LINKS))
        assertEquals("source", Files.readString(source.resolve("entry")))
    }

    companion object {
        private fun plan(relocation: Relocation): ReconciliationPlan {
            val inspector = PathInspector()
            val archive = inspectArchiveDestinations(listOf(relocation), inspector::inspect).single()
            return ReconciliationPlanner().plan(listOf(RelocationState(relocation,
                    inspector.inspect(relocation.sourcePath), inspector.inspect(relocation.targetPath), archive)))
        }
    }
}
