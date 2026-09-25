package io.github.bigswlittlesw.homelight.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/// The unsaved contents of a new configuration. It is deliberately separate from
/// loaded configuration: editing a draft cannot change an existing file.
public record ConfigurationDraft(Path targetRoot, List<Relocation> relocations, Optional<Path> sharedList) {
    public ConfigurationDraft {
        targetRoot = Objects.requireNonNull(targetRoot, "targetRoot").toAbsolutePath().normalize();
        relocations = List.copyOf(relocations);
        sharedList = sharedList.map(DiscoverySetting::normalize);
    }

    public ConfigurationDraft(Path targetRoot, List<Relocation> relocations) {
        this(targetRoot, relocations, Optional.empty());
    }

    public ConfigurationDraft withTargetRoot(Path value) {
        return new ConfigurationDraft(value, relocations, sharedList);
    }

    public ConfigurationDraft withRelocations(List<Relocation> value) {
        return new ConfigurationDraft(targetRoot, value, sharedList);
    }
}
