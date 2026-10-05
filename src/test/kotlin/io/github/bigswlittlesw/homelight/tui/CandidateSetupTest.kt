package io.github.bigswlittlesw.homelight.tui

import dev.tamboui.tui.event.KeyCode
import dev.tamboui.tui.event.KeyEvent
import dev.tamboui.tui.event.KeyModifiers
import io.github.bigswlittlesw.homelight.application.ApplyModel
import io.github.bigswlittlesw.homelight.application.HomeLightSession
import io.github.bigswlittlesw.homelight.application.PlanModel
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
        val app = HomeLightApp(HomeLightSession(root.resolve("config.json")))
        key(app, 'i'); locations(app, root, root.resolve("shared.json"))
        key(app, 'a'); type(app, "team-cache"); down(app); down(app)
        repeat(3) { key(app, ' ') }
        for (width in listOf(80, 120, 80, 120)) {
            val height = if (width == 80) 24 else 30
            val details = WorkspaceViewTest.render(app.render(), width, height)
            assertTrue(details.contains("❯ Both directories: Discard both"), details)
            val text = details.replace(Regex("[│█\\s]"), "")
            assertTrue(text.contains("Permanentlydeletebothsourceandtargetdirectorytrees.Createanemptytargetdirectoryandlinkthesourcetoit."), details)
            assertFalse(details.contains("Discard target") || details.contains("relocate source"), details)
            escape(app)
            val table = WorkspaceViewTest.render(app.render(), width, height)
            assertTrue(table.contains("Policies") && table.contains("Discard both"), table)
            assertFalse(table.contains("Discard target"), table)
            enter(app); down(app); down(app)
        }
        escape(app); key(app, 's')
        val plan = assertInstanceOf(PlanModel.Configured::class.java, app.session.planModel())
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
        assertInstanceOf(ApplyModel.Idle::class.java, app.session.applyModel())
    }

    @Test fun browseAddEditRefreshRemovalAndSavePreserveChoices() {
        val root = fixture()
        SetupDiscoveryFixture().use { workers ->
            val app = app(root, workers)
            locations(app, root, root.resolve("shared.json"))
            key(app, 'b'); await(workers, app)
            val list = render(app)
            assertTrue(list.contains("Maven (1)"), list)
            assertTrue(list.contains("Mixed advice"), list)
            assertTrue(list.contains("1 usually-unnecessary directory hidden"), list)
            choose(app, "team-cache"); enter(app)
            key(app, 'a')
            assertTrue(render(app).contains("e: Edit draft row"))
            key(app, 'e'); down(app); clear(app); type(app, "custom-target")
            down(app); key(app, ' ') // Adopt target, no inferred source disposition.
            escape(app); key(app, 'b')
            Files.copy(Path.of("docs/research/session-b-fixtures/nested/shared-refreshed.json"), root.resolve("shared.json"), StandardCopyOption.REPLACE_EXISTING)
            key(app, 'r'); await(workers, app)
            // Refresh while inspecting does not leave details or erase the row; the dropped list entry is not recalled.
            val details = all(app)
            assertTrue(details.contains("No current catalog attribution."), details)
            assertTrue(details.contains("custom-target"), details)
            key(app, 'e')
            assertTrue(all(app).contains("Adopt target"))
            escape(app); key(app, 's')
            assertTrue(render(app).contains("[1: Workspace]"))
            val saved = ConfigurationLoader().load(root.resolve("config.json"))
            assertEquals(1, saved.relocations.size)
            assertEquals(root.resolve("local/custom-target"), saved.relocations.first().targetPath)
            assertEquals(root.resolve("shared.json"), saved.sharedList)
            assertEquals("unchanged", Files.readString(root.resolve("home/team-cache/payload")))
            assertFalse(Files.exists(root.resolve("local/custom-target")))
            assertInstanceOf(ApplyModel.Idle::class.java, app.session.applyModel())
            assertNull(workers.workers.first().snapshot().request)
        }
    }

    @Test fun pathFieldsTakeVimLettersAsTextAndKeepFocus() {
        val root = fixture()
        // Vim navigation (h j k l g G), delete forward (x) and quit (q Q) letters.
        val letters = "hjklgGxqQ"
        val app = HomeLightApp(HomeLightSession(root.resolve("config.json")))
        key(app, 'i'); clear(app); type(app, "/$letters")
        down(app); type(app, letters); down(app); type(app, letters)
        var screen = render(app)
        assertTrue(screen.contains("  Source root: /$letters"), screen)
        assertTrue(screen.contains("  Target root: $letters"), screen)
        assertTrue(screen.contains("❯ Shared candidate list (optional): $letters"), screen)
        assertFalse(screen.contains("Discard setup draft?"), screen)
        ctrl(app, 'd'); ctrl(app, 'k')
        assertTrue(render(app).contains("❯ Shared candidate list (optional): $letters "), "Ctrl chords are not text")
        ctrl(app, 'u')
        assertTrue(render(app).contains("❯ Shared candidate list (optional):"))
        assertFalse(render(app).contains("❯ Shared candidate list (optional): $letters"), "Ctrl+U still clears")

        down(app); clear(app); type(app, root.resolve("home").toString())
        down(app); clear(app); type(app, root.resolve("local").toString()); enter(app)
        assertTrue(render(app).contains("No relocations yet"))
        key(app, 'a'); type(app, letters)
        screen = render(app)
        assertTrue(screen.contains("Edit relocation 1"), screen)
        assertTrue(screen.contains("❯ Source path: $letters"), screen)
        assertTrue(screen.contains("  Target path: $letters"), "The target follows the source")
        down(app); key(app, 'x'); repeat(4) { down(app) }; type(app, "/$letters")
        screen = render(app)
        assertTrue(screen.contains("  Target path: ${letters}x"), screen)
        assertTrue(screen.contains("❯ Archive root: /$letters"), screen)
        assertFalse(screen.contains("Discard setup draft?"), screen)
    }

    @Test fun vimPagingChordsAndDeleteForwardDoNotActOutsideTextFields() {
        val root = fixture()
        SetupDiscoveryFixture().use { workers ->
            val app = app(root, workers); locations(app, root, root.resolve("shared.json"))
            key(app, 'a'); type(app, "manual"); escape(app)
            for (chord in listOf('d', 'u')) ctrl(app, chord)
            key(app, 'x')
            val table = render(app)
            assertTrue(table.contains("❯ manual"), table)
            assertFalse(table.contains("Relocation removed"), table)
            enter(app); down(app); down(app)
            ctrl(app, 'd'); key(app, 'x')
            assertTrue(render(app).contains("Edit relocation 1"), "Ctrl+D on a policy does not remove the row")
            escape(app); key(app, 'b'); await(workers, app)
            ctrl(app, 'u'); ctrl(app, 'd'); key(app, 'x')
            val list = render(app)
            assertTrue(list.contains("1 usually-unnecessary directory hidden"), list)
            assertTrue(list.contains("1 in draft"), list)
            key(app, 'u')
            assertTrue(render(app).contains("1 usually-unnecessary directory revealed"))
            escape(app)
            assertTrue(render(app).contains("❯ manual"))
            app.closeSetup()
        }
    }

    @Test fun groupExpansionNeverSelectsAndAdviceCollapseKeepsAddedRowsVisible() {
        val root = fixture()
        SetupDiscoveryFixture().use { workers ->
            val app = app(root, workers); locations(app, root, root.resolve("shared.json"))
            key(app, 'b'); await(workers, app)
            enter(app)
            assertFalse(render(app).contains("[ ] .m2"))
            enter(app)
            key(app, 'u')
            choose(app, ".cache/example"); enter(app); key(app, 'a'); escape(app)
            key(app, 'u')
            assertTrue(all(app).contains("[x] .cache/example"))
            // No action on a heading may create rows.
            escape(app); key(app, 's')
            val saved = ConfigurationLoader().load(root.resolve("config.json"))
            assertEquals(listOf(root.resolve("home/.cache/example")), saved.relocations.map(Relocation::sourcePath))
        }
    }

    @Test fun checklistAddsInPlaceWithoutReorderingAndKeepsRejectedChoices() {
        val root = fixture()
        SetupDiscoveryFixture().use { workers ->
            val app = app(root, workers); locations(app, root, root.resolve("shared.json"))
            key(app, 'b'); await(workers, app)
            key(app, ' '); key(app, 'a')
            assertTrue(render(app).contains("0 in draft"), "App headings cannot add children")
            choose(app, ".m2")
            val before = render(app)
            key(app, ' ')
            val added = render(app)
            assertTrue(added.contains("Browse candidates") && added.contains("❯   [x] .m2"), added)
            assertFalse(added.contains("Candidate details") || added.contains("Space/a: Add"), added)
            assertEquals(before.indexOf("Maven (1)"), added.indexOf("Maven (1)"))
            key(app, ' '); key(app, 'a')
            assertTrue(render(app).contains("1 in draft"))
            choose(app, ".local/share/uv"); key(app, 'a')
            choose(app, ".local/share/uv/tools"); key(app, ' ')
            val rejected = render(app)
            assertTrue(rejected.contains("Browse candidates") && rejected.contains("prior choices are unchanged"), rejected)
            assertTrue(rejected.contains("2 in draft"))
            enter(app)
            assertTrue(all(app).contains("paths overlap"))
            escape(app)
            choose(app, "absent-cache"); key(app, ' '); key(app, 'a')
            assertTrue(render(app).contains("3 in draft"))
            choose(app, ".m2"); key(app, 'e')
            assertTrue(render(app).contains("Edit relocation 1"))
            escape(app); key(app, 's')
            assertEquals(3, ConfigurationLoader().load(root.resolve("config.json")).relocations.size)
        }
    }

    @Test fun missingCandidateCanBeAddedEditedRefreshedAndSavedBeforeTheAppCreatesIt() {
        val root = fixture()
        SetupDiscoveryFixture().use { workers ->
            val app = app(root, workers); locations(app, root, root.resolve("shared.json"))
            key(app, 'b'); await(workers, app); choose(app, "absent-cache")
            assertTrue(render(app).contains("Space/a: Add"))
            assertTrue(render(app).contains("[ ] absent-cache"))
            assertFalse(render(app).contains("Missing") || render(app).contains("Not created yet"))
            enter(app)
            val details = all(app)
            assertTrue(details.contains("Metadata: Not created yet"), details)
            assertTrue(details.contains("Not found under the source root"), details)
            assertTrue(details.contains("source and target are both missing"), details)
            assertTrue(details.contains("If only the target exists"), details)
            assertTrue(details.contains("Save writes configuration only"), details)
            escape(app); key(app, ' ')
            assertTrue(render(app).contains("❯   [x] absent-cache"))
            assertFalse(render(app).contains("Missing") || render(app).contains("Not created yet"))
            key(app, 'e'); down(app); clear(app); type(app, "future-cache")
            down(app); down(app); key(app, ' ')
            escape(app); key(app, 'b'); key(app, 'r'); await(workers, app)
            assertTrue(render(app).contains("❯   [x] absent-cache"))
            enter(app)
            assertTrue(all(app).contains("future-cache"))
            escape(app); escape(app); key(app, 's')
            assertTrue(render(app).contains("[1: Workspace]"))
            val row = ConfigurationLoader().load(root.resolve("config.json")).relocations.first()
            assertEquals(root.resolve("home/absent-cache"), row.sourcePath)
            assertEquals(root.resolve("local/future-cache"), row.targetPath)
            assertEquals(WhenOnlyTargetExists.ADOPT_TARGET, row.whenOnlyTargetExists)
            assertFalse(Files.exists(row.sourcePath))
            assertFalse(Files.exists(root.resolve("local")))
            assertInstanceOf(ApplyModel.Idle::class.java, app.session.applyModel())
        }
    }

    @Test fun overlapRejectionAndManualMatchesDoNotLoseEarlierRows() {
        val root = fixture()
        SetupDiscoveryFixture().use { workers ->
            val app = app(root, workers); locations(app, root, root.resolve("shared.json"))
            key(app, 'a'); type(app, ".m2"); down(app); clear(app); type(app, "manual-target"); escape(app)
            key(app, 'b'); await(workers, app); choose(app, ".m2"); enter(app)
            assertTrue(render(app).contains("e: Edit draft row"))
            assertFalse(render(app).contains("a: Add to draft"))
            key(app, 'a'); escape(app)
            choose(app, ".local/share/uv"); enter(app); key(app, 'a'); escape(app)
            choose(app, ".local/share/uv/tools"); enter(app); key(app, 'a')
            val error = all(app)
            // all() joins wrapped lines without the wrap-point space, and where the message wraps depends on the temp path length.
            val text = error.replace(Regex("\\s"), "")
            assertTrue(text.contains("Notadded."), error)
            assertTrue(text.contains("Priorchoicesareunchanged"), error)
            assertTrue(text.contains("pathsoverlap"), error)
            escape(app); escape(app); key(app, 's')
            val saved = ConfigurationLoader().load(root.resolve("config.json"))
            assertEquals(2, saved.relocations.size)
            assertEquals(root.resolve("local/manual-target"), saved.relocations.first().targetPath)
        }
    }

    @Test fun stalledReadAllowsManualEditingFailedAndSuccessfulSaveAndIgnoresLateCompletion() {
        val root = fixture()
        SetupDiscoveryFixture().use { workers ->
            workers.block = true
            val app = app(root, workers); locations(app, root, root.resolve("shared.json")); key(app, 'b')
            assertTrue(workers.entered.await(2, TimeUnit.SECONDS))
            assertTimeout(Duration.ofSeconds(1), Executable {
                workers.expire(); render(app); key(app, 'r'); key(app, 'i')
                assertTrue(all(app).contains("Previous read still pending"))
                escape(app); escape(app); key(app, 'a'); type(app, "manual"); escape(app)
                Files.writeString(root.resolve("config.json"), "concurrent winner")
                key(app, 's'); assertTrue(all(app).contains("Save failed"))
                assertEquals("concurrent winner", Files.readString(root.resolve("config.json")))
            })
            Files.delete(root.resolve("config.json"))
            assertTimeout(Duration.ofSeconds(1), Executable { key(app, 's') })
            assertTrue(render(app).contains("[1: Workspace]"))
            assertNull(workers.workers.first().snapshot().request)
            workers.release.countDown()
            assertTrue(render(app).contains("[1: Workspace]"))
            assertEquals(1, workers.reads.get())
            assertEquals(1, ConfigurationLoader().load(root.resolve("config.json")).relocations.size)
        }
    }

    @Test fun discardCancelReopenAndRootLocationEditsRejectObsoleteResults() {
        val root = fixture()
        SetupDiscoveryFixture().use { workers ->
            workers.block = true
            val app = app(root, workers); locations(app, root, root.resolve("shared.json")); key(app, 'b')
            assertTrue(workers.entered.await(2, TimeUnit.SECONDS))
            escape(app); key(app, 'a'); type(app, "manual"); escape(app)
            key(app, 'q'); escape(app)
            assertTrue(render(app).contains("manual"))
            key(app, 'e'); clear(app); type(app, root.resolve("other-home").toString()); down(app); down(app); clear(app); enter(app)
            key(app, 'b')
            workers.release.countDown(); await(workers, app)
            assertFalse(all(app).contains("team-cache"))
            escape(app); key(app, 'q'); enter(app)
            assertNull(workers.workers.first().snapshot().request)
            key(app, 'i')
            assertTrue(render(app).contains("Storage locations"))
            assertFalse(render(app).contains("other-home"))
            assertFalse(render(app).contains("shared.json"))
            enter(app); assertTrue(render(app).contains("No relocations yet"))
            assertFalse(Files.exists(root.resolve("config.json")))
            app.closeSetup()
        }
    }

    @Test fun malformedAndMissingSourcesLeaveBundledAndManualSaveAvailable() {
        for (missing in listOf(false, true)) {
            val root = fixture()
            if (missing) Files.delete(root.resolve("shared.json"))
            else Files.writeString(root.resolve("shared.json"), "{\"apps\": [")
            SetupDiscoveryFixture().use { workers ->
                val app = app(root, workers); locations(app, root, root.resolve("shared.json")); key(app, 'b'); await(workers, app)
                assertTrue(render(app).contains("Bundled: current · Shared: unavailable"))
                key(app, 'i'); assertTrue(all(app).contains(if (missing) "NoSuchFileException" else "line"))
                escape(app); escape(app); key(app, 'a'); type(app, "manual"); escape(app); key(app, 's')
                assertTrue(render(app).contains("[1: Workspace]"))
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
        browser.key(KeyEvent.ofChar('j', KEY_BINDINGS), draft); browser.key(KeyEvent.ofKey(KeyCode.ENTER, KEY_BINDINGS), draft)
        val text = WorkspaceViewTest.render(browser.render(draft), 120, 30)
        assertTrue(text.contains("Configured"))
        assertTrue(text.contains("Saved target:"))
        assertTrue(text.contains("both directories: Discard both"), text)
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
                "both directories: Prompt; only target: Prompt; adopt target: Prompt",
            Triple(WhenSourceAndTargetDirectoriesExist.ADOPT, WhenOnlyTargetExists.ADOPT_TARGET, WhenAdoptingTarget.DISCARD_SOURCE) to
                "both directories: Adopt target; only target: Adopt target; adopt target: Discard source",
            Triple(WhenSourceAndTargetDirectoriesExist.LEAVE_UNCHANGED, WhenOnlyTargetExists.PROMPT, WhenAdoptingTarget.ARCHIVE_SOURCE) to
                "both directories: Leave unchanged; only target: Prompt; adopt target: Archive source",
            Triple(WhenSourceAndTargetDirectoriesExist.DISCARD, WhenOnlyTargetExists.PROMPT, WhenAdoptingTarget.PROMPT) to
                "both directories: Discard both; only target: Prompt; adopt target: Prompt",
        )
        for ((policies, expected) in rows) {
            val relocation = Relocation(root.resolve("home/.m2"), root.resolve("local/saved"), policies.first, policies.second, policies.third)
            val draft = SetupDraft(root.resolve("home"), root.resolve("local"), null, listOf(relocation))
            val browser = CandidateBrowser()
            WorkspaceViewTest.render(browser.render(draft), 80, 24)
            browser.key(KeyEvent.ofChar('j', KEY_BINDINGS), draft); browser.key(KeyEvent.ofKey(KeyCode.ENTER, KEY_BINDINGS), draft)
            val text = WorkspaceViewTest.render(browser.render(draft), 200, 30)
            val line = text.lines().first { it.contains("Saved policies: ") }
                .substringAfter("Saved policies: ").substringBefore('│').trimEnd()
            assertEquals(expected, line, text)
            val shown = line.split("; ").map { part -> part.substringAfter(": ") }
            assertTrue(shown.none { label -> label in rawKeys }, line)
        }
    }

    @Test fun arrivingResultsDoNotStealPathFocusOrEraseActiveRowText() {
        val root = fixture()
        SetupDiscoveryFixture().use { workers ->
            workers.block = true
            val app = app(root, workers); locations(app, root, root.resolve("shared.json")); key(app, 'b')
            assertTrue(workers.entered.await(2, TimeUnit.SECONDS))
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!render(app).contains("[ ] .m2") && System.nanoTime() < until) LockSupport.parkNanos(1_000_000)
            choose(app, ".m2"); enter(app); key(app, 'a'); key(app, 'e')
            down(app); clear(app); type(app, "unfinished-target")
            workers.release.countDown(); await(workers, app)
            val editing = render(app)
            assertTrue(editing.contains("❯ Target path: unfinished-target"), editing)
            type(app, "-continued"); escape(app); key(app, 'b')
            assertTrue(all(app).contains("unfinished-target-continued"))
            assertTrue(render(app).contains("Candidate details"))
            escape(app)
            assertTrue(render(app).lines().any { line -> line.contains("❯   [x] .m2") })
            app.closeSetup()
        }
    }

    @Test fun lateCompletionAfterConfirmedDiscardCannotResurrectSetup() {
        val root = fixture()
        SetupDiscoveryFixture().use { workers ->
            workers.block = true
            val app = app(root, workers); locations(app, root, root.resolve("shared.json")); key(app, 'b')
            assertTrue(workers.entered.await(2, TimeUnit.SECONDS))
            key(app, 'q'); enter(app)
            workers.release.countDown()
            repeat(20) {
                assertFalse(render(app).contains("[Setup]"))
                LockSupport.parkNanos(1_000_000)
            }
            assertNull(workers.workers.first().snapshot().request)
            key(app, 'i'); enter(app)
            assertTrue(render(app).contains("No relocations yet"))
            assertFalse(Files.exists(root.resolve("config.json")))
            app.closeSetup()
        }
    }

    @Test fun explicitRootChangeRebasesRowsWithoutChangingTargetsOrPolicies() {
        val root = fixture()
        SetupDiscoveryFixture().use { workers ->
            val app = app(root, workers); locations(app, root, root.resolve("shared.json"))
            key(app, 'a'); type(app, "manual"); down(app); clear(app); type(app, "chosen-target")
            down(app); key(app, ' '); escape(app)
            key(app, 'e'); clear(app); type(app, root.resolve("other-home").toString())
            down(app); clear(app); type(app, root.resolve("other-target").toString())
            down(app); clear(app); enter(app); key(app, 's')
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
        fun app(root: Path, workers: SetupDiscoveryFixture): HomeLightApp {
            val app = HomeLightApp(HomeLightSession(root.resolve("config.json")), discoveryFactory = workers::get); key(app, 'i'); return app
        }
        fun locations(app: HomeLightApp, root: Path, shared: Path) {
            clear(app); type(app, root.resolve("home").toString()); down(app); type(app, root.resolve("local").toString())
            down(app); type(app, shared.toString()); enter(app)
        }
        fun await(workers: SetupDiscoveryFixture, app: HomeLightApp) {
            pollUntil("Discovery did not settle") {
                val result = workers.workers.last().snapshot()
                result.sources.none { s -> s.status == CandidateDiscovery.SourceStatus.PENDING }
                    && result.candidates.none { c -> c.observation.kind == CandidateObservation.Kind.PENDING }
            }
            // Render exactly once after discovery settles: the app accepts snapshots only when rendering, and the
            // next key acts on what it last rendered.
            render(app)
        }
        fun choose(app: HomeLightApp, relative: String) {
            app.handleKeyEvent(KeyEvent.ofKey(KeyCode.HOME, KEY_BINDINGS))
            repeat(100) {
                if (render(app).lines().any { line -> line.matches(Regex(".*❯   (\\[.\\]| − ) " + Pattern.quote(relative) + "(?: +.*|│.*)")) }) return
                key(app, 'j')
            }
            fail<Unit>("Could not focus " + relative + "\n" + render(app))
        }
        fun all(app: HomeLightApp): String {
            val screens = linkedSetOf<String>()
            screens.add(render(app))
            repeat(80) { key(app, ']'); screens.add(render(app)) }
            repeat(80) { key(app, '[') }
            // Kotlin's lines() adds a trailing empty line, which the border filter drops. The trimEnd is Java's
            // stripTrailing, which Kotlin hides.
            return screens.joinToString("\n") + screens.flatMap { it.lines() }
                .filter { line -> line.startsWith("│") }.map { line -> line.substring(1).replace("│", "").replace("█", "").trimEnd { Character.isWhitespace(it) } }
                .joinToString("")
        }
        fun render(app: HomeLightApp): String {
            try { return WorkspaceViewTest.render(app.render(), 120, 30) }
            catch (error: Exception) { throw AssertionError(error) }
        }
        fun key(app: HomeLightApp, key: Char) { app.handleKeyEvent(KeyEvent.ofChar(key, KEY_BINDINGS)) }
        fun type(app: HomeLightApp, value: String) { value.forEach { c -> key(app, c) } }
        fun clear(app: HomeLightApp) { key(app, '\u0015') }
        fun ctrl(app: HomeLightApp, key: Char) { app.handleKeyEvent(KeyEvent.ofChar(key, KeyModifiers.CTRL, KEY_BINDINGS)) }
        fun down(app: HomeLightApp) { app.handleKeyEvent(KeyEvent.ofKey(KeyCode.DOWN, KEY_BINDINGS)) }
        fun enter(app: HomeLightApp) { app.handleKeyEvent(KeyEvent.ofKey(KeyCode.ENTER, KEY_BINDINGS)) }
        fun escape(app: HomeLightApp) { app.handleKeyEvent(KeyEvent.ofKey(KeyCode.ESCAPE, KEY_BINDINGS)) }
    }
}
