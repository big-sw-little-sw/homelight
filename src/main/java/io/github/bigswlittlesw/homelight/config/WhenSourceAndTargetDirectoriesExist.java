package io.github.bigswlittlesw.homelight.config;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/// The decision required when both relocation paths are real directories.
public enum WhenSourceAndTargetDirectoriesExist {
    PROMPT,
    ADOPT,
    LEAVE_UNCHANGED,
    DISCARD;

    /// Returns the stable configuration and JSON representation.
    @JsonValue
    public String value() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
