package io.github.bigswlittlesw.homelight.tui;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import io.github.bigswlittlesw.homelight.application.ApplyModel;
import io.github.bigswlittlesw.homelight.application.HomeLightSession;
import io.github.bigswlittlesw.homelight.application.PlanModel;
import io.github.bigswlittlesw.homelight.application.SetupDraft;
import io.github.bigswlittlesw.homelight.config.ConfigurationLoader;
import io.github.bigswlittlesw.homelight.config.Relocation;
import io.github.bigswlittlesw.homelight.discovery.CandidateDiscovery;
import io.github.bigswlittlesw.homelight.discovery.CandidateObservation;
import io.github.bigswlittlesw.homelight.discovery.SetupDiscoveryFixture;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;

class CandidateSetupTest {
    @TempDir Path temporary;

    @Test void discardBothCopyMatchesThePlannedDeletionsAndEmptyTargetAtBothSizes() throws Exception {
        var root = fixture();
        var source = root.resolve("home/team-cache");
        var target = Files.createDirectories(root.resolve("local/team-cache"));
        Files.writeString(target.resolve("payload"), "target unchanged");
        var app = new HomeLightApp(new HomeLightSession(root.resolve("config.yaml")));
        key(app, 'i'); locations(app, root, root.resolve("shared.yaml"));
        key(app, 'a'); type(app, "team-cache"); down(app); down(app);
        for (int i = 0; i < 4; i++) key(app, ' ');
        for (int width : List.of(80, 120, 80, 120)) {
            int height = width == 80 ? 24 : 30;
            var details = WorkspaceViewTest.render(app.render(), width, height);
            assertTrue(details.contains("❯ Both directories: Discard both"), details);
            var text = details.replaceAll("[│█\\s]", "");
            assertTrue(text.contains("Permanentlydeletebothsourceandtargetdirectorytrees.Createanemptytargetdirectoryandlinkthesourcetoit."), details);
            assertFalse(details.contains("Discard target") || details.contains("relocate source"), details);
            escape(app);
            var table = WorkspaceViewTest.render(app.render(), width, height);
            assertTrue(table.contains("Policies") && table.contains("Discard both"), table);
            assertFalse(table.contains("Discard target"), table);
            enter(app); down(app); down(app);
        }
        escape(app); key(app, 's');
        var plan = assertInstanceOf(PlanModel.Configured.class, app.session().planModel());
        assertEquals(List.of(
                new ReconciliationAction.DeleteDirectory(source),
                new ReconciliationAction.DeleteDirectory(target),
                new ReconciliationAction.EnsureDirectory(target.getParent()),
                new ReconciliationAction.CreateDirectory(target),
                new ReconciliationAction.EnsureDirectory(source.getParent()),
                new ReconciliationAction.CreateSymlink(source, target)),
                plan.items().getFirst().plan().actions());
        assertEquals("unchanged", Files.readString(source.resolve("payload")));
        assertEquals("target unchanged", Files.readString(target.resolve("payload")));
        assertInstanceOf(ApplyModel.Idle.class, app.session().applyModel());
    }

