package io.github.bigswlittlesw.homelight.discovery;

import io.github.bigswlittlesw.homelight.application.HomeLightSession;
import io.github.bigswlittlesw.homelight.application.SetupDraft;
import io.github.bigswlittlesw.homelight.config.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;

class SetupDraftTest {
    @TempDir Path temporary;

    @Test void joinsNormalizedConfiguredAndManualSourcesWithoutChangingThem() throws Exception {
        var root = Files.createDirectory(temporary.resolve("home"));
        Files.createDirectories(root.resolve(".m2"));
        Files.createDirectories(root.resolve("datasets"));
        var configured = new Relocation(root.resolve("x/../.m2"), temporary.resolve("custom/maven"),
                Optional.of(WhenSourceAndTargetDirectoriesExist.ADOPT), Optional.empty(), Optional.empty(), Optional.empty());
        var outside = new Relocation(temporary.resolve("outside"), temporary.resolve("custom/outside"));
        var draft = draft(root, List.of(configured, outside));
        var manual = new SetupDraft.Row("./datasets", "my-data");
        draft.append(manual);
        try (var worker = worker()) {
            refresh(draft, worker);
            var maven = entry(draft, root.resolve(".m2"));
            assertSame(configured, maven.configured().orElseThrow());
            assertEquals(2, maven.discovery().orElseThrow().catalog().definitions().size());
            assertTrue(entry(draft, outside.sourcePath()).outsideRoot());
            assertSame(manual, entry(draft, root.resolve("datasets")).draft().orElseThrow());
            assertEquals(12, draft.entries().size()); // 11 catalog identities plus the outside row
            assertEquals(List.of(configured, outside, new Relocation(root.resolve("datasets"),
                    temporary.resolve("target/my-data"))), draft.validate().relocations());
            assertThrows(IllegalArgumentException.class, () -> draft.add(root.resolve(".m2")));
            assertThrows(IllegalArgumentException.class, () -> draft.add(root.resolve("datasets")));
            assertEquals(List.of(manual), draft.rows());
        }
    }

