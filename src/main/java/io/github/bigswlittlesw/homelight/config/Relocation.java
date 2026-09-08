package io.github.bigswlittlesw.homelight.config;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/// A source location, resolved storage destination, and optional durable existing-content decision.
public record Relocation(Path sourcePath, Path targetPath, Optional<ExistingContentPolicy> existingContentPolicy) {
    public Relocation {
        existingContentPolicy = Objects.requireNonNull(existingContentPolicy, "existingContentPolicy");
    }

    public Relocation(Path sourcePath, Path targetPath) {
        this(sourcePath, targetPath, Optional.empty());
    }
}
