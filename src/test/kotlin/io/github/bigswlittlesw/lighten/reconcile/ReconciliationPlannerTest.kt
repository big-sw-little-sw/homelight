package io.github.bigswlittlesw.lighten.reconcile

import io.github.bigswlittlesw.lighten.config.Relocation
import io.github.bigswlittlesw.lighten.config.WhenAdoptingTarget
import io.github.bigswlittlesw.lighten.config.WhenOnlyTargetExists
import io.github.bigswlittlesw.lighten.config.WhenSourceAndTargetDirectoriesExist
import io.github.bigswlittlesw.lighten.config.defaultArchiveRoot
import io.github.bigswlittlesw.lighten.config.validateConfiguration
import io.github.bigswlittlesw.lighten.fifoAt
import io.github.bigswlittlesw.lighten.fs.PathInspector
import io.github.bigswlittlesw.lighten.fs.PathObservation
import io.github.bigswlittlesw.lighten.fs.PathState
import io.github.bigswlittlesw.lighten.socketAt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ReconciliationPlannerTest {
    @Test
    fun sourceDirectoryWithAbsentTargetRequiresStagedPublication(@TempDir root: Path) {
        val source = Files.createDirectories(root.resolve("home/cache"))

        val plan = plan(Relocation(source, root.resolve("local/cache")))

        assertEquals(RelocationOutcome.CONVERGED, plan.relocations.first().outcome)
        assertTrue(plan.actions().any { it is ReconciliationAction.MigrateDirectoryForPublication })
        assertTrue(plan.actions().any { it is ReconciliationAction.ReplaceDirectoryWithSymlink })
        assertFalse(plan.hasBlockedActions())
    }

    @Test
    fun bothDirectoriesRequireADecisionByDefault(@TempDir root: Path) {
        val source = Files.createDirectories(root.resolve("home/cache"))
        val target = Files.createDirectories(root.resolve("local/cache"))

        val plan = plan(Relocation(source, target))

        assertEquals(RelocationOutcome.UNRESOLVED, plan.relocations.first().outcome)
        assertTrue(plan.hasConflicts())
    }

    @Test
    fun leaveUnchangedIsSuccessfulButNotConverged(@TempDir root: Path) {
        val source = Files.createDirectories(root.resolve("home/cache"))
        val target = Files.createDirectories(root.resolve("local/cache"))

        val plan = plan(Relocation(source, target,
                whenSourceAndTargetDirectoriesExist = WhenSourceAndTargetDirectoriesExist.LEAVE_UNCHANGED))

        assertEquals(RelocationOutcome.UNCHANGED, plan.relocations.first().outcome)
        assertEquals("leave-unchanged", plan.actions().first().type)
    }

    @Test
    fun onlyTargetRequiresAdoptTargetDecision(@TempDir root: Path) {
        val source = root.resolve("home/cache")
        val target = Files.createDirectories(root.resolve("local/cache"))

        val unresolved = plan(Relocation(source, target))
        val adopted = plan(Relocation(source, target, whenOnlyTargetExists = WhenOnlyTargetExists.ADOPT_TARGET))

        assertTrue(unresolved.hasConflicts())
        assertEquals(RelocationOutcome.CONVERGED, adopted.relocations.first().outcome)
    }

    @Test
    fun archiveSourceUsesTheSourceNameUnderTheArchiveRoot(@TempDir root: Path) {
        val source = Files.createDirectories(root.resolve("home/cache"))
        val target = Files.createDirectories(root.resolve("local/cache"))
        val archiveRoot = root.resolve("archive")

        val plan = plan(archiving(source, target, archiveRoot))

        assertEquals(listOf(archiveRoot.resolve("cache")), archiveTargets(plan))
        assertEquals(ReconciliationAction.EnsureDirectory(archiveRoot), plan.actions().first())
    }

    @Test
    fun archiveSourceAddsASuffixWhenTheNameExistsAndBlocksWhenThatExistsToo(@TempDir root: Path) {
        val source = Files.createDirectories(root.resolve("home/cache"))
        val target = Files.createDirectories(root.resolve("local/cache"))
        val archiveRoot = root.resolve("archive")
        val relocation = archiving(source, target, archiveRoot)
        Files.createDirectories(archiveRoot.resolve("cache"))

        val suffixed = archiveTargets(plan(relocation)).single()

        assertEquals(archiveRoot.resolve("cache-" + sha256Hex(source.toRealPath().toString()).take(8)), suffixed)
        assertEquals(listOf(suffixed), archiveTargets(plan(relocation)), "a re-run gives the same destination")
        Files.createDirectories(suffixed)
        assertEquals(listOf(ReconciliationAction.Blocked(source, "source archive destination already exists")),
                plan(relocation).actions())
    }

    @Test
    fun relocationsWithTheSameSourceNameArchiveToDifferentDeterministicNames(@TempDir root: Path) {
        val archiveRoot = root.resolve("archive")
        val relocations = listOf("a", "b").map { parent ->
            archiving(Files.createDirectories(root.resolve("$parent/cache")),
                    Files.createDirectories(root.resolve("local/$parent-cache")), archiveRoot)
        }

        val targets = archiveTargets(ReconciliationPlanner().plan(states(relocations)))

        assertEquals(relocations.map { archiveRoot.resolve("cache-" + sha256Hex(it.sourcePath.toRealPath().toString()).take(8)) },
                targets)
        assertEquals(targets, archiveTargets(ReconciliationPlanner().plan(states(relocations))))
        assertEquals(listOf(archiveRoot.resolve("cache")), archiveTargets(plan(relocations.first())),
                "alone, the name is not taken")
    }

    @Test
    fun archiveRootsThatAreTheSameDirectoryShareNames(@TempDir root: Path) {
        val archiveRoot = Files.createDirectories(root.resolve("archive"))
        val alias = Files.createSymbolicLink(root.resolve("alias"), archiveRoot)
        val relocations = listOf(archiveRoot, alias).mapIndexed { i, archive ->
            archiving(Files.createDirectories(root.resolve("$i/cache")),
                    Files.createDirectories(root.resolve("local/$i-cache")), archive)
        }

        val names = archiveTargets(ReconciliationPlanner().plan(states(relocations))).map { it.fileName.toString() }

        assertEquals(2, names.toSet().size, names.toString())
        assertTrue(names.all { it.startsWith("cache-") }, names.toString())
    }

    @Test
    fun archiveSourceIsBlockedWithoutAnInspectedDestination(@TempDir root: Path) {
        val source = Files.createDirectories(root.resolve("home/cache"))
        val target = Files.createDirectories(root.resolve("local/cache"))
        val relocation = archiving(source, target, root.resolve("archive"))
        val inspector = PathInspector()
        val state = RelocationState(relocation, inspector.inspect(source), inspector.inspect(target))

        assertEquals(listOf(ReconciliationAction.Blocked(source, "source archive destination was not inspected")),
                ReconciliationPlanner().plan(listOf(state)).actions())
    }

    @Test
    fun archiveSourceDefaultsBesideTheSourceAndRejectsAnOverlappingRoot(@TempDir root: Path) {
        val source = Files.createDirectories(root.resolve("home/cache"))
        val target = Files.createDirectories(root.resolve("local/cache"))
        fun archive(archiveRoot: Path) = plan(archiving(source, target, archiveRoot)).relocations.single()

        val default = archive(defaultArchiveRoot(source))
        assertEquals(root.resolve("home/.lighten-archive/cache"),
                default.actions.filterIsInstance<ReconciliationAction.ArchiveDirectory>().single().target)
        for (overlapping in listOf(target.resolve("archive"), source.resolve("archive"))) {
            assertEquals(listOf(ReconciliationAction.Blocked(source, "source archive path overlaps a relocation path")),
                    archive(overlapping).actions, overlapping.toString())
        }
    }

    @Test
    fun blocksArchivingWhenTheArchiveLocationIsNotAFolder(@TempDir root: Path) {
        val source = Files.createDirectories(root.resolve("home/cache"))
        val target = Files.createDirectories(root.resolve("local/cache"))
        val archiveRoot = root.resolve("archive")
        val cases = mapOf<String, () -> Unit>(
            "is a file, not a folder" to { Files.writeString(archiveRoot, "a file") },
            "is a link, not a folder" to {
                Files.createSymbolicLink(archiveRoot, Files.writeString(root.resolve("file"), "a file"))
            },
            "is a broken link, not a folder" to { Files.createSymbolicLink(archiveRoot, root.resolve("missing")) },
        )
        for ((words, make) in cases) {
            make()

            assertEquals(listOf(ReconciliationAction.Blocked(source, "$archiveRoot $words")),
                    plan(archiving(source, target, archiveRoot)).actions(), words)
            Files.delete(archiveRoot)
        }
    }

    @Test
    fun blocksOnTheFirstPathThatIsNotAFolderAboveAMissingTarget(@TempDir root: Path) {
        val source = Files.createDirectories(root.resolve("home/cache"))
        val file = Files.writeString(root.resolve("local"), "a file")

        val moving = plan(Relocation(source, root.resolve("local/deeper/cache")))
        val creating = plan(Relocation(root.resolve("home/new"), root.resolve("local/deeper/new")))

        for (plan in listOf(moving, creating)) {
            assertEquals("$file is a file, not a folder",
                    plan.actions().filterIsInstance<ReconciliationAction.Blocked>().single().reason)
        }
    }

    @Test
    fun blocksMovingAFolderWithANamedPipeButNotOneWithASocket(@TempDir root: Path) {
        val source = Files.createDirectories(root.resolve("home/cache/sub"))
        socketAt(source.resolve("app.sock"))
        val target = root.resolve("local/cache")

        assertFalse(plan(Relocation(source.parent, target)).hasBlockedActions())
        val pipe = fifoAt(source.resolve("ipc"))
        assertEquals(listOf(ReconciliationAction.Blocked(source.parent, "$pipe is a named pipe; Lighten can't move it")),
                plan(Relocation(source.parent, target)).actions())
    }

    /**
     * A device file can't be made without root, so `/dev/null` shows the kind is recognized and the planner is given
     * one; the walk that finds it is the one that finds a named pipe.
     */
    @Test
    fun blocksMovingAFolderWithADeviceFile(@TempDir root: Path) {
        val source = Files.createDirectories(root.resolve("home/cache"))
        val relocation = Relocation(source, root.resolve("local/cache"))
        val device = Path.of("/dev/null")
        val state = states(listOf(relocation)).single().copy(unmovable = SpecialFile(device, SpecialFileKind.DEVICE))

        assertEquals(SpecialFileKind.DEVICE, specialFileKind(device))
        assertEquals(listOf(ReconciliationAction.Blocked(source, "/dev/null is a device file; Lighten can't move it")),
                ReconciliationPlanner().plan(listOf(state)).actions())
    }

    /** Only a planned copy walks the source, so a named pipe does not block a plan that moves or deletes it whole. */
    @Test
    fun aNamedPipeDoesNotBlockAPlanThatDoesNotCopy(@TempDir root: Path) {
        val source = Files.createDirectories(root.resolve("home/cache"))
        fifoAt(source.resolve("ipc"))
        val target = Files.createDirectories(root.resolve("local/cache"))
        val relocation = Relocation(source, target, WhenSourceAndTargetDirectoriesExist.ADOPT,
                whenAdoptingTarget = WhenAdoptingTarget.DISCARD_SOURCE)

        assertEquals(null, states(listOf(relocation)).single().unmovable)
        assertFalse(plan(relocation).hasBlockedActions())
    }

    @Test
    fun blocksAdoptingWhenTheSourceFolderIsAFile(@TempDir root: Path) {
        val target = Files.createDirectories(root.resolve("local/cache"))
        val file = Files.writeString(root.resolve("home"), "a file")

        val plan = plan(Relocation(file.resolve("cache"), target, whenOnlyTargetExists = WhenOnlyTargetExists.ADOPT_TARGET))

        assertEquals(listOf(ReconciliationAction.Blocked(file.resolve("cache"), "$file is a file, not a folder")),
                plan.actions())
    }

    @Test
    fun aStagingRootMustBeARealFolderButOtherFoldersMayBeLinks(@TempDir root: Path) {
        val source = Files.createDirectories(root.resolve("home/cache"))
        val storage = Files.createDirectories(root.resolve("storage"))
        val linked = Files.createSymbolicLink(root.resolve("local"), storage)
        val stagingRoot = root.resolve("staging")

        val throughLinks = plan(Relocation(source, linked.resolve("cache"), stagingRoot = linked.resolve("staging")))
        Files.createSymbolicLink(stagingRoot, Files.createDirectories(root.resolve("elsewhere")))
        val linkedStaging = plan(Relocation(source, linked.resolve("cache"), stagingRoot = stagingRoot))

        assertFalse(throughLinks.hasBlockedActions(), throughLinks.actions().toString())
        assertEquals("the staging folder must be a real folder, not a link: $stagingRoot",
                linkedStaging.actions().filterIsInstance<ReconciliationAction.Blocked>().single().reason)
    }

    @Test
    fun aFolderInTheWayDoesNotBlockAPlanThatDoesNotNeedIt(@TempDir root: Path) {
        val target = Files.createDirectories(root.resolve("local/cache"))
        val source = Files.createSymbolicLink(Files.createDirectories(root.resolve("home")).resolve("cache"), target)
        Files.writeString(root.resolve("home/.lighten-archive"), "a file")
        Files.writeString(root.resolve("local/.lighten-staging"), "a file")

        assertEquals(listOf(ReconciliationAction.NoOp(source)), plan(Relocation(source, target)).actions())
    }

    @Test
    fun refusesFilesAndTargetSymlinks(@TempDir root: Path) {
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
    fun repairsBrokenLinksOnlyWhenTheTargetIsARealDirectory(@TempDir root: Path) {
        val source = root.resolve("home/cache")
        Files.createDirectories(source.parent)
        Files.createSymbolicLink(source, root.resolve("missing"))

        val unavailable = plan(Relocation(source, root.resolve("local/cache")))
        val target = Files.createDirectories(root.resolve("local/cache"))
        val repair = plan(Relocation(source, target))

        assertTrue(unavailable.hasBlockedActions())
        assertEquals(RelocationOutcome.CONVERGED, repair.relocations.first().outcome)
        assertTrue(repair.actions().any { it is ReconciliationAction.ReplaceSymlink })
    }

    @Test
    fun correctLinksAreTheOnlyNoOpState(@TempDir root: Path) {
        val target = Files.createDirectories(root.resolve("local/cache"))
        val source = root.resolve("home/cache")
        Files.createDirectories(source.parent)
        Files.createSymbolicLink(source, target)

        val plan = plan(Relocation(source, target))

        assertEquals(RelocationOutcome.CONVERGED, plan.relocations.first().outcome)
        assertEquals("no-op", plan.actions().first().type)
    }

    @Test
    fun wrongLiveSourceLinksRequireARepairPlan(@TempDir root: Path) {
        val target = Files.createDirectories(root.resolve("local/cache"))
        val source = root.resolve("home/cache")
        Files.createDirectories(source.parent)
        Files.createSymbolicLink(source, Files.createDirectories(root.resolve("other")))

        val plan = plan(Relocation(source, target))

        assertEquals(RelocationOutcome.UNRESOLVED, plan.relocations.first().outcome)
        assertTrue(plan.hasConflicts())
    }

    @Test
    fun blocksInaccessibleSourcesAndOverlappingRelocations(@TempDir root: Path) {
        val relocation = Relocation(root.resolve("home/cache"), root.resolve("local/cache"))
        val inaccessible = RelocationState(relocation,
                PathObservation(
                        PathState.INACCESSIBLE),
                PathObservation(
                        PathState.ABSENT))
        val inaccessiblePlan = ReconciliationPlanner().plan(listOf(inaccessible))

        val parent = Relocation(root.resolve("home/parent"), root.resolve("local/parent"))
        val child = Relocation(root.resolve("home/parent/child"), root.resolve("local/child"))
        val overlapPlan = ReconciliationPlanner().plan(states(listOf(parent, child)))

        assertTrue(inaccessiblePlan.hasBlockedActions())
        assertTrue(overlapPlan.hasBlockedActions())
        assertEquals("INVALID_RELOCATION", overlapPlan.diagnostics.first().code)
    }

    @Test
    fun reportsTheSameRelocationProblemAsDraftValidation(@TempDir root: Path) {
        val home = root.resolve("home")
        val local = root.resolve("local")
        val invalidSets = listOf(
            listOf(Relocation(home.resolve("cache"), home.resolve("cache/local"))),
            listOf(Relocation(home.resolve("a"), local.resolve("same")), Relocation(home.resolve("b"), local.resolve("same"))),
            listOf(
                Relocation(home.resolve("parent"), local.resolve("parent")),
                Relocation(home.resolve("parent/child"), local.resolve("child")),
            ),
        )
        for (relocations in invalidSets) {
            val expected = assertThrows<IllegalArgumentException> {
                validateConfiguration(relocations)
            }.message
            val diagnostic = ReconciliationPlanner().plan(states(relocations)).diagnostics.single()
            assertEquals(expected, diagnostic.message)
            assertEquals(relocations.first().sourcePath, diagnostic.source)
            assertEquals("INVALID_RELOCATION", diagnostic.code)
        }
    }

    companion object {
        private fun archiving(source: Path, target: Path, archiveRoot: Path) = Relocation(source, target,
                WhenSourceAndTargetDirectoriesExist.ADOPT, whenAdoptingTarget = WhenAdoptingTarget.ARCHIVE_SOURCE,
                archiveRoot = archiveRoot)

        private fun plan(relocation: Relocation): ReconciliationPlan = ReconciliationPlanner().plan(states(listOf(relocation)))

        private fun archiveTargets(plan: ReconciliationPlan): List<Path> =
            plan.actions().filterIsInstance<ReconciliationAction.ArchiveDirectory>().map { it.target }

        private fun states(relocations: List<Relocation>): List<RelocationState> =
            inspectRelocations(relocations, PathInspector()::inspect)
    }
}
