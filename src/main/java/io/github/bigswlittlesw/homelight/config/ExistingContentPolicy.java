package io.github.bigswlittlesw.homelight.config;

import java.util.Locale;

/// The explicit decision for pre-existing directory content during relocation.
public enum ExistingContentPolicy {
    /// Relocate source content, refusing to merge it into a populated destination.
    MOVE,

    /// Delete existing source and destination directory content before creating the managed link.
    DISCARD,

    /// Leave existing source content unmanaged without treating the relocation as converged.
    PRESERVE;

    /// Returns the stable configuration and JSON representation of this policy.
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }
}