    @Test void refreshFailureRemovalAdviceAndStateChangesPreserveDraftAndReviewedPlan() throws Exception {
        var root = Files.createDirectory(temporary.resolve("home"));
        Files.createDirectories(root.resolve("team-cache"));
        Files.createDirectories(root.resolve(".cache/uv"));
        Files.createDirectories(root.resolve(".m2"));
        var config = temporary.resolve("existing.yaml");
        var configured = new Relocation(root.resolve(".m2"), temporary.resolve("saved/maven"));
        new ConfigurationPublisher().saveNew(config, new ConfigurationDraft(temporary.resolve("saved"), List.of(configured)));
        var bytes = Files.readAllBytes(config);
        var session = new HomeLightSession(config);
        assertTrue(session.requestApply());
        var plan = session.planModel();
        var review = session.applyModel();
        var draft = draft(root, List.of(configured));
        try (var worker = worker()) {
            refresh(draft, worker);
            draft.add(root.resolve("team-cache"));
            draft.add(root.resolve(".cache/uv"));
            var edited = new SetupDraft.Row("team-cache", "custom-team", Optional.empty(),
                    Optional.of(WhenOnlyTargetExists.ADOPT_TARGET), Optional.empty(), Optional.empty());
            draft.edit(0, edited);
            var savedRows = draft.rows();
            Files.writeString(shared(), "directories: [");
            refresh(draft, worker);
            assertTrue(entry(draft, root.resolve("team-cache")).discovery().orElseThrow().observation().stale());
            assertEquals(savedRows, draft.rows());
            Files.copy(fixture("shared-refreshed"), shared(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.delete(root.resolve(".cache/uv"));
            refresh(draft, worker);
            assertSame(edited, draft.rows().getFirst());
            var removed = entry(draft, root.resolve("team-cache"));
            assertTrue(removed.discovery().isEmpty());
            assertFalse(removed.lastKnownDefinitions().isEmpty());
            assertTrue(entry(draft, root.resolve("new-cache")).draft().isEmpty());
            assertEquals(CandidateObservation.Kind.MISSING,
                    entry(draft, root.resolve(".cache/uv")).discovery().orElseThrow().observation().kind());
            assertEquals(savedRows, draft.rows());
            assertEquals(2, entry(draft, root.resolve(".cache/uv")).discovery().orElseThrow().catalog().definitions().size());
            assertEquals(savedRows, draft.rows());
            assertArrayEquals(bytes, Files.readAllBytes(config));
            assertSame(plan, session.planModel());
            assertEquals(review, session.applyModel());
            assertSame(((io.github.bigswlittlesw.homelight.application.PlanModel.Configured) plan).plan(),
                    ((io.github.bigswlittlesw.homelight.application.ApplyModel.Confirmation) session.applyModel()).plan());
        }
    }

    @Test void staleSourceAdviceDoesNotPreventAddAfterFreshMetadataButOldMetadataDoes() throws Exception {
        var root = Files.createDirectory(temporary.resolve("home"));
        Files.createDirectories(root.resolve("team-cache"));
        Files.createDirectories(root.resolve(".cache/uv"));
        var draft = draft(root, List.of());
        try (var worker = worker()) {
            refresh(draft, worker);
            Files.writeString(shared(), "directories: [");
            refresh(draft, worker);
            assertThrows(IllegalArgumentException.class, () -> draft.add(root.resolve("team-cache")));
            draft.add(root.resolve(".cache/uv"));
            assertEquals(List.of(new SetupDraft.Row(".cache/uv", ".cache/uv")), draft.rows());
        }
    }

    @Test void explicitAddOmitsPoliciesAndRejectsNestedOrDuplicateSelections() throws Exception {
        var root = Files.createDirectory(temporary.resolve("home"));
        Files.createDirectories(root.resolve(".local/share/uv/tools"));
        var draft = draft(root, List.of());
        try (var worker = worker()) {
            refresh(draft, worker);
            assertTrue(draft.rows().isEmpty());
            draft.add(root.resolve(".local/share/uv"));
            var chosen = draft.rows();
            assertEquals(List.of(new SetupDraft.Row(".local/share/uv", ".local/share/uv")), chosen);
            assertThrows(IllegalArgumentException.class, () -> draft.add(root.resolve(".local/share/uv/tools")));
            assertThrows(IllegalArgumentException.class, () -> draft.add(root.resolve(".local/share/uv")));
            assertEquals(chosen, draft.rows());
            draft.remove(0);
            draft.add(root.resolve(".local/share/uv/tools"));
            assertThrows(IllegalArgumentException.class, () -> draft.add(root.resolve(".local/share/uv")));
            assertEquals(1, draft.rows().size());
        }
    }

    @Test void manualInvalidRowsRemainVisibleButEntireProposedConfigurationMustValidate() throws Exception {
        var root = Files.createDirectory(temporary.resolve("home"));
        var first = new SetupDraft.Row("a", "a");
        for (var second : List.of(new SetupDraft.Row("./a", "b"), new SetupDraft.Row("a/child", "b"),
                new SetupDraft.Row("b", "a"), new SetupDraft.Row("b", "a/child"),
                new SetupDraft.Row("b", ""), new SetupDraft.Row("b", "."), new SetupDraft.Row("b", "../escape"))) {
            var draft = draft(root, List.of());
            draft.append(first);
            draft.append(second);
            assertEquals(2, draft.entries().size());
            assertThrows(IllegalArgumentException.class, draft::validate);
            assertThrows(IllegalArgumentException.class, () -> new ConfigurationPublisher().saveNew(
                    temporary.resolve("invalid.yaml"), draft.validate()));
            assertEquals(List.of(first, second), draft.rows());
            assertFalse(Files.exists(temporary.resolve("invalid.yaml")));
        }
        // Cross source/target intersections and cycles are checked across configured and draft rows.
        var configured = new Relocation(root.resolve("a"), temporary.resolve("target/b"));
        var cross = new SetupDraft(root, root, Optional.empty(), List.of(configured));
        cross.append(new SetupDraft.Row("c", "a"));
        assertThrows(IllegalArgumentException.class, cross::validate);
        var cycle = new SetupDraft(temporary.resolve("target"), root, Optional.empty(), List.of(configured));
        cycle.append(new SetupDraft.Row("b", "a"));
        assertThrows(IllegalArgumentException.class, cycle::validate);
    }

    @Test void rootAndLocationEditsRejectOldResultsEvenAfterReturningToTheOldRoot() throws Exception {
        var root = Files.createDirectory(temporary.resolve("home"));
        Files.createDirectories(root.resolve("team-cache"));
        var other = Files.createDirectory(temporary.resolve("other"));
        var draft = draft(root, List.of());
        try (var worker = worker()) {
            var old = refresh(draft, worker);
            draft.add(root.resolve("team-cache"));
            var rows = draft.rows();
            draft.roots(other, temporary.resolve("new-target"));
            assertFalse(draft.accept(old));
            assertTrue(draft.entries().getFirst().discovery().isEmpty());
            assertEquals(other.resolve("team-cache"), draft.validate().relocations().getFirst().sourcePath());
            assertEquals(temporary.resolve("new-target/team-cache"), draft.validate().relocations().getFirst().targetPath());
            refresh(draft, worker);
            draft.roots(root, temporary.resolve("target"));
            assertFalse(draft.accept(old));
            refresh(draft, worker);
            assertFalse(draft.accept(old));
            draft.sharedList("");
            assertFalse(draft.accept(old));
            assertEquals(rows, draft.rows());
            assertTrue(draft.validate().sharedList().isEmpty());
            assertFalse(draft.entries().getFirst().lastKnownDefinitions().isEmpty());
        }
    }

    @Test void blockedSharedReadAllowsManualAddSaveAndEditingAfterFailure() throws Exception {
        var root = Files.createDirectory(temporary.resolve("home"));
        var cache = Files.createDirectory(root.resolve("manual"));
        Files.writeString(cache.resolve("data"), "keep");
        var link = Files.createSymbolicLink(root.resolve("link"), Path.of("manual"));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var clock = new AtomicLong();
        var draft = draft(root, List.of());
        try (var worker = new CandidateDiscovery(new CandidateDiscovery.Lanes(), clock::get, path -> {
            entered.countDown();
            try { release.await(); } catch (InterruptedException exception) { throw new AssertionError(exception); }
            return Files.readAllBytes(shared());
        }, r -> new CandidateParser().parse(CandidateCatalog.BUNDLED, r, "directories: []".getBytes()), new CandidateMetadata())) {
            draft.refresh(worker);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            clock.set(5_000_000_000L);
            assertTrue(draft.accept(worker.snapshot()));
            draft.append(new SetupDraft.Row("manual", "manual"));
            var blocked = Files.writeString(temporary.resolve("blocked"), "occupied");
            assertThrows(ConfigurationPublisher.ConfigurationException.class, () ->
                    new ConfigurationPublisher().saveNew(blocked.resolve("config.yaml"), draft.validate()));
            draft.edit(0, new SetupDraft.Row("manual", "edited"));
            var config = temporary.resolve("saved.yaml");
            new ConfigurationPublisher().saveNew(config, draft.validate());
            var loaded = new ConfigurationLoader().load(config);
            assertEquals(Optional.of(shared()), loaded.sharedList());
            assertEquals(temporary.resolve("target/edited"), loaded.relocations().getFirst().targetPath());
            assertEquals("keep", Files.readString(cache.resolve("data")));
            assertEquals(Path.of("manual"), Files.readSymbolicLink(link));
            assertFalse(Files.exists(temporary.resolve("target")));
            assertEquals(1, draft.rows().size());
            draft.sharedList("");
            release.countDown();
            assertFalse(draft.accept(worker.snapshot()));
        } finally { release.countDown(); }
    }

    @Test void addRejectsTargetAndCrossIntersectionsWithoutChangingRows() throws Exception {
        var root = Files.createDirectory(temporary.resolve("home"));
        Files.createDirectories(root.resolve("team-cache"));
        for (var target : List.of(temporary.resolve("target/team-cache"), temporary.resolve("target/team-cache/child"),
                root.resolve("team-cache/child"))) {
            var configured = new Relocation(root.resolve("existing"), target);
            var draft = draft(root, List.of(configured));
            draft.append(new SetupDraft.Row("manual", "custom"));
            try (var worker = worker()) {
                refresh(draft, worker);
                var before = draft.rows();
                assertThrows(IllegalArgumentException.class, () -> draft.add(root.resolve("team-cache")));
                assertEquals(before, draft.rows());
            }
        }
    }

    @Test void adviceDoesNotPreventAddAndOnlyExplicitRowsArePublished() throws Exception {
        var root = Files.createDirectory(temporary.resolve("home"));
        Files.createDirectories(root.resolve(".cache/example"));
        Files.createDirectories(root.resolve("datasets"));
        Files.createDirectories(root.resolve("manual"));
        Files.writeString(root.resolve("manual/data"), "unchanged");
        var draft = draft(root, List.of());
        draft.append(new SetupDraft.Row("manual", "my-manual"));
        try (var worker = worker()) {
            refresh(draft, worker);
            draft.add(root.resolve(".cache/example")); // usually-unnecessary is informational
            draft.add(root.resolve("absent-cache"));
            Files.delete(shared());
            refresh(draft, worker);
            var path = temporary.resolve("chosen.yaml");
            new ConfigurationPublisher().saveNew(path, draft.validate());
            var loaded = new ConfigurationLoader().load(path);
            assertEquals(draft.validate().relocations(), loaded.relocations());
            assertEquals(3, loaded.relocations().size());
            assertTrue(loaded.relocations().stream().allMatch(r -> r.whenSourceAndTargetDirectoriesExist().isEmpty()
                    && r.whenOnlyTargetExists().isEmpty() && r.whenAdoptingTarget().isEmpty()));
            assertEquals(Optional.of(shared()), loaded.sharedList());
            assertEquals("unchanged", Files.readString(root.resolve("manual/data")));
            assertFalse(Files.exists(temporary.resolve("target")));
            var yaml = Files.readString(path);
            for (var forbidden : List.of("advice", "usually-unnecessary", "Example IDE", "observations", "provenance", "datasets")) {
                assertFalse(yaml.contains(forbidden), yaml);
            }
        }
    }

    private SetupDraft draft(Path root, List<Relocation> configured) throws Exception {
        if (!Files.exists(shared())) Files.copy(fixture("shared"), shared());
        return new SetupDraft(root, temporary.resolve("target"), Optional.of(shared()), configured);
    }

    @Test void onlyCurrentDirectoryOrMissingObservationsAllowAddition() throws Exception {
        var root = Files.createDirectory(temporary.resolve("home"));
        var draft = draft(root, List.of());
        var path = root.resolve("absent-cache");
        try (var worker = worker()) {
            var result = refresh(draft, worker);
            var candidate = entry(draft, path).discovery().orElseThrow();
            assertEquals(CandidateObservation.Kind.MISSING, candidate.observation().kind());
            for (var kind : CandidateObservation.Kind.values()) for (boolean current : List.of(true, false)) {
                var observation = new CandidateObservation(path, kind, Optional.empty(),
                        result.generation() - (current ? 0 : 1), java.time.Instant.now(), !current, List.of());
                assertTrue(draft.accept(new CandidateDiscovery.Result(result.generation(), result.request(), result.sources(),
                        List.of(new CandidateDiscovery.Candidate(candidate.catalog(), observation, candidate.ancestors())), result.rootFailure())));
                boolean eligible = current && (kind == CandidateObservation.Kind.DIRECTORY || kind == CandidateObservation.Kind.MISSING);
                assertEquals(eligible, draft.canAdd(entry(draft, path)), kind + " current=" + current);
                if (eligible) {
                    draft.add(path);
                    assertFalse(draft.canAdd(entry(draft, path)));
                    assertThrows(IllegalArgumentException.class, () -> draft.add(path));
                    assertEquals(List.of(new SetupDraft.Row("absent-cache", "absent-cache")), draft.rows());
                    draft.remove(0);
                } else assertThrows(IllegalArgumentException.class, () -> draft.add(path));
                assertTrue(draft.rows().isEmpty());
            }
            assertTrue(draft.accept(result));
            var oldEntry = entry(draft, path);
            draft.roots(root, temporary.resolve("target"));
            assertFalse(draft.accept(result));
            assertFalse(draft.canAdd(oldEntry));
        }
    }

    @Test void missingNestedPathsStillRejectOverlapWithoutCreatingDirectories() throws Exception {
        var root = Files.createDirectory(temporary.resolve("home"));
        var draft = draft(root, List.of());
        try (var worker = worker()) {
            refresh(draft, worker);
            draft.add(root.resolve(".local/share/uv"));
            assertThrows(IllegalArgumentException.class, () -> draft.add(root.resolve(".local/share/uv/tools")));
            assertEquals(List.of(new SetupDraft.Row(".local/share/uv", ".local/share/uv")), draft.rows());
            assertFalse(Files.exists(root.resolve(".local")));
            assertFalse(Files.exists(temporary.resolve("target")));
        }
    }

    @Test void duplicateOccurrencesKeepHistoryWhenEitherIsEditedOrRemoved() throws Exception {
        var root = Files.createDirectory(temporary.resolve("home"));
        for (boolean sameObject : List.of(false, true)) for (int index = 0; index < 2; index++) {
            for (boolean remove : List.of(false, true)) {
                Files.copy(fixture("shared"), shared(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                var draft = draft(root, List.of());
                var row = new SetupDraft.Row("team-cache", "team-cache");
                draft.append(row);
                draft.append(sameObject ? row : new SetupDraft.Row("team-cache", "team-cache"));
                try (var worker = worker()) {
                    refresh(draft, worker);
                    var history = draft.entries().getFirst().lastKnownDefinitions();
                    assertFalse(history.isEmpty());
                    Files.writeString(shared(), "directories: []");
                    refresh(draft, worker);
                    assertTrue(draft.entries().get(0).discovery().isEmpty());
                    assertTrue(draft.entries().get(1).discovery().isEmpty());
                    assertThrows(IllegalArgumentException.class, draft::validate);
                    var config = temporary.resolve("duplicates.yaml");
                    assertThrows(IllegalArgumentException.class,
                            () -> new ConfigurationPublisher().saveNew(config, draft.validate()));
                    assertFalse(Files.exists(config));
                    var before = draft.entries();
                    if (remove) draft.remove(index);
                    else draft.edit(index, new SetupDraft.Row("edited", "edited"));
                    refresh(draft, worker);
                    var selected = draft.entries().stream().filter(entry -> entry.draft().isPresent()).toList();
                    assertEquals(remove ? 1 : 2, selected.size());
                    selected.forEach(entry -> assertEquals(history, entry.lastKnownDefinitions()));
                    assertEquals(history, before.get(0).lastKnownDefinitions());
                    assertEquals(history, before.get(1).lastKnownDefinitions());
                    assertEquals(remove ? 1 : 2, draft.validate().relocations().size());
                }
            }
        }
    }

    @Test void appendingAnEqualRowAfterCandidateRemovalDoesNotInheritHistory() throws Exception {
        var root = Files.createDirectory(temporary.resolve("home"));
        for (boolean sameObject : List.of(false, true)) for (int index = 0; index < 2; index++) {
            Files.copy(fixture("shared"), shared(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            var draft = draft(root, List.of());
            var row = new SetupDraft.Row("team-cache", "team-cache");
            try (var worker = worker()) {
                refresh(draft, worker);
                draft.append(row);
                var history = draft.entries().getFirst().lastKnownDefinitions();
                assertFalse(history.isEmpty());
                Files.writeString(shared(), "directories: []");
                refresh(draft, worker);
                draft.append(sameObject ? row : new SetupDraft.Row("team-cache", "team-cache"));
                assertEquals(history, draft.entries().get(0).lastKnownDefinitions());
                assertTrue(draft.entries().get(1).lastKnownDefinitions().isEmpty());
                assertThrows(IllegalArgumentException.class, draft::validate);
                draft.edit(index, new SetupDraft.Row("edited", "edited"));
                refresh(draft, worker);
                assertEquals(history, draft.entries().get(0).lastKnownDefinitions());
                assertTrue(draft.entries().get(1).lastKnownDefinitions().isEmpty());
                draft.remove(index);
                assertEquals(index == 0 ? List.of() : history, draft.entries().getFirst().lastKnownDefinitions());
            }
        }
    }

    @Test void editingIntoAnotherRowsValuePreservesBothDistinctHistories() throws Exception {
        var root = Files.createDirectory(temporary.resolve("home"));
        for (int index = 0; index < 2; index++) {
            Files.writeString(shared(), "directories: [{path: first-cache}, {path: second-cache}]");
            var draft = draft(root, List.of());
            draft.append(new SetupDraft.Row("first-cache", "first-cache"));
            draft.append(new SetupDraft.Row("second-cache", "second-cache"));
            try (var worker = worker()) {
                refresh(draft, worker);
                var first = draft.entries().get(0).lastKnownDefinitions();
                var second = draft.entries().get(1).lastKnownDefinitions();
                assertFalse(first.isEmpty());
                assertFalse(second.isEmpty());
                assertNotEquals(first, second);
                Files.writeString(shared(), "directories: []");
                refresh(draft, worker);
                assertTrue(draft.entries().get(0).discovery().isEmpty());
                assertTrue(draft.entries().get(1).discovery().isEmpty());
                draft.edit(index, draft.rows().get(1 - index));
                assertSame(draft.rows().get(0), draft.rows().get(1));
                assertThrows(IllegalArgumentException.class, draft::validate);
                refresh(draft, worker);
                assertEquals(first, draft.entries().get(0).lastKnownDefinitions());
                assertEquals(second, draft.entries().get(1).lastKnownDefinitions());
                draft.remove(index);
                assertEquals(index == 0 ? second : first, draft.entries().getFirst().lastKnownDefinitions());
                assertEquals(1, draft.validate().relocations().size());
            }
        }
    }

    private CandidateDiscovery worker() throws Exception {
        var bundled = Files.readAllBytes(fixture("bundled"));
        return new CandidateDiscovery(new CandidateDiscovery.Lanes(), System::nanoTime, Files::readAllBytes,
                root -> new CandidateParser().parse(CandidateCatalog.BUNDLED, root, bundled), new CandidateMetadata());
    }

    private Path shared() { return temporary.resolve("shared.yaml"); }
    private static Path fixture(String name) { return Path.of("docs/research/session-b-fixtures/nested/" + name + ".yaml"); }
    private static SetupDraft.Entry entry(SetupDraft draft, Path path) {
        return draft.entries().stream().filter(row -> row.sourcePath().equals(Optional.of(path))).findFirst().orElseThrow();
    }

    private static CandidateDiscovery.Result refresh(SetupDraft draft, CandidateDiscovery worker) {
        draft.refresh(worker);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (true) {
            var result = worker.snapshot();
            var currentSources = result.sources().stream()
                    .filter(source -> source.status() == CandidateDiscovery.SourceStatus.CURRENT)
                    .map(CandidateDiscovery.SourceOutcome::source).toList();
            if (result.sources().stream().noneMatch(s -> s.status() == CandidateDiscovery.SourceStatus.PENDING)
                    && result.candidates().stream().noneMatch(c -> c.observation().kind() == CandidateObservation.Kind.PENDING)
                    && result.candidates().stream().filter(c -> c.catalog().definitions().stream()
                            .anyMatch(definition -> currentSources.contains(definition.source())))
                            .allMatch(c -> c.observation().generation() == result.generation())) {
                assertTrue(draft.accept(result));
                return result;
            }
            if (System.nanoTime() > deadline) fail("Discovery fixture did not finish");
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
    }
}