    @Test void browseAddEditRefreshRemovalAndSavePreserveChoicesAndHistory() throws Exception {
        var root = fixture();
        try (var workers = new SetupDiscoveryFixture()) {
            var app = app(root, workers);
            locations(app, root, root.resolve("shared.yaml"));
            key(app, 'b'); await(workers, app);
            var list = render(app);
            assertTrue(list.contains("Maven (1)"), list);
            assertTrue(list.contains("Mixed advice"), list);
            assertTrue(list.contains("1 usually-unnecessary directory hidden"), list);
            choose(app, "team-cache"); enter(app);
            key(app, 'a');
            assertTrue(render(app).contains("e: Edit draft row"));
            key(app, 'e'); down(app); clear(app); type(app, "custom-target");
            down(app); key(app, ' '); key(app, ' '); // Adopt target, no inferred source disposition.
            escape(app); key(app, 'b');
            Files.copy(Path.of("docs/research/session-b-fixtures/nested/shared-refreshed.yaml"), root.resolve("shared.yaml"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            key(app, 'r'); await(workers, app);
            // Refresh while inspecting does not leave details or erase the row.
            var history = all(app);
            assertTrue(history.contains("Historical attribution"), history);
            assertTrue(history.contains("custom-target"), history);
            key(app, 'e');
            assertTrue(all(app).contains("Adopt target"));
            escape(app); key(app, 's');
            assertTrue(render(app).contains("[1: Workspace]"));
            var saved = new ConfigurationLoader().load(root.resolve("config.yaml"));
            assertEquals(1, saved.relocations().size());
            assertEquals(root.resolve("local/custom-target"), saved.relocations().getFirst().targetPath());
            assertEquals(Optional.of(root.resolve("shared.yaml")), saved.sharedList());
            assertEquals("unchanged", Files.readString(root.resolve("home/team-cache/payload")));
            assertFalse(Files.exists(root.resolve("local/custom-target")));
            assertInstanceOf(ApplyModel.Idle.class, app.session().applyModel());
            assertTrue(workers.workers.getFirst().snapshot().request().isEmpty());
        }
    }

    @Test void groupExpansionNeverSelectsAndAdviceCollapseKeepsAddedRowsVisible() throws Exception {
        var root = fixture();
        try (var workers = new SetupDiscoveryFixture()) {
            var app = app(root, workers); locations(app, root, root.resolve("shared.yaml"));
            key(app, 'b'); await(workers, app);
            enter(app);
            assertFalse(render(app).contains("[ ] .m2"));
            enter(app);
            key(app, 'u');
            choose(app, ".cache/example"); enter(app); key(app, 'a'); escape(app);
            key(app, 'u');
            assertTrue(all(app).contains("[x] .cache/example"));
            // No action on a heading may create rows.
            escape(app); key(app, 's');
            var saved = new ConfigurationLoader().load(root.resolve("config.yaml"));
            assertEquals(List.of(root.resolve("home/.cache/example")), saved.relocations().stream().map(Relocation::sourcePath).toList());
        }
    }

    @Test void checklistAddsInPlaceWithoutReorderingAndKeepsRejectedChoices() throws Exception {
        var root = fixture();
        try (var workers = new SetupDiscoveryFixture()) {
            var app = app(root, workers); locations(app, root, root.resolve("shared.yaml"));
            key(app, 'b'); await(workers, app);
            key(app, ' '); key(app, 'a');
            assertTrue(render(app).contains("0 in draft"), "App headings cannot add children");
            choose(app, ".m2");
            var before = render(app);
            key(app, ' ');
            var added = render(app);
            assertTrue(added.contains("Browse candidates") && added.contains("❯   [x] .m2"), added);
            assertFalse(added.contains("Candidate details") || added.contains("Space/a: Add"), added);
            assertEquals(before.indexOf("Maven (1)"), added.indexOf("Maven (1)"));
            key(app, ' '); key(app, 'a');
            assertTrue(render(app).contains("1 in draft"));
            choose(app, ".local/share/uv"); key(app, 'a');
            choose(app, ".local/share/uv/tools"); key(app, ' ');
            var rejected = render(app);
            assertTrue(rejected.contains("Browse candidates") && rejected.contains("prior choices are unchanged"), rejected);
            assertTrue(rejected.contains("2 in draft"));
            enter(app);
            assertTrue(all(app).contains("paths overlap"));
            escape(app);
            choose(app, "absent-cache"); key(app, ' '); key(app, 'a');
            assertTrue(render(app).contains("3 in draft"));
            choose(app, ".m2"); key(app, 'e');
            assertTrue(render(app).contains("Edit relocation 1"));
            escape(app); key(app, 's');
            assertEquals(3, new ConfigurationLoader().load(root.resolve("config.yaml")).relocations().size());
        }
    }

    @Test void missingCandidateCanBeAddedEditedRefreshedAndSavedBeforeTheAppCreatesIt() throws Exception {
        var root = fixture();
        try (var workers = new SetupDiscoveryFixture()) {
            var app = app(root, workers); locations(app, root, root.resolve("shared.yaml"));
            key(app, 'b'); await(workers, app); choose(app, "absent-cache");
            assertTrue(render(app).contains("Space/a: Add"));
            assertTrue(render(app).contains("[ ] absent-cache"));
            assertFalse(render(app).contains("Missing") || render(app).contains("Not created yet"));
            enter(app);
            var details = all(app);
            assertTrue(details.contains("Metadata: Not created yet"), details);
            assertTrue(details.contains("Not found under the source root"), details);
            assertTrue(details.contains("source and target are both missing"), details);
            assertTrue(details.contains("If only the target exists"), details);
            assertTrue(details.contains("Save writes configuration only"), details);
            escape(app); key(app, ' ');
            assertTrue(render(app).contains("❯   [x] absent-cache"));
            assertFalse(render(app).contains("Missing") || render(app).contains("Not created yet"));
            key(app, 'e'); down(app); clear(app); type(app, "future-cache");
            down(app); down(app); key(app, ' '); key(app, ' ');
            escape(app); key(app, 'b'); key(app, 'r'); await(workers, app);
            assertTrue(render(app).contains("❯   [x] absent-cache"));
            enter(app);
            assertTrue(all(app).contains("future-cache"));
            escape(app); escape(app); key(app, 's');
            assertTrue(render(app).contains("[1: Workspace]"));
            var row = new ConfigurationLoader().load(root.resolve("config.yaml")).relocations().getFirst();
            assertEquals(root.resolve("home/absent-cache"), row.sourcePath());
            assertEquals(root.resolve("local/future-cache"), row.targetPath());
            assertEquals(Optional.of(io.github.bigswlittlesw.homelight.config.WhenOnlyTargetExists.ADOPT_TARGET), row.whenOnlyTargetExists());
            assertFalse(Files.exists(row.sourcePath()));
            assertFalse(Files.exists(root.resolve("local")));
            assertInstanceOf(ApplyModel.Idle.class, app.session().applyModel());
        }
    }

    @Test void overlapRejectionAndManualMatchesDoNotLoseEarlierRows() throws Exception {
        var root = fixture();
        try (var workers = new SetupDiscoveryFixture()) {
            var app = app(root, workers); locations(app, root, root.resolve("shared.yaml"));
            key(app, 'a'); type(app, ".m2"); down(app); clear(app); type(app, "manual-target"); escape(app);
            key(app, 'b'); await(workers, app); choose(app, ".m2"); enter(app);
            assertTrue(render(app).contains("e: Edit draft row"));
            assertFalse(render(app).contains("a: Add to draft"));
            key(app, 'a'); escape(app);
            choose(app, ".local/share/uv"); enter(app); key(app, 'a'); escape(app);
            choose(app, ".local/share/uv/tools"); enter(app); key(app, 'a');
            var error = all(app);
            // all() joins wrapped lines without the wrap-point space, and where the message wraps depends on the temp path length.
            var text = error.replaceAll("\\s", "");
            assertTrue(text.contains("Notadded."), error);
            assertTrue(text.contains("Priorchoicesareunchanged"), error);
            assertTrue(text.contains("pathsoverlap"), error);
            escape(app); escape(app); key(app, 's');
            var saved = new ConfigurationLoader().load(root.resolve("config.yaml"));
            assertEquals(2, saved.relocations().size());
            assertEquals(root.resolve("local/manual-target"), saved.relocations().getFirst().targetPath());
        }
    }

    @Test void stalledReadAllowsManualEditingFailedAndSuccessfulSaveAndIgnoresLateCompletion() throws Exception {
        var root = fixture();
        try (var workers = new SetupDiscoveryFixture()) {
            workers.block = true;
            var app = app(root, workers); locations(app, root, root.resolve("shared.yaml")); key(app, 'b');
            assertTrue(workers.entered.await(2, TimeUnit.SECONDS));
            assertTimeout(Duration.ofSeconds(1), () -> {
                workers.expire(); render(app); key(app, 'r'); key(app, 'i');
                assertTrue(all(app).contains("Previous read still pending"));
                escape(app); escape(app); key(app, 'a'); type(app, "manual"); escape(app);
                Files.writeString(root.resolve("config.yaml"), "concurrent winner");
                key(app, 's'); assertTrue(all(app).contains("Save failed"));
                assertEquals("concurrent winner", Files.readString(root.resolve("config.yaml")));
            });
            Files.delete(root.resolve("config.yaml"));
            assertTimeout(Duration.ofSeconds(1), () -> key(app, 's'));
            assertTrue(render(app).contains("[1: Workspace]"));
            assertTrue(workers.workers.getFirst().snapshot().request().isEmpty());
            workers.release.countDown();
            assertTrue(render(app).contains("[1: Workspace]"));
            assertEquals(1, workers.reads.get());
            assertEquals(1, new ConfigurationLoader().load(root.resolve("config.yaml")).relocations().size());
        }
    }

    @Test void discardCancelReopenAndRootLocationEditsRejectObsoleteResults() throws Exception {
        var root = fixture();
        try (var workers = new SetupDiscoveryFixture()) {
            workers.block = true;
            var app = app(root, workers); locations(app, root, root.resolve("shared.yaml")); key(app, 'b');
            assertTrue(workers.entered.await(2, TimeUnit.SECONDS));
            escape(app); key(app, 'a'); type(app, "manual"); escape(app);
            key(app, 'q'); escape(app);
            assertTrue(render(app).contains("manual"));
            key(app, 'e'); clear(app); type(app, root.resolve("other-home").toString()); down(app); down(app); clear(app); enter(app);
            key(app, 'b');
            workers.release.countDown(); await(workers, app);
            assertFalse(all(app).contains("team-cache"));
            escape(app); key(app, 'q'); enter(app);
            assertTrue(workers.workers.getFirst().snapshot().request().isEmpty());
            key(app, 'i');
            assertTrue(render(app).contains("Storage locations"));
            assertFalse(render(app).contains("other-home"));
            assertFalse(render(app).contains("shared.yaml"));
            enter(app); assertTrue(render(app).contains("No relocations yet"));
            assertFalse(Files.exists(root.resolve("config.yaml")));
            app.closeSetup();
        }
    }

    @Test void malformedAndMissingSourcesLeaveBundledAndManualSaveAvailable() throws Exception {
        for (boolean missing : List.of(false, true)) {
            var root = fixture();
            if (missing) Files.delete(root.resolve("shared.yaml"));
            else Files.writeString(root.resolve("shared.yaml"), "apps: [");
            try (var workers = new SetupDiscoveryFixture()) {
                var app = app(root, workers); locations(app, root, root.resolve("shared.yaml")); key(app, 'b'); await(workers, app);
                assertTrue(render(app).contains("Bundled: current · Shared: unavailable"));
                key(app, 'i'); assertTrue(all(app).contains(missing ? "NoSuchFileException" : "line"));
                escape(app); escape(app); key(app, 'a'); type(app, "manual"); escape(app); key(app, 's');
                assertTrue(render(app).contains("[1: Workspace]"));
            }
        }
    }

    @Test void configuredRowsAreInspectionOnlyAndTextIsEscaped() throws Exception {
        var root = fixture();
        var empty = new CandidateBrowser();
        var emptyText = WorkspaceViewTest.render(empty.render(new SetupDraft(root.resolve("home"), root.resolve("local"), Optional.empty(), List.of())), 80, 24);
        assertFalse(emptyText.contains("Enter:") || emptyText.contains("a: Add"), emptyText);
        var relocation = new Relocation(root.resolve("home/.m2"), root.resolve("local/saved"),
                Optional.of(io.github.bigswlittlesw.homelight.config.WhenSourceAndTargetDirectoriesExist.DISCARD),
                Optional.empty(), Optional.empty(), Optional.empty());
        var draft = new SetupDraft(root.resolve("home"), root.resolve("local"), Optional.empty(), List.of(relocation));
        var browser = new CandidateBrowser();
        WorkspaceViewTest.render(browser.render(draft), 80, 24);
        browser.key(KeyEvent.ofChar('j'), draft); browser.key(KeyEvent.ofKey(KeyCode.ENTER), draft);
        var text = WorkspaceViewTest.render(browser.render(draft), 120, 30);
        assertTrue(text.contains("Configured"));
        assertTrue(text.contains("Saved target:"));
        assertTrue(text.contains("both directories: Discard both"), text);
        assertFalse(text.contains("a: Add") || text.contains("e: Edit"));
        browser.key(KeyEvent.ofChar('a'), draft); browser.key(KeyEvent.ofChar('e'), draft);
        assertTrue(draft.rows().isEmpty());
        assertEquals("hello\\u001b[2J\\u000aworld", CandidateBrowser.literal("hello\u001b[2J\nworld"));
    }

    @Test void arrivingResultsDoNotStealPathFocusOrEraseActiveRowText() throws Exception {
        var root = fixture();
        try (var workers = new SetupDiscoveryFixture()) {
            workers.block = true;
            var app = app(root, workers); locations(app, root, root.resolve("shared.yaml")); key(app, 'b');
            assertTrue(workers.entered.await(2, TimeUnit.SECONDS));
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!render(app).contains("[ ] .m2") && System.nanoTime() < until) LockSupport.parkNanos(1_000_000);
            choose(app, ".m2"); enter(app); key(app, 'a'); key(app, 'e');
            down(app); clear(app); type(app, "unfinished-target");
            workers.release.countDown(); await(workers, app);
            var editing = render(app);
            assertTrue(editing.contains("❯ Target path: unfinished-target"), editing);
            type(app, "-continued"); escape(app); key(app, 'b');
            assertTrue(all(app).contains("unfinished-target-continued"));
            assertTrue(render(app).contains("Candidate details"));
            escape(app);
            assertTrue(render(app).lines().anyMatch(line -> line.contains("❯   [x] .m2")));
            app.closeSetup();
        }
    }

    @Test void lateCompletionAfterConfirmedDiscardCannotResurrectSetup() throws Exception {
        var root = fixture();
        try (var workers = new SetupDiscoveryFixture()) {
            workers.block = true;
            var app = app(root, workers); locations(app, root, root.resolve("shared.yaml")); key(app, 'b');
            assertTrue(workers.entered.await(2, TimeUnit.SECONDS));
            key(app, 'q'); enter(app);
            workers.release.countDown();
            for (int i = 0; i < 20; i++) {
                assertFalse(render(app).contains("[Setup]"));
                LockSupport.parkNanos(1_000_000);
            }
            assertTrue(workers.workers.getFirst().snapshot().request().isEmpty());
            key(app, 'i'); enter(app);
            assertTrue(render(app).contains("No relocations yet"));
            assertFalse(Files.exists(root.resolve("config.yaml")));
            app.closeSetup();
        }
    }

    @Test void explicitRootChangeRebasesRowsWithoutChangingTargetsOrPolicies() throws Exception {
        var root = fixture();
        try (var workers = new SetupDiscoveryFixture()) {
            var app = app(root, workers); locations(app, root, root.resolve("shared.yaml"));
            key(app, 'a'); type(app, "manual"); down(app); clear(app); type(app, "chosen-target");
            down(app); key(app, ' '); key(app, ' '); escape(app);
            key(app, 'e'); clear(app); type(app, root.resolve("other-home").toString());
            down(app); clear(app); type(app, root.resolve("other-target").toString());
            down(app); clear(app); enter(app); key(app, 's');
            var configuration = new ConfigurationLoader().load(root.resolve("config.yaml"));
            var row = configuration.relocations().getFirst();
            assertEquals(root.resolve("other-home/manual"), row.sourcePath());
            assertEquals(root.resolve("other-target/chosen-target"), row.targetPath());
            assertEquals(Optional.of(io.github.bigswlittlesw.homelight.config.WhenSourceAndTargetDirectoriesExist.ADOPT), row.whenSourceAndTargetDirectoriesExist());
            assertTrue(configuration.sharedList().isEmpty());
        }
    }

    private Path fixture() throws Exception {
        var root = Files.createTempDirectory(temporary, "fixture-").toRealPath();
        for (var relative : List.of(".m2", ".cache/uv", ".cache/example", ".local/share/uv/tools", "team-cache", "datasets")) Files.createDirectories(root.resolve("home").resolve(relative));
        Files.writeString(root.resolve("home/team-cache/payload"), "unchanged");
        Files.copy(Path.of("docs/research/session-b-fixtures/nested/shared.yaml"), root.resolve("shared.yaml"));
        return root;
    }
    private static HomeLightApp app(Path root, SetupDiscoveryFixture workers) {
        var app = new HomeLightApp(new HomeLightSession(root.resolve("config.yaml")), workers); key(app, 'i'); return app;
    }
    private static void locations(HomeLightApp app, Path root, Path shared) {
        clear(app); type(app, root.resolve("home").toString()); down(app); type(app, root.resolve("local").toString());
        down(app); type(app, shared.toString()); enter(app);
    }
    private static void await(SetupDiscoveryFixture workers, HomeLightApp app) {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (System.nanoTime() < until) {
            render(app);
            var result = workers.workers.getLast().snapshot();
            if (result.sources().stream().noneMatch(s -> s.status() == CandidateDiscovery.SourceStatus.PENDING)
                    && result.candidates().stream().noneMatch(c -> c.observation().kind() == CandidateObservation.Kind.PENDING)) return;
            LockSupport.parkNanos(1_000_000);
        }
        fail("Discovery did not settle");
    }
    private static void choose(HomeLightApp app, String relative) {
        app.handleKeyEvent(KeyEvent.ofKey(KeyCode.HOME));
        for (int i = 0; i < 100; i++) {
            if (render(app).lines().anyMatch(line -> line.matches(".*❯   (\\[.\\]| − ) " + java.util.regex.Pattern.quote(relative) + "(?: +.*|│.*)"))) return;
            key(app, 'j');
        }
        fail("Could not focus " + relative + "\n" + render(app));
    }
    private static String all(HomeLightApp app) {
        var screens = new java.util.LinkedHashSet<String>();
        screens.add(render(app));
        for (int i = 0; i < 80; i++) { key(app, ']'); screens.add(render(app)); }
        for (int i = 0; i < 80; i++) key(app, '[');
        return String.join("\n", screens) + screens.stream().flatMap(String::lines)
                .filter(line -> line.startsWith("│")).map(line -> line.substring(1).replace("│", "").replace("█", "").stripTrailing())
                .collect(java.util.stream.Collectors.joining());
    }
    private static String render(HomeLightApp app) {
        try { return WorkspaceViewTest.render(app.render(), 120, 30); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private static void key(HomeLightApp app, char key) { app.handleKeyEvent(KeyEvent.ofChar(key)); }
    private static void type(HomeLightApp app, String value) { value.chars().forEach(c -> key(app, (char) c)); }
    private static void clear(HomeLightApp app) { key(app, '\u0015'); }
    private static void down(HomeLightApp app) { app.handleKeyEvent(KeyEvent.ofKey(KeyCode.DOWN)); }
    private static void enter(HomeLightApp app) { app.handleKeyEvent(KeyEvent.ofKey(KeyCode.ENTER)); }
    private static void escape(HomeLightApp app) { app.handleKeyEvent(KeyEvent.ofKey(KeyCode.ESCAPE)); }
}
