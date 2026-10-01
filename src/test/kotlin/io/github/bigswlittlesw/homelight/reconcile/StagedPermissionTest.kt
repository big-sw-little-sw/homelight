package io.github.bigswlittlesw.homelight.reconcile

import io.github.bigswlittlesw.homelight.config.Relocation
import io.github.bigswlittlesw.homelight.fs.PathInspector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.FileVisitor
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

// Characterizes #25's current gaps, not a promise to retain provider-default modes.
// Change the mode expectations when the coordinator establishes the platform policy.
class StagedPermissionTest {
    @TempDir
    lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["rwx------", "r-x------"])
    fun emptySourceRootCurrentlyPublishesWithCreationDefaults(sourceMode: String) {
        val root = posixRoot()
        val defaults = defaultDirectoryPermissions(root)
        val source = Files.createDirectory(root.resolve("source"))
        mode(source, sourceMode)
        val target = root.resolve("local/target")

        val result = ReconciliationExecutor().execute(plan(source, target))

        assertTrue(result.succeeded(), result.toString())
        assertEquals(defaults, Files.getPosixFilePermissions(target))
        assertTrue(Files.isSymbolicLink(source))
        assertEmpty(target)
        assertEmpty(target.parent.resolve(".homelight-staging"))
    }

    @Test
    fun publicationUsesDefaultDirectoryModesAndRetainsFileAndLinkBehavior() {
        val root = posixRoot()
        val defaults = defaultDirectoryPermissions(root)
        val source = Files.createDirectory(root.resolve("source"))
        val nested = Files.createDirectory(source.resolve("nested"))
        val empty = Files.createDirectory(nested.resolve("empty"))
        val file = Files.writeString(source.resolve("entry"), "private contents")
        val executable = Files.writeString(nested.resolve("executable"), "executable contents")
        val outside = Files.createDirectory(root.resolve("outside"))
        Files.writeString(outside.resolve("keep"), "untouched")
        mode(source, "rwx------")
        mode(nested, "rwx--x---")
        mode(empty, "r-x------")
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
                    try {
                        published[0] = true
                        assertTrue(Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS))
                        assertMode(source, "rwx------")
                        assertMode(nested, "rwx--x---")
                        assertMode(empty, "r-x------")
                        assertEquals(defaults, Files.getPosixFilePermissions(target))
                        assertEquals(defaults, Files.getPosixFilePermissions(target.resolve("nested")))
                        assertEquals(defaults, Files.getPosixFilePermissions(target.resolve("nested/empty")))
                    } catch (exception: IOException) {
                        throw AssertionError(exception)
                    }
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
        assertTrue(repeated.actions().stream().allMatch(ReconciliationAction.NoOp::class.java::isInstance))
        assertTrue(executor.execute(repeated).succeeded())
        assertEquals(defaults, Files.getPosixFilePermissions(target))
        assertMode(target.resolve("entry"), "rw-------")
    }

    @Test
    fun stagingCopierUsesDefaultsWhilePopulatingReadOnlyDirectories() {
        val root = posixRoot()
        val defaults = defaultDirectoryPermissions(root)
        val source = Files.createDirectory(root.resolve("source"))
        val nested = Files.createDirectory(source.resolve("nested"))
        val file = Files.writeString(nested.resolve("entry"), "contents")
        mode(nested, "r-x------")
        mode(source, "r-x------")
        try {
            // Reproduce the executor's staging creation calls; observe the actual shared
            // visitor synchronously, without a production hook or a background watcher.
            val operation = Files.createDirectory(root.resolve("operation"))
            val copy = Files.createDirectory(operation.resolve("copy"))
            val visitor = copyVisitor(source, copy)
            visitor.preVisitDirectory(source, Files.readAttributes(source, BasicFileAttributes::class.java))
            visitor.preVisitDirectory(nested, Files.readAttributes(nested, BasicFileAttributes::class.java))
            assertEquals(defaults, Files.getPosixFilePermissions(operation))
            assertEquals(defaults, Files.getPosixFilePermissions(copy))
            assertEquals(defaults, Files.getPosixFilePermissions(copy.resolve("nested")))
            visitor.visitFile(file, Files.readAttributes(file, BasicFileAttributes::class.java))
            assertEquals("contents", Files.readString(copy.resolve("nested/entry")))
            assertMode(source, "r-x------")
            assertMode(nested, "r-x------")
        } finally {
            mode(source, "rwx------")
            mode(nested, "rwx------")
        }
    }

    @Test
    fun unreadableNestedDirectoryFailsBeforePublicationAndCleansOnlyItsOperation() {
        val root = posixRoot()
        val source = Files.createDirectory(root.resolve("source"))
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
            assertEquals("keep", Files.readString(unrelated))
            Files.list(staging).use { entries ->
                assertEquals(listOf(unrelated), entries.toList())
            }
        } finally {
            mode(nested, "rwx------")
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
    fun readOnlyPopulatedSourceReportsRecoveryAfterPublicationWithoutChmod() {
        val root = posixRoot()
        val defaults = defaultDirectoryPermissions(root)
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
            assertEquals(defaults, Files.getPosixFilePermissions(target))
            assertEmpty(target.parent.resolve(".homelight-staging"))
            assertTrue(plan(source, target).hasConflicts())
        } finally {
            mode(source, "rwx------")
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
    fun sharedCopierDoesNotRequireUnsupportedPosixOperations() {
        // ZIP is a local, deterministic unsupported-view probe, not a supported
        // relocation platform or a stand-in for Windows/NFS publication behavior.
        FileSystems.newFileSystem(temporary.resolve("unsupported.zip"), mapOf("create" to "true")).use { zip ->
            val source = Files.createDirectory(zip.getPath("/source"))
            Files.createDirectory(source.resolve("empty"))
            Files.writeString(source.resolve("entry"), "contents")
            assertNull(Files.getFileAttributeView(source, PosixFileAttributeView::class.java))
            assertThrows(UnsupportedOperationException::class.java) { Files.getPosixFilePermissions(source) }
            assertThrows(UnsupportedOperationException::class.java,
                    { mode(source, "rwx------") })
            val target = zip.getPath("/target")
            val relocation = Relocation(source, target)
            val planned = ReconciliationPlan(listOf(RelocationPlan(relocation, RelocationOutcome.CONVERGED,
                    listOf(ReconciliationAction.CopyDirectory(source, target)), listOf())), listOf())

            val result = ReconciliationExecutor().execute(planned)

            assertTrue(result.succeeded(), result.toString())
            assertEquals("contents", Files.readString(target.resolve("entry")))
            assertEmpty(target.resolve("empty"))
            assertEquals("contents", Files.readString(source.resolve("entry")))
        }
    }

    private fun posixRoot(): Path {
        val root = temporary.toRealPath()
        assumeTrue(Files.getFileStore(root).supportsFileAttributeView(PosixFileAttributeView::class.java),
                "requires a POSIX filesystem")
        return root
    }

    companion object {
        private fun defaultDirectoryPermissions(root: Path): Set<PosixFilePermission> {
            return Files.getPosixFilePermissions(Files.createDirectory(root.resolve("default-mode-control")))
        }

        private fun mode(path: Path, mode: String) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode))
        }

        private fun assertMode(path: Path, mode: String) {
            assertEquals(PosixFilePermissions.fromString(mode), Files.getPosixFilePermissions(path))
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
