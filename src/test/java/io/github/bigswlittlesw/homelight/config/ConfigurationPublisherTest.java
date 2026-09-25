package io.github.bigswlittlesw.homelight.config;

import io.github.bigswlittlesw.homelight.application.HomeLightSession;
import io.github.bigswlittlesw.homelight.tui.HomeLightApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class ConfigurationPublisherTest {
    @Test void validatesThenCreatesAndReloadsWithoutApplying(@TempDir Path root) {
        var path = root.resolve("new/config.yaml");
        var draft = draft(root);
        var session = new HomeLightSession(path);
        assertFalse(session.requestApply());

        new ConfigurationPublisher().saveNew(path, draft);

        assertFalse(Files.exists(root.resolve("home/cache")), "save must not relocate");
        session.refresh();
        assertEquals(root.resolve("local").toAbsolutePath(),
                assertInstanceOf(io.github.bigswlittlesw.homelight.application.ConfigurationEvaluation.Loaded.class,
                        session.evaluation()).savedConfiguration().targetRoot());
        assertEquals(root.resolve("home/cache").toAbsolutePath(),
                new ConfigurationLoader().load(path).relocations().getFirst().sourcePath());
    }

    @Test void rejectsOverlapsAndDuplicateTargetsWithoutPublishing(@TempDir Path root) {
        var path = root.resolve("config.yaml");
        var first = relocation(root.resolve("home/a"), root.resolve("local/a"));
        var overlapping = relocation(root.resolve("home/a/child"), root.resolve("local/b"));
        var duplicateTarget = relocation(root.resolve("home/b"), root.resolve("local/a"));
        var publisher = new ConfigurationPublisher();
        assertThrows(IllegalArgumentException.class, () -> publisher.saveNew(path,
                new ConfigurationDraft(root.resolve("local"), List.of(first, overlapping))));
        assertThrows(IllegalArgumentException.class, () -> publisher.saveNew(path,
                new ConfigurationDraft(root.resolve("local"), List.of(first, duplicateTarget))));
        assertFalse(Files.exists(path));
    }

    @Test void failedWriteAndExistingMalformedFileKeepTheDraftAndFile(@TempDir Path root) throws Exception {
        var draft = draft(root);
        var blockedParent = Files.writeString(root.resolve("not-a-directory"), "occupied");
        assertThrows(ConfigurationPublisher.ConfigurationException.class,
                () -> new ConfigurationPublisher().saveNew(blockedParent.resolve("config.yaml"), draft));

        var path = root.resolve("config.yaml");
        Files.writeString(path, "homelight: [");
        assertThrows(ConfigurationPublisher.ConfigurationException.class, () -> new ConfigurationPublisher().saveNew(path, draft));
        assertEquals("homelight: [", Files.readString(path));
        assertThrows(RuntimeException.class, () -> new ConfigurationLoader().load(path));
    }

    @Test void concurrentCreationPublishesExactlyOneCompleteConfiguration(@TempDir Path root) throws Exception {
        var path = root.resolve("config.yaml");
        var publisher = new ConfigurationPublisher();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var results = pool.invokeAll(List.<Callable<Boolean>>of(
                    () -> save(publisher, path, draft(root)), () -> save(publisher, path, draft(root))));
            assertEquals(1, results.stream().filter(result -> {
                try { return result.get(); } catch (Exception exception) { throw new AssertionError(exception); }
            }).count());
        }
        assertEquals(1, new ConfigurationLoader().load(path).relocations().size());
    }

    @Test void cancellingManualSetupWritesNothing(@TempDir Path root) {
        var path = root.resolve("config.yaml");
        var app = new HomeLightApp(new HomeLightSession(path));
        app.handleKeyEvent(dev.tamboui.tui.event.KeyEvent.ofChar('i'));
        app.handleKeyEvent(dev.tamboui.tui.event.KeyEvent.ofKey(dev.tamboui.tui.event.KeyCode.ESCAPE));
        assertFalse(Files.exists(path));
        assertFalse(app.session().requestApply());
    }

    private static boolean save(ConfigurationPublisher publisher, Path path, ConfigurationDraft draft) {
        try { publisher.saveNew(path, draft); return true; }
        catch (ConfigurationPublisher.ConfigurationException expected) { return false; }
    }
    private static ConfigurationDraft draft(Path root) {
        return new ConfigurationDraft(root.resolve("local"), List.of(relocation(root.resolve("home/cache"), root.resolve("local/cache"))));
    }
    private static Relocation relocation(Path source, Path target) { return new Relocation(source, target); }
}
