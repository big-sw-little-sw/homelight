package io.github.bigswlittlesw.homelight.tui

import dev.tamboui.tui.event.KeyCode
import io.github.bigswlittlesw.homelight.application.ApplyModel
import io.github.bigswlittlesw.homelight.application.HomeLightSession
import io.github.bigswlittlesw.homelight.config.ConfigurationLoader
import io.github.bigswlittlesw.homelight.config.Relocation
import io.github.bigswlittlesw.homelight.config.WhenOnlyTargetExists
import io.github.bigswlittlesw.homelight.config.WhenSourceAndTargetDirectoriesExist
import io.github.bigswlittlesw.homelight.discovery.CandidateDiscovery
import io.github.bigswlittlesw.homelight.discovery.CandidateObservation
import io.github.bigswlittlesw.homelight.discovery.SetupDiscoveryFixture
import io.github.bigswlittlesw.homelight.pollUntil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTimeout
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import java.util.regex.Pattern

/** Browse inside Configuration: suggestions join the draft by source path, and discovery never edits the draft. */
class BrowseTest {
    @TempDir lateinit var temporary: Path

    @Test fun browseAddEditRefreshRemovalAndSavePreserveChoices() {
        val root = fixture()
        SetupDiscoveryFixture().use { workers ->
            val ui = ui(root, workers)
            locations(ui, root, root.resolve("shared.json"))
            key(ui, 'b'); await(workers, ui)
            val list = render(ui)
            assertTrue(list.contains("Maven (1)"), list)
            assertTrue(list.contains("mixed advice"), list)
            assertTrue(list.contains("1 usually not needed, hidden"), list)
            choose(ui, "team-cache"); enter(ui)
            key(ui, 'a')
            assertTrue(render(ui).contains("e: Edit draft row"))
            key(ui, 'e'); down(ui); clear(ui); type(ui, root.resolve("local/custom-target").toString())
            down(ui); right(ui) // Keep target, delete source.
            escape(ui); key(ui, 'b')
            Files.copy(Path.of("docs/research/session-b-fixtures/nested/shared-refreshed.json"), root.resolve("shared.json"), StandardCopyOption.REPLACE_EXISTING)
            key(ui, 'r'); await(workers, ui)
            // Refresh while inspecting does not leave details or erase the row; the dropped list entry is not recalled.
            val details = all(ui)
            assertTrue(details.contains("No current catalog attribution."), details)
            assertTrue(details.contains("In draft"), details)
            key(ui, 'e')
            assertTrue(all(ui).contains("‹ Keep target, delete source ›"))
            escape(ui); key(ui, 's')
            assertTrue(render(ui).contains("[1: Workspace]"))
            val saved = ConfigurationLoader().load(root.resolve("config.json"))
            assertEquals(1, saved.relocations.size)
            assertEquals(root.resolve("local/custom-target"), saved.relocations.first().targetPath)
            assertEquals(WhenSourceAndTargetDirectoriesExist.ADOPT, saved.relocations.first().whenSourceAndTargetDirectoriesExist)
            assertEquals(root.resolve("shared.json"), saved.sharedList)
            assertEquals("unchanged", Files.readString(root.resolve("home/team-cache/payload")))
            assertFalse(Files.exists(root.resolve("local/custom-target")))
            assertInstanceOf(ApplyModel.Idle::class.java, ui.app.session.applyModel())
            assertNull(workers.workers.first().snapshot().request)
        }
    }

    @Test fun ctrlAndAltChordsDoNotActAsLetterCommands() {
        val root = fixture()
        SetupDiscoveryFixture().use { workers ->
            val ui = ui(root, workers); locations(ui, root, root.resolve("shared.json"))
            key(ui, 'a'); type(ui, "manual"); escape(ui)
            ctrl(ui, 'd'); ctrl(ui, 'u'); alt(ui, 'd'); alt(ui, 'a')
            val table = render(ui)
            assertEquals(CONFIG_LIST, ui.focused())
            // Three storage settings and the relocation: nothing was removed or added.
            assertTrue(table.contains("4 unsaved changes"), table)
            enter(ui); down(ui); down(ui)
            ctrl(ui, 'd'); alt(ui, 'd')
            assertEquals("config-both-exist", ui.focused(), "Ctrl+D and Alt+D on a rule do not remove the row")
            escape(ui); key(ui, 'b'); await(workers, ui)
            ctrl(ui, 'u'); alt(ui, 'u'); ctrl(ui, 'd')
            val list = render(ui)
            assertTrue(list.contains("1 usually not needed, hidden"), list)
            assertTrue(list.contains("1 in draft"), list)
            key(ui, 'u')
            assertTrue(render(ui).contains("1 usually not needed, shown"))
            escape(ui)
            assertTrue(render(ui).contains("home/manual┃"), render(ui))
            ui.app.closeEditor()
        }
    }

