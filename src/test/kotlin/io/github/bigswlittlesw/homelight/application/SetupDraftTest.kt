package io.github.bigswlittlesw.homelight.application

import io.github.bigswlittlesw.homelight.config.CandidateCatalog
import io.github.bigswlittlesw.homelight.config.CandidateParser
import io.github.bigswlittlesw.homelight.config.ConfigurationDraft
import io.github.bigswlittlesw.homelight.config.ConfigurationException
import io.github.bigswlittlesw.homelight.config.ConfigurationLoader
import io.github.bigswlittlesw.homelight.config.ConfigurationPublisher
import io.github.bigswlittlesw.homelight.config.Relocation
import io.github.bigswlittlesw.homelight.config.WhenAdoptingTarget
import io.github.bigswlittlesw.homelight.config.WhenOnlyTargetExists
import io.github.bigswlittlesw.homelight.config.WhenSourceAndTargetDirectoriesExist
import io.github.bigswlittlesw.homelight.discovery.CandidateDiscovery
import io.github.bigswlittlesw.homelight.discovery.CandidateMetadata
import io.github.bigswlittlesw.homelight.discovery.CandidateObservation
import io.github.bigswlittlesw.homelight.discovery.Workers
import io.github.bigswlittlesw.homelight.pollUntil
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class SetupDraftTest {
    @TempDir lateinit var temporary: Path

    @Test fun joinsNormalizedConfiguredAndManualSourcesWithoutChangingThem() {
        val root = Files.createDirectory(temporary.resolve("home"))
        Files.createDirectories(root.resolve(".m2"))
        Files.createDirectories(root.resolve("datasets"))
        val configured = Relocation(root.resolve("x/../.m2"), temporary.resolve("custom/maven"),
                WhenSourceAndTargetDirectoriesExist.ADOPT)
        val outside = Relocation(temporary.resolve("outside"), temporary.resolve("custom/outside"))
        val draft = draft(root, listOf(configured, outside))
        val manual = SetupDraft.Row("./datasets", "my-data")
        draft.append(manual)
        worker().use { worker ->
            refresh(draft, worker)
            val maven = entry(draft, root.resolve(".m2"))
            assertSame(configured, maven.configured)
            assertEquals(2, checkNotNull(maven.discovery).catalog.definitions.size)
            assertTrue(entry(draft, outside.sourcePath).outsideRoot)
            assertSame(manual, entry(draft, root.resolve("datasets")).draft)
            assertEquals(12, draft.entries().size) // 11 catalog identities plus the outside row
            assertEquals(listOf(configured, outside, Relocation(root.resolve("datasets"),
                    temporary.resolve("target/my-data"))), draft.validate().relocations)
            assertThrows<IllegalArgumentException> { draft.add(root.resolve(".m2")) }
            assertThrows<IllegalArgumentException> { draft.add(root.resolve("datasets")) }
            assertEquals(listOf(manual), draft.rows)
        }
    }

    @Test fun refreshFailureRemovalAdviceAndStateChangesPreserveDraftAndReviewedPlan() {
        val root = Files.createDirectory(temporary.resolve("home"))
        Files.createDirectories(root.resolve("team-cache"))
        Files.createDirectories(root.resolve(".cache/uv"))
        Files.createDirectories(root.resolve(".m2"))
        val config = temporary.resolve("existing.json")
        val configured = Relocation(root.resolve(".m2"), temporary.resolve("saved/maven"))
        ConfigurationPublisher().saveNew(config, ConfigurationDraft.of(temporary.resolve("saved"), listOf(configured)))
        val bytes = Files.readAllBytes(config)
        val session = HomeLightSession(config)
        assertTrue(session.requestApply())
        val plan = session.planModel()
        val review = session.applyModel()
        val draft = draft(root, listOf(configured))
        worker().use { worker ->
            refresh(draft, worker)
            draft.add(root.resolve("team-cache"))
            draft.add(root.resolve(".cache/uv"))
            val edited = SetupDraft.Row("team-cache", "custom-team", null,
                    WhenOnlyTargetExists.ADOPT_TARGET, null, null)
            draft.edit(0, edited)
            val savedRows = draft.rows
            Files.writeString(shared(), "{\"directories\": [")
            refresh(draft, worker)
            // A failed list read drops its candidates; the draft row stays.
            assertNull(entry(draft, root.resolve("team-cache")).discovery)
            assertEquals(savedRows, draft.rows)
            Files.copy(fixture("shared-refreshed"), shared(), StandardCopyOption.REPLACE_EXISTING)
            Files.delete(root.resolve(".cache/uv"))
            refresh(draft, worker)
            assertSame(edited, draft.rows.first())
            val removed = entry(draft, root.resolve("team-cache"))
            assertNull(removed.discovery)
            assertNull(entry(draft, root.resolve("new-cache")).draft)
            assertEquals(CandidateObservation.Kind.MISSING,
                    checkNotNull(entry(draft, root.resolve(".cache/uv")).discovery).observation.kind)
            assertEquals(savedRows, draft.rows)
            assertEquals(2, checkNotNull(entry(draft, root.resolve(".cache/uv")).discovery).catalog.definitions.size)
            assertEquals(savedRows, draft.rows)
            assertArrayEquals(bytes, Files.readAllBytes(config))
            assertSame(plan, session.planModel())
            assertEquals(review, session.applyModel())
            assertSame((plan as PlanModel.Configured).plan,
                    (session.applyModel() as ApplyModel.Confirmation).plan)
        }
    }

    @Test fun failedSharedReadDropsItsCandidatesButBundledOnesStayAddable() {
        val root = Files.createDirectory(temporary.resolve("home"))
        Files.createDirectories(root.resolve("team-cache"))
        Files.createDirectories(root.resolve(".cache/uv"))
        val draft = draft(root, listOf())
        worker().use { worker ->
            refresh(draft, worker)
            Files.writeString(shared(), "{\"directories\": [")
            refresh(draft, worker)
            assertThrows<IllegalArgumentException> { draft.add(root.resolve("team-cache")) }
            draft.add(root.resolve(".cache/uv"))
            assertEquals(listOf(SetupDraft.Row(".cache/uv", ".cache/uv")), draft.rows)
        }
    }

    @Test fun explicitAddOmitsPoliciesAndRejectsNestedOrDuplicateSelections() {
        val root = Files.createDirectory(temporary.resolve("home"))
        Files.createDirectories(root.resolve(".local/share/uv/tools"))
        val draft = draft(root, listOf())
        worker().use { worker ->
            refresh(draft, worker)
            assertTrue(draft.rows.isEmpty())
            draft.add(root.resolve(".local/share/uv"))
            val chosen = draft.rows
            assertEquals(listOf(SetupDraft.Row(".local/share/uv", ".local/share/uv")), chosen)
            assertThrows<IllegalArgumentException> { draft.add(root.resolve(".local/share/uv/tools")) }
            assertThrows<IllegalArgumentException> { draft.add(root.resolve(".local/share/uv")) }
            assertEquals(chosen, draft.rows)
            draft.remove(0)
            draft.add(root.resolve(".local/share/uv/tools"))
            assertThrows<IllegalArgumentException> { draft.add(root.resolve(".local/share/uv")) }
            assertEquals(1, draft.rows.size)
        }
    }

    @Test fun manualInvalidRowsRemainVisibleButEntireProposedConfigurationMustValidate() {
        val root = Files.createDirectory(temporary.resolve("home"))
        val first = SetupDraft.Row("a", "a")
        for (second in listOf(SetupDraft.Row("./a", "b"), SetupDraft.Row("a/child", "b"),
                SetupDraft.Row("b", "a"), SetupDraft.Row("b", "a/child"),
                SetupDraft.Row("b", ""), SetupDraft.Row("b", "."), SetupDraft.Row("b", "../escape"))) {
            val draft = draft(root, listOf())
            draft.append(first)
            draft.append(second)
            assertEquals(2, draft.entries().size)
            assertThrows<IllegalArgumentException> { draft.validate() }
            assertThrows<IllegalArgumentException> { ConfigurationPublisher().saveNew(
                    temporary.resolve("invalid.json"), draft.validate()) }
            assertEquals(listOf(first, second), draft.rows)
            assertFalse(Files.exists(temporary.resolve("invalid.json")))
        }
        // Cross source/target intersections and cycles are checked across configured and draft rows.
        val configured = Relocation(root.resolve("a"), temporary.resolve("target/b"))
        val cross = SetupDraft(root, root, null, listOf(configured))
        cross.append(SetupDraft.Row("c", "a"))
        assertThrows<IllegalArgumentException> { cross.validate() }
        val cycle = SetupDraft(temporary.resolve("target"), root, null, listOf(configured))
        cycle.append(SetupDraft.Row("b", "a"))
        assertThrows<IllegalArgumentException> { cycle.validate() }
    }

    @Test fun archiveRootDefaultsBesideTheSourceForEveryPolicy() {
        val root = Files.createDirectory(temporary.resolve("home"))
        val archive = temporary.resolve("archive")
        fun resolved(adopting: WhenAdoptingTarget?, archiveRoot: Path?): Relocation {
            val draft = draft(root, listOf())
            draft.append(SetupDraft.Row("a/b", "a/b", adopting = adopting, archiveRoot = archiveRoot))
            return draft.validate().relocations.single()
        }
        for (adopting in WhenAdoptingTarget.entries + null) {
            assertEquals(root.resolve("a/.homelight-archive"), resolved(adopting, null).archiveRoot, adopting.toString())
            assertEquals(archive, resolved(adopting, archive).archiveRoot, adopting.toString())
            assertEquals(adopting, resolved(adopting, null).whenAdoptingTarget)
        }
    }

    @Test fun rootAndLocationEditsRejectOldResultsEvenAfterReturningToTheOldRoot() {
        val root = Files.createDirectory(temporary.resolve("home"))
        Files.createDirectories(root.resolve("team-cache"))
        val other = Files.createDirectory(temporary.resolve("other"))
        val draft = draft(root, listOf())
        worker().use { worker ->
            val old = refresh(draft, worker)
            draft.add(root.resolve("team-cache"))
            val rows = draft.rows
            draft.roots(other, temporary.resolve("new-target"))
            assertFalse(draft.accept(old))
            assertNull(draft.entries().first().discovery)
            assertEquals(other.resolve("team-cache"), draft.validate().relocations.first().sourcePath)
            assertEquals(temporary.resolve("new-target/team-cache"), draft.validate().relocations.first().targetPath)
            refresh(draft, worker)
            draft.roots(root, temporary.resolve("target"))
            assertFalse(draft.accept(old))
            refresh(draft, worker)
            assertFalse(draft.accept(old))
            draft.sharedList("")
            assertFalse(draft.accept(old))
            assertEquals(rows, draft.rows)
            assertNull(draft.validate().sharedList)
        }
    }

    @Test fun blockedSharedReadAllowsManualAddSaveAndEditingAfterFailure() {
        val root = Files.createDirectory(temporary.resolve("home"))
        val cache = Files.createDirectory(root.resolve("manual"))
        Files.writeString(cache.resolve("data"), "keep")
        val link = Files.createSymbolicLink(root.resolve("link"), Path.of("manual"))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val clock = AtomicLong()
        val draft = draft(root, listOf())
        try {
            CandidateDiscovery(Workers(), clock::get, { path ->
                entered.countDown()
                try { release.await() } catch (exception: InterruptedException) { throw AssertionError(exception) }
                Files.readAllBytes(shared())
            }, { r -> CandidateParser().parse(CandidateCatalog.BUNDLED, r, "{\"directories\": []}".toByteArray()) },
                    CandidateMetadata()).use { worker ->
                draft.refresh(worker)
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                clock.set(5_000_000_000L)
                assertTrue(draft.accept(worker.snapshot()))
                draft.append(SetupDraft.Row("manual", "manual"))
                val blocked = Files.writeString(temporary.resolve("blocked"), "occupied")
                assertThrows<ConfigurationException> {
                    ConfigurationPublisher().saveNew(blocked.resolve("config.json"), draft.validate()) }
                draft.edit(0, SetupDraft.Row("manual", "edited"))
                val config = temporary.resolve("saved.json")
                ConfigurationPublisher().saveNew(config, draft.validate())
                val loaded = ConfigurationLoader().load(config)
                assertEquals(shared(), loaded.sharedList)
                assertEquals(temporary.resolve("target/edited"), loaded.relocations.first().targetPath)
                assertEquals("keep", Files.readString(cache.resolve("data")))
                assertEquals(Path.of("manual"), Files.readSymbolicLink(link))
                assertFalse(Files.exists(temporary.resolve("target")))
                assertEquals(1, draft.rows.size)
                draft.sharedList("")
                release.countDown()
                assertFalse(draft.accept(worker.snapshot()))
            }
        } finally { release.countDown() }
    }

    @Test fun addRejectsTargetAndCrossIntersectionsWithoutChangingRows() {
        val root = Files.createDirectory(temporary.resolve("home"))
        Files.createDirectories(root.resolve("team-cache"))
        for (target in listOf(temporary.resolve("target/team-cache"), temporary.resolve("target/team-cache/child"),
                root.resolve("team-cache/child"))) {
            val configured = Relocation(root.resolve("existing"), target)
            val draft = draft(root, listOf(configured))
            draft.append(SetupDraft.Row("manual", "custom"))
            worker().use { worker ->
                refresh(draft, worker)
                val before = draft.rows
                assertThrows<IllegalArgumentException> { draft.add(root.resolve("team-cache")) }
                assertEquals(before, draft.rows)
            }
        }
    }

    @Test fun adviceDoesNotPreventAddAndOnlyExplicitRowsArePublished() {
        val root = Files.createDirectory(temporary.resolve("home"))
        Files.createDirectories(root.resolve(".cache/example"))
        Files.createDirectories(root.resolve("datasets"))
        Files.createDirectories(root.resolve("manual"))
        Files.writeString(root.resolve("manual/data"), "unchanged")
        val draft = draft(root, listOf())
        draft.append(SetupDraft.Row("manual", "my-manual"))
        worker().use { worker ->
            refresh(draft, worker)
            draft.add(root.resolve(".cache/example")) // usually-unnecessary is informational
            draft.add(root.resolve("absent-cache"))
            Files.delete(shared())
            refresh(draft, worker)
            val path = temporary.resolve("chosen.json")
            ConfigurationPublisher().saveNew(path, draft.validate())
            val loaded = ConfigurationLoader().load(path)
            assertEquals(draft.validate().relocations, loaded.relocations)
            assertEquals(3, loaded.relocations.size)
            assertTrue(loaded.relocations.all { r -> r.whenSourceAndTargetDirectoriesExist == null
                    && r.whenOnlyTargetExists == null && r.whenAdoptingTarget == null })
            assertEquals(shared(), loaded.sharedList)
            assertEquals("unchanged", Files.readString(root.resolve("manual/data")))
            assertFalse(Files.exists(temporary.resolve("target")))
            val json = Files.readString(path)
            assertTrue(json.contains("\"source-root\": \"$root\""), json)
            for (forbidden in listOf("advice", "usually-unnecessary", "Example IDE", "observations", "provenance", "datasets")) {
                assertFalse(json.contains(forbidden), json)
            }
        }
    }

    private fun draft(root: Path, configured: List<Relocation>): SetupDraft {
        if (!Files.exists(shared())) Files.copy(fixture("shared"), shared())
        return SetupDraft(root, temporary.resolve("target"), shared(), configured)
    }

    @Test fun onlyDirectoryOrMissingObservationsAllowAddition() {
        val root = Files.createDirectory(temporary.resolve("home"))
        val draft = draft(root, listOf())
        val path = root.resolve("absent-cache")
        worker().use { worker ->
            val result = refresh(draft, worker)
            val candidate = checkNotNull(entry(draft, path).discovery)
            assertEquals(CandidateObservation.Kind.MISSING, candidate.observation.kind)
            for (kind in CandidateObservation.Kind.entries) {
                val observation = CandidateObservation(path, kind, null, result.generation, Instant.now(), listOf())
                assertTrue(draft.accept(CandidateDiscovery.Result(result.generation, result.request, result.sources,
                        listOf(CandidateDiscovery.Candidate(candidate.catalog, observation, candidate.ancestors)), result.rootFailure)))
                val eligible = kind == CandidateObservation.Kind.DIRECTORY || kind == CandidateObservation.Kind.MISSING
                assertEquals(eligible, draft.canAdd(entry(draft, path)), kind.toString())
                if (eligible) {
                    draft.add(path)
                    assertFalse(draft.canAdd(entry(draft, path)))
                    assertThrows<IllegalArgumentException> { draft.add(path) }
                    assertEquals(listOf(SetupDraft.Row("absent-cache", "absent-cache")), draft.rows)
                    draft.remove(0)
                } else assertThrows<IllegalArgumentException> { draft.add(path) }
                assertTrue(draft.rows.isEmpty())
            }
            assertTrue(draft.accept(result))
            draft.roots(root, temporary.resolve("target"))
            assertFalse(draft.accept(result))
            assertThrows<IllegalArgumentException> { draft.add(path) }
        }
    }

    @Test fun missingNestedPathsStillRejectOverlapWithoutCreatingDirectories() {
        val root = Files.createDirectory(temporary.resolve("home"))
        val draft = draft(root, listOf())
        worker().use { worker ->
            refresh(draft, worker)
            draft.add(root.resolve(".local/share/uv"))
            assertThrows<IllegalArgumentException> { draft.add(root.resolve(".local/share/uv/tools")) }
            assertEquals(listOf(SetupDraft.Row(".local/share/uv", ".local/share/uv")), draft.rows)
            assertFalse(Files.exists(root.resolve(".local")))
            assertFalse(Files.exists(temporary.resolve("target")))
        }
    }

    @Test fun duplicateRowsAreRejectedUntilEitherIsEditedOrRemoved() {
        val root = Files.createDirectory(temporary.resolve("home"))
        for (sameObject in listOf(false, true)) for (index in 0 until 2) for (remove in listOf(false, true)) {
            val draft = draft(root, listOf())
            val row = SetupDraft.Row("team-cache", "team-cache")
            draft.append(row)
            draft.append(if (sameObject) row else SetupDraft.Row("team-cache", "team-cache"))
            assertThrows<IllegalArgumentException> { draft.validate() }
            if (remove) draft.remove(index) else draft.edit(index, SetupDraft.Row("edited", "edited"))
            assertEquals(if (remove) 1 else 2, draft.validate().relocations.size)
            if (!remove) assertEquals(SetupDraft.Row("edited", "edited"), draft.rows[index])
        }
    }

    private fun worker(): CandidateDiscovery {
        val bundled = Files.readAllBytes(fixture("bundled"))
        return CandidateDiscovery(Workers(), System::nanoTime, Files::readAllBytes,
                { root -> CandidateParser().parse(CandidateCatalog.BUNDLED, root, bundled) }, CandidateMetadata())
    }

    private fun shared(): Path { return temporary.resolve("shared.json") }

    companion object {
        private fun fixture(name: String): Path { return Path.of("docs/research/session-b-fixtures/nested/$name.json") }
        private fun entry(draft: SetupDraft, path: Path): SetupDraft.Entry {
            return draft.entries().first { row -> row.sourcePath == path }
        }

        private fun refresh(draft: SetupDraft, worker: CandidateDiscovery): CandidateDiscovery.Result {
            draft.refresh(worker)
            lateinit var result: CandidateDiscovery.Result
            pollUntil("Discovery fixture did not finish") { result = worker.snapshot(); settled(result) }
            assertTrue(draft.accept(result))
            return result
        }

        private fun settled(result: CandidateDiscovery.Result): Boolean =
            result.sources.none { s -> s.status == CandidateDiscovery.SourceStatus.PENDING }
                && result.candidates.none { c -> c.observation.kind == CandidateObservation.Kind.PENDING }
    }
}
