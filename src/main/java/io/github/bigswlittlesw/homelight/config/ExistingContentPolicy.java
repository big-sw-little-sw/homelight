package io.github.bigswlittlesw.homelight.config;

import java.util.Locale;

/// The explicit decision for pre-existing directory content during relocation.
public enum ExistingContentPolicy {
    /// Copy and verify source content at an absent target, then require explicit `adopt` before source replacement.
    MOVE,

    /// Delete existing source and destination directory content before creating the managed link.
    DISCARD,

    /// Leave existing source content unmanaged without treating the relocation as converged.
    PRESERVE,

    /// Accept an existing target as authoritative and replace the source with a managed link.
    ADOPT;

    /// Returns the stable configuration and JSON representation of this policy.
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }
}
