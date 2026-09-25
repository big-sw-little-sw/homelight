package io.github.bigswlittlesw.homelight.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.bigswlittlesw.homelight.discovery.CandidateObservation.*;
import static org.junit.jupiter.api.Assertions.*;

class CandidateMetadataTest {
    @TempDir Path temporary;

    @Test void fixtureStatesHaveNoSizeAndNeverInspectContents() throws Exception {
        var root = temporary.toRealPath();
        for (var path : List.of(".m2/repository", ".cache/uv", ".cache/example", ".local/share/uv/tools")) {
            Files.createDirectories(root.resolve(path));
        }
        Files.write(root.resolve(".m2/repository/secret"), new byte[4096]);
        Files.write(root.resolve("not-a-directory"), new byte[16]);
        var allowed = new HashSet<Path>(List.of(root));
        for (var path : List.of(".m2", ".cache/uv", ".cache/example", ".local/share/uv/tools",
                "not-a-directory", "absent-cache")) {
            for (var part = root.resolve(path); part.startsWith(root); part = part.getParent()) allowed.add(part);
        }
        var reader = guarded(root, allowed);
        var anchor = reader.anchor(root);
        for (var path : List.of(".m2", ".cache/uv", ".cache/example", ".local/share/uv", ".local/share/uv/tools")) {
            assertState(reader.inspect(anchor, root.resolve(path), 7), Kind.DIRECTORY);
        }
        assertState(reader.inspect(anchor, root.resolve("not-a-directory"), 7), Kind.REGULAR_FILE);
        assertState(reader.inspect(anchor, root.resolve("not-a-directory/child"), 7), Kind.BLOCKED_BY_NON_DIRECTORY);
        assertState(reader.inspect(anchor, root.resolve("absent-cache"), 7), Kind.MISSING);
        assertEquals(4096, Files.size(root.resolve(".m2/repository/secret")));
    }

    @Test void rootAliasAllowedButLeafAndIntermediateLinksNeverFollowed() throws Exception {
        var physical = Files.createDirectory(temporary.resolve("root")).toRealPath();
        var outside = Files.createDirectory(temporary.resolve("outside")).toRealPath();
        Files.createDirectories(outside.resolve("cache"));
        Files.write(outside.resolve("cache/secret"), new byte[100]);
        var alias = Files.createSymbolicLink(temporary.resolve("alias"), physical);
        Files.createDirectory(physical.resolve("inside"));
        Files.createSymbolicLink(physical.resolve("link"), outside);
        Files.createSymbolicLink(physical.resolve("broken"), Path.of("missing"));
        Files.createSymbolicLink(physical.resolve("inside-parent"), Path.of("inside"));
        var allowed = Set.of(physical, physical.resolve("inside"), physical.resolve("link"),
                physical.resolve("broken"), physical.resolve("inside-parent"));
        var reader = guarded(alias, allowed);
        var anchor = reader.anchor(alias);
        var directory = reader.inspect(anchor, alias.resolve("inside"), 1);
        assertState(directory, Kind.DIRECTORY);
        assertReason(directory, Reason.ALIAS_UNCERTAINTY);
        for (var name : List.of("link", "broken")) {
            var result = reader.inspect(anchor, alias.resolve(name), 1);
            assertState(result, Kind.LINK);
            assertEquals(Files.readSymbolicLink(physical.resolve(name)), result.rawLinkTarget().orElseThrow());
            assertEquals(LinkTargetStatus.UNKNOWN, result.linkTargetStatus());
            assertEquals(alias.resolve(name), result.path());
        }
        for (var path : List.of("link/cache", "inside-parent/child")) {
            assertState(reader.inspect(anchor, alias.resolve(path), 1), Kind.BLOCKED_BY_LINK);
        }
        assertEquals(outside, Files.readSymbolicLink(physical.resolve("link")));
        assertEquals(100, Files.size(outside.resolve("cache/secret")));
    }

