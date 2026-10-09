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
 * The plan blocks a relocation for a directory it needs exactly when the apply would fail on that directory.
 *
 * Inspection ([inspectDirectories], [stagingElsewhere]) and the executor (`ensureDirectories`, the staging file-store
 * check) apply the same rules separately. Each case plans for real, then forces the apply: it plans again from the
 * same observations without what inspection found in the way, and runs the executor on that.
 *
 * An unreadable path is tested only at the staging root. At the parent of a source, target or archive destination it
 * makes that path unreadable too, and the plan blocks on that observation before any directory rule.
 */
class DirectoryPrerequisiteParityTest {
    @ParameterizedTest
    @EnumSource(Case::class)
    internal fun planBlocksExactlyWhenApplyFails(case: Case, @TempDir root: Path) {
        val directory = case.obstacle.directory(root)
        val relocation = case.position.relocation(root, directory)
        case.obstacle.make(root, directory)
        try {
            assumeFalse(case.obstacle == Obstacle.UNREADABLE && Files.isReadable(directory.parent),
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

            assertEquals(listOf(ReconciliationAction.Blocked(relocation.sourcePath, blocked.reason(directory, relocation))), plan.actions())

            val forced = ReconciliationPlanner().plan(states.map { it.copy(notDirectories = mapOf(), stagingElsewhere = false) })
            assertFalse(forced.hasBlockedActions(), forced.actions().toString())
            val failed = ReconciliationExecutor().execute(forced).relocations.single().actions
                .single { it.status == ReconciliationExecutor.ActionStatus.FAILED }
            assertEquals(blocked.failure(directory, relocation), failed.failure, failed.message)
        } finally {
            if (case.obstacle == Obstacle.UNREADABLE) {
                Files.setPosixFilePermissions(directory.parent, PosixFilePermissions.fromString("rwx------"))
            }
        }
    }

    /** Where the needed directory is, and a relocation whose plan needs it. */
    internal enum class Position(val relocation: (root: Path, directory: Path) -> Relocation) {
        /** Creating a new directory and link: `EnsureDirectory` of the source's parent. */
        SOURCE_PARENT({ root, directory -> Relocation(directory.resolve("cache"), root.resolve("local/cache")) }),

        /** Creating a new directory and link: `EnsureDirectory` of the target's parent. */
        TARGET_PARENT({ root, directory -> Relocation(root.resolve("home/cache"), directory.resolve("cache")) }),

        /** Moving a directory: the migration's target parent, also the parent of the default staging root. */
        MOVE_TARGET_PARENT({ root, directory -> Relocation(source(root), directory.resolve("cache")) }),

        /** Archiving the source: `EnsureDirectory` of the archive destination's parent. */
        ARCHIVE_ROOT({ root, directory ->
            Relocation(source(root), Files.createDirectories(root.resolve("local/cache")),
                WhenSourceAndTargetDirectoriesExist.ADOPT, whenAdoptingTarget = WhenAdoptingTarget.ARCHIVE_SOURCE,
                archiveRoot = directory)
        }),

        /** Moving a directory through an explicit staging root. */
        STAGING_ROOT({ root, directory -> Relocation(source(root), root.resolve("local/cache"), stagingRoot = directory) }),
    }

    /** What is at the needed directory. */
    internal enum class Obstacle(val make: (root: Path, directory: Path) -> Unit) {
        DIRECTORY({ _, directory -> Files.createDirectories(directory) }),
        LINK_TO_DIRECTORY({ root, directory ->
            Files.createSymbolicLink(directory, Files.createDirectories(root.resolve("elsewhere")))
        }),
        FILE({ _, directory -> Files.writeString(directory, "a file") }),
        LINK_TO_FILE({ root, directory -> Files.createSymbolicLink(directory, Files.writeString(root.resolve("a-file"), "a file")) }),
        BROKEN_LINK({ root, directory -> Files.createSymbolicLink(directory, root.resolve("missing")) }),

        /** Inside a directory that can't be searched, so nothing can tell what it is. */
        UNREADABLE({ _, directory -> Files.setPosixFilePermissions(Files.createDirectories(directory.parent), setOf()) }),

        /** Nothing in the way, but on another filesystem than the target: `/dev` is its own on Linux and macOS. */
        ELSEWHERE({ _, _ -> });

        fun directory(root: Path): Path = when (this) {
            UNREADABLE -> root.resolve("locked/needed")
            ELSEWHERE -> Path.of("/dev/lighten-staging")
            DIRECTORY, LINK_TO_DIRECTORY, FILE, LINK_TO_FILE, BROKEN_LINK -> root.resolve("needed")
        }
    }

    /** The plan's reason for blocking, and the failure the forced apply reports. */
    internal class Blocked(
        val reason: (directory: Path, Relocation) -> PathText, val failure: (directory: Path, Relocation) -> ActionFailure,
    )

    /** The table: no [blocked] means plan and apply both pass. */
    internal enum class Case(val position: Position, val obstacle: Obstacle, val blocked: Blocked?) {
        SOURCE_PARENT_DIRECTORY(Position.SOURCE_PARENT, Obstacle.DIRECTORY, null),
        SOURCE_PARENT_LINK_TO_DIRECTORY(Position.SOURCE_PARENT, Obstacle.LINK_TO_DIRECTORY, null),
        SOURCE_PARENT_FILE(Position.SOURCE_PARENT, Obstacle.FILE, isAFile),
        SOURCE_PARENT_LINK_TO_FILE(Position.SOURCE_PARENT, Obstacle.LINK_TO_FILE, isALink),
        SOURCE_PARENT_BROKEN_LINK(Position.SOURCE_PARENT, Obstacle.BROKEN_LINK, isABrokenLink),

        TARGET_PARENT_DIRECTORY(Position.TARGET_PARENT, Obstacle.DIRECTORY, null),
        TARGET_PARENT_LINK_TO_DIRECTORY(Position.TARGET_PARENT, Obstacle.LINK_TO_DIRECTORY, null),
        TARGET_PARENT_FILE(Position.TARGET_PARENT, Obstacle.FILE, isAFile),
        TARGET_PARENT_LINK_TO_FILE(Position.TARGET_PARENT, Obstacle.LINK_TO_FILE, isALink),
        TARGET_PARENT_BROKEN_LINK(Position.TARGET_PARENT, Obstacle.BROKEN_LINK, isABrokenLink),

        MOVE_TARGET_PARENT_DIRECTORY(Position.MOVE_TARGET_PARENT, Obstacle.DIRECTORY, null),
        MOVE_TARGET_PARENT_LINK_TO_DIRECTORY(Position.MOVE_TARGET_PARENT, Obstacle.LINK_TO_DIRECTORY, null),
        MOVE_TARGET_PARENT_FILE(Position.MOVE_TARGET_PARENT, Obstacle.FILE, isAFile),
        MOVE_TARGET_PARENT_LINK_TO_FILE(Position.MOVE_TARGET_PARENT, Obstacle.LINK_TO_FILE, isALink),
        MOVE_TARGET_PARENT_BROKEN_LINK(Position.MOVE_TARGET_PARENT, Obstacle.BROKEN_LINK, isABrokenLink),

        ARCHIVE_ROOT_DIRECTORY(Position.ARCHIVE_ROOT, Obstacle.DIRECTORY, null),
        ARCHIVE_ROOT_LINK_TO_DIRECTORY(Position.ARCHIVE_ROOT, Obstacle.LINK_TO_DIRECTORY, null),
        ARCHIVE_ROOT_FILE(Position.ARCHIVE_ROOT, Obstacle.FILE, isAFile),
        ARCHIVE_ROOT_LINK_TO_FILE(Position.ARCHIVE_ROOT, Obstacle.LINK_TO_FILE, isALink),
        ARCHIVE_ROOT_BROKEN_LINK(Position.ARCHIVE_ROOT, Obstacle.BROKEN_LINK, isABrokenLink),

        STAGING_ROOT_DIRECTORY(Position.STAGING_ROOT, Obstacle.DIRECTORY, null),
        // The staging root itself must be a real directory, so a live link there blocks, with its own reason.
        STAGING_ROOT_LINK_TO_DIRECTORY(Position.STAGING_ROOT, Obstacle.LINK_TO_DIRECTORY, stagingIsALink),
        STAGING_ROOT_FILE(Position.STAGING_ROOT, Obstacle.FILE, isAFile),
        STAGING_ROOT_LINK_TO_FILE(Position.STAGING_ROOT, Obstacle.LINK_TO_FILE, stagingIsALink),
        STAGING_ROOT_BROKEN_LINK(Position.STAGING_ROOT, Obstacle.BROKEN_LINK, isABrokenLink),
        STAGING_ROOT_UNREADABLE(Position.STAGING_ROOT, Obstacle.UNREADABLE, Blocked(
            { directory, _ -> PathText(directory, " can't be read, so Lighten can't tell if it is a directory") },
            { directory, _ -> ActionFailure.AccessDenied(directory) },
        )),
        STAGING_ROOT_ELSEWHERE(Position.STAGING_ROOT, Obstacle.ELSEWHERE, Blocked(
            { directory, relocation ->
                PathText("the staging directory ", directory, " is on another filesystem than ", relocation.targetPath,
                    ", so Lighten can't move the copy there in one step. Set staging-root to a directory on the target's " +
                        "filesystem, or remove staging-root to stage beside each target")
            },
            { directory, relocation -> ActionFailure.StagingElsewhere(directory, relocation.targetPath) },
        )),
    }
}

private fun source(root: Path): Path {
    val source = Files.createDirectories(root.resolve("home/cache"))
    Files.writeString(source.resolve("entry"), "source")
    return source
}

private fun notADirectory(words: String, found: PathState) = DirectoryPrerequisiteParityTest.Blocked(
    { directory, _ -> PathText(directory, words) }, { directory, _ -> ActionFailure.Drift(directory, PathState.DIRECTORY, found) },
)

private val isAFile = notADirectory(" is a file, not a directory", PathState.FILE)
private val isALink = notADirectory(" is a link, not a directory", PathState.SYMLINK)
private val isABrokenLink = notADirectory(" is a broken link, not a directory", PathState.SYMLINK)
private val stagingIsALink = DirectoryPrerequisiteParityTest.Blocked(
    { directory, _ -> PathText("the staging directory must be a real directory, not a link: ", directory) },
    { directory, _ -> ActionFailure.Drift(directory, PathState.DIRECTORY, PathState.SYMLINK) },
)
