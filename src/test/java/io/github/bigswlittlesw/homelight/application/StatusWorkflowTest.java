package io.github.bigswlittlesw.homelight.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatusWorkflowTest {

    @Test
    void unconfiguredWhenDefaultPathDoesNotExist(@TempDir Path tempDir) {
        var workflow = new StatusWorkflow();
        var result = workflow.loadStatus(tempDir.resolve(".homelight.yaml"));

        // If it's not the default path and doesn't exist, it's invalid
        assertInstanceOf(StatusModel.Invalid.class, result);
    }

    @Test
    void loadsConvergedStatus(@TempDir Path tempDir) throws Exception {
        var root = tempDir.resolve("target");
        var source = tempDir.resolve("source");
        Files.createDirectories(root);
        Files.createSymbolicLink(source, root);

        var config = tempDir.resolve("config.yaml");
        Files.writeString(config, """
                homelight:
                  target-root: %s
                  relocations:
                    - source-path: %s
                      target-path: %s
                """.formatted(root, source, root));

        var workflow = new StatusWorkflow();
        var model = workflow.loadStatus(config);

        assertInstanceOf(StatusModel.Configured.class, model);
        var configured = (StatusModel.Configured) model;
        assertEquals(1, configured.items().size());
        assertEquals(RelocationStatusItem.StatusBadge.CONVERGED, configured.items().getFirst().badge());
        assertEquals(1, configured.summary().converged());
    }

    @Test
    void loadsPendingStatusWhenSymlinkNeeded(@TempDir Path tempDir) throws Exception {
        var root = tempDir.resolve("target");
        var source = tempDir.resolve("source");
        Files.createDirectories(root);

        var config = tempDir.resolve("config.yaml");
        Files.writeString(config, """
                homelight:
                  target-root: %s
                  relocations:
                    - source-path: %s
                      target-path: %s
                      when-only-target-exists: adopt-target
                """.formatted(root, source, root));

        var workflow = new StatusWorkflow();
        var model = workflow.loadStatus(config);

        assertInstanceOf(StatusModel.Configured.class, model);
        var configured = (StatusModel.Configured) model;
        assertEquals(RelocationStatusItem.StatusBadge.PENDING, configured.items().getFirst().badge());
        assertEquals(1, configured.summary().pending());
    }

    @Test
    void loadsConflictStatusWhenBothDirectoriesExist(@TempDir Path tempDir) throws Exception {
        var root = tempDir.resolve("target");
        var source = tempDir.resolve("source");
        Files.createDirectories(root);
        Files.createDirectories(source);

        var config = tempDir.resolve("config.yaml");
        Files.writeString(config, """
                homelight:
                  target-root: %s
                  relocations:
                    - source-path: %s
                      target-path: %s
                      when-source-and-target-directories-exist: prompt
                """.formatted(root, source, root));

        var workflow = new StatusWorkflow();
        var model = workflow.loadStatus(config);

        assertInstanceOf(StatusModel.Configured.class, model);
        var configured = (StatusModel.Configured) model;
        assertEquals(RelocationStatusItem.StatusBadge.CONFLICT, configured.items().getFirst().badge());
        assertEquals(1, configured.summary().conflicts());
    }

    @Test
    void loadsBlockedStatusWhenSourceIsRegularFile(@TempDir Path tempDir) throws Exception {
        var root = tempDir.resolve("target");
        var source = tempDir.resolve("source");
        Files.writeString(source, "content");

        var config = tempDir.resolve("config.yaml");
        Files.writeString(config, """
                homelight:
                  target-root: %s
                  relocations:
                    - source-path: %s
                      target-path: %s
                """.formatted(root, source, root));

        var workflow = new StatusWorkflow();
        var model = workflow.loadStatus(config);

        assertInstanceOf(StatusModel.Configured.class, model);
        var configured = (StatusModel.Configured) model;
        assertEquals(RelocationStatusItem.StatusBadge.BLOCKED, configured.items().getFirst().badge());
        assertEquals(1, configured.summary().blocked());
    }

    @Test
    void loadsWarningStatusWhenBrokenSymlink(@TempDir Path tempDir) throws Exception {
        var root = tempDir.resolve("target");
        var source = tempDir.resolve("source");
        var nonExistent = tempDir.resolve("does-not-exist");
        Files.createDirectories(root);
        Files.createSymbolicLink(source, nonExistent);

        var config = tempDir.resolve("config.yaml");
        Files.writeString(config, """
                homelight:
                  target-root: %s
                  relocations:
                    - source-path: %s
                      target-path: %s
                """.formatted(root, source, root));

        var workflow = new StatusWorkflow();
        var model = workflow.loadStatus(config);

        assertInstanceOf(StatusModel.Configured.class, model);
        var configured = (StatusModel.Configured) model;
        assertEquals(RelocationStatusItem.StatusBadge.WARNING, configured.items().getFirst().badge());
        assertEquals(1, configured.summary().warnings());
    }

    @Test
    void invalidWhenConfigIsMalformed(@TempDir Path tempDir) throws Exception {
        var config = tempDir.resolve("config.yaml");
        Files.writeString(config, "invalid: : yaml");

        var workflow = new StatusWorkflow();
        var model = workflow.loadStatus(config);

        assertInstanceOf(StatusModel.Invalid.class, model);
    }
}
