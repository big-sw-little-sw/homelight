package io.github.bigswlittlesw.homelight.config

import io.github.bigswlittlesw.homelight.application.HomeLightSession
import io.github.bigswlittlesw.homelight.tui.HomeLightApp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class ConfigurationPublisherTest {
    @Test fun validatesThenCreatesAndReloadsWithoutApplying(@TempDir root: Path) {
        val path = root.resolve("new/config.json")
        val draft = draft(root)
        val session = HomeLightSession(path)
        assertFalse(session.requestApply())

        ConfigurationPublisher().saveNew(path, draft)

        assertFalse(Files.exists(root.resolve("home/cache")), "save must not relocate")
        session.refresh()
        assertEquals(root.resolve("local").toAbsolutePath(),
                assertInstanceOf(io.github.bigswlittlesw.homelight.application.ConfigurationEvaluation.Loaded::class.java,
                        session.evaluation()).savedConfiguration.targetRoot)
        assertEquals(root.resolve("home/cache").toAbsolutePath(),
                ConfigurationLoader().load(path).relocations.first().sourcePath)
    }

    @Test fun rejectsOverlapsAndDuplicateTargetsWithoutPublishing(@TempDir root: Path) {
        val path = root.resolve("config.json")
        val first = relocation(root.resolve("home/a"), root.resolve("local/a"))
        val overlapping = relocation(root.resolve("home/a/child"), root.resolve("local/b"))
        val duplicateTarget = relocation(root.resolve("home/b"), root.resolve("local/a"))
        val publisher = ConfigurationPublisher()
        assertThrows(IllegalArgumentException::class.java) { publisher.saveNew(path,
                ConfigurationDraft.of(root.resolve("local"), listOf(first, overlapping))) }
        assertThrows(IllegalArgumentException::class.java) { publisher.saveNew(path,
                ConfigurationDraft.of(root.resolve("local"), listOf(first, duplicateTarget))) }
        assertFalse(Files.exists(path))
    }

    @Test fun failedWriteAndExistingMalformedFileKeepTheDraftAndFile(@TempDir root: Path) {
        val draft = draft(root)
        val blockedParent = Files.writeString(root.resolve("not-a-directory"), "occupied")
        assertThrows(ConfigurationException::class.java) {
            ConfigurationPublisher().saveNew(blockedParent.resolve("config.json"), draft) }

        val path = root.resolve("config.json")
        Files.writeString(path, "{\"homelight\": [")
        assertThrows(ConfigurationException::class.java) { ConfigurationPublisher().saveNew(path, draft) }
        assertEquals("{\"homelight\": [", Files.readString(path))
        assertThrows(RuntimeException::class.java) { ConfigurationLoader().load(path) }
    }

    @Test fun concurrentCreationPublishesExactlyOneCompleteConfiguration(@TempDir root: Path) {
        val path = root.resolve("config.json")
        val publisher = ConfigurationPublisher()
        Executors.newFixedThreadPool(2).use { pool ->
            val results = pool.invokeAll(listOf<Callable<Boolean>>(
                    Callable { save(publisher, path, draft(root)) }, Callable { save(publisher, path, draft(root)) }))
            assertEquals(1, results.count { result ->
                try { result.get() } catch (exception: Exception) { throw AssertionError(exception) }
            })
        }
        assertEquals(1, ConfigurationLoader().load(path).relocations.size)
    }

    @Test fun cancellingManualSetupWritesNothing(@TempDir root: Path) {
        val path = root.resolve("config.json")
        val app = HomeLightApp(HomeLightSession(path))
        app.handleKeyEvent(dev.tamboui.tui.event.KeyEvent.ofChar('i'))
        app.handleKeyEvent(dev.tamboui.tui.event.KeyEvent.ofKey(dev.tamboui.tui.event.KeyCode.ESCAPE))
        assertFalse(Files.exists(path))
        assertFalse(app.session.requestApply())
    }

    companion object {
        private fun save(publisher: ConfigurationPublisher, path: Path, draft: ConfigurationDraft): Boolean {
            try { publisher.saveNew(path, draft); return true }
            catch (expected: ConfigurationException) { return false }
        }
        private fun draft(root: Path): ConfigurationDraft {
            return ConfigurationDraft.of(root.resolve("local"), listOf(relocation(root.resolve("home/cache"), root.resolve("local/cache"))))
        }
        private fun relocation(source: Path, target: Path): Relocation { return Relocation(source, target) }
    }
}
