package io.github.bigswlittlesw.homelight.config;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/// The decision required when the source is absent and the target is a real directory.
public enum WhenOnlyTargetExists {
    PROMPT,
    ADOPT_TARGET;

    /// Returns the stable configuration and JSON representation.
    @JsonValue
    public String value() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
