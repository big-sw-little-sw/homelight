package io.github.bigswlittlesw.homelight.tui

import dev.tamboui.tui.event.KeyCode
import dev.tamboui.tui.event.KeyEvent
import dev.tamboui.tui.event.KeyModifiers
import io.github.bigswlittlesw.homelight.application.ApplyModel
import io.github.bigswlittlesw.homelight.application.HomeLightSession
import io.github.bigswlittlesw.homelight.application.ConfigurationEvaluation
import io.github.bigswlittlesw.homelight.application.SetupDraft
import io.github.bigswlittlesw.homelight.config.ConfigurationLoader
import io.github.bigswlittlesw.homelight.config.Relocation
import io.github.bigswlittlesw.homelight.config.WhenAdoptingTarget
import io.github.bigswlittlesw.homelight.config.WhenOnlyTargetExists
import io.github.bigswlittlesw.homelight.config.WhenSourceAndTargetDirectoriesExist
import io.github.bigswlittlesw.homelight.discovery.CandidateDiscovery
import io.github.bigswlittlesw.homelight.discovery.CandidateObservation
import io.github.bigswlittlesw.homelight.discovery.SetupDiscoveryFixture
import io.github.bigswlittlesw.homelight.pollUntil
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction
import org.junit.jupiter.api.Assertions.*
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

class CandidateSetupTest {
    @TempDir lateinit var temporary: Path

