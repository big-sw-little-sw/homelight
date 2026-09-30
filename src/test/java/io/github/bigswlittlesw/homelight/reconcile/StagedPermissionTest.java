package io.github.bigswlittlesw.homelight.reconcile;

import io.github.bigswlittlesw.homelight.config.Relocation;
import io.github.bigswlittlesw.homelight.fs.PathInspector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

// Characterizes #25's current gaps, not a promise to retain provider-default modes.
// Change the mode expectations when the coordinator establishes the platform policy.
class StagedPermissionTest {
    @TempDir
    Path temporary;

    @ParameterizedTest
    @ValueSource(strings = {"rwx------", "r-x------"})
    void emptySourceRootCurrentlyPublishesWithCreationDefaults(String sourceMode) throws Exception {
        var root = posixRoot();
        var defaults = defaultDirectoryPermissions(root);
        var source = Files.createDirectory(root.resolve("source"));
        mode(source, sourceMode);
        var target = root.resolve("local/target");

        var result = new ReconciliationExecutor().execute(plan(source, target));

        assertTrue(result.succeeded(), result.toString());
        assertEquals(defaults, Files.getPosixFilePermissions(target));
        assertTrue(Files.isSymbolicLink(source));
        assertEmpty(target);
        assertEmpty(target.getParent().resolve(".homelight-staging"));
    }

    @Test
    void publicationUsesDefaultDirectoryModesAndRetainsFileAndLinkBehavior() throws Exception {
        var root = posixRoot();
        var defaults = defaultDirectoryPermissions(root);
        var source = Files.createDirectory(root.resolve("source"));
        var nested = Files.createDirectory(source.resolve("nested"));
        var empty = Files.createDirectory(nested.resolve("empty"));
        var file = Files.writeString(source.resolve("entry"), "private contents");
        var executable = Files.writeString(nested.resolve("executable"), "executable contents");
        var outside = Files.createDirectory(root.resolve("outside"));
        Files.writeString(outside.resolve("keep"), "untouched");
        mode(source, "rwx------");
        mode(nested, "rwx--x---");
        mode(empty, "r-x------");
        mode(file, "rw-------");
        mode(executable, "rwx------");
        mode(outside, "rwx------");
        Files.createSymbolicLink(nested.resolve("relative"), Path.of("../entry"));
        Files.createSymbolicLink(source.resolve("broken"), Path.of("missing"));
        Files.createSymbolicLink(source.resolve("external"), outside);
        var target = root.resolve("local/target");
        var published = new boolean[1];
        var executor = new ReconciliationExecutor();

        var result = executor.execute(plan(source, target), new ReconciliationExecutor.ProgressListener() {
            @Override
            public void finished(RelocationPlan relocation, ReconciliationExecutor.ActionExecution action) {
                if (action.action() instanceof ReconciliationAction.MigrateDirectoryForPublication
                        && action.status() == ReconciliationExecutor.ActionStatus.COMPLETED) {
                    try {
                        published[0] = true;
                        assertTrue(Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS));
                        assertMode(source, "rwx------");
                        assertMode(nested, "rwx--x---");
                        assertMode(empty, "r-x------");
                        assertEquals(defaults, Files.getPosixFilePermissions(target));
                        assertEquals(defaults, Files.getPosixFilePermissions(target.resolve("nested")));
                        assertEquals(defaults, Files.getPosixFilePermissions(target.resolve("nested/empty")));
                    } catch (IOException exception) {
                        throw new AssertionError(exception);
                    }
                }
            }
        });

        assertTrue(result.succeeded(), result.toString());
        assertTrue(published[0], "must observe publication before source replacement");
        assertTrue(Files.isSymbolicLink(source));
        assertEquals(target, Files.readSymbolicLink(source));
        assertMode(target.resolve("entry"), "rw-------");
        assertMode(target.resolve("nested/executable"), "rwx------");
        assertEquals("private contents", Files.readString(target.resolve("entry")));
        assertEquals("executable contents", Files.readString(target.resolve("nested/executable")));
        assertEquals(Path.of("../entry"), Files.readSymbolicLink(target.resolve("nested/relative")));
        assertEquals(Path.of("missing"), Files.readSymbolicLink(target.resolve("broken")));
        assertEquals(outside, Files.readSymbolicLink(target.resolve("external")));
        assertEquals("untouched", Files.readString(outside.resolve("keep")));
        assertMode(outside, "rwx------");
        assertEmpty(target.resolve("nested/empty"));
        assertEmpty(target.getParent().resolve(".homelight-staging"));

