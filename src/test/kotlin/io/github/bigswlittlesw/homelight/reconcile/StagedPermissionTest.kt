package io.github.bigswlittlesw.homelight.reconcile

import io.github.bigswlittlesw.homelight.config.Relocation
import io.github.bigswlittlesw.homelight.fs.PathInspector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.FileSystems
import java.nio.file.FileVisitor
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions

/**
 * Staged publication keeps each directory's nine POSIX permission bits and refuses targets that
 * cannot represent them (decision 2026-09-30). File modes and symlink values keep their existing
 * provider behavior.
 */
class StagedPermissionTest {
    @TempDir
    lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["rwx------", "rwxr-x---", "rwxr-xr-x", "r-x------"])
    fun emptySourceRootPublishesWithItsMode(sourceMode: String) {
        val root = posixRoot()
        val source = Files.createDirectory(root.resolve("source"))
        mode(source, sourceMode)
        val target = root.resolve("local/target")

        val result = ReconciliationExecutor().execute(plan(source, target))

        assertTrue(result.succeeded(), result.toString())
        assertMode(target, sourceMode)
        assertTrue(Files.isSymbolicLink(source))
        assertEmpty(target)
        assertEmpty(target.parent.resolve(".homelight-staging"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["rwx------", "rwxr-x---", "rwxr-xr-x"])
    fun publicationPreservesDirectoryModesAndRetainsFileAndLinkBehavior(rootMode: String) {
        val root = posixRoot()
        val source = Files.createDirectory(root.resolve("source"))
        val nested = Files.createDirectory(source.resolve("nested"))
        val empty = Files.createDirectory(nested.resolve("empty"))
        val shared = Files.createDirectory(source.resolve("shared"))
        val file = Files.writeString(source.resolve("entry"), "private contents")
        val executable = Files.writeString(nested.resolve("executable"), "executable contents")
        val outside = Files.createDirectory(root.resolve("outside"))
        Files.writeString(outside.resolve("keep"), "untouched")
        mode(source, rootMode)
        mode(nested, "rwx--x---")
        mode(empty, "r-x------")
        mode(shared, "rwxr-xr-x")
        mode(file, "rw-------")
        mode(executable, "rwx------")
        mode(outside, "rwx------")
        Files.createSymbolicLink(nested.resolve("relative"), Path.of("../entry"))
        Files.createSymbolicLink(source.resolve("broken"), Path.of("missing"))
        Files.createSymbolicLink(source.resolve("external"), outside)
        val target = root.resolve("local/target")
        val published = BooleanArray(1)
        val executor = ReconciliationExecutor()

        val result = executor.execute(plan(source, target), object : ReconciliationExecutor.ProgressListener {
            override fun finished(relocation: RelocationPlan, action: ReconciliationExecutor.ActionExecution) {
                if (action.action is ReconciliationAction.MigrateDirectoryForPublication
                        && action.status == ReconciliationExecutor.ActionStatus.COMPLETED) {
                    published[0] = true
                    assertTrue(Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS))
                    assertMode(source, rootMode)
                    assertMode(nested, "rwx--x---")
                    assertMode(empty, "r-x------")
                    assertMode(target, rootMode)
                    assertMode(target.resolve("nested"), "rwx--x---")
                    assertMode(target.resolve("nested/empty"), "r-x------")
                    assertMode(target.resolve("shared"), "rwxr-xr-x")
                }
            }
        })

        assertTrue(result.succeeded(), result.toString())
        assertTrue(published[0], "must observe publication before source replacement")
        assertTrue(Files.isSymbolicLink(source))
        assertEquals(target, Files.readSymbolicLink(source))
        assertMode(target.resolve("entry"), "rw-------")
        assertMode(target.resolve("nested/executable"), "rwx------")
        assertEquals("private contents", Files.readString(target.resolve("entry")))
        assertEquals("executable contents", Files.readString(target.resolve("nested/executable")))
        assertEquals(Path.of("../entry"), Files.readSymbolicLink(target.resolve("nested/relative")))
        assertEquals(Path.of("missing"), Files.readSymbolicLink(target.resolve("broken")))
        assertEquals(outside, Files.readSymbolicLink(target.resolve("external")))
        assertEquals("untouched", Files.readString(outside.resolve("keep")))
        assertMode(outside, "rwx------")
        assertEmpty(target.resolve("nested/empty"))
        assertEmpty(target.parent.resolve(".homelight-staging"))

        val repeated = plan(source, target)
        assertTrue(repeated.actions().all { it is ReconciliationAction.NoOp })
        assertTrue(executor.execute(repeated).succeeded())
        assertMode(target, rootMode)
        assertMode(target.resolve("nested"), "rwx--x---")
        assertMode(target.resolve("nested/empty"), "r-x------")
        assertMode(target.resolve("entry"), "rw-------")
    }

    @Test
    fun copierKeepsDirectoriesOwnerOnlyUntilTheirContentsAreCopied() {
        val root = posixRoot()
        val source = Files.createDirectory(root.resolve("source"))
        val nested = Files.createDirectory(source.resolve("nested"))
        val file = Files.writeString(nested.resolve("entry"), "contents")
        mode(nested, "r-x------")
        mode(source, "rwxr-xr-x")
        val copy = Files.createDirectory(root.resolve("operation")).resolve("copy")
        try {
            // Drive the executor's visitor step by step to observe each intermediate mode.
            val visitor = copyVisitor(source, copy)
            visitor.preVisitDirectory(source, Files.readAttributes(source, BasicFileAttributes::class.java))
            assertMode(copy, "rwx------")
            visitor.preVisitDirectory(nested, Files.readAttributes(nested, BasicFileAttributes::class.java))
            assertMode(copy.resolve("nested"), "rwx------")
            visitor.visitFile(file, Files.readAttributes(file, BasicFileAttributes::class.java))
            assertEquals("contents", Files.readString(copy.resolve("nested/entry")))
            visitor.postVisitDirectory(nested, null)
            assertMode(copy.resolve("nested"), "r-x------")
            assertMode(copy, "rwx------")
            visitor.postVisitDirectory(source, null)
            assertMode(copy, "rwxr-xr-x")
            assertMode(source, "rwxr-xr-x")
            assertMode(nested, "r-x------")
        } finally {
            mode(nested, "rwx------")
            if (Files.exists(copy.resolve("nested"))) {
                mode(copy.resolve("nested"), "rwx------")
            }
        }
    }

    @Test
    fun failedCopyRemovesItsRestrictiveDirectoriesAndKeepsUnownedEntries() {
        val root = posixRoot()
        val source = Files.createDirectory(root.resolve("source"))
        // Several read-only siblings, so some are copied with their final mode before the
        // unreadable one fails, whatever the directory listing order.
        val readOnly = listOf("a", "b", "c", "d", "e").map { name ->
            Files.createDirectory(source.resolve(name)).also { directory ->
                Files.writeString(directory.resolve("entry"), "keep")
                mode(directory, "r-x------")
            }
        }
        val nested = Files.createDirectory(source.resolve("unreadable"))
        Files.writeString(nested.resolve("entry"), "keep")
        mode(source, "rwx------")
        val target = root.resolve("local/target")
        val staging = Files.createDirectories(target.parent.resolve(".homelight-staging"))
        val unrelated = Files.writeString(staging.resolve("unowned"), "keep")
        val planned = plan(source, target)
        mode(nested, "---------")
        try {
            assumeFalse(Files.isReadable(nested), "requires directory read denial, not a privileged process")
            val result = ReconciliationExecutor().execute(planned)
            assertUnpublishedFailure(result, source, target)
            assertMode(source, "rwx------")
            assertMode(nested, "---------")
            readOnly.forEach { directory -> assertMode(directory, "r-x------") }
            assertEquals("keep", Files.readString(unrelated))
            Files.list(staging).use { entries ->
                assertEquals(listOf(unrelated), entries.toList())
            }
        } finally {
            mode(nested, "rwx------")
            readOnly.forEach { directory -> mode(directory, "rwx------") }
        }
        assertEquals("keep", Files.readString(nested.resolve("entry")))
    }

    @Test
    fun unwritableStagingFailsWithoutChangingSourceOrUnownedEntries() {
        val root = posixRoot()
        val source = Files.createDirectory(root.resolve("source"))
        Files.writeString(source.resolve("entry"), "keep")
        val target = root.resolve("local/target")
        val staging = Files.createDirectories(target.parent.resolve(".homelight-staging"))
        val unrelated = Files.writeString(staging.resolve("unowned"), "keep")
        mode(staging, "r-x------")
        try {
            assumeFalse(Files.isWritable(staging), "requires directory write denial, not a privileged process")
            val result = ReconciliationExecutor().execute(plan(source, target))
            assertUnpublishedFailure(result, source, target)
            assertMode(staging, "r-x------")
            assertEquals("keep", Files.readString(source.resolve("entry")))
            assertEquals("keep", Files.readString(unrelated))
        } finally {
            mode(staging, "rwx------")
        }
    }

    @Test
    fun readOnlyPopulatedSourceIsPublishedReadOnlyAndReportsRecoveryWithoutChmod() {
        val root = posixRoot()
        val source = Files.createDirectory(root.resolve("source"))
        Files.writeString(source.resolve("entry"), "keep")
        mode(source, "r-x------")
        val target = root.resolve("local/target")
        try {
            assumeFalse(Files.isWritable(source), "requires directory write denial, not a privileged process")
            val result = ReconciliationExecutor().execute(plan(source, target))
            assertFalse(result.succeeded())
            assertEquals(ReconciliationExecutor.ExecutionOutcome.FAILED_RECOVERY,
                    result.relocations.first().outcome())
            assertTrue(Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS))
            assertEquals("keep", Files.readString(source.resolve("entry")))
            assertEquals("keep", Files.readString(target.resolve("entry")))
            assertMode(source, "r-x------")
            assertMode(target, "r-x------")
            assertEmpty(target.parent.resolve(".homelight-staging"))
            assertTrue(plan(source, target).hasConflicts())
        } finally {
            mode(source, "rwx------")
            if (Files.isDirectory(target)) {
                mode(target, "rwx------")
            }
        }
    }

    @Test
    fun restrictiveStaleCopyIsRetainedAndStopsNewPublication() {
        val root = posixRoot()
        val source = Files.createDirectory(root.resolve("source"))
        Files.writeString(source.resolve("entry"), "keep")
        val target = root.resolve("local/target")
        val staging = Files.createDirectories(target.parent.resolve(".homelight-staging"))
        val stale = Files.createDirectory(staging.resolve("operation-00000000-0000-0000-0000-000000000000"))
        Files.writeString(stale.resolve("target"), "homelight-staging-v1\n" + target + "\n")
        Files.createFile(stale.resolve("lock"))
        val copy = Files.createDirectory(stale.resolve("copy"))
        Files.writeString(copy.resolve("entry"), "stale")
        mode(copy, "r-x------")
        try {
            assumeFalse(Files.isWritable(copy), "requires directory write denial, not a privileged process")
            val result = ReconciliationExecutor().execute(plan(source, target))
            assertUnpublishedFailure(result, source, target)
            assertMode(copy, "r-x------")
            assertEquals("stale", Files.readString(copy.resolve("entry")))
            assertEquals("keep", Files.readString(source.resolve("entry")))
        } finally {
            mode(copy, "rwx------")
        }
    }

    @Test
    fun targetWithoutPosixPermissionsIsRefusedBeforeAnythingIsStaged() {
        val root = posixRoot()
        val source = Files.createDirectory(root.resolve("source"))
        Files.writeString(source.resolve("entry"), "keep")
        mode(source, "rwx------")
        // ZIP is a local, deterministic provider without the POSIX view, not a supported
        // relocation platform or a stand-in for Windows/NFS publication behavior.
        FileSystems.newFileSystem(temporary.resolve("unsupported.zip"), mapOf("create" to "true")).use { zip ->
            val target = zip.getPath("/local/target")
            assertNull(Files.getFileAttributeView(zip.getPath("/"), PosixFileAttributeView::class.java))

            val result = ReconciliationExecutor().execute(plan(source, target))

            assertUnpublishedFailure(result, source, target)
            val failure = result.relocations.first().actions.first()
            assertTrue(failure.message.startsWith("cannot preserve directory permissions"), failure.message)
            assertFalse(failure.stateDrift)
            assertTrue(Files.notExists(zip.getPath("/local")), "nothing is created on the refused filesystem")
            assertMode(source, "rwx------")
            assertEquals("keep", Files.readString(source.resolve("entry")))
        }
    }

    private fun posixRoot(): Path {
        val root = temporary.toRealPath()
        assumeTrue(Files.getFileStore(root).supportsFileAttributeView(PosixFileAttributeView::class.java),
                "requires a POSIX filesystem")
        return root
    }

    companion object {
        private fun mode(path: Path, mode: String) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode))
        }

        private fun assertMode(path: Path, mode: String) {
            assertEquals(PosixFilePermissions.fromString(mode), Files.getPosixFilePermissions(path), path.toString())
        }

        private fun assertEmpty(path: Path) {
            Files.list(path).use { entries ->
                assertTrue(entries.findAny().isEmpty(), path.toString())
            }
        }

        private fun assertUnpublishedFailure(result: ReconciliationExecutor.ExecutionResult, source: Path, target: Path) {
            val actions = result.relocations.first().actions
            assertEquals(ReconciliationExecutor.ActionStatus.FAILED, actions.first().status, result.toString())
            assertEquals(ReconciliationExecutor.ActionStatus.PENDING, actions.last().status)
            assertEquals(ReconciliationExecutor.ExecutionOutcome.UNRESOLVED, result.relocations.first().outcome())
            assertTrue(Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS))
            assertTrue(Files.notExists(target, LinkOption.NOFOLLOW_LINKS))
        }

        @Suppress("UNCHECKED_CAST")
        private fun copyVisitor(source: Path, target: Path): FileVisitor<Path> {
            val type = Class.forName(ReconciliationExecutor::class.java.name + "\$CopyVisitor")
            val constructor = type.getDeclaredConstructor(Path::class.java, Path::class.java)
            constructor.setAccessible(true)
            return constructor.newInstance(source, target) as FileVisitor<Path>
        }

        private fun plan(source: Path, target: Path): ReconciliationPlan {
            val inspector = PathInspector()
            return ReconciliationPlanner().plan(listOf(RelocationState(Relocation(source, target),
                    inspector.inspect(source), inspector.inspect(target))))
        }
    }
}
