package io.github.bigswlittlesw.lighten.config

import io.github.bigswlittlesw.lighten.application.ConfigurationEvaluation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors

class DiscoverySettingTest {
    @TempDir lateinit var temporary: Path

    /** The same rules as every other path (#207): `~`, `~/` and `${USER}` expand, and the result must be full. */
    @Test fun followsThePathRulesOfEveryOtherPathWithoutInspectingIt() {
        fun list(value: String) =
            ConfigurationLoader().configuration(LightenFile(targetRoot = "/local", suggestionList = value)).sharedList
        val home = Path.of(System.getProperty("user.home"))
        assertNull(list("  "))
        assertEquals(temporary.resolve("missing.json"), list(temporary.resolve("absent/../missing.json").toString()))
        assertEquals(home.resolve("shared.json"), list("~/folder/../shared.json"))
        assertEquals(home, list("~"))
        assertEquals(Path.of("/net/${userName()}/list.json"), list("/net/\${USER}/list.json"))
        for (invalid in listOf("relative.json", "../relative.json", "https://example.com/list", "\$HOME/list", "\${HOME}/list")) {
            assertEquals("lighten.suggestion-list: $FULL_PATH",
                assertThrows<ConfigurationException>(invalid) { list(invalid) }.text.toString())
        }
    }

    @Test fun unavailableAndMalformedLocationsRoundTripWithoutBeingRead() {
        val malformed = Files.writeString(temporary.resolve("malformed.json"), "{\"directories\": [")
        val directory = Files.createDirectory(temporary.resolve("directory"))
        val missing = temporary.resolve("unavailable/list.json")
        var index = 0
        for (location in listOf(malformed, directory, missing)) {
            val path = temporary.resolve("config-" + index++ + ".json")
            val file = file(location.parent.resolve("unused/../" + location.fileName), "manual", "chosen")
            ConfigurationPublisher().saveNew(path, file)
            val loaded = ConfigurationLoader().load(path)
            assertEquals(location, loaded.sharedList)
            assertEquals(ConfigurationLoader().configuration(file).relocations, loaded.relocations)
            val evaluation = assertInstanceOf(ConfigurationEvaluation.Loaded::class.java,
                    ConfigurationEvaluation().load(path))
            assertEquals(loaded.sharedList, evaluation.savedConfiguration.sharedList)
            val text = Files.readString(path)
            for (forbidden in listOf("\"apps\"", "\"directories\"", "\"advice\"", "\"reason\"", "\"provenance\"",
                    "\"observations\"", "\"selected\"", "when-source", "when-only", "when-adopting")) {
                assertFalse(text.contains(forbidden), text)
            }
            val second = temporary.resolve("roundtrip-$index.json")
            ConfigurationPublisher().saveNew(second, ConfigurationLoader().read(path).file)
            assertEquals(text, Files.readString(second))
        }
        assertFalse(Files.exists(missing))
        assertEquals("{\"directories\": [", Files.readString(malformed))
    }

    @Test fun omittedAndBlankSettingRemainCompatibleAndDoNotCreateAnEmptySection() {
        val file = file(null, "manual")
        val path = temporary.resolve("old.json")
        ConfigurationPublisher().saveNew(path, file)
        assertNull(ConfigurationLoader().load(path).sharedList)
        val text = Files.readString(path)
        assertFalse(text.contains("suggestion-list"))
        Files.writeString(path, text.replace("\"relocations\":", "\"suggestion-list\": \"   \", \"relocations\":"))
        assertNull(ConfigurationLoader().load(path).sharedList)
        assertThrows<IllegalArgumentException> {
            ConfigurationPublisher().saveNew(temporary.resolve("setting-only.json"), file(path))
        }
        assertFalse(Files.exists(temporary.resolve("setting-only.json")))
    }

    @Test fun concurrentPublicationKeepsOneWholeSettingAndRelocationPair() {
        val path = temporary.resolve("config.json")
        val first = file(temporary.resolve("list-a"), "a")
        val second = file(temporary.resolve("list-b"), "b")
        val gate = CyclicBarrier(2)
        Executors.newFixedThreadPool(2).use { pool ->
            val results = pool.invokeAll(listOf<Callable<Boolean>>(Callable { save(path, first, gate) }, Callable { save(path, second, gate) }))
            assertNotEquals(results.get(0).get(), results.get(1).get())
            val winner = if (results.get(0).get()) first else second
            assertEquals(winner, ConfigurationLoader().read(path).file)
        }
        assertFalse(Files.exists(temporary.resolve("target")))
    }

    /** A configuration with the suggestion list at `list` and one relocation per name, from `home` to `target`. */
    private fun file(list: Path?, vararg names: String) = LightenFile(
        targetRoot = temporary.resolve("target").toString(),
        suggestionList = list?.toString(),
        relocations = names.map { RelocationFile(temporary.resolve("home/$it").toString(), temporary.resolve("target/$it").toString()) },
    )

    companion object {
        private fun save(path: Path, file: LightenFile, gate: CyclicBarrier): Boolean {
            gate.await()
            try { ConfigurationPublisher().saveNew(path, file); return true }
            catch (expected: ConfigurationException) { return false }
        }
    }
}
