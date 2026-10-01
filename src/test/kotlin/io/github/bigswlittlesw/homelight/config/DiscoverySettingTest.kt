package io.github.bigswlittlesw.homelight.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors

class DiscoverySettingTest {
    @TempDir lateinit var temporary: Path

    @Test fun normalizesLocationWithoutInspectingItAndRejectsNonFilesystemInput() {
        assertNull(parseSharedList("  "))
        assertEquals(temporary.resolve("missing.yaml"),
                parseSharedList(temporary.resolve("absent/../missing.yaml").toString()))
        assertEquals(Path.of(System.getProperty("user.home"), "shared.yaml").normalize(),
                parseSharedList("~/folder/../shared.yaml"))
        for (invalid in listOf("relative.yaml", "../relative.yaml", "https://example.com/list", "\$HOME/list", "\${HOME}/list", "/tmp/\$LIST", "/tmp/list\n")) {
            assertThrows(IllegalArgumentException::class.java, { parseSharedList(invalid) }, invalid)
        }
    }

    @Test fun unavailableAndMalformedLocationsRoundTripWithoutBeingRead() {
        val malformed = Files.writeString(temporary.resolve("malformed.yaml"), "directories: [")
        val directory = Files.createDirectory(temporary.resolve("directory"))
        val missing = temporary.resolve("unavailable/list.yaml")
        var index = 0
        for (location in listOf(malformed, directory, missing)) {
            val path = temporary.resolve("config-" + index++ + ".yaml")
            val draft = ConfigurationDraft.of(temporary.resolve("target"), listOf(
                    Relocation(temporary.resolve("home/manual"), temporary.resolve("target/manual")),
                    Relocation(temporary.resolve("home/chosen"), temporary.resolve("target/chosen"))),
                    location.parent.resolve("unused/../" + location.fileName))
            ConfigurationPublisher().saveNew(path, draft)
            val loaded = ConfigurationLoader().load(path)
            assertEquals(location, loaded.sharedList)
            assertEquals(draft.relocations, loaded.relocations)
            val evaluation = assertInstanceOf(io.github.bigswlittlesw.homelight.application.ConfigurationEvaluation.Loaded::class.java,
                    io.github.bigswlittlesw.homelight.application.ConfigurationEvaluation().load(path))
            assertEquals(loaded.sharedList, evaluation.savedConfiguration.sharedList)
            val text = Files.readString(path)
            for (forbidden in listOf("apps:", "directories:", "advice:", "reason:", "provenance:", "observations:",
                    "selected:", "when-source", "when-only", "when-adopting")) assertFalse(text.contains(forbidden), text)
            val second = temporary.resolve("roundtrip-$index.yaml")
            ConfigurationPublisher().saveNew(second,
                    ConfigurationDraft.of(loaded.targetRoot, loaded.relocations, loaded.sharedList))
            assertEquals(text, Files.readString(second))
            assertEquals(draft.sharedList, draft.withTargetRoot(temporary.resolve("other")).sharedList)
            assertEquals(draft.sharedList, draft.withRelocations(listOf()).sharedList)
        }
        assertFalse(Files.exists(missing))
        assertEquals("directories: [", Files.readString(malformed))
    }

    @Test fun omittedAndBlankSettingRemainCompatibleAndDoNotCreateAnEmptySection() {
        val draft = ConfigurationDraft.of(temporary.resolve("target"), listOf(
                Relocation(temporary.resolve("home/manual"), temporary.resolve("target/manual"))))
        val path = temporary.resolve("old.yaml")
        ConfigurationPublisher().saveNew(path, draft)
        assertNull(ConfigurationLoader().load(path).sharedList)
        val text = Files.readString(path)
        assertFalse(text.contains("discovery:"))
        Files.writeString(path, text.replace("  relocations:", "  discovery:\n    shared-list: '   '\n  relocations:"))
        assertNull(ConfigurationLoader().load(path).sharedList)
        assertThrows(IllegalArgumentException::class.java) { ConfigurationPublisher().saveNew(
                temporary.resolve("setting-only.yaml"), ConfigurationDraft.of(draft.targetRoot, listOf(), path)) }
        assertFalse(Files.exists(temporary.resolve("setting-only.yaml")))
    }

    @Test fun concurrentPublicationKeepsOneWholeSettingAndRelocationPair() {
        val path = temporary.resolve("config.yaml")
        val first = ConfigurationDraft.of(temporary.resolve("target"), listOf(
                Relocation(temporary.resolve("home/a"), temporary.resolve("target/a"))), temporary.resolve("list-a"))
        val second = ConfigurationDraft.of(temporary.resolve("target"), listOf(
                Relocation(temporary.resolve("home/b"), temporary.resolve("target/b"))), temporary.resolve("list-b"))
        val gate = CyclicBarrier(2)
        Executors.newFixedThreadPool(2).use { pool ->
            val results = pool.invokeAll(listOf<Callable<Boolean>>(Callable { save(path, first, gate) }, Callable { save(path, second, gate) }))
            assertNotEquals(results.get(0).get(), results.get(1).get())
            val winner = if (results.get(0).get()) first else second
            val loaded = ConfigurationLoader().load(path)
            assertEquals(winner.sharedList, loaded.sharedList)
            assertEquals(winner.relocations, loaded.relocations)
        }
        assertEquals(1, first.relocations.size)
        assertEquals(1, second.relocations.size)
        assertFalse(Files.exists(temporary.resolve("target")))
    }

    companion object {
        private fun save(path: Path, draft: ConfigurationDraft, gate: CyclicBarrier): Boolean {
            gate.await()
            try { ConfigurationPublisher().saveNew(path, draft); return true }
            catch (expected: ConfigurationPublisher.ConfigurationException) { return false }
        }
    }
}
