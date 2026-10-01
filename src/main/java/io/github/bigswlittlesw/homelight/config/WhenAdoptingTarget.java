package io.github.bigswlittlesw.homelight.config;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/// The disposition required for a source directory when its target is adopted.
public enum WhenAdoptingTarget {
    PROMPT,
    DISCARD_SOURCE,
    ARCHIVE_SOURCE;

    /// Returns the stable configuration and JSON representation.
    @JsonValue
    public String value() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
