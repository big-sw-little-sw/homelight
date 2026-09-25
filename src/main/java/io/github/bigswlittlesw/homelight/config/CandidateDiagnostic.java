package io.github.bigswlittlesw.homelight.config;

import java.util.Objects;

/// Locations are one-based; zero means unavailable or source-wide. Key is empty
/// when no schema key applies. Messages are data and require escaping for display.
/// Structural location identifies the enclosing record or collection using zero-based
/// indices; it is empty for source-wide failures or failures before schema association.
public record CandidateDiagnostic(CandidateSource source, Kind kind, int recordIndex,
                                  int line, int column, String location, String key, String message) {
    public CandidateDiagnostic {
        Objects.requireNonNull(source);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(location);
        Objects.requireNonNull(key);
        Objects.requireNonNull(message);
        if (recordIndex < 0 || line < 0 || column < 0 || message.isBlank()) {
            throw new IllegalArgumentException("Invalid diagnostic location or message");
        }
    }

    public enum Kind { SYNTAX, SCHEMA, UNSAFE_PATH, LIMIT, ENCODING, RESOURCE }
}