    @Test fun groupExpansionNeverSelectsAndAdviceCollapseKeepsAddedRowsVisible() {
        val root = fixture()
        SetupDiscoveryFixture().use { workers ->
            val ui = ui(root, workers); locations(ui, root, root.resolve("shared.json"))
            key(ui, 'b'); await(workers, ui)
            enter(ui)
            assertFalse(render(ui).contains("[ ] .m2"))
            enter(ui)
            key(ui, 'u')
            choose(ui, ".cache/example"); enter(ui); key(ui, 'a'); escape(ui)
            key(ui, 'u')
            assertTrue(all(ui).contains("[x] .cache/example"))
            // No action on a heading may create rows.
            escape(ui); key(ui, 's')
            val saved = ConfigurationLoader().load(root.resolve("config.json"))
            assertEquals(listOf(root.resolve("home/.cache/example")), saved.relocations.map(Relocation::sourcePath))
        }
    }

    @Test fun checklistAddsInPlaceWithoutReorderingAndKeepsRejectedChoices() {
        val root = fixture()
        SetupDiscoveryFixture().use { workers ->
            val ui = ui(root, workers); locations(ui, root, root.resolve("shared.json"))
            key(ui, 'b'); await(workers, ui)
            key(ui, ' '); key(ui, 'a')
            assertTrue(render(ui).contains("0 in draft"), "App headings cannot add children")
            choose(ui, ".m2")
            val before = render(ui)
            key(ui, ' ')
            val added = render(ui)
            assertTrue(added.contains("Browse candidates") && added.contains("❯   [x] .m2"), added)
            assertFalse(added.contains("Candidate details") || added.contains("Space/a: Add"), added)
            assertEquals(before.indexOf("Maven (1)"), added.indexOf("Maven (1)"))
            key(ui, ' '); key(ui, 'a')
            assertTrue(render(ui).contains("1 in draft"))
            choose(ui, ".local/share/uv"); key(ui, 'a')
            choose(ui, ".local/share/uv/tools"); key(ui, ' ')
            val rejected = render(ui)
            assertTrue(rejected.contains("Browse candidates") && rejected.contains("prior choices are unchanged"), rejected)
            assertTrue(rejected.contains("2 in draft"))
            enter(ui)
            assertTrue(all(ui).contains("paths overlap"))
            escape(ui)
            choose(ui, "absent-cache"); key(ui, ' '); key(ui, 'a')
            assertTrue(render(ui).contains("3 in draft"))
            choose(ui, ".m2"); key(ui, 'e')
            assertEquals("config-source", ui.focused())
            assertTrue(render(ui).contains("❯ Source"), render(ui))
            escape(ui); key(ui, 's')
            assertEquals(3, ConfigurationLoader().load(root.resolve("config.json")).relocations.size)
        }
    }

    @Test fun missingCandidateCanBeAddedEditedRefreshedAndSavedBeforeTheAppCreatesIt() {
        val root = fixture()
        SetupDiscoveryFixture().use { workers ->
            val ui = ui(root, workers); locations(ui, root, root.resolve("shared.json"))
            key(ui, 'b'); await(workers, ui); choose(ui, "absent-cache")
            assertTrue(render(ui).contains("Space/a: Add"))
            assertTrue(render(ui).contains("[ ] absent-cache"))
            // The row says why it is unusual (tui-design §8).
            assertTrue(render(ui).contains("absent-cache                    not created yet"), render(ui))
            enter(ui)
            val details = all(ui)
            assertTrue(details.contains("State: not created yet"), details)
            assertTrue(details.contains("Not found under the source root"), details)
            escape(ui); key(ui, ' ')
            assertTrue(render(ui).contains("❯   [x] absent-cache"))
            key(ui, 'e'); down(ui); clear(ui); type(ui, root.resolve("local/future-cache").toString())
            down(ui); down(ui); right(ui)
            escape(ui); key(ui, 'b'); key(ui, 'r'); await(workers, ui)
            assertTrue(render(ui).contains("❯   [x] absent-cache"))
            escape(ui); key(ui, 's')
            assertTrue(render(ui).contains("[1: Workspace]"))
            val row = ConfigurationLoader().load(root.resolve("config.json")).relocations.first()
            assertEquals(root.resolve("home/absent-cache"), row.sourcePath)
            assertEquals(root.resolve("local/future-cache"), row.targetPath)
            assertEquals(WhenOnlyTargetExists.ADOPT_TARGET, row.whenOnlyTargetExists)
            assertFalse(Files.exists(row.sourcePath))
            assertFalse(Files.exists(root.resolve("local")))
            assertInstanceOf(ApplyModel.Idle::class.java, ui.app.session.applyModel())
        }
    }