    @Test void permissionsAndGenericErrorsHaveTypedUnknownEvidence() throws Exception {
        var reader = new CandidateMetadata(new CandidateMetadata.Access() {
            @Override BasicFileAttributes attributes(Path path) throws IOException {
                if (path.endsWith("denied")) throw new AccessDeniedException(path.toString());
                if (path.endsWith("error")) throw new IOException("Controlled I/O failure");
                return super.attributes(path);
            }
        });
        var anchor = reader.anchor(temporary);
        var denied = reader.inspect(anchor, temporary.resolve("denied/child"), 1);
        assertState(denied, Kind.INACCESSIBLE);
        assertReason(denied, Reason.ACCESS_DENIED);
        var error = reader.inspect(anchor, temporary.resolve("error"), 1);
        assertState(error, Kind.INACCESSIBLE);
        assertReason(error, Reason.IO_ERROR);
    }

    @Test void intermediateReplacementAbortsBeforeReadingBelowLink() throws Exception {
        Files.createDirectory(temporary.resolve("parent"));
        var outside = Files.createDirectory(temporary.resolve("outside"));
        var reads = new AtomicInteger();
        var reader = new CandidateMetadata(new CandidateMetadata.Access() {
            @Override BasicFileAttributes attributes(Path path) throws IOException {
                assertFalse(path.endsWith("child"), "Traversed changed intermediate component");
                if (path.endsWith("parent") && reads.incrementAndGet() == 2) {
                    Files.move(path, path.resolveSibling("original"));
                    Files.createSymbolicLink(path, outside);
                }
                return super.attributes(path);
            }
        });
        var result = reader.inspect(reader.anchor(temporary), temporary.resolve("parent/child"), 1);
        assertState(result, Kind.UNKNOWN);
        assertReason(result, Reason.CHANGED);
    }

    @Test void rootAliasReplacementInvalidatesRecordedAnchor() throws Exception {
        var root = Files.createDirectory(temporary.resolve("root"));
        var other = Files.createDirectory(temporary.resolve("other"));
        var alias = Files.createSymbolicLink(temporary.resolve("alias"), root);
        var reader = new CandidateMetadata();
        var anchor = reader.anchor(alias);
        Files.delete(alias);
        Files.createSymbolicLink(alias, other);
        var result = reader.inspect(anchor, alias.resolve("cache"), 1);
        assertState(result, Kind.UNKNOWN);
        assertReason(result, Reason.CHANGED);
    }

    @Test void filesystemSeamExposesNoEnumerationOrContentOperations() {
        var names = new HashSet<String>();
        for (var method : CandidateMetadata.Access.class.getDeclaredMethods()) names.add(method.getName());
        assertEquals(Set.of("attributes", "realPath", "readLink"), names);
        for (var kind : Kind.values()) {
            var value = new CandidateObservation(temporary, kind, java.util.Optional.empty(), 1,
                    java.time.Instant.now(), false, List.of());
            assertEquals(Size.NOT_ESTIMATED, value.size());
            assertTrue(value.size().bytes().isEmpty());
        }
    }

    private CandidateMetadata guarded(Path lexicalRoot, Set<Path> allowed) {
        return new CandidateMetadata(new CandidateMetadata.Access() {
            @Override Path realPath(Path path) throws IOException {
                assertEquals(lexicalRoot, path, "Only the chosen root may be resolved physically");
                return super.realPath(path);
            }
            @Override BasicFileAttributes attributes(Path path) throws IOException {
                assertTrue(allowed.contains(path), "Unexpected descendant/target probe: " + path);
                return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            }
        });
    }
    private static void assertState(CandidateObservation result, Kind kind) {
        assertEquals(kind, result.kind(), result.toString());
        assertEquals(Size.NOT_ESTIMATED, result.size());
        assertTrue(result.size().bytes().isEmpty());
        assertEquals(Ownership.NOT_EVALUATED, result.ownership());
    }
    private static void assertReason(CandidateObservation result, Reason reason) {
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.reason() == reason), result.toString());
    }
}
