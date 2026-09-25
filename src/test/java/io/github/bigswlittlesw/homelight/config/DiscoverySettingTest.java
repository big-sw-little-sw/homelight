package io.github.bigswlittlesw.homelight.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class DiscoverySettingTest {
    @TempDir Path temporary;

    @Test void normalizesLocationWithoutInspectingItAndRejectsNonFilesystemInput() {
        assertEquals(Optional.empty(), DiscoverySetting.parse("  "));
        assertEquals(Optional.of(temporary.resolve("missing.yaml")),
                DiscoverySetting.parse(temporary.resolve("absent/../missing.yaml").toString()));
        assertEquals(Optional.of(Path.of(System.getProperty("user.home"), "shared.yaml").normalize()),
                DiscoverySetting.parse("~/folder/../shared.yaml"));
        for (var invalid : List.of("relative.yaml", "../relative.yaml", "https://example.com/list", "$HOME/list", "${HOME}/list", "/tmp/$LIST", "/tmp/list\n")) {
            assertThrows(IllegalArgumentException.class, () -> DiscoverySetting.parse(invalid), invalid);
        }
    }

    @Test void unavailableAndMalformedLocationsRoundTripWithoutBeingRead() throws Exception {
        var malformed = Files.writeString(temporary.resolve("malformed.yaml"), "directories: [");
        var directory = Files.createDirectory(temporary.resolve("directory"));
        var missing = temporary.resolve("unavailable/list.yaml");
        int index = 0;
        for (var location : List.of(malformed, directory, missing)) {
            var path = temporary.resolve("config-" + index++ + ".yaml");
            var draft = new ConfigurationDraft(temporary.resolve("target"), List.of(
                    new Relocation(temporary.resolve("home/manual"), temporary.resolve("target/manual")),
                    new Relocation(temporary.resolve("home/chosen"), temporary.resolve("target/chosen"))),
                    Optional.of(location.getParent().resolve("unused/../" + location.getFileName())));
            new ConfigurationPublisher().saveNew(path, draft);
            var loaded = new ConfigurationLoader().load(path);
            assertEquals(Optional.of(location), loaded.sharedList());
            assertEquals(draft.relocations(), loaded.relocations());
            var evaluation = assertInstanceOf(io.github.bigswlittlesw.homelight.application.ConfigurationEvaluation.Loaded.class,
                    new io.github.bigswlittlesw.homelight.application.ConfigurationEvaluation().load(path));
            assertEquals(loaded.sharedList(), evaluation.savedConfiguration().sharedList());
            var text = Files.readString(path);
            for (var forbidden : List.of("apps:", "directories:", "advice:", "reason:", "provenance:", "observations:",
                    "selected:", "when-source", "when-only", "when-adopting")) assertFalse(text.contains(forbidden), text);
            var second = temporary.resolve("roundtrip-" + index + ".yaml");
            new ConfigurationPublisher().saveNew(second,
                    new ConfigurationDraft(loaded.targetRoot(), loaded.relocations(), loaded.sharedList()));
            assertEquals(text, Files.readString(second));
            assertEquals(draft.sharedList(), draft.withTargetRoot(temporary.resolve("other")).sharedList());
            assertEquals(draft.sharedList(), draft.withRelocations(List.of()).sharedList());
        }
        assertFalse(Files.exists(missing));
        assertEquals("directories: [", Files.readString(malformed));
    }

    @Test void omittedAndBlankSettingRemainCompatibleAndDoNotCreateAnEmptySection() throws Exception {
        var draft = new ConfigurationDraft(temporary.resolve("target"), List.of(
                new Relocation(temporary.resolve("home/manual"), temporary.resolve("target/manual"))));
        var path = temporary.resolve("old.yaml");
        new ConfigurationPublisher().saveNew(path, draft);
        assertTrue(new ConfigurationLoader().load(path).sharedList().isEmpty());
        var text = Files.readString(path);
        assertFalse(text.contains("discovery:"));
        Files.writeString(path, text.replace("  relocations:", "  discovery:\n    shared-list: '   '\n  relocations:"));
        assertTrue(new ConfigurationLoader().load(path).sharedList().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new ConfigurationPublisher().saveNew(
                temporary.resolve("setting-only.yaml"), new ConfigurationDraft(draft.targetRoot(), List.of(), Optional.of(path))));
        assertFalse(Files.exists(temporary.resolve("setting-only.yaml")));
    }

    @Test void concurrentPublicationKeepsOneWholeSettingAndRelocationPair() throws Exception {
        var path = temporary.resolve("config.yaml");
        var first = new ConfigurationDraft(temporary.resolve("target"), List.of(
                new Relocation(temporary.resolve("home/a"), temporary.resolve("target/a"))), Optional.of(temporary.resolve("list-a")));
        var second = new ConfigurationDraft(temporary.resolve("target"), List.of(
                new Relocation(temporary.resolve("home/b"), temporary.resolve("target/b"))), Optional.of(temporary.resolve("list-b")));
        var gate = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var results = pool.invokeAll(List.<Callable<Boolean>>of(() -> save(path, first, gate), () -> save(path, second, gate)));
            assertNotEquals(results.get(0).get(), results.get(1).get());
            var winner = results.get(0).get() ? first : second;
            var loaded = new ConfigurationLoader().load(path);
            assertEquals(winner.sharedList(), loaded.sharedList());
            assertEquals(winner.relocations(), loaded.relocations());
        }
        assertEquals(1, first.relocations().size());
        assertEquals(1, second.relocations().size());
        assertFalse(Files.exists(temporary.resolve("target")));
    }

    private static boolean save(Path path, ConfigurationDraft draft, CyclicBarrier gate) throws Exception {
        gate.await();
        try { new ConfigurationPublisher().saveNew(path, draft); return true; }
        catch (ConfigurationPublisher.ConfigurationException expected) { return false; }
    }
}
