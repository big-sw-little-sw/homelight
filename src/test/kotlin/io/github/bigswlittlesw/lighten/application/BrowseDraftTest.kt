package io.github.bigswlittlesw.lighten.application

import io.github.bigswlittlesw.lighten.config.CandidateCatalog
import io.github.bigswlittlesw.lighten.config.CandidateParser
import io.github.bigswlittlesw.lighten.discovery.CandidateDiscovery
import io.github.bigswlittlesw.lighten.discovery.CandidateMetadata
import io.github.bigswlittlesw.lighten.discovery.CandidateObservation
import io.github.bigswlittlesw.lighten.discovery.Workers
import io.github.bigswlittlesw.lighten.pollUntil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class BrowseDraftTest {
    @TempDir lateinit var temporary: Path

    @Test fun joinsTheDraftsRelocationsWithSuggestionsBySource() {
        val root = Files.createDirectory(temporary.resolve("home"))
        Files.createDirectories(root.resolve(".m2"))
        Suggestions(worker()).use { suggestions ->
            val result = settled(suggestions, root)
            // A row being typed has no source yet; a row outside every list is still an entry.
            val draft = BrowseDraft(root, listOf(root.resolve(".m2"), null, temporary.resolve("outside")), result)
            val entries = draft.entries()
            assertEquals(listOf(0, 1, 2), entries.take(3).map { it.row })
            assertEquals(2, checkNotNull(entries[0].discovery).catalog.definitions.size)
            assertNull(entries[1].discovery)
            assertNull(entries[2].discovery)
            // Each suggestion appears once: the one in the draft is not listed again. Both lists name .m2.
            assertEquals(result.candidates.size - 1, entries.size - 3)
            assertTrue(entries.drop(3).none { it.sourcePath == root.resolve(".m2") || it.row != null })
            assertFalse(draft.canAdd(entries[0]), "already in the draft")
        }
    }

    @Test fun onlyDirectoryOrMissingSuggestionsCanBeAdded() {
        val root = Files.createDirectory(temporary.resolve("home"))
        val path = root.resolve("absent-cache")
        Suggestions(worker()).use { suggestions ->
            val result = settled(suggestions, root)
            val candidate = result.candidates.single { it.catalog.sourcePath == path }
            assertEquals(CandidateObservation.Kind.MISSING, candidate.observation.kind)
            for (kind in CandidateObservation.Kind.entries) {
                val observation = CandidateObservation(path, kind, null, result.generation, Instant.now(), listOf())
                val seen = CandidateDiscovery.Result(result.generation, result.request, result.sources,
                    listOf(CandidateDiscovery.Candidate(candidate.catalog, observation, candidate.ancestors)), result.rootFailure)
                val entry = BrowseDraft(root, listOf(), seen).entries().single()
                val eligible = kind == CandidateObservation.Kind.DIRECTORY || kind == CandidateObservation.Kind.MISSING
                assertEquals(eligible, BrowseDraft(root, listOf(), seen).canAdd(entry), kind.toString())
            }
        }
    }

    /** A check for other roots forgets the last result, and a result from an earlier check is never taken. */
    @Test fun keepsOnlyAResultForTheCurrentCheck() {
        val first = Files.createDirectory(temporary.resolve("first"))
        val second = Files.createDirectory(temporary.resolve("second"))
        Suggestions(worker()).use { suggestions ->
            settled(suggestions, first, null)
            suggestions.check(second, null)
            assertEquals(CandidateDiscovery.Request.of(second, null), suggestions.request)
            val result = suggestions.result()
            assertTrue(result == null || result.request == CandidateDiscovery.Request.of(second, null))
            assertEquals(second, settled(suggestions, second, null).request?.root)
        }
    }

    private fun shared(): Path {
        val path = temporary.resolve("shared.json")
        if (!Files.exists(path)) Files.copy(Path.of("docs/research/session-b-fixtures/nested/shared.json"), path)
        return path
    }

    private fun worker(): CandidateDiscovery {
        val bundled = Files.readAllBytes(Path.of("docs/research/session-b-fixtures/nested/bundled.json"))
        return CandidateDiscovery(Workers(), System::nanoTime, Files::readAllBytes,
            { root -> CandidateParser().parse(CandidateCatalog.BUNDLED, root, bundled) }, CandidateMetadata())
    }

    /** Checks `root` with the fixture lists and waits until every suggestion has been looked at. */
    private fun settled(suggestions: Suggestions, root: Path, list: Path? = shared()): CandidateDiscovery.Result {
        suggestions.check(root, list)
        var result: CandidateDiscovery.Result? = null
        pollUntil("Discovery fixture did not finish") {
            result = suggestions.result()?.takeIf { latest ->
                latest.sources.none { it.status == CandidateDiscovery.SourceStatus.PENDING } &&
                    latest.candidates.none { it.observation.kind == CandidateObservation.Kind.PENDING }
            }
            result != null
        }
        return checkNotNull(result)
    }
}
