package io.github.bigswlittlesw.homelight.config;

import java.util.Objects;

/// Attribution only. The shared location is never opened or used as a resolution root.
public record CandidateSource(Kind kind, String location) {
    public CandidateSource {
        Objects.requireNonNull(kind);
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("Source location is required");
        }
    }

    public enum Kind { BUNDLED, SHARED }
}
