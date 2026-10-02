package io.github.bigswlittlesw.homelight.discovery

import io.github.bigswlittlesw.homelight.discovery.CandidateObservation.Kind
import io.github.bigswlittlesw.homelight.discovery.CandidateObservation.LinkTargetStatus
import io.github.bigswlittlesw.homelight.discovery.CandidateObservation.Ownership
import io.github.bigswlittlesw.homelight.discovery.CandidateObservation.Reason
import io.github.bigswlittlesw.homelight.discovery.CandidateObservation.Size
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicInteger

class CandidateMetadataTest {
    @TempDir lateinit var temporary: Path

    @Test fun fixtureStatesHaveNoSizeAndNeverInspectContents() {
        val root = temporary.toRealPath()
        for (path in listOf(".m2/repository", ".cache/uv", ".cache/example", ".local/share/uv/tools")) {
            Files.createDirectories(root.resolve(path))
        }
        Files.write(root.resolve(".m2/repository/secret"), ByteArray(4096))
        Files.write(root.resolve("not-a-directory"), ByteArray(16))
        val allowed = HashSet<Path>(listOf(root))
        for (path in listOf(".m2", ".cache/uv", ".cache/example", ".local/share/uv/tools",
                "not-a-directory", "absent-cache")) {
            var part: Path = root.resolve(path)
            while (part.startsWith(root)) { allowed.add(part); part = part.parent }
        }
        val reader = guarded(root, allowed)
        val anchor = reader.anchor(root)
        for (path in listOf(".m2", ".cache/uv", ".cache/example", ".local/share/uv", ".local/share/uv/tools")) {
            assertState(reader.inspect(anchor, root.resolve(path), 7), Kind.DIRECTORY)
        }
        assertState(reader.inspect(anchor, root.resolve("not-a-directory"), 7), Kind.REGULAR_FILE)
        assertState(reader.inspect(anchor, root.resolve("not-a-directory/child"), 7), Kind.BLOCKED_BY_NON_DIRECTORY)
        assertState(reader.inspect(anchor, root.resolve("absent-cache"), 7), Kind.MISSING)
        assertEquals(4096L, Files.size(root.resolve(".m2/repository/secret")))
    }

    @Test fun rootAliasAllowedButLeafAndIntermediateLinksNeverFollowed() {
        val physical = Files.createDirectory(temporary.resolve("root")).toRealPath()
        val outside = Files.createDirectory(temporary.resolve("outside")).toRealPath()
        Files.createDirectories(outside.resolve("cache"))
        Files.write(outside.resolve("cache/secret"), ByteArray(100))
        val alias = Files.createSymbolicLink(temporary.resolve("alias"), physical)
        Files.createDirectory(physical.resolve("inside"))
        Files.createSymbolicLink(physical.resolve("link"), outside)
        Files.createSymbolicLink(physical.resolve("broken"), Path.of("missing"))
        Files.createSymbolicLink(physical.resolve("inside-parent"), Path.of("inside"))
        val allowed = setOf(physical, physical.resolve("inside"), physical.resolve("link"),
                physical.resolve("broken"), physical.resolve("inside-parent"))
        val reader = guarded(alias, allowed)
        val anchor = reader.anchor(alias)
        val directory = reader.inspect(anchor, alias.resolve("inside"), 1)
        assertState(directory, Kind.DIRECTORY)
        assertReason(directory, Reason.ALIAS_UNCERTAINTY)
        for (name in listOf("link", "broken")) {
            val result = reader.inspect(anchor, alias.resolve(name), 1)
            assertState(result, Kind.LINK)
            assertEquals(Files.readSymbolicLink(physical.resolve(name)), result.rawLinkTarget)
            assertEquals(LinkTargetStatus.UNKNOWN, result.linkTargetStatus)
            assertEquals(alias.resolve(name), result.path)
        }
        for (path in listOf("link/cache", "inside-parent/child")) {
            assertState(reader.inspect(anchor, alias.resolve(path), 1), Kind.BLOCKED_BY_LINK)
        }
        assertEquals(outside, Files.readSymbolicLink(physical.resolve("link")))
        assertEquals(100L, Files.size(outside.resolve("cache/secret")))
    }

