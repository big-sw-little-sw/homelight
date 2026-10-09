package io.github.bigswlittlesw.lighten.reconcile

import io.github.bigswlittlesw.lighten.config.Relocation
import io.github.bigswlittlesw.lighten.config.WhenAdoptingTarget
import io.github.bigswlittlesw.lighten.config.WhenSourceAndTargetDirectoriesExist
import io.github.bigswlittlesw.lighten.fs.PathInspector
import io.github.bigswlittlesw.lighten.fs.PathState
import io.github.bigswlittlesw.lighten.fs.PathText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * The plan blocks a relocation for a folder it needs exactly when the apply would fail on that folder.
 *
 * Inspection ([inspectFolders], [stagingElsewhere]) and the executor (`ensureDirectories`, the staging file-store
 * check) apply the same rules separately. Each case plans for real, then forces the apply: it plans again from the
 * same observations without what inspection found in the way, and runs the executor on that.
 *
 * An unreadable path is tested only at the staging root. At the parent of a source, target or archive destination it
 * makes that path unreadable too, and the plan blocks on that observation before any folder rule.
 */
class FolderPrerequisiteParityTest {
    @ParameterizedTest
    @EnumSource(Case::class)
    internal fun planBlocksExactlyWhenApplyFails(case: Case, @TempDir root: Path) {
        val folder = case.obstacle.folder(root)
        val relocation = case.position.relocation(root, folder)
        case.obstacle.make(root, folder)
        try {
            assumeFalse(case.obstacle == Obstacle.UNREADABLE && Files.isReadable(folder.parent),
                "requires directory read denial, not a privileged process")
            val states = inspectRelocations(listOf(relocation), PathInspector()::inspect)
            val plan = ReconciliationPlanner().plan(states)
            val blocked = case.blocked
            if (blocked == null) {
                assertFalse(plan.hasBlockedActions(), plan.actions().toString())
                val result = ReconciliationExecutor().execute(plan)
                assertTrue(result.succeeded(), result.toString())
                return
            }

            assertEquals(listOf(ReconciliationAction.Blocked(relocation.sourcePath, blocked.reason(folder, relocation))), plan.actions())

            val forced = ReconciliationPlanner().plan(states.map { it.copy(notFolders = mapOf(), stagingElsewhere = false) })
            assertFalse(forced.hasBlockedActions(), forced.actions().toString())
            val failed = ReconciliationExecutor().execute(forced).relocations.single().actions
                .single { it.status == ReconciliationExecutor.ActionStatus.FAILED }
            assertEquals(blocked.failure(folder, relocation), failed.failure, failed.message)
        } finally {
            if (case.obstacle == Obstacle.UNREADABLE) {
                Files.setPosixFilePermissions(folder.parent, PosixFilePermissions.fromString("rwx------"))
            }
        }
    }

    /** Where the needed folder is, and a relocation whose plan needs it. */
    internal enum class Position(val relocation: (root: Path, folder: Path) -> Relocation) {
        /** Creating a new folder and link: `EnsureDirectory` of the source's parent. */
        SOURCE_PARENT({ root, folder -> Relocation(folder.resolve("cache"), root.resolve("local/cache")) }),

        /** Creating a new folder and link: `EnsureDirectory` of the target's parent. */
        TARGET_PARENT({ root, folder -> Relocation(root.resolve("home/cache"), folder.resolve("cache")) }),

        /** Moving a folder: the migration's target parent, also the parent of the default staging root. */
        MOVE_TARGET_PARENT({ root, folder -> Relocation(source(root), folder.resolve("cache")) }),

        /** Archiving the source: `EnsureDirectory` of the archive destination's parent. */
        ARCHIVE_ROOT({ root, folder ->
            Relocation(source(root), Files.createDirectories(root.resolve("local/cache")),
                WhenSourceAndTargetDirectoriesExist.ADOPT, whenAdoptingTarget = WhenAdoptingTarget.ARCHIVE_SOURCE,
                archiveRoot = folder)
        }),

        /** Moving a folder through an explicit staging root. */
        STAGING_ROOT({ root, folder -> Relocation(source(root), root.resolve("local/cache"), stagingRoot = folder) }),
    }

    /** What is at the needed folder. */
    internal enum class Obstacle(val make: (root: Path, folder: Path) -> Unit) {
        FOLDER({ _, folder -> Files.createDirectories(folder) }),
        LINK_TO_FOLDER({ root, folder ->
            Files.createSymbolicLink(folder, Files.createDirectories(root.resolve("elsewhere")))
        }),
        FILE({ _, folder -> Files.writeString(folder, "a file") }),
        LINK_TO_FILE({ root, folder -> Files.createSymbolicLink(folder, Files.writeString(root.resolve("a-file"), "a file")) }),
        BROKEN_LINK({ root, folder -> Files.createSymbolicLink(folder, root.resolve("missing")) }),

        /** Inside a folder that can't be searched, so nothing can tell what it is. */
        UNREADABLE({ _, folder -> Files.setPosixFilePermissions(Files.createDirectories(folder.parent), setOf()) }),

        /** Nothing in the way, but on another filesystem than the target: `/dev` is its own on Linux and macOS. */
        ELSEWHERE({ _, _ -> });

        fun folder(root: Path): Path = when (this) {
            UNREADABLE -> root.resolve("locked/needed")
            ELSEWHERE -> Path.of("/dev/lighten-staging")
            FOLDER, LINK_TO_FOLDER, FILE, LINK_TO_FILE, BROKEN_LINK -> root.resolve("needed")
        }
    }

    /** The plan's reason for blocking, and the failure the forced apply reports. */
    internal class Blocked(
        val reason: (folder: Path, Relocation) -> PathText, val failure: (folder: Path, Relocation) -> ActionFailure,
    )

