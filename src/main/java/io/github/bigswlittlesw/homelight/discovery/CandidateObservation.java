package io.github.bigswlittlesw.homelight.discovery;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/// Read-only evidence, never an execution safety or ownership assessment.
/// Discovery never estimates sizes or enumerates directory contents.
public record CandidateObservation(Path path, Kind kind, Optional<Path> rawLinkTarget,
                                   long generation, Instant observedAt, boolean stale,
                                   List<Diagnostic> diagnostics) {
    public CandidateObservation {
        Objects.requireNonNull(path);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(rawLinkTarget);
        Objects.requireNonNull(observedAt);
        diagnostics = List.copyOf(diagnostics);
        if (!path.isAbsolute() || !path.equals(path.normalize())) {
            throw new IllegalArgumentException("Observation requires normalized absolute identity");
        }
    }

    public enum Kind { PENDING, DIRECTORY, LINK, MISSING, REGULAR_FILE, OTHER, INACCESSIBLE,
        BLOCKED_BY_LINK, BLOCKED_BY_NON_DIRECTORY, UNKNOWN }
    public enum Reason { SYMLINK_EXCLUDED, ACCESS_DENIED, IO_ERROR, CHANGED, DEADLINE,
        CAPACITY, ALIAS_UNCERTAINTY, NOT_DIRECTORY, MISSING }
    public enum Ownership { NOT_EVALUATED }
    public enum LinkTargetStatus { NOT_A_LINK, UNKNOWN }

    public Ownership ownership() { return Ownership.NOT_EVALUATED; }
    public Size size() { return Size.NOT_ESTIMATED; }
    public LinkTargetStatus linkTargetStatus() {
        return kind == Kind.LINK ? LinkTargetStatus.UNKNOWN : LinkTargetStatus.NOT_A_LINK;
    }

    public enum Size {
        NOT_ESTIMATED;

        public OptionalLong bytes() { return OptionalLong.empty(); }
    }

    /// Paths and details are unescaped data; a presentation must escape controls.
    public record Diagnostic(Path path, Reason reason, String detail) {
        public Diagnostic {
            Objects.requireNonNull(path);
            Objects.requireNonNull(reason);
            Objects.requireNonNull(detail);
        }
    }

    CandidateObservation retained() {
        return new CandidateObservation(path, kind, rawLinkTarget, generation, observedAt, true, diagnostics);
    }

    static CandidateObservation unknown(Path path, long generation, Reason reason, String detail) {
        return new CandidateObservation(path, Kind.UNKNOWN, Optional.empty(), generation,
                Instant.now(), false, List.of(new Diagnostic(path, reason, detail)));
    }
}