    @Test fun overlapRejectionAndManualMatchesDoNotLoseEarlierRows() {
        val root = fixture()
        SetupDiscoveryFixture().use { workers ->
            val ui = ui(root, workers); locations(ui, root, root.resolve("shared.json"))
            key(ui, 'a'); type(ui, ".m2"); down(ui); type(ui, root.resolve("local/manual-target").toString()); escape(ui)
            key(ui, 'b'); await(workers, ui); choose(ui, ".m2"); enter(ui)
            assertTrue(render(ui).contains("e: Edit draft row"))
            assertFalse(render(ui).contains("a: Add to draft"))
            key(ui, 'a'); escape(ui)
            choose(ui, ".local/share/uv"); enter(ui); key(ui, 'a'); escape(ui)
            choose(ui, ".local/share/uv/tools"); enter(ui); key(ui, 'a')
            val text = all(ui).replace(Regex("\\s"), "")
            assertTrue(text.contains("Notadded."), text)
            assertTrue(text.contains("Priorchoicesareunchanged"), text)
            assertTrue(text.contains("pathsoverlap"), text)
            escape(ui); escape(ui); key(ui, 's')
            val saved = ConfigurationLoader().load(root.resolve("config.json"))
            assertEquals(2, saved.relocations.size)
            assertEquals(root.resolve("local/manual-target"), saved.relocations.first().targetPath)
        }
    }

    @Test fun stalledReadAllowsManualEditingFailedAndSuccessfulSaveAndIgnoresLateCompletion() {
        val root = fixture()
        SetupDiscoveryFixture().use { workers ->
            workers.block = true
            val ui = ui(root, workers); locations(ui, root, root.resolve("shared.json")); key(ui, 'b')
            assertTrue(workers.entered.await(2, TimeUnit.SECONDS))
            assertTimeout(Duration.ofSeconds(1), Executable {
                workers.expire(); render(ui); key(ui, 'r'); key(ui, 'i')
                assertTrue(all(ui).contains("Previous read still pending"))
                escape(ui); escape(ui); key(ui, 'a'); type(ui, "manual"); escape(ui)
                Files.writeString(root.resolve("config.json"), "concurrent winner")
                key(ui, 's'); assertTrue(all(ui).contains("Not saved"))
                assertEquals("concurrent winner", Files.readString(root.resolve("config.json")))
            })
            Files.delete(root.resolve("config.json"))
            assertTimeout(Duration.ofSeconds(1), Executable { key(ui, 's') })
            assertTrue(render(ui).contains("[1: Workspace]"))
            assertNull(workers.workers.first().snapshot().request)
            workers.release.countDown()
            assertTrue(render(ui).contains("[1: Workspace]"))
            assertEquals(1, workers.reads.get())
            assertEquals(1, ConfigurationLoader().load(root.resolve("config.json")).relocations.size)
        }
    }

    @Test fun discardCancelReopenAndRootLocationEditsRejectObsoleteResults() {
        val root = fixture()
        SetupDiscoveryFixture().use { workers ->
            workers.block = true
            val ui = ui(root, workers); locations(ui, root, root.resolve("shared.json")); key(ui, 'b')
            assertTrue(workers.entered.await(2, TimeUnit.SECONDS))
            escape(ui); key(ui, 'a'); type(ui, "manual"); escape(ui)
            key(ui, 'q'); escape(ui)
            assertTrue(render(ui).contains("manual"))
            ui.press(KeyCode.HOME); enter(ui); clear(ui); type(ui, root.resolve("other-home").toString())
            down(ui); down(ui); clear(ui); escape(ui)
            key(ui, 'b')
            workers.release.countDown(); await(workers, ui)
            assertFalse(all(ui).contains("team-cache"))
            escape(ui); key(ui, 'q'); key(ui, 'y')
            assertNull(workers.workers.first().snapshot().request)
            key(ui, 'i')
            val fresh = render(ui)
            assertTrue(fresh.contains("Storage locations") && fresh.contains("no unsaved changes"), fresh)
            assertFalse(fresh.contains("other-home") || fresh.contains("shared.json") || fresh.contains("manual"), fresh)
            assertFalse(Files.exists(root.resolve("config.json")))
            ui.app.closeEditor()
        }
    }