        var repeated = plan(source, target);
        assertTrue(repeated.actions().stream().allMatch(ReconciliationAction.NoOp.class::isInstance));
        assertTrue(executor.execute(repeated).succeeded());
        assertEquals(defaults, Files.getPosixFilePermissions(target));
        assertMode(target.resolve("entry"), "rw-------");
    }

    @Test
    void stagingCopierUsesDefaultsWhilePopulatingReadOnlyDirectories() throws Exception {
        var root = posixRoot();
        var defaults = defaultDirectoryPermissions(root);
        var source = Files.createDirectory(root.resolve("source"));
        var nested = Files.createDirectory(source.resolve("nested"));
        var file = Files.writeString(nested.resolve("entry"), "contents");
        mode(nested, "r-x------");
        mode(source, "r-x------");
        try {
            // Reproduce the executor's staging creation calls; observe the actual shared
            // visitor synchronously, without a production hook or a background watcher.
            var operation = Files.createDirectory(root.resolve("operation"));
            var copy = Files.createDirectory(operation.resolve("copy"));
            var visitor = copyVisitor(source, copy);
            visitor.preVisitDirectory(source, Files.readAttributes(source, BasicFileAttributes.class));
            visitor.preVisitDirectory(nested, Files.readAttributes(nested, BasicFileAttributes.class));
            assertEquals(defaults, Files.getPosixFilePermissions(operation));
            assertEquals(defaults, Files.getPosixFilePermissions(copy));
            assertEquals(defaults, Files.getPosixFilePermissions(copy.resolve("nested")));
            visitor.visitFile(file, Files.readAttributes(file, BasicFileAttributes.class));
            assertEquals("contents", Files.readString(copy.resolve("nested/entry")));
            assertMode(source, "r-x------");
            assertMode(nested, "r-x------");
        } finally {
            mode(source, "rwx------");
            mode(nested, "rwx------");
        }
    }

    @Test
    void unreadableNestedDirectoryFailsBeforePublicationAndCleansOnlyItsOperation() throws Exception {
        var root = posixRoot();
        var source = Files.createDirectory(root.resolve("source"));
        var nested = Files.createDirectory(source.resolve("unreadable"));
        Files.writeString(nested.resolve("entry"), "keep");
        mode(source, "rwx------");
        var target = root.resolve("local/target");
        var staging = Files.createDirectories(target.getParent().resolve(".homelight-staging"));
        var unrelated = Files.writeString(staging.resolve("unowned"), "keep");
        var planned = plan(source, target);
        mode(nested, "---------");
        try {
            assumeFalse(Files.isReadable(nested), "requires directory read denial, not a privileged process");
            var result = new ReconciliationExecutor().execute(planned);
            assertUnpublishedFailure(result, source, target);
            assertMode(source, "rwx------");
            assertMode(nested, "---------");
            assertEquals("keep", Files.readString(unrelated));
            try (var entries = Files.list(staging)) {
                assertEquals(List.of(unrelated), entries.toList());
            }
        } finally {
            mode(nested, "rwx------");
        }
        assertEquals("keep", Files.readString(nested.resolve("entry")));
    }

    @Test
    void unwritableStagingFailsWithoutChangingSourceOrUnownedEntries() throws Exception {
        var root = posixRoot();
        var source = Files.createDirectory(root.resolve("source"));
        Files.writeString(source.resolve("entry"), "keep");
        var target = root.resolve("local/target");
        var staging = Files.createDirectories(target.getParent().resolve(".homelight-staging"));
        var unrelated = Files.writeString(staging.resolve("unowned"), "keep");
        mode(staging, "r-x------");
        try {
            assumeFalse(Files.isWritable(staging), "requires directory write denial, not a privileged process");
            var result = new ReconciliationExecutor().execute(plan(source, target));
            assertUnpublishedFailure(result, source, target);
            assertMode(staging, "r-x------");
            assertEquals("keep", Files.readString(source.resolve("entry")));
            assertEquals("keep", Files.readString(unrelated));
        } finally {
            mode(staging, "rwx------");
        }
    }

    @Test
    void readOnlyPopulatedSourceReportsRecoveryAfterPublicationWithoutChmod() throws Exception {
        var root = posixRoot();
        var defaults = defaultDirectoryPermissions(root);
        var source = Files.createDirectory(root.resolve("source"));
        Files.writeString(source.resolve("entry"), "keep");
        mode(source, "r-x------");
        var target = root.resolve("local/target");
        try {
            assumeFalse(Files.isWritable(source), "requires directory write denial, not a privileged process");
            var result = new ReconciliationExecutor().execute(plan(source, target));
            assertFalse(result.succeeded());
            assertEquals(ReconciliationExecutor.ExecutionOutcome.FAILED_RECOVERY,
                    result.relocations().getFirst().outcome());
            assertTrue(Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS));
            assertEquals("keep", Files.readString(source.resolve("entry")));
            assertEquals("keep", Files.readString(target.resolve("entry")));
            assertMode(source, "r-x------");
            assertEquals(defaults, Files.getPosixFilePermissions(target));
            assertEmpty(target.getParent().resolve(".homelight-staging"));
            assertTrue(plan(source, target).hasConflicts());
        } finally {
            mode(source, "rwx------");
        }
    }

    @Test
    void restrictiveStaleCopyIsRetainedAndStopsNewPublication() throws Exception {
        var root = posixRoot();
        var source = Files.createDirectory(root.resolve("source"));
        Files.writeString(source.resolve("entry"), "keep");
        var target = root.resolve("local/target");
        var staging = Files.createDirectories(target.getParent().resolve(".homelight-staging"));
        var stale = Files.createDirectory(staging.resolve("operation-00000000-0000-0000-0000-000000000000"));
        Files.writeString(stale.resolve("target"), "homelight-staging-v1\n" + target + "\n");
        Files.createFile(stale.resolve("lock"));
        var copy = Files.createDirectory(stale.resolve("copy"));
        Files.writeString(copy.resolve("entry"), "stale");
        mode(copy, "r-x------");
        try {
            assumeFalse(Files.isWritable(copy), "requires directory write denial, not a privileged process");
            var result = new ReconciliationExecutor().execute(plan(source, target));
            assertUnpublishedFailure(result, source, target);
            assertMode(copy, "r-x------");
            assertEquals("stale", Files.readString(copy.resolve("entry")));
            assertEquals("keep", Files.readString(source.resolve("entry")));
        } finally {
            mode(copy, "rwx------");
        }
    }

    @Test
    void sharedCopierDoesNotRequireUnsupportedPosixOperations() throws Exception {
        // ZIP is a local, deterministic unsupported-view probe, not a supported
        // relocation platform or a stand-in for Windows/NFS publication behavior.
        try (var zip = FileSystems.newFileSystem(temporary.resolve("unsupported.zip"), Map.of("create", "true"))) {
            var source = Files.createDirectory(zip.getPath("/source"));
            Files.createDirectory(source.resolve("empty"));
            Files.writeString(source.resolve("entry"), "contents");
            assertNull(Files.getFileAttributeView(source, PosixFileAttributeView.class));
            assertThrows(UnsupportedOperationException.class, () -> Files.getPosixFilePermissions(source));
            assertThrows(UnsupportedOperationException.class,
                    () -> mode(source, "rwx------"));
            var target = zip.getPath("/target");
            var relocation = new Relocation(source, target);
            var planned = new ReconciliationPlan(List.of(new RelocationPlan(relocation, RelocationOutcome.CONVERGED,
                    List.of(new ReconciliationAction.CopyDirectory(source, target)), List.of(), Optional.empty())), List.of());

            var result = new ReconciliationExecutor().execute(planned);

            assertTrue(result.succeeded(), result.toString());
            assertEquals("contents", Files.readString(target.resolve("entry")));
            assertEmpty(target.resolve("empty"));
            assertEquals("contents", Files.readString(source.resolve("entry")));
        }
    }

    private Path posixRoot() throws IOException {
        var root = temporary.toRealPath();
        assumeTrue(Files.getFileStore(root).supportsFileAttributeView(PosixFileAttributeView.class),
                "requires a POSIX filesystem");
        return root;
    }

    private static Set<PosixFilePermission> defaultDirectoryPermissions(Path root) throws IOException {
        return Files.getPosixFilePermissions(Files.createDirectory(root.resolve("default-mode-control")));
    }

    private static void mode(Path path, String mode) throws IOException {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode));
    }

    private static void assertMode(Path path, String mode) throws IOException {
        assertEquals(PosixFilePermissions.fromString(mode), Files.getPosixFilePermissions(path));
    }

    private static void assertEmpty(Path path) throws IOException {
        try (var entries = Files.list(path)) {
            assertTrue(entries.findAny().isEmpty(), path.toString());
        }
    }

    private static void assertUnpublishedFailure(ReconciliationExecutor.ExecutionResult result, Path source, Path target) {
        var actions = result.relocations().getFirst().actions();
        assertEquals(ReconciliationExecutor.ActionStatus.FAILED, actions.getFirst().status(), result.toString());
        assertEquals(ReconciliationExecutor.ActionStatus.PENDING, actions.getLast().status());
        assertEquals(ReconciliationExecutor.ExecutionOutcome.UNRESOLVED, result.relocations().getFirst().outcome());
        assertTrue(Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.notExists(target, LinkOption.NOFOLLOW_LINKS));
    }

    @SuppressWarnings("unchecked")
    private static FileVisitor<Path> copyVisitor(Path source, Path target) throws ReflectiveOperationException {
        var type = Class.forName(ReconciliationExecutor.class.getName() + "$CopyVisitor");
        var constructor = type.getDeclaredConstructor(Path.class, Path.class);
        constructor.setAccessible(true);
        return (FileVisitor<Path>) constructor.newInstance(source, target);
    }

    private static ReconciliationPlan plan(Path source, Path target) {
        var inspector = new PathInspector();
        return new ReconciliationPlanner().plan(List.of(new RelocationState(new Relocation(source, target),
                inspector.inspect(source), inspector.inspect(target))));
    }
}