    /** The table: no [blocked] means plan and apply both pass. */
    internal enum class Case(val position: Position, val obstacle: Obstacle, val blocked: Blocked?) {
        SOURCE_PARENT_FOLDER(Position.SOURCE_PARENT, Obstacle.FOLDER, null),
        SOURCE_PARENT_LINK_TO_FOLDER(Position.SOURCE_PARENT, Obstacle.LINK_TO_FOLDER, null),
        SOURCE_PARENT_FILE(Position.SOURCE_PARENT, Obstacle.FILE, isAFile),
        SOURCE_PARENT_LINK_TO_FILE(Position.SOURCE_PARENT, Obstacle.LINK_TO_FILE, isALink),
        SOURCE_PARENT_BROKEN_LINK(Position.SOURCE_PARENT, Obstacle.BROKEN_LINK, isABrokenLink),

        TARGET_PARENT_FOLDER(Position.TARGET_PARENT, Obstacle.FOLDER, null),
        TARGET_PARENT_LINK_TO_FOLDER(Position.TARGET_PARENT, Obstacle.LINK_TO_FOLDER, null),
        TARGET_PARENT_FILE(Position.TARGET_PARENT, Obstacle.FILE, isAFile),
        TARGET_PARENT_LINK_TO_FILE(Position.TARGET_PARENT, Obstacle.LINK_TO_FILE, isALink),
        TARGET_PARENT_BROKEN_LINK(Position.TARGET_PARENT, Obstacle.BROKEN_LINK, isABrokenLink),

        MOVE_TARGET_PARENT_FOLDER(Position.MOVE_TARGET_PARENT, Obstacle.FOLDER, null),
        MOVE_TARGET_PARENT_LINK_TO_FOLDER(Position.MOVE_TARGET_PARENT, Obstacle.LINK_TO_FOLDER, null),
        MOVE_TARGET_PARENT_FILE(Position.MOVE_TARGET_PARENT, Obstacle.FILE, isAFile),
        MOVE_TARGET_PARENT_LINK_TO_FILE(Position.MOVE_TARGET_PARENT, Obstacle.LINK_TO_FILE, isALink),
        MOVE_TARGET_PARENT_BROKEN_LINK(Position.MOVE_TARGET_PARENT, Obstacle.BROKEN_LINK, isABrokenLink),

        ARCHIVE_ROOT_FOLDER(Position.ARCHIVE_ROOT, Obstacle.FOLDER, null),
        ARCHIVE_ROOT_LINK_TO_FOLDER(Position.ARCHIVE_ROOT, Obstacle.LINK_TO_FOLDER, null),
        ARCHIVE_ROOT_FILE(Position.ARCHIVE_ROOT, Obstacle.FILE, isAFile),
        ARCHIVE_ROOT_LINK_TO_FILE(Position.ARCHIVE_ROOT, Obstacle.LINK_TO_FILE, isALink),
        ARCHIVE_ROOT_BROKEN_LINK(Position.ARCHIVE_ROOT, Obstacle.BROKEN_LINK, isABrokenLink),

        STAGING_ROOT_FOLDER(Position.STAGING_ROOT, Obstacle.FOLDER, null),
        // The staging root itself must be a real folder, so a live link there blocks, with its own reason.
        STAGING_ROOT_LINK_TO_FOLDER(Position.STAGING_ROOT, Obstacle.LINK_TO_FOLDER, stagingIsALink),
        STAGING_ROOT_FILE(Position.STAGING_ROOT, Obstacle.FILE, isAFile),
        STAGING_ROOT_LINK_TO_FILE(Position.STAGING_ROOT, Obstacle.LINK_TO_FILE, stagingIsALink),
        STAGING_ROOT_BROKEN_LINK(Position.STAGING_ROOT, Obstacle.BROKEN_LINK, isABrokenLink),
        STAGING_ROOT_UNREADABLE(Position.STAGING_ROOT, Obstacle.UNREADABLE, Blocked(
            { folder, _ -> PathText(folder, " can't be read, so Lighten can't tell if it is a folder") },
            { folder, _ -> ActionFailure.AccessDenied(folder) },
        )),
        STAGING_ROOT_ELSEWHERE(Position.STAGING_ROOT, Obstacle.ELSEWHERE, Blocked(
            { folder, relocation ->
                PathText("the staging folder ", folder, " is on another filesystem than ", relocation.targetPath,
                    ", so Lighten can't move the copy there in one step. Set staging-root to a folder on the target's " +
                        "filesystem, or remove staging-root to stage beside each target")
            },
            { folder, relocation -> ActionFailure.StagingElsewhere(folder, relocation.targetPath) },
        )),
    }
}

private fun source(root: Path): Path {
    val source = Files.createDirectories(root.resolve("home/cache"))
    Files.writeString(source.resolve("entry"), "source")
    return source
}

private fun notAFolder(words: String, found: PathState) = FolderPrerequisiteParityTest.Blocked(
    { folder, _ -> PathText(folder, words) }, { folder, _ -> ActionFailure.Drift(folder, PathState.DIRECTORY, found) },
)

private val isAFile = notAFolder(" is a file, not a folder", PathState.FILE)
private val isALink = notAFolder(" is a link, not a folder", PathState.SYMLINK)
private val isABrokenLink = notAFolder(" is a broken link, not a folder", PathState.SYMLINK)
private val stagingIsALink = FolderPrerequisiteParityTest.Blocked(
    { folder, _ -> PathText("the staging folder must be a real folder, not a link: ", folder) },
    { folder, _ -> ActionFailure.Drift(folder, PathState.DIRECTORY, PathState.SYMLINK) },
)