    @Test fun malformedAndMissingSourcesLeaveBundledAndManualSaveAvailable() {
        for (missing in listOf(false, true)) {
            val root = fixture()
            if (missing) Files.delete(root.resolve("shared.json"))
            else Files.writeString(root.resolve("shared.json"), "{\"apps\": [")
            SetupDiscoveryFixture().use { workers ->
                val ui = ui(root, workers); locations(ui, root, root.resolve("shared.json")); key(ui, 'b'); await(workers, ui)
                assertTrue(render(ui).contains("Bundled: current · Shared: unavailable"))
                key(ui, 'i'); assertTrue(all(ui).contains(if (missing) "NoSuchFileException" else "line"))
                escape(ui); escape(ui); key(ui, 'a'); type(ui, "manual"); escape(ui); key(ui, 's')
                assertTrue(render(ui).contains("[1: Workspace]"))
            }
        }
    }

    /** Relocations the file already has are ordinary draft rows: marked `[x]` and edited like any other. */
    @Test fun relocationsFromTheFileAreInTheDraftAndTextIsEscaped() {
        val root = fixture()
        val config = Files.writeString(
            root.resolve("config.json"),
            """
            {"homelight": {"source-root": "${root.resolve("home")}", "target-root": "${root.resolve("local")}",
              "relocations": [{"source-path": "${root.resolve("home/.m2")}"}]}}
            """.trimIndent(),
        )
        SetupDiscoveryFixture().use { workers ->
            val ui = HeadlessTui(HomeLightSession(config), discoveryFactory = workers::get)
            key(ui, 'e'); key(ui, 'b'); await(workers, ui)
            choose(ui, ".m2")
            assertTrue(render(ui).contains("❯   [x] .m2"), render(ui))
            assertTrue(render(ui).contains("e: Edit"), render(ui))
            key(ui, 'e')
            assertEquals("config-source", ui.focused())
            assertTrue(render(ui).contains("no unsaved changes"), render(ui))
            ui.app.closeEditor()
        }
        assertEquals("hello\\u001b[2J\\u000aworld", literal("hello\u001b[2J\nworld"))
    }

