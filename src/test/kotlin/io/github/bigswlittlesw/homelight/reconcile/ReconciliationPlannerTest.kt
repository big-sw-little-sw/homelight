package io.github.bigswlittlesw.homelight.reconcile

import io.github.bigswlittlesw.homelight.config.Relocation
import io.github.bigswlittlesw.homelight.config.WhenAdoptingTarget
import io.github.bigswlittlesw.homelight.config.WhenOnlyTargetExists
import io.github.bigswlittlesw.homelight.config.WhenSourceAndTargetDirectoriesExist
import io.github.bigswlittlesw.homelight.fs.PathInspector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class ReconciliationPlannerTest {
    @Test
    fun sourceDirectoryWithAbsentTargetRequiresStagedPublication() {
        val root = Files.createTempDirectory("homelight")
        val source = Files.createDirectories(root.resolve("home/cache"))

        val plan = plan(Relocation(source, root.resolve("local/cache")))

        assertEquals(RelocationOutcome.CONVERGED, plan.relocations.first().outcome)
        assertTrue(plan.actions().stream().anyMatch(ReconciliationAction.MigrateDirectoryForPublication::class.java::isInstance))
        assertTrue(plan.actions().stream().anyMatch(ReconciliationAction.ReplaceDirectoryWithSymlink::class.java::isInstance))
        assertFalse(plan.hasBlockedActions())
    }

    @Test
    fun bothDirectoriesRequireADecisionByDefault() {
        val root = Files.createTempDirectory("homelight")
        val source = Files.createDirectories(root.resolve("home/cache"))
        val target = Files.createDirectories(root.resolve("local/cache"))

        val plan = plan(Relocation(source, target))

        assertEquals(RelocationOutcome.UNRESOLVED, plan.relocations.first().outcome)
        assertTrue(plan.hasConflicts())
    }

    @Test
    fun leaveUnchangedIsSuccessfulButNotConverged() {
        val root = Files.createTempDirectory("homelight")
        val source = Files.createDirectories(root.resolve("home/cache"))
        val target = Files.createDirectories(root.resolve("local/cache"))

        val plan = plan(relocation(source, target, WhenSourceAndTargetDirectoriesExist.LEAVE_UNCHANGED,
                null, null, null))

        assertEquals(RelocationOutcome.UNCHANGED, plan.relocations.first().outcome)
        assertEquals("leave-unchanged", plan.actions().first().type)
    }

    @Test
    fun onlyTargetRequiresAdoptTargetDecision() {
        val root = Files.createTempDirectory("homelight")
        val source = root.resolve("home/cache")
        val target = Files.createDirectories(root.resolve("local/cache"))

        val unresolved = plan(Relocation(source, target))
        val adopted = plan(relocation(source, target, null, WhenOnlyTargetExists.ADOPT_TARGET, null, null))

        assertTrue(unresolved.hasConflicts())
        assertEquals(RelocationOutcome.CONVERGED, adopted.relocations.first().outcome)
    }

    @Test
    fun archiveSourceUsesADeterministicPath() {
        val root = Files.createTempDirectory("homelight")
        val source = Files.createDirectories(root.resolve("home/cache"))
        val target = Files.createDirectories(root.resolve("local/cache"))
        val archiveRoot = root.resolve("archive")
        val relocation = relocation(source, target, WhenSourceAndTargetDirectoriesExist.ADOPT,
                null, WhenAdoptingTarget.ARCHIVE_SOURCE, archiveRoot)

        val archive = plan(relocation).actions().stream()
                .filter(ReconciliationAction.ArchiveDirectory::class.java::isInstance).findFirst().orElseThrow()
                as ReconciliationAction.ArchiveDirectory
        assertEquals(archiveRoot.resolve(sourceRelativeToRoot(source)), archive.target)

        Files.createDirectories(archive.target)
        assertTrue(plan(relocation).hasBlockedActions())

    }

    @Test
    fun refusesFilesAndTargetSymlinks() {
        val root = Files.createTempDirectory("homelight")
        val fileSource = Files.writeString(root.resolve("source-file"), "value")
        val filePlan = plan(Relocation(fileSource, root.resolve("target")))

        val directorySource = Files.createDirectories(root.resolve("home/cache"))
        val target = root.resolve("local/cache")
        Files.createDirectories(target.parent)
        Files.createSymbolicLink(target, Files.createDirectories(root.resolve("other")))
        val symlinkPlan = plan(Relocation(directorySource, target))

        assertTrue(filePlan.hasBlockedActions())
        assertTrue(symlinkPlan.hasBlockedActions())
    }

    @Test
    fun repairsBrokenLinksOnlyWhenTheTargetIsARealDirectory() {
        val root = Files.createTempDirectory("homelight")
        val source = root.resolve("home/cache")
        Files.createDirectories(source.parent)
        Files.createSymbolicLink(source, root.resolve("missing"))

        val unavailable = plan(Relocation(source, root.resolve("local/cache")))
        val target = Files.createDirectories(root.resolve("local/cache"))
        val repair = plan(Relocation(source, target))

        assertTrue(unavailable.hasBlockedActions())
        assertEquals(RelocationOutcome.CONVERGED, repair.relocations.first().outcome)
        assertTrue(repair.actions().stream().anyMatch(ReconciliationAction.ReplaceSymlink::class.java::isInstance))
    }

    @Test
    fun correctLinksAreTheOnlyNoOpState() {
        val root = Files.createTempDirectory("homelight")
        val target = Files.createDirectories(root.resolve("local/cache"))
        val source = root.resolve("home/cache")
        Files.createDirectories(source.parent)
        Files.createSymbolicLink(source, target)

        val plan = plan(Relocation(source, target))

        assertEquals(RelocationOutcome.CONVERGED, plan.relocations.first().outcome)
        assertEquals("no-op", plan.actions().first().type)
    }

    @Test
    fun wrongLiveSourceLinksRequireARepairPlan() {
        val root = Files.createTempDirectory("homelight")
        val target = Files.createDirectories(root.resolve("local/cache"))
        val source = root.resolve("home/cache")
        Files.createDirectories(source.parent)
        Files.createSymbolicLink(source, Files.createDirectories(root.resolve("other")))

        val plan = plan(Relocation(source, target))

        assertEquals(RelocationOutcome.UNRESOLVED, plan.relocations.first().outcome)
        assertTrue(plan.hasConflicts())
    }

    @Test
    fun blocksInaccessibleSourcesAndOverlappingRelocations() {
        val root = Files.createTempDirectory("homelight")
        val relocation = Relocation(root.resolve("home/cache"), root.resolve("local/cache"))
        val inaccessible = RelocationState(relocation,
                io.github.bigswlittlesw.homelight.fs.PathObservation(
                        io.github.bigswlittlesw.homelight.fs.PathState.INACCESSIBLE),
                io.github.bigswlittlesw.homelight.fs.PathObservation(
                        io.github.bigswlittlesw.homelight.fs.PathState.ABSENT))
        val inaccessiblePlan = ReconciliationPlanner().plan(listOf(inaccessible))

        val parent = Relocation(root.resolve("home/parent"), root.resolve("local/parent"))
        val child = Relocation(root.resolve("home/parent/child"), root.resolve("local/child"))
        val overlapPlan = ReconciliationPlanner().plan(listOf(state(parent), state(child)))

        assertTrue(inaccessiblePlan.hasBlockedActions())
        assertTrue(overlapPlan.hasBlockedActions())
        assertEquals("INVALID_RELOCATION", overlapPlan.diagnostics.first().code)
    }

    companion object {
        private fun relocation(source: Path, target: Path, directories: WhenSourceAndTargetDirectoriesExist?,
                onlyTarget: WhenOnlyTargetExists?, adoption: WhenAdoptingTarget?, archiveRoot: Path?): Relocation {
            return Relocation(source, target, directories, onlyTarget, adoption, archiveRoot)
        }

        private fun sourceRelativeToRoot(source: Path): Path {
            val absolute = source.toAbsolutePath()
            return absolute.root.relativize(absolute)
        }

        private fun plan(relocation: Relocation): ReconciliationPlan {
            return ReconciliationPlanner().plan(listOf(state(relocation)))
        }

        private fun state(relocation: Relocation): RelocationState {
            val inspector = PathInspector()
            val archive = relocation.sourceArchiveRoot?.let { root ->
                val source = relocation.sourcePath.toAbsolutePath()
                val path = root.resolve(source.root.relativize(source))
                RelocationState.ArchiveDestination(path, inspector.inspect(path))
            }
            return RelocationState(relocation, inspector.inspect(relocation.sourcePath),
                    inspector.inspect(relocation.targetPath), archive)
        }
    }
}