    @Test fun discardBothCopyMatchesThePlannedDeletionsAndEmptyTargetAtBothSizes() {
        val root = fixture()
        val source = root.resolve("home/team-cache")
        val target = Files.createDirectories(root.resolve("local/team-cache"))
        Files.writeString(target.resolve("payload"), "target unchanged")
        val ui = HeadlessTui(HomeLightSession(root.resolve("config.json")))
        key(ui, 'i'); locations(ui, root, root.resolve("shared.json"))
        key(ui, 'a'); type(ui, "team-cache"); down(ui); down(ui)
        repeat(3) { key(ui, ' ') }
        for (width in listOf(80, 120, 80, 120)) {
            val height = if (width == 80) 24 else 30
            val details = ui.screen(width, height)
            assertTrue(details.contains("❯ Both exist: Delete both, start empty"), details)
            val text = details.replace(Regex("[│█\\s]"), "")
            assertTrue(text.contains("Permanentlydeletebothsourceandtargetdirectorytrees.Createanemptytargetdirectoryandlinkthesourcetoit."), details)
            assertFalse(details.contains("Discard target") || details.contains("relocate source"), details)
            escape(ui)
            val table = ui.screen(width, height)
            assertTrue(table.contains("Rules") && table.contains("Delete both, start empty"), table)
            assertFalse(table.contains("Discard target"), table)
            enter(ui); down(ui); down(ui)
        }
        escape(ui); key(ui, 's')
        val plan = assertInstanceOf(ConfigurationEvaluation.Loaded::class.java, ui.app.session.evaluation())
        assertEquals(listOf(
            ReconciliationAction.DeleteDirectory(source),
            ReconciliationAction.DeleteDirectory(target),
            ReconciliationAction.EnsureDirectory(target.parent),
            ReconciliationAction.CreateDirectory(target),
            ReconciliationAction.EnsureDirectory(source.parent),
            ReconciliationAction.CreateSymlink(source, target)),
            plan.items.first().plan.actions)
        assertEquals("unchanged", Files.readString(source.resolve("payload")))
        assertEquals("target unchanged", Files.readString(target.resolve("payload")))
        assertInstanceOf(ApplyModel.Idle::class.java, ui.app.session.applyModel())
    }

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
            key(ui, 'e'); down(ui); clear(ui); type(ui, "custom-target")
            down(ui); key(ui, ' ') // Keep target, no inferred source rule.
            escape(ui); key(ui, 'b')
            Files.copy(Path.of("docs/research/session-b-fixtures/nested/shared-refreshed.json"), root.resolve("shared.json"), StandardCopyOption.REPLACE_EXISTING)
            key(ui, 'r'); await(workers, ui)
            // Refresh while inspecting does not leave details or erase the row; the dropped list entry is not recalled.
            val details = all(ui)
            assertTrue(details.contains("No current catalog attribution."), details)
            assertTrue(details.contains("custom-target"), details)
            key(ui, 'e')
            assertTrue(all(ui).contains("Both exist: Keep target"))
            escape(ui); key(ui, 's')
            assertTrue(render(ui).contains("[1: Workspace]"))
            val saved = ConfigurationLoader().load(root.resolve("config.json"))
            assertEquals(1, saved.relocations.size)
            assertEquals(root.resolve("local/custom-target"), saved.relocations.first().targetPath)
            assertEquals(root.resolve("shared.json"), saved.sharedList)
            assertEquals("unchanged", Files.readString(root.resolve("home/team-cache/payload")))
            assertFalse(Files.exists(root.resolve("local/custom-target")))
            assertInstanceOf(ApplyModel.Idle::class.java, ui.app.session.applyModel())
            assertNull(workers.workers.first().snapshot().request)
        }
    }

    @Test fun pathFieldsTakeCommandLettersAsTextAndKeepFocus() {
        val root = fixture()
        // Quit (q Q), setup commands (a b d e r u) and the former vim keys (h j k l g G x).
        val letters = "qQabderuhjklgGx"
        val ui = HeadlessTui(HomeLightSession(root.resolve("config.json")))
        key(ui, 'i'); clear(ui); type(ui, "/$letters")
        down(ui); type(ui, letters); down(ui); type(ui, letters)
        var screen = render(ui)
        assertTrue(screen.contains("  Source root: /$letters"), screen)
        assertTrue(screen.contains("  Target root: $letters"), screen)
        assertTrue(screen.contains("❯ Suggestion list (optional): $letters"), screen)
        assertFalse(screen.contains("Discard this configuration?"), screen)
        ctrl(ui, 'd'); ctrl(ui, 'k')
        assertTrue(render(ui).contains("❯ Suggestion list (optional): $letters "), "Ctrl chords are not text")
        ctrl(ui, 'u')
        assertTrue(render(ui).contains("❯ Suggestion list (optional):"))
        assertFalse(render(ui).contains("❯ Suggestion list (optional): $letters"), "Ctrl+U still clears")

        down(ui); clear(ui); type(ui, root.resolve("home").toString())
        down(ui); clear(ui); type(ui, root.resolve("local").toString()); enter(ui)
        assertTrue(render(ui).contains("No relocations yet"))
        key(ui, 'a'); type(ui, letters)
        screen = render(ui)
        assertTrue(screen.contains("Edit relocation 1"), screen)
        assertTrue(screen.contains("❯ Source path: $letters"), screen)
        assertTrue(screen.contains("  Target path: $letters"), "The target follows the source")
        down(ui); key(ui, 'x'); repeat(4) { down(ui) }; type(ui, "/$letters")
        screen = render(ui)
        assertTrue(screen.contains("  Target path: ${letters}x"), screen)
        assertTrue(screen.contains("❯ Archive root: /$letters"), screen)
        assertFalse(screen.contains("Discard this configuration?"), screen)
    }

    @Test fun ctrlAndAltChordsDoNotActAsLetterCommands() {
        val root = fixture()
        SetupDiscoveryFixture().use { workers ->
            val ui = ui(root, workers); locations(ui, root, root.resolve("shared.json"))
            key(ui, 'a'); type(ui, "manual"); escape(ui)
            ctrl(ui, 'd'); ctrl(ui, 'u'); alt(ui, 'd')
            val table = render(ui)
            assertTrue(table.contains("❯ manual"), table)
            assertFalse(table.contains("Relocation removed"), table)
            enter(ui); down(ui); down(ui)
            ctrl(ui, 'd'); alt(ui, 'd')
            assertTrue(render(ui).contains("Edit relocation 1"), "Ctrl+D and Alt+D on a policy do not remove the row")
            escape(ui); key(ui, 'b'); await(workers, ui)
            ctrl(ui, 'u'); alt(ui, 'u'); ctrl(ui, 'd')
            val list = render(ui)
            assertTrue(list.contains("1 usually not needed, hidden"), list)
            assertTrue(list.contains("1 in draft"), list)
            key(ui, 'u')
            assertTrue(render(ui).contains("1 usually not needed, shown"))
            escape(ui)
            assertTrue(render(ui).contains("❯ manual"))
            ui.app.closeSetup()
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
            assertTrue(render(ui).contains("Edit relocation 1"))
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
            assertFalse(render(ui).contains("Missing"))
            enter(ui)
            val details = all(ui)
            assertTrue(details.contains("State: not created yet"), details)
            assertTrue(details.contains("Not found under the source root"), details)
            assertTrue(details.contains("source and target are both missing"), details)
            assertTrue(details.contains("If only the target exists"), details)
            assertTrue(details.contains("Save writes configuration only"), details)
            escape(ui); key(ui, ' ')
            assertTrue(render(ui).contains("❯   [x] absent-cache"))
            assertTrue(render(ui).contains("not created yet"))
            assertFalse(render(ui).contains("Missing"))
            key(ui, 'e'); down(ui); clear(ui); type(ui, "future-cache")
            down(ui); down(ui); key(ui, ' ')
            escape(ui); key(ui, 'b'); key(ui, 'r'); await(workers, ui)
            assertTrue(render(ui).contains("❯   [x] absent-cache"))
            enter(ui)
            assertTrue(all(ui).contains("future-cache"))
            escape(ui); escape(ui); key(ui, 's')
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
            key(ui, 'a'); type(ui, ".m2"); down(ui); clear(ui); type(ui, "manual-target"); escape(ui)
            key(ui, 'b'); await(workers, ui); choose(ui, ".m2"); enter(ui)
            assertTrue(render(ui).contains("e: Edit draft row"))
            assertFalse(render(ui).contains("a: Add to draft"))
            key(ui, 'a'); escape(ui)
            choose(ui, ".local/share/uv"); enter(ui); key(ui, 'a'); escape(ui)
            choose(ui, ".local/share/uv/tools"); enter(ui); key(ui, 'a')
            val error = all(ui)
            // all() joins wrapped lines without the wrap-point space, and where the message wraps depends on the temp path length.
            val text = error.replace(Regex("\\s"), "")
            assertTrue(text.contains("Notadded."), error)
            assertTrue(text.contains("Priorchoicesareunchanged"), error)
            assertTrue(text.contains("pathsoverlap"), error)
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
                key(ui, 's'); assertTrue(all(ui).contains("Save failed"))
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
            key(ui, 'e'); clear(ui); type(ui, root.resolve("other-home").toString()); down(ui); down(ui); clear(ui); enter(ui)
            key(ui, 'b')
            workers.release.countDown(); await(workers, ui)
            assertFalse(all(ui).contains("team-cache"))
            escape(ui); key(ui, 'q'); key(ui, 'y')
            assertNull(workers.workers.first().snapshot().request)
            key(ui, 'i')
            assertTrue(render(ui).contains("Storage locations"))
            assertFalse(render(ui).contains("other-home"))
            assertFalse(render(ui).contains("shared.json"))
            enter(ui); assertTrue(render(ui).contains("No relocations yet"))
            assertFalse(Files.exists(root.resolve("config.json")))
            ui.app.closeSetup()
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

    @Test fun configuredRowsAreInspectionOnlyAndTextIsEscaped() {
        val root = fixture()
        val empty = CandidateBrowser()
        val emptyText = WorkspaceViewTest.render(empty.render(SetupDraft(root.resolve("home"), root.resolve("local"), null, listOf())), 80, 24)
        assertFalse(emptyText.contains("Enter:") || emptyText.contains("a: Add"), emptyText)
        val relocation = Relocation(root.resolve("home/.m2"), root.resolve("local/saved"),
            WhenSourceAndTargetDirectoriesExist.DISCARD)
        val draft = SetupDraft(root.resolve("home"), root.resolve("local"), null, listOf(relocation))
        val browser = CandidateBrowser()
        WorkspaceViewTest.render(browser.render(draft), 80, 24)
        browser.key(KeyEvent.ofKey(KeyCode.DOWN, KEY_BINDINGS), draft); browser.key(KeyEvent.ofKey(KeyCode.ENTER, KEY_BINDINGS), draft)
        val text = WorkspaceViewTest.render(browser.render(draft), 120, 30)
        assertTrue(text.contains("Configured"))
        assertTrue(text.contains("Saved target:"))
        assertTrue(text.contains("Both exist: Delete both, start empty"), text)
        assertFalse(text.contains("a: Add") || text.contains("e: Edit"))
        browser.key(KeyEvent.ofChar('a', KEY_BINDINGS), draft); browser.key(KeyEvent.ofChar('e', KEY_BINDINGS), draft)
        assertTrue(draft.rows.isEmpty())
        assertEquals("hello\\u001b[2J\\u000aworld", literal("hello\u001b[2J\nworld"))
    }

    @Test fun savedPoliciesUseTheirLabelsAndNoRawKeys() {
        val root = fixture()
        val rawKeys = setOf(
            "prompt", "adopt", "leave-unchanged", "discard", "adopt-target", "discard-source", "archive-source",
            "PROMPT", "ADOPT", "LEAVE_UNCHANGED", "DISCARD", "ADOPT_TARGET", "DISCARD_SOURCE", "ARCHIVE_SOURCE",
        )
        // Together these rows hold every value of each policy.
        val rows = listOf(
            Triple(WhenSourceAndTargetDirectoriesExist.PROMPT, WhenOnlyTargetExists.PROMPT, WhenAdoptingTarget.PROMPT) to
                "Both exist: Ask each time · Only target: Ask each time",
            Triple(WhenSourceAndTargetDirectoriesExist.ADOPT, WhenOnlyTargetExists.ADOPT_TARGET, WhenAdoptingTarget.DISCARD_SOURCE) to
                "Both exist: Keep target, delete source · Only target: Keep target, link source",
            Triple(WhenSourceAndTargetDirectoriesExist.ADOPT, WhenOnlyTargetExists.PROMPT, WhenAdoptingTarget.ARCHIVE_SOURCE) to
                "Both exist: Keep target, archive source · Only target: Ask each time",
            Triple(WhenSourceAndTargetDirectoriesExist.ADOPT, WhenOnlyTargetExists.PROMPT, WhenAdoptingTarget.PROMPT) to
                "Both exist: Keep target, ask about source · Only target: Ask each time",
            Triple(WhenSourceAndTargetDirectoriesExist.LEAVE_UNCHANGED, WhenOnlyTargetExists.PROMPT, WhenAdoptingTarget.ARCHIVE_SOURCE) to
                "Both exist: Leave both as they are · Only target: Ask each time",
            Triple(WhenSourceAndTargetDirectoriesExist.DISCARD, WhenOnlyTargetExists.PROMPT, WhenAdoptingTarget.PROMPT) to
                "Both exist: Delete both, start empty · Only target: Ask each time",
        )
        for ((policies, expected) in rows) {
            val relocation = Relocation(root.resolve("home/.m2"), root.resolve("local/saved"), policies.first, policies.second, policies.third)
            val draft = SetupDraft(root.resolve("home"), root.resolve("local"), null, listOf(relocation))
            val browser = CandidateBrowser()
            WorkspaceViewTest.render(browser.render(draft), 80, 24)
            browser.key(KeyEvent.ofKey(KeyCode.DOWN, KEY_BINDINGS), draft); browser.key(KeyEvent.ofKey(KeyCode.ENTER, KEY_BINDINGS), draft)
            val text = WorkspaceViewTest.render(browser.render(draft), 200, 30)
            val line = text.lines().first { it.contains("Saved rules: ") }
                .substringAfter("Saved rules: ").substringBefore('│').trimEnd()
            assertEquals(expected, line, text)
            val shown = line.split(" · ").map { part -> part.substringAfter(": ") }
            assertTrue(shown.none { label -> label in rawKeys }, line)
        }
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
            val editing = render(ui)
            assertTrue(editing.contains("❯ Target path: unfinished-target"), editing)
            type(ui, "-continued"); escape(ui); key(ui, 'b')
            assertTrue(all(ui).contains("unfinished-target-continued"))
            assertTrue(render(ui).contains("Candidate details"))
            escape(ui)
            assertTrue(render(ui).lines().any { line -> line.contains("❯   [x] .m2") })
            ui.app.closeSetup()
        }
    }

    @Test fun lateCompletionAfterConfirmedDiscardCannotResurrectSetup() {
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
            key(ui, 'i'); enter(ui)
            assertTrue(render(ui).contains("No relocations yet"))
            assertFalse(Files.exists(root.resolve("config.json")))
            ui.app.closeSetup()
        }
    }

    @Test fun explicitRootChangeRebasesRowsWithoutChangingTargetsOrPolicies() {
        val root = fixture()
        SetupDiscoveryFixture().use { workers ->
            val ui = ui(root, workers); locations(ui, root, root.resolve("shared.json"))
            key(ui, 'a'); type(ui, "manual"); down(ui); clear(ui); type(ui, "chosen-target")
            down(ui); key(ui, ' '); escape(ui)
            key(ui, 'e'); clear(ui); type(ui, root.resolve("other-home").toString())
            down(ui); clear(ui); type(ui, root.resolve("other-target").toString())
            down(ui); clear(ui); enter(ui); key(ui, 's')
            val configuration = ConfigurationLoader().load(root.resolve("config.json"))
            val row = configuration.relocations.first()
            assertEquals(root.resolve("other-home/manual"), row.sourcePath)
            assertEquals(root.resolve("other-target/chosen-target"), row.targetPath)
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
        fun locations(ui: HeadlessTui, root: Path, shared: Path) {
            clear(ui); type(ui, root.resolve("home").toString()); down(ui); type(ui, root.resolve("local").toString())
            down(ui); type(ui, shared.toString()); enter(ui)
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
            // Kotlin's lines() adds a trailing empty line, which the border filter drops. The trimEnd is Java's
            // stripTrailing, which Kotlin hides.
            return screens.joinToString("\n") + screens.flatMap { it.lines() }
                .filter { line -> line.startsWith("│") }.map { line -> line.substring(1).replace("│", "").replace("█", "").trimEnd { Character.isWhitespace(it) } }
                .joinToString("")
        }
        fun render(ui: HeadlessTui): String {
            try { return ui.screen(120, 30) }
            catch (error: Exception) { throw AssertionError(error) }
        }
        fun key(ui: HeadlessTui, key: Char) { ui.press(key) }
        fun type(ui: HeadlessTui, value: String) { value.forEach { c -> key(ui, c) } }
        fun clear(ui: HeadlessTui) { key(ui, '\u0015') }
        fun ctrl(ui: HeadlessTui, key: Char) { ui.ctrl(key) }
        fun alt(ui: HeadlessTui, key: Char) { ui.alt(key) }
        fun down(ui: HeadlessTui) { ui.press(KeyCode.DOWN) }
        fun enter(ui: HeadlessTui) { ui.press(KeyCode.ENTER) }
        fun escape(ui: HeadlessTui) { ui.press(KeyCode.ESCAPE) }
    }
}
