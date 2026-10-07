package io.github.bigswlittlesw.homelight.tui

import dev.tamboui.tui.event.KeyCode
import dev.tamboui.tui.event.MouseButton
import dev.tamboui.tui.event.MouseEvent
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
            assertTrue(list.contains("Build tools") && !list.contains("Maven"), list)
            assertTrue(list.contains("1 usually not needed, hidden"), list)
            choose(ui, "team-cache"); enter(ui)
            key(ui, ' ')
            assertTrue(render(ui).contains("e: Edit"))
            key(ui, 'e'); down(ui); clear(ui); type(ui, root.resolve("local/custom-target").toString())
            down(ui); right(ui) // Keep target, delete source.
            escape(ui); key(ui, 'b')
            Files.copy(Path.of("docs/research/session-b-fixtures/nested/shared-refreshed.json"), root.resolve("shared.json"), StandardCopyOption.REPLACE_EXISTING)
            key(ui, 'r'); await(workers, ui)
            // Refresh while inspecting does not leave details or erase the row; the dropped list entry is not recalled.
            val details = all(ui)
            assertTrue(details.contains("No list suggests it."), details)
            assertTrue(details.contains("In the configuration"), details)
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
            assertTrue(list.contains("● manual"), list)
            key(ui, 'u')
            assertTrue(render(ui).contains("1 usually not needed, shown"))
            escape(ui)
            // Back on the list with the row still there; a long path is shortened in the middle, so match its end.
            assertEquals(CONFIG_LIST, ui.focused())
            assertTrue(Regex("home/manual *┃").containsMatchIn(render(ui)), render(ui))
            ui.app.closeEditor()
        }
    }

    @Test fun enterOnAHeadingDoesNothingAndHidingKeepsAddedRowsVisible() {
        val root = fixture()
        SetupDiscoveryFixture().use { workers ->
            val ui = ui(root, workers); locations(ui, root, root.resolve("shared.json"))
            key(ui, 'b'); await(workers, ui)
            val before = render(ui)
            enter(ui)
            assertEquals(before, render(ui))
            key(ui, 'u')
            choose(ui, ".cache/example"); enter(ui); key(ui, ' '); escape(ui)
            key(ui, 'u')
            assertTrue(all(ui).contains("● .cache/example"))
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
            choose(ui, ".m2")
            val before = render(ui)
            key(ui, ' ')
            val added = render(ui)
            assertTrue(added.contains("┏Browse") && selected(added, "● .m2"), added)
            assertFalse(added.contains("┏Details") || added.contains("Space: Add"), added)
            assertTrue(added.contains("Space: Remove"), added)
            assertEquals(before.indexOf("Build tools"), added.indexOf("Build tools"))
            // Space toggles: it takes the row out again, then puts it back.
            key(ui, ' ')
            assertTrue(selected(render(ui), "○ .m2"), render(ui))
            assertEquals(0, added(ui))
            key(ui, ' ')
            assertEquals(1, added(ui))
            choose(ui, ".local/share/uv"); key(ui, ' ')
            choose(ui, ".local/share/uv/tools"); key(ui, ' ')
            val rejected = render(ui)
            assertTrue(rejected.contains("┏Browse") && rejected.contains("Prior choices are unchanged"), rejected)
            assertEquals(2, added(ui))
            enter(ui)
            assertTrue(all(ui).contains("paths overlap"))
            escape(ui)
            choose(ui, "absent-cache"); key(ui, ' ')
            assertEquals(3, added(ui))
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
            assertTrue(render(ui).contains("Space: Add"))
            assertTrue(render(ui).contains("○ absent-cache"))
            // The row says why it is unusual (tui-design §8).
            assertTrue(render(ui).contains("absent-cache                  not created yet"), render(ui))
            enter(ui)
            val details = all(ui)
            assertTrue(details.contains("State: not created yet"), details)
            assertTrue(details.contains("Not found under the source root"), details)
            escape(ui); key(ui, ' ')
            assertTrue(selected(render(ui), "● absent-cache"), render(ui))
            key(ui, 'e'); down(ui); clear(ui); type(ui, root.resolve("local/future-cache").toString())
            down(ui); down(ui); right(ui)
            escape(ui); key(ui, 'b'); key(ui, 'r'); await(workers, ui)
            assertTrue(selected(render(ui), "● absent-cache"), render(ui))
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
            assertTrue(render(ui).contains("e: Edit"))
            assertFalse(render(ui).contains("Space: Add"))
            escape(ui)
            choose(ui, ".local/share/uv"); enter(ui); key(ui, ' '); escape(ui)
            choose(ui, ".local/share/uv/tools"); enter(ui); key(ui, ' ')
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
                val lists = render(ui)
                assertTrue(lists.contains("Built-in list · 6 suggestions"), lists)
                assertTrue(
                    lists.contains("Your list · ${root.resolve("shared.json")} · not used: " + if (missing) "file not found" else "1 error in the file"),
                    lists,
                )
                key(ui, 'i'); assertTrue(all(ui).contains(if (missing) "NoSuchFileException" else "line"))
                escape(ui); escape(ui); key(ui, 'a'); type(ui, "manual"); escape(ui); key(ui, 's')
                assertTrue(render(ui).contains("[1: Workspace]"))
            }
        }
    }

    /**
     * When both lists name a directory, your list's group and advice show on the row, and Details shows the built-in
     * advice too. A directory is hidden only when every list that names it marks it usually not needed.
     */
    @Test fun yourListWinsAndHidingNeedsEveryList() {
        val root = fixture()
        SetupDiscoveryFixture().use { workers ->
            val ui = ui(root, workers); locations(ui, root, root.resolve("shared.json"))
            key(ui, 'b'); await(workers, ui)
            val list = render(ui)
            // The built-in list calls .m2 Maven and Consider; your list calls it Build tools and usually not needed.
            assertTrue(list.contains("Build tools") && !list.contains("Maven"), list)
            assertTrue(list.contains(".m2                           usually not needed"), list)
            // Only the built-in list names .cache/example, as usually not needed, so it alone is hidden.
            assertTrue(list.contains("1 usually not needed, hidden") && !list.contains(".cache/example"), list)
            choose(ui, ".m2"); enter(ui)
            val details = all(ui)
            val yours = details.indexOf("Your list · Build tools")
            val builtIn = details.indexOf("Built-in list · Maven")
            assertTrue(yours in 0..<builtIn, details)
            assertTrue(details.indexOf("Advice: Usually not needed", yours) < builtIn, details)
            assertTrue(details.indexOf("Advice: Consider", builtIn) > builtIn, details)
            ui.app.closeEditor()
        }
    }

    /** The Lists lines name each list with its count, your list's location and when its file changed. */
    @Test fun listsLinesSayWhatEachListGave() {
        val root = fixture()
        Files.setLastModifiedTime(root.resolve("shared.json"), java.nio.file.attribute.FileTime.from(java.time.Instant.parse("2026-09-28T12:00:00Z")))
        SetupDiscoveryFixture().use { workers ->
            val ui = ui(root, workers); locations(ui, root, root.resolve("shared.json"))
            key(ui, 'b'); await(workers, ui)
            val lines = render(ui).lines()
            assertEquals("Built-in list · 6 suggestions", lines[1].trim(), lines.joinToString("\n"))
            assertTrue(Regex("Your list · .*shared\\.json · 7 suggestions · file updated 2[89] Sep").matches(lines[2].trim()), lines[2])
            ui.app.closeEditor()
        }
        SetupDiscoveryFixture().use { workers ->
            val ui = ui(root, workers); locations(ui, root, root.resolve("shared.json"))
            // Clearing the field leaves only the built-in list.
            enter(ui); down(ui); down(ui); clear(ui); escape(ui)
            key(ui, 'b'); await(workers, ui)
            assertTrue(render(ui).contains(NO_LIST_OF_YOUR_OWN), render(ui))
            ui.app.closeEditor()
        }
    }

    /** Space takes a saved relocation out of the draft only: the file changes when Configuration saves. */
    @Test fun spaceRemovesFromTheDraftOnly() {
        val root = fixture()
        val config = Files.writeString(
            root.resolve("config.json"),
            """
            {"homelight": {"source-root": "${root.resolve("home")}", "target-root": "${root.resolve("local")}",
              "relocations": [{"source-path": "${root.resolve("home/.m2")}"}]}}
            """.trimIndent(),
        )
        val bytes = Files.readAllBytes(config)
        SetupDiscoveryFixture().use { workers ->
            val ui = HeadlessTui(HomeLightSession(config), discoveryFactory = workers::get)
            key(ui, 'e'); key(ui, 'b'); await(workers, ui)
            choose(ui, ".m2")
            assertTrue(render(ui).contains("Space: Remove"), render(ui))
            key(ui, ' ')
            assertTrue(selected(render(ui), "○ .m2"), render(ui))
            escape(ui)
            assertTrue(render(ui).contains("1 unsaved change"), render(ui))
            assertTrue(bytes.contentEquals(Files.readAllBytes(config)))
            key(ui, 'q'); key(ui, 'y')
            assertTrue(bytes.contentEquals(Files.readAllBytes(config)))
        }
    }

    /**
     * The list's selection follows its item: checking again and `u` keep it. When the item is hidden, the row at its
     * place is selected and stays selected when the item is listed again.
     */
    @Test fun selectionFollowsItsItemThroughCheckAgainAndU() {
        val root = fixture()
        SetupDiscoveryFixture().use { workers ->
            val ui = ui(root, workers); locations(ui, root, root.resolve("shared.json"))
            key(ui, 'b'); await(workers, ui)
            key(ui, 'u')
            choose(ui, ".cache/example")
            key(ui, 'r'); await(workers, ui)
            assertTrue(selected(render(ui), "○ .cache/example"), render(ui))
            key(ui, 'u')
            assertFalse(render(ui).contains(".cache/example"), render(ui))
            assertTrue(selected(render(ui), "○ datasets"), render(ui))
            key(ui, 'u')
            assertTrue(selected(render(ui), "○ datasets"), render(ui))
            // Adding a row above does not move the selection off its item.
            ui.press(KeyCode.UP); ui.press(KeyCode.UP); key(ui, ' '); ui.press(KeyCode.DOWN); ui.press(KeyCode.DOWN)
            assertTrue(selected(render(ui), "○ datasets"), render(ui))
            ui.app.closeEditor()
        }
    }

    /** A configured directory no list suggests stays listed, in its place, after Space takes it out, until Browse closes. */
    @Test fun aRemovedRowStaysListedUntilBrowseCloses() {
        val root = fixture()
        Files.createDirectories(root.resolve("home/manual"))
        val config = Files.writeString(
            root.resolve("config.json"),
            """
            {"homelight": {"source-root": "${root.resolve("home")}", "target-root": "${root.resolve("local")}",
              "relocations": [{"source-path": "${root.resolve("home/manual")}"}, {"source-path": "${root.resolve("home/zz-last")}"}]}}
            """.trimIndent(),
        )
        SetupDiscoveryFixture().use { workers ->
            val ui = HeadlessTui(HomeLightSession(config), discoveryFactory = workers::get)
            key(ui, 'e'); key(ui, 'b'); await(workers, ui)
            assertTrue(render(ui).contains("[Configuration › Browse]"), render(ui))
            choose(ui, "manual")
            val before = render(ui).lines().indexOfFirst { it.contains("manual") }
            key(ui, ' ')
            assertTrue(selected(render(ui), "○ manual"), render(ui))
            assertEquals(before, render(ui).lines().indexOfFirst { it.contains("manual") }, render(ui))
            key(ui, ' ')
            assertTrue(selected(render(ui), "● manual"), render(ui))
            key(ui, ' '); escape(ui)
            assertTrue(render(ui).contains("1 unsaved change"), render(ui))
            key(ui, 'b')
            assertFalse(render(ui).contains("manual"), render(ui))
            ui.app.closeEditor()
        }
    }

    /**
     * Space on an app group adds every directory shown in it that can be added, or takes them all out when all are
     * in; the heading counts how many are added. Each directory counts as one unsaved change.
     */
    @Test fun spaceOnAGroupAddsOrRemovesItsDirectories() {
        val root = fixture()
        SetupDiscoveryFixture().use { workers ->
            val ui = ui(root, workers); locations(ui, root, root.resolve("shared.json"))
            key(ui, 'b'); await(workers, ui)
            chooseGroup(ui, "Other directories")
            assertTrue(selectedGroup(render(ui), "○ Other directories", "0 of 6 added"), render(ui))
            assertTrue(render(ui).contains("Space: Add all"), render(ui))
            key(ui, ' ')
            assertTrue(selectedGroup(render(ui), "● Other directories", "6 of 6 added"), render(ui))
            assertEquals(6, added(ui))
            assertTrue(render(ui).contains("Space: Remove all"), render(ui))
            // Taking one out leaves the group partly in the configuration.
            down(ui); key(ui, ' '); ui.press(KeyCode.UP)
            assertTrue(selectedGroup(render(ui), "◐ Other directories", "5 of 6 added"), render(ui))
            key(ui, ' ')
            assertTrue(selectedGroup(render(ui), "● Other directories", "6 of 6 added"), render(ui))
            key(ui, ' ')
            assertTrue(selectedGroup(render(ui), "○ Other directories", "0 of 6 added"), render(ui))
            assertEquals(0, added(ui))
            // A new file: the three storage locations are the only changes, then each added directory counts.
            escape(ui)
            assertTrue(render(ui).contains("3 unsaved changes"), render(ui))
            key(ui, 'b'); key(ui, ' ')
            escape(ui)
            assertTrue(render(ui).contains("9 unsaved changes"), render(ui))
            ui.app.closeEditor()
        }
    }

    /** Space on a group skips rows that overlap and rows that cannot be added, and says so. */
    @Test fun spaceOnAGroupSaysWhatItSkipped() {
        val root = fixture()
        Files.writeString(root.resolve("home/not-a-directory"), "file")
        SetupDiscoveryFixture().use { workers ->
            val ui = ui(root, workers); locations(ui, root, root.resolve("shared.json"))
            key(ui, 'b'); await(workers, ui)
            chooseGroup(ui, "uv")
            key(ui, ' ')
            val uv = all(ui)
            assertTrue(uv.contains("Added 1. Skipped 1 that overlaps ${root.resolve("home/.local/share/uv")}."), uv)
            assertTrue(selected(uv, "● .local/share/uv") || uv.contains("● .local/share/uv"), uv)
            assertTrue(uv.contains("○ .local/share/uv/tools"), uv)
            chooseGroup(ui, "Other directories")
            key(ui, ' ')
            val other = render(ui)
            assertTrue(other.contains("Added 5. Skipped 1 that can't be added."), other)
            assertTrue(selectedGroup(other, "● Other directories", "5 of 5 added"), other)
            ui.app.closeEditor()
        }
    }

    /**
     * Ecosystems head their apps, in the order they first appear; Other tools (apps no list gives an ecosystem) and
     * Other directories come last. Each level is two cells further in, and every count starts in the notes column.
     */
    @Test fun ecosystemsHeadTheirAppsWithOtherToolsAndOtherDirectoriesLast() {
        val root = fixture()
        SetupDiscoveryFixture().use { workers ->
            val ui = ui(root, workers); locations(ui, root, root.resolve("shared.json"))
            key(ui, 'b'); await(workers, ui)
            val lines = render(ui).lines()
            fun row(text: String) = lines.indexOfFirst { it.contains(text) }.also { assertTrue(it >= 0, text + "\n" + lines.joinToString("\n")) }
            // The shared list calls .m2 and .cache/uv by apps of its own with no ecosystem, so they are Other tools.
            val order = listOf(
                "○ Python", "○ uv", "○ .local/share/uv", "○ .local/share/uv/tools",
                "○ Other tools", "○ Build tools", "○ .m2", "○ Python tools", "○ .cache/uv",
                "○ Other directories", "○ datasets", "○ team-cache",
            )
            val at = order.map(::row)
            assertEquals(at.sorted(), at, lines.joinToString("\n"))
            // Marks at three depths: pointer, then 0, 2 or 4 cells.
            fun indent(text: String) = lines[row(text)].indexOf(text) - 2
            assertEquals(listOf(0, 2, 4, 0, 2, 4, 0, 4), listOf("○ Python", "○ uv", "○ .local/share/uv", "○ Other tools", "○ Build tools", "○ .m2", "○ Other directories", "○ team-cache").map(::indent))
            val notes = lines[row("○ absent-cache")].indexOf("not created yet")
            for (heading in listOf("○ Python", "○ uv", "○ Other tools", "○ Other directories")) {
                assertEquals(notes, Regex("\\d+ of \\d+ added").find(lines[row(heading)])?.range?.first, heading + "\n" + lines.joinToString("\n"))
            }
            ui.app.closeEditor()
        }
    }

    /**
     * Space on an ecosystem acts on every shown directory under its apps, as on an app: it adds those that can be
     * added and says what it skipped, completes a partly added one, and takes them all out when all are in.
     */
    @Test fun spaceOnAnEcosystemAddsOrRemovesEveryDirectoryUnderIt() {
        val root = fixture()
        SetupDiscoveryFixture().use { workers ->
            val ui = ui(root, workers); locations(ui, root, root.resolve("shared.json"))
            key(ui, 'b'); await(workers, ui)
            chooseGroup(ui, "Python")
            assertTrue(selectedGroup(render(ui), "○ Python", "0 of 2 added"), render(ui))
            assertTrue(render(ui).contains("Space: Add all") && !render(ui).contains("Enter:"), render(ui))
            key(ui, ' ')
            val python = render(ui)
            assertTrue(python.contains("Added 1. Skipped 1 that overlaps ${root.resolve("home/.local/share/uv")}."), python)
            assertTrue(selectedGroup(python, "◐ Python", "1 of 2 added") && python.contains("◐ uv"), python)
            // None, then all, then some, then all again.
            chooseGroup(ui, "Other tools")
            assertTrue(selectedGroup(render(ui), "○ Other tools", "0 of 2 added"), render(ui))
            key(ui, ' ')
            val all = render(ui)
            assertTrue(selectedGroup(all, "● Other tools", "2 of 2 added") && all.contains("● Build tools") && all.contains("● Python tools"), all)
            assertTrue(all.contains("Space: Remove all"), all)
            key(ui, ' ')
            assertTrue(selectedGroup(render(ui), "○ Other tools", "0 of 2 added"), render(ui))
            choose(ui, ".m2"); key(ui, ' ')
            chooseGroup(ui, "Other tools")
            assertTrue(selectedGroup(render(ui), "◐ Other tools", "1 of 2 added"), render(ui))
            key(ui, ' ')
            assertTrue(selectedGroup(render(ui), "● Other tools", "2 of 2 added"), render(ui))
            assertEquals(3, added(ui))
            ui.app.closeEditor()
        }
    }

    /**
     * When your list gives an app an ecosystem, all the app's directories go under it, the built-in list's too; when
     * it names the app without one, the built-in list's ecosystem stays.
     */
    @Test fun yourListsEcosystemForAnAppWins() {
        val root = fixture()
        Files.writeString(
            root.resolve("shared.json"),
            """{"apps": [{"name": "Maven", "ecosystem": "Build", "directories": [{"path": "maven-extra"}]},
                         {"name": "uv", "directories": [{"path": "uv-extra"}]}]}""",
        )
        SetupDiscoveryFixture().use { workers ->
            val ui = ui(root, workers); locations(ui, root, root.resolve("shared.json"))
            key(ui, 'b'); await(workers, ui)
            val lines = render(ui).lines()
            fun row(text: String) = lines.indexOfFirst { it.contains(text) }
            assertFalse(lines.any { it.contains("JVM") }, lines.joinToString("\n"))
            val at = listOf("○ Build ", "○ Maven", "○ .m2", "○ maven-extra", "○ Python", "○ uv", "○ uv-extra").map(::row)
            assertTrue(at.none { it < 0 } && at == at.sorted(), lines.joinToString("\n"))
            ui.app.closeEditor()
        }
    }

    /**
     * A heading none of whose directories can be added reads `−` and `can't add`, an ecosystem's as an app's, and
     * Space there does nothing.
     */
    @Test fun aHeadingWithNothingToAddCannotAdd() {
        val root = fixture()
        Files.createDirectories(root.resolve("home/link-target"))
        Files.createSymbolicLink(root.resolve("home/link-cache"), root.resolve("home/link-target"))
        Files.writeString(
            root.resolve("shared.json"),
            """{"apps": [{"name": "Links", "ecosystem": "Linked", "directories": [{"path": "link-cache"}]}]}""",
        )
        SetupDiscoveryFixture().use { workers ->
            val ui = ui(root, workers); locations(ui, root, root.resolve("shared.json"))
            key(ui, 'b'); await(workers, ui)
            for (heading in listOf("Linked", "Links")) {
                chooseGroup(ui, heading)
                val before = render(ui)
                assertTrue(selectedGroup(before, "− $heading", "can't add"), before)
                assertFalse(before.contains("Space:"), before)
                key(ui, ' ')
                assertEquals(before, render(ui))
            }
            ui.app.closeEditor()
        }
    }

    /**
     * ↓ and the wheel move through all three levels a row at a time; Enter inspects only a directory, and the
     * selection stays on a heading through `u`.
     */
    @Test fun selectionAndTheWheelMoveThroughEcosystemsAppsAndDirectories() {
        val root = fixture()
        SetupDiscoveryFixture().use { workers ->
            val ui = ui(root, workers); locations(ui, root, root.resolve("shared.json"))
            key(ui, 'b'); await(workers, ui)
            ui.press(KeyCode.HOME)
            assertTrue(selectedGroup(render(ui), "○ Python", "0 of 2 added"), render(ui))
            enter(ui)
            assertTrue(selectedGroup(render(ui), "○ Python", "0 of 2 added"), "Enter on a heading does nothing")
            down(ui)
            assertTrue(selectedGroup(render(ui), "○ uv", "0 of 2 added"), render(ui))
            down(ui)
            assertTrue(selected(render(ui), "○ .local/share/uv"), render(ui))
            ui.press(KeyCode.HOME)
            val y = render(ui).lines().indexOfFirst { it.contains("○ Python") }
            ui.press(MouseEvent.scrollDown(10, y))
            assertTrue(selectedGroup(render(ui), "○ uv", "0 of 2 added"), render(ui))
            ui.press(MouseEvent.scrollDown(10, y))
            assertTrue(selected(render(ui), "○ .local/share/uv"), render(ui))
            ui.press(MouseEvent.scrollUp(10, y))
            assertTrue(selectedGroup(render(ui), "○ uv", "0 of 2 added"), render(ui))
            ui.press(MouseEvent.scrollUp(10, y))
            assertTrue(selectedGroup(render(ui), "○ Python", "0 of 2 added"), render(ui))
            chooseGroup(ui, "Other tools")
            key(ui, 'u')
            assertTrue(selectedGroup(render(ui), "○ Other tools", "0 of 3 added") && render(ui).contains("○ Example IDE"), render(ui))
            ui.app.closeEditor()
        }
    }

    /**
     * At 80x24 and 120x30 the three levels keep their indents, every line fits, and a heading's count starts in the
     * notes column.
     */
    @Test fun ecosystemsFitAtBothSizes() {
        val root = fixture()
        SetupDiscoveryFixture().use { workers ->
            val ui = ui(root, workers); locations(ui, root, root.resolve("shared.json"))
            key(ui, 'b'); await(workers, ui)
            for ((width, height) in listOf(80 to 24, 120 to 30)) {
                val screen = ui.screen(width, height)
                val lines = screen.lines()
                assertTrue(lines.all { it.length <= width }, screen)
                val python = lines.first { it.contains("○ Python") }
                val uv = lines.first { it.contains("○ uv ") }
                val directory = lines.first { it.contains("○ .local/share/uv ") }
                assertEquals(listOf(2, 4, 6), listOf(python.indexOf('○'), uv.indexOf('○'), directory.indexOf('○')), screen)
                assertEquals(python.indexOf("0 of 2 added"), uv.indexOf("0 of 2 added"), screen)
                assertEquals(38, python.indexOf("0 of 2 added"), screen)
            }
            ui.app.closeEditor()
        }
    }

    /** As in Review, the wheel over the list moves its selection a row, and a click does nothing. */
    @Test fun theWheelMovesTheSelectionAndClicksDoNothing() {
        val root = fixture()
        SetupDiscoveryFixture().use { workers ->
            val ui = ui(root, workers); locations(ui, root, root.resolve("shared.json"))
            key(ui, 'b'); await(workers, ui)
            choose(ui, ".m2")
            val y = render(ui).lines().indexOfFirst { it.contains("○ .m2") }
            ui.press(MouseEvent.scrollDown(10, y))
            assertTrue(render(ui).lines()[y + 1].contains("❯"), render(ui))
            ui.press(MouseEvent.scrollUp(10, y))
            assertTrue(selected(render(ui), "○ .m2"), render(ui))
            val before = render(ui)
            for (event in listOf(MouseEvent.press(MouseButton.LEFT, 10, y + 3), MouseEvent.release(MouseButton.LEFT, 10, y + 3))) ui.press(event)
            assertEquals(before, render(ui))
            assertEquals(CONFIG_BROWSE, ui.focused())
            ui.app.closeEditor()
        }
    }

    /**
     * At 80 columns a row keeps its marker, its path and its whole note beside the scrollbar, and a heading's count
     * starts in the rows' notes column.
     */
    @Test fun rowNotesFitAtEightyColumns() {
        val root = fixture()
        SetupDiscoveryFixture().use { workers ->
            val ui = ui(root, workers); locations(ui, root, root.resolve("shared.json"))
            key(ui, 'b'); await(workers, ui)
            ui.press(KeyCode.END)
            val screen = ui.screen(80, 24)
            assertTrue(screen.contains("○ linked-parent/cache           not created yet"), screen)
            val lines = screen.lines()
            val note = lines.first { it.contains("linked-parent/cache") }.indexOf("not created yet")
            assertEquals(note, lines.first { it.contains("Other directories") }.indexOf("0 of 6 added"), screen)
            // The longest single note fits before the scrollbar, in the pane's last column (78) at 80 columns.
            assertTrue(note + "can't read: its real location is unclear".length <= 78, "notes start at $note")
            assertTrue(screen.lines().all { it.length <= 80 }, screen)
            ui.app.closeEditor()
        }
    }

    /** Relocations the file already has are ordinary draft rows: marked `●` and edited like any other. */
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
            assertTrue(selected(render(ui), "● .m2"), render(ui))
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
            while (!render(ui).contains("○ .m2") && System.nanoTime() < until) LockSupport.parkNanos(1_000_000)
            choose(ui, ".m2"); enter(ui); key(ui, ' '); key(ui, 'e')
            down(ui); clear(ui); type(ui, "unfinished-target")
            workers.release.countDown(); await(workers, ui)
            assertEquals("config-target", ui.focused())
            assertTrue(render(ui).contains("  unfinished-target"), render(ui))
            type(ui, "-continued"); escape(ui); key(ui, 'b')
            assertTrue(render(ui).contains("┏Details"))
            escape(ui)
            assertTrue(selected(render(ui), "● .m2"), render(ui))
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
                if (render(ui).lines().any { line -> line.matches(Regex(".*❯    [●○−] " + Pattern.quote(relative) + "(?: +.*|┃.*)")) }) return
                down(ui)
            }
            fail<Unit>("Could not focus " + relative + "\n" + render(ui))
        }
        fun chooseGroup(ui: HeadlessTui, name: String) {
            ui.press(KeyCode.HOME)
            repeat(100) {
                if (render(ui).lines().any { line -> line.matches(Regex(".*❯ *[●◐○−] " + Pattern.quote(name) + " +(\\d+ of \\d+ added|can't add).*")) }) return
                down(ui)
            }
            fail<Unit>("Could not focus " + name + "\n" + render(ui))
        }
        /** Whether the selected row of `screen` is the heading `heading` (mark and name) with `count`, such as `2 of 5 added`. */
        fun selectedGroup(screen: String, heading: String, count: String): Boolean =
            screen.lines().any { line -> line.matches(Regex(".*❯ *" + Pattern.quote(heading) + " +" + Pattern.quote(count) + ".*")) }
        /** Whether the selected row of `screen` reads `row`: its marker and path. */
        fun selected(screen: String, row: String): Boolean =
            screen.lines().any { line -> line.matches(Regex(".*❯    " + Pattern.quote(row) + "(?: +.*|┃.*)")) }
        /** How many directories are marked in the configuration; a heading's line has its count, so it is left out. */
        fun added(ui: HeadlessTui): Int = render(ui).lines().count { line ->
            Regex("^[┃│][❯ ]    ● ").containsMatchIn(line) && !Regex("\\d+ of \\d+ added").containsMatchIn(line)
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
