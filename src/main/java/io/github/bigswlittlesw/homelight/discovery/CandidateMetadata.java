package io.github.bigswlittlesw.homelight.discovery;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static io.github.bigswlittlesw.homelight.discovery.CandidateObservation.*;

/// Only root resolution, no-follow attributes and raw link text. No directory
/// enumeration or target probes. Rechecks detect replacements best-effort; Java
/// path-based operations do not provide atomic containment under concurrent renames.
final class CandidateMetadata {
    private final Access access;

    CandidateMetadata() { this(new Access()); }
    CandidateMetadata(Access access) { this.access = access; }

    Anchor anchor(Path root) throws IOException {
        var physical = access.realPath(root);
        var attributes = access.attributes(physical);
        if (!attributes.isDirectory()) throw new IOException("Root is not a directory: " + root);
        return new Anchor(root, physical, attributes);
    }

    CandidateObservation inspect(Anchor anchor, Path candidate, long generation) {
        if (!candidate.startsWith(anchor.lexical()) || candidate.equals(anchor.lexical())) {
            throw new IllegalArgumentException("Candidate must be below the chosen root");
        }
        var guards = new ArrayList<Guard>();
        guards.add(new Guard(anchor.physical(), anchor.attributes()));
        var diagnostics = new ArrayList<Diagnostic>();
        try {
            checkAnchor(anchor);
            if (!anchor.lexical().equals(anchor.physical()) || anchor.attributes().fileKey() == null) {
                diagnostics.add(new Diagnostic(candidate, Reason.ALIAS_UNCERTAINTY,
                        "Root alias or unavailable file identity; lexical identities are not physical deduplication"));
            }
            var relative = anchor.lexical().relativize(candidate);
            var current = anchor.physical();
            for (int i = 0; i < relative.getNameCount(); i++) {
                checkGuards(guards);
                current = current.resolve(relative.getName(i));
                BasicFileAttributes attributes;
                try {
                    attributes = access.attributes(current);
                } catch (NoSuchFileException e) {
                    checkGuards(guards);
                    checkAnchor(anchor);
                    diagnostics.add(new Diagnostic(current, Reason.MISSING, e.toString()));
                    return observation(candidate, Kind.MISSING, Optional.empty(), generation, diagnostics);
                }
                if (attributes.fileKey() == null
                        && diagnostics.stream().noneMatch(d -> d.reason() == Reason.ALIAS_UNCERTAINTY)) {
                    diagnostics.add(new Diagnostic(current, Reason.ALIAS_UNCERTAINTY,
                            "Stable file identity unavailable; replacements may not be detectable"));
                }
                guards.add(new Guard(current, attributes));
                checkGuards(guards);
                boolean leaf = i == relative.getNameCount() - 1;
                Kind kind;
                Optional<Path> target = Optional.empty();
                if (attributes.isSymbolicLink()) {
                    kind = leaf ? Kind.LINK : Kind.BLOCKED_BY_LINK;
                    if (leaf) target = Optional.of(access.readLink(current));
                    diagnostics.add(new Diagnostic(current, Reason.SYMLINK_EXCLUDED,
                            "Link destination not inspected"));
                } else if (!leaf && !attributes.isDirectory()) {
                    kind = Kind.BLOCKED_BY_NON_DIRECTORY;
                    diagnostics.add(new Diagnostic(current, Reason.NOT_DIRECTORY,
                            "Intermediate component is not a directory"));
                } else if (!leaf) {
                    continue;
                } else {
                    kind = attributes.isDirectory() ? Kind.DIRECTORY
                            : attributes.isRegularFile() ? Kind.REGULAR_FILE : Kind.OTHER;
                }
                checkGuards(guards);
                checkAnchor(anchor);
                return observation(candidate, kind, target, generation, diagnostics);
            }
            throw new IllegalArgumentException("Empty relative candidate path");
        } catch (IOException | SecurityException e) {
            var reason = e instanceof Changed ? Reason.CHANGED
                    : e instanceof AccessDeniedException || e instanceof SecurityException
                    ? Reason.ACCESS_DENIED : Reason.IO_ERROR;
            diagnostics.add(new Diagnostic(candidate, reason, e.toString()));
            return observation(candidate, e instanceof Changed ? Kind.UNKNOWN : Kind.INACCESSIBLE,
                    Optional.empty(), generation, diagnostics);
        }
    }

    private void checkAnchor(Anchor anchor) throws IOException {
        if (!access.realPath(anchor.lexical()).equals(anchor.physical())
                || !same(anchor.attributes(), access.attributes(anchor.physical()))) {
            throw new Changed(anchor.lexical());
        }
    }

    private void checkGuards(List<Guard> guards) throws IOException {
        for (var guard : guards) {
            if (!same(guard.attributes(), access.attributes(guard.path()))) throw new Changed(guard.path());
        }
    }

    private static boolean same(BasicFileAttributes before, BasicFileAttributes after) {
        return before.isDirectory() == after.isDirectory() && before.isRegularFile() == after.isRegularFile()
                && before.isSymbolicLink() == after.isSymbolicLink()
                && Objects.equals(before.fileKey(), after.fileKey())
                && before.creationTime().equals(after.creationTime())
                && (!before.isSymbolicLink() || before.lastModifiedTime().equals(after.lastModifiedTime()));
    }

    private static CandidateObservation observation(Path path, Kind kind, Optional<Path> target,
                                                    long generation, List<Diagnostic> diagnostics) {
        return new CandidateObservation(path, kind, target, generation, Instant.now(), false, diagnostics);
    }

    record Anchor(Path lexical, Path physical, BasicFileAttributes attributes) {}
    private record Guard(Path path, BasicFileAttributes attributes) {}
    private static final class Changed extends IOException {
        Changed(Path path) { super("Filesystem identity changed: " + path); }
    }

    // Deliberately excludes directory enumeration and file-content operations.
    static class Access {
        Path realPath(Path path) throws IOException { return path.toRealPath(); }
        BasicFileAttributes attributes(Path path) throws IOException {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        }
        Path readLink(Path path) throws IOException { return Files.readSymbolicLink(path); }
    }
}
