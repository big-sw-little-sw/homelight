package io.github.bigswlittlesw.homelight.config

import dev.tamboui.tui.event.KeyCode
import io.github.bigswlittlesw.homelight.application.ConfigurationEvaluation
import io.github.bigswlittlesw.homelight.application.HomeLightSession
import io.github.bigswlittlesw.homelight.tui.HeadlessTui
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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
                assertInstanceOf(ConfigurationEvaluation.Loaded::class.java,
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
        assertThrows<IllegalArgumentException> { publisher.saveNew(path,
                ConfigurationDraft.of(root.resolve("local"), listOf(first, overlapping))) }
        assertThrows<IllegalArgumentException> { publisher.saveNew(path,
                ConfigurationDraft.of(root.resolve("local"), listOf(first, duplicateTarget))) }
        assertFalse(Files.exists(path))
    }

    @Test fun failedWriteAndExistingMalformedFileKeepTheDraftAndFile(@TempDir root: Path) {
        val draft = draft(root)
        val blockedParent = Files.writeString(root.resolve("not-a-directory"), "occupied")
        assertThrows<ConfigurationException> {
            ConfigurationPublisher().saveNew(blockedParent.resolve("config.json"), draft) }

        val path = root.resolve("config.json")
        Files.writeString(path, "{\"homelight\": [")
        assertThrows<ConfigurationException> { ConfigurationPublisher().saveNew(path, draft) }
        assertEquals("{\"homelight\": [", Files.readString(path))
        assertThrows<RuntimeException> { ConfigurationLoader().load(path) }
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

    @Test fun replacesAFileUnchangedSinceLoadAndLeavesNoTemporaryFile(@TempDir root: Path) {
        val path = root.resolve("config.json")
        val loaded = existing(path, root)

        ConfigurationPublisher().replace(path, draft(root), loaded)

        assertEquals(root.resolve("home/cache").toAbsolutePath(),
                ConfigurationLoader().load(path).relocations.single().sourcePath)
        assertEquals(listOf(path), Files.list(root).use { it.toList() })
    }

    @Test fun refusesAFileChangedOrDeletedSinceLoad(@TempDir root: Path) {
        val path = root.resolve("config.json")
        val loaded = existing(path, root)
        val edited = Files.readString(path).replace("other", "edited")
        Files.writeString(path, edited)

        val changed = assertThrows<ConfigurationException> { ConfigurationPublisher().replace(path, draft(root), loaded) }
        assertTrue(changed.message.orEmpty().contains("changed since it was loaded"))
        assertEquals(edited, Files.readString(path))

        Files.delete(path)
        assertThrows<ConfigurationException> { ConfigurationPublisher().replace(path, draft(root), loaded) }
        assertFalse(Files.exists(path))
    }

    @Test fun replacesASymlinkedConfigurationAtItsTarget(@TempDir root: Path) {
        val real = root.resolve("dotfiles/homelight.json")
        Files.createDirectories(real.parent)
        val loaded = existing(real, root)
        val path = Files.createSymbolicLink(root.resolve("config.json"), real)

        ConfigurationPublisher().replace(path, draft(root), loaded)

        assertTrue(Files.isSymbolicLink(path))
        assertEquals(root.resolve("home/cache").toAbsolutePath(),
                ConfigurationLoader().load(real).relocations.single().sourcePath)
    }

    @Test fun concurrentReplacesLeaveOneCompleteConfiguration(@TempDir root: Path) {
        val path = root.resolve("config.json")
        val loaded = existing(path, root)
        val publisher = ConfigurationPublisher()
        Executors.newFixedThreadPool(2).use { pool ->
            pool.invokeAll(listOf<Callable<Unit>>(
                    Callable { replaceOrRefuse(publisher, path, loaded, root) },
                    Callable { replaceOrRefuse(publisher, path, loaded, root) })).forEach { it.get() }
        }
        assertEquals(root.resolve("home/cache").toAbsolutePath(),
                ConfigurationLoader().load(path).relocations.single().sourcePath)
    }

    @Test fun cancellingManualSetupWritesNothing(@TempDir root: Path) {
        val path = root.resolve("config.json")
        val ui = HeadlessTui(HomeLightSession(path))
        ui.press('i')
        ui.press(KeyCode.ESCAPE)
        assertFalse(Files.exists(path))
        assertFalse(ui.app.session.requestApply())
    }

    companion object {
        private fun save(publisher: ConfigurationPublisher, path: Path, draft: ConfigurationDraft): Boolean {
            try { publisher.saveNew(path, draft); return true }
            catch (expected: ConfigurationException) { return false }
        }
        /** Writes a configuration that differs from [draft] and returns its bytes, as a load would read them. */
        private fun existing(path: Path, root: Path): ByteArray {
            ConfigurationPublisher().saveNew(path, ConfigurationDraft.of(root.resolve("local"),
                    listOf(relocation(root.resolve("home/other"), root.resolve("local/other")))))
            return Files.readAllBytes(path)
        }
        // Both may pass the comparison before either moves (see replace), so either outcome is allowed per writer.
        private fun replaceOrRefuse(publisher: ConfigurationPublisher, path: Path, loaded: ByteArray, root: Path) {
            try { publisher.replace(path, draft(root), loaded) } catch (expected: ConfigurationException) {}
        }
        private fun draft(root: Path): ConfigurationDraft {
            return ConfigurationDraft.of(root.resolve("local"), listOf(relocation(root.resolve("home/cache"), root.resolve("local/cache"))))
        }
        private fun relocation(source: Path, target: Path): Relocation { return Relocation(source, target) }
    }
}
