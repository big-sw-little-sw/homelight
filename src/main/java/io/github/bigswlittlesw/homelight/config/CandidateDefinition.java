package io.github.bigswlittlesw.homelight.config;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/// One attributed occurrence, including its original spelling and literal reason.
/// Record, line and column numbers are one-based. Consumers must escape control
/// characters when displaying text; reasons are not terminal markup or commands.
/// Structural locations use zero-based indices, e.g. `apps[1].directories[0]`.
public record CandidateDefinition(Path sourcePath, CandidateSource source, int recordIndex,
                                  int line, int column, String location, String originalPath,
                                  Optional<String> app, Optional<Advice> advice, Optional<String> reason) {
    public CandidateDefinition {
        Objects.requireNonNull(sourcePath);
        Objects.requireNonNull(source);
        Objects.requireNonNull(location);
        Objects.requireNonNull(originalPath);
        Objects.requireNonNull(app);
        Objects.requireNonNull(advice);
        Objects.requireNonNull(reason);
        if (!sourcePath.isAbsolute() || !sourcePath.equals(sourcePath.normalize())
                || recordIndex < 1 || line < 1 || column < 1 || location.isBlank()) {
            throw new IllegalArgumentException("Definition requires normalized absolute identity and location");
        }
        CandidateParser.validatePath(originalPath);
        if (app.filter(s -> s.isBlank() || !s.equals(s.strip())).isPresent()
                || reason.filter(String::isBlank).isPresent()) {
            throw new IllegalArgumentException("Optional text must be nonblank; app must be trimmed");
        }
    }

    public enum Advice { CONSIDER, USUALLY_UNNECESSARY }
}
