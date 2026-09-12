package io.github.bigswlittlesw.homelight.reconcile;

import io.github.bigswlittlesw.homelight.config.Relocation;
import io.github.bigswlittlesw.homelight.config.WhenAdoptingTarget;
import io.github.bigswlittlesw.homelight.config.WhenSourceAndTargetDirectoriesExist;
import io.github.bigswlittlesw.homelight.fs.PathInspector;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconciliationExecutorTest {
    @Test
    void rejectsUnresolvedPlans() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var source = Files.createDirectories(root.resolve("source"));
        var target = Files.createDirectories(root.resolve("target"));

        assertThrows(IllegalArgumentException.class, () -> new ReconciliationExecutor().execute(plan(new Relocation(source, target))));
    }

    @Test
    void createsAnAbsentSourceLinkAndTargetDirectory() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var source = root.resolve("source");
        var target = root.resolve("target");

        var result = new ReconciliationExecutor().execute(plan(new Relocation(source, target)));

        assertTrue(result.succeeded());
        assertTrue(Files.isSymbolicLink(source));
    }

    @Test
    void archivesTheSourceBeforeLinkingToAnAdoptedTarget() throws Exception {
        var root = Files.createTempDirectory("homelight");
        var source = Files.createDirectories(root.resolve("home/cache"));
        Files.writeString(source.resolve("entry"), "source");
        var target = Files.createDirectories(root.resolve("local/cache"));
        var archiveRoot = root.resolve("archive");
        var relocation = new Relocation(source, target,
                java.util.Optional.of(WhenSourceAndTargetDirectoriesExist.ADOPT), java.util.Optional.empty(),
                java.util.Optional.of(WhenAdoptingTarget.ARCHIVE_SOURCE), java.util.Optional.of(archiveRoot));

        var result = new ReconciliationExecutor().execute(plan(relocation));

        assertTrue(result.succeeded());
        assertTrue(Files.isSymbolicLink(source));
        assertTrue(Files.exists(archiveRoot.resolve(source.toAbsolutePath().getRoot().relativize(source.toAbsolutePath()))
                .resolve("entry")));
    }

    private static ReconciliationPlan plan(Relocation relocation) {
        var inspector = new PathInspector();
        var archive = relocation.sourceArchiveRoot().map(root -> {
            var source = relocation.sourcePath().toAbsolutePath();
            var path = root.resolve(source.getRoot().relativize(source));
            return new RelocationState.ArchiveDestination(path, inspector.inspect(path));
        });
        return new ReconciliationPlanner().plan(List.of(new RelocationState(relocation,
                inspector.inspect(relocation.sourcePath()), inspector.inspect(relocation.targetPath()), archive)));
    }
}