    @Test fun arrivingResultsDoNotStealPathFocusOrEraseActiveRowText() {
        val root = fixture()
        SetupDiscoveryFixture().use { workers ->
            workers.block = true
            val ui = ui(root, workers); locations(ui, root, root.resolve("shared.json")); key(ui, 'b')
            assertTrue(workers.entered.await(2, TimeUnit.SECONDS))
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!render(ui).contains("[ ] .m2") && System.nanoTime() < until) LockSupport.parkNanos(1_000_000)
            choose(ui, ".m2"); enter(ui); key(ui, 'a'); key(ui, 'e')
            down(ui); clear(ui); type(ui, "unfinished-target")
            workers.release.countDown(); await(workers, ui)
            assertEquals("config-target", ui.focused())
            assertTrue(render(ui).contains("  unfinished-target"), render(ui))
            type(ui, "-continued"); escape(ui); key(ui, 'b')
            assertTrue(render(ui).contains("Candidate details"))
            escape(ui)
            assertTrue(render(ui).lines().any { line -> line.contains("❯   [x] .m2") })
            escape(ui)
            assertTrue(render(ui).contains("  unfinished-target-continued"), render(ui))
            ui.app.closeEditor()
        }
    }

    @Test fun lateCompletionAfterConfirmedDiscardCannotResurrectConfiguration() {
        val root = fixture()
        SetupDiscoveryFixture().use { workers ->
            workers.block = true
            val ui = ui(root, workers); locations(ui, root, root.resolve("shared.json")); key(ui, 'b')
            assertTrue(workers.entered.await(2, TimeUnit.SECONDS))
            key(ui, 'q'); key(ui, 'y')
            workers.release.countDown()
            repeat(20) {
                assertFalse(render(ui).contains("[Configuration]"))
                LockSupport.parkNanos(1_000_000)
            }
            assertNull(workers.workers.first().snapshot().request)
            key(ui, 'i')
            assertTrue(render(ui).contains("no unsaved changes"))
            assertFalse(Files.exists(root.resolve("config.json")))
            ui.app.closeEditor()
        }
    }

    /** Sources stay as written when the roots change; a blank Target follows the new roots. */
    @Test fun changingTheRootsKeepsSourcesAndRederivesBlankTargets() {
        val root = fixture()
        SetupDiscoveryFixture().use { workers ->
            val ui = ui(root, workers); locations(ui, root, root.resolve("shared.json"))
            key(ui, 'a'); type(ui, "manual"); down(ui); down(ui); right(ui); escape(ui)
            ui.press(KeyCode.HOME); enter(ui); clear(ui); type(ui, root.resolve("other-home").toString())
            down(ui); clear(ui); type(ui, root.resolve("other-target").toString())
            down(ui); clear(ui); escape(ui); key(ui, 's')
            val saved = render(ui)
            assertTrue(saved.contains("Not saved"), "the source is now outside the source root: $saved")
            down(ui); enter(ui); down(ui); type(ui, root.resolve("other-target/chosen").toString()); escape(ui); key(ui, 's')
            val configuration = ConfigurationLoader().load(root.resolve("config.json"))
            val row = configuration.relocations.first()
            assertEquals(root.resolve("home/manual"), row.sourcePath)
            assertEquals(root.resolve("other-target/chosen"), row.targetPath)
            assertEquals(WhenSourceAndTargetDirectoriesExist.ADOPT, row.whenSourceAndTargetDirectoriesExist)
            assertNull(configuration.sharedList)
        }
    }

    private fun fixture(): Path {
        val root = Files.createTempDirectory(temporary, "fixture-").toRealPath()
        for (relative in listOf(".m2", ".cache/uv", ".cache/example", ".local/share/uv/tools", "team-cache", "datasets")) Files.createDirectories(root.resolve("home").resolve(relative))
        Files.writeString(root.resolve("home/team-cache/payload"), "unchanged")
        Files.copy(Path.of("docs/research/session-b-fixtures/nested/shared.json"), root.resolve("shared.json"))
        return root
    }

    private companion object {
        fun ui(root: Path, workers: SetupDiscoveryFixture): HeadlessTui {
            val ui = HeadlessTui(HomeLightSession(root.resolve("config.json")), discoveryFactory = workers::get); key(ui, 'i'); return ui
        }

        /** From the Target root field a new file opens on: both roots and the suggestion list, then back to the list. */
        fun locations(ui: HeadlessTui, root: Path, shared: Path) {
            type(ui, root.resolve("local").toString())
            ui.press(KeyCode.UP); clear(ui); type(ui, root.resolve("home").toString())
            down(ui); down(ui); type(ui, shared.toString()); escape(ui)
        }
        fun await(workers: SetupDiscoveryFixture, ui: HeadlessTui) {
            pollUntil("Discovery did not settle") {
                val result = workers.workers.last().snapshot()
                result.sources.none { s -> s.status == CandidateDiscovery.SourceStatus.PENDING }
                    && result.candidates.none { c -> c.observation.kind == CandidateObservation.Kind.PENDING }
            }
            // Render exactly once after discovery settles: the ui accepts snapshots only when rendering, and the
            // next key acts on what it last rendered.
            render(ui)
        }
        fun choose(ui: HeadlessTui, relative: String) {
            ui.press(KeyCode.HOME)
            repeat(100) {
                if (render(ui).lines().any { line -> line.matches(Regex(".*❯   (\\[.\\]| − ) " + Pattern.quote(relative) + "(?: +.*|│.*)")) }) return
                down(ui)
            }
            fail<Unit>("Could not focus " + relative + "\n" + render(ui))
        }
        fun all(ui: HeadlessTui): String {
            val screens = linkedSetOf<String>()
            screens.add(render(ui))
            repeat(80) { key(ui, ']'); screens.add(render(ui)) }
            repeat(80) { key(ui, '[') }
            // Kotlin's lines() adds a trailing empty line, which the border filter drops.
            return screens.joinToString("\n") + screens.flatMap { lightBorders(it).lines() }
                .filter { line -> line.startsWith("│") }.map { line -> line.substring(1).replace("│", "").replace("█", "").trimEnd { Character.isWhitespace(it) } }
                .joinToString("")
        }
        fun render(ui: HeadlessTui): String {
            try { return ui.screen(200, 40) }
            catch (error: Exception) { throw AssertionError(error) }
        }
        fun key(ui: HeadlessTui, key: Char) { ui.press(key) }
        fun type(ui: HeadlessTui, value: String) { value.forEach { c -> key(ui, c) } }
        fun clear(ui: HeadlessTui) { key(ui, '\u0015') }
        fun ctrl(ui: HeadlessTui, key: Char) { ui.ctrl(key) }
        fun alt(ui: HeadlessTui, key: Char) { ui.alt(key) }
        fun down(ui: HeadlessTui) { ui.press(KeyCode.DOWN) }
        fun right(ui: HeadlessTui) { ui.press(KeyCode.RIGHT) }
        fun enter(ui: HeadlessTui) { ui.press(KeyCode.ENTER) }
        fun escape(ui: HeadlessTui) { ui.press(KeyCode.ESCAPE) }
    }
}