    @Test fun permissionsAndGenericErrorsHaveTypedUnknownEvidence() {
        val reader = CandidateMetadata(object : CandidateMetadata.Access() {
            override fun attributes(path: Path): BasicFileAttributes {
                if (path.endsWith("denied")) throw AccessDeniedException(path.toString())
                if (path.endsWith("error")) throw IOException("Controlled I/O failure")
                return super.attributes(path)
            }
        })
        val anchor = reader.anchor(temporary)
        val denied = reader.inspect(anchor, temporary.resolve("denied/child"), 1)
        assertState(denied, Kind.INACCESSIBLE)
        assertReason(denied, Reason.ACCESS_DENIED)
        val error = reader.inspect(anchor, temporary.resolve("error"), 1)
        assertState(error, Kind.INACCESSIBLE)
        assertReason(error, Reason.IO_ERROR)
    }

    @Test fun intermediateReplacementAbortsBeforeReadingBelowLink() {
        Files.createDirectory(temporary.resolve("parent"))
        val outside = Files.createDirectory(temporary.resolve("outside"))
        val reads = AtomicInteger()
        val reader = CandidateMetadata(object : CandidateMetadata.Access() {
            override fun attributes(path: Path): BasicFileAttributes {
                assertFalse(path.endsWith("child"), "Traversed changed intermediate component")
                if (path.endsWith("parent") && reads.incrementAndGet() == 2) {
                    Files.move(path, path.resolveSibling("original"))
                    Files.createSymbolicLink(path, outside)
                }
                return super.attributes(path)
            }
        })
        val result = reader.inspect(reader.anchor(temporary), temporary.resolve("parent/child"), 1)
        assertState(result, Kind.UNKNOWN)
        assertReason(result, Reason.CHANGED)
    }

    @Test fun rootAliasReplacementInvalidatesRecordedAnchor() {
        val root = Files.createDirectory(temporary.resolve("root"))
        val other = Files.createDirectory(temporary.resolve("other"))
        val alias = Files.createSymbolicLink(temporary.resolve("alias"), root)
        val reader = CandidateMetadata()
        val anchor = reader.anchor(alias)
        Files.delete(alias)
        Files.createSymbolicLink(alias, other)
        val result = reader.inspect(anchor, alias.resolve("cache"), 1)
        assertState(result, Kind.UNKNOWN)
        assertReason(result, Reason.CHANGED)
    }

    @Test fun filesystemSeamExposesNoEnumerationOrContentOperations() {
        val names = HashSet<String>()
        for (method in CandidateMetadata.Access::class.java.declaredMethods) names.add(method.name)
        assertEquals(setOf("attributes", "realPath", "readLink"), names)
        for (kind in Kind.values()) {
            val value = CandidateObservation(temporary, kind, null, 1,
                    java.time.Instant.now(), false, listOf())
            assertEquals(Size.NOT_ESTIMATED, value.size)
            assertNull(value.size.bytes)
        }
    }

    private fun guarded(lexicalRoot: Path, allowed: Set<Path>): CandidateMetadata {
        return CandidateMetadata(object : CandidateMetadata.Access() {
            override fun realPath(path: Path): Path {
                assertEquals(lexicalRoot, path, "Only the chosen root may be resolved physically")
                return super.realPath(path)
            }
            override fun attributes(path: Path): BasicFileAttributes {
                assertTrue(allowed.contains(path), "Unexpected descendant/target probe: $path")
                return Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            }
        })
    }

    companion object {
        private fun assertState(result: CandidateObservation, kind: Kind) {
            assertEquals(kind, result.kind, result.toString())
            assertEquals(Size.NOT_ESTIMATED, result.size)
            assertNull(result.size.bytes)
            assertEquals(Ownership.NOT_EVALUATED, result.ownership)
        }
        private fun assertReason(result: CandidateObservation, reason: Reason) {
            assertTrue(result.diagnostics.any { d -> d.reason == reason }, result.toString())
        }
    }
}
