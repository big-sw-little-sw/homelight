package io.github.bigswlittlesw.homelight.config;

import java.nio.file.Path;
import java.util.Optional;

/// Lexical setting conversion only. Availability is discovery's concern, never
/// a prerequisite for loading or saving configuration.
public final class DiscoverySetting {
    private DiscoverySetting() { }

    public static Optional<Path> parse(String value) {
        if (value.isBlank()) return Optional.empty();
        if (value.indexOf('$') >= 0 || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Shared list must be a filesystem path without variables or controls");
        }
        var expanded = value.startsWith("~/") ? System.getProperty("user.home") + value.substring(1) : value;
        return Optional.of(normalize(Path.of(expanded)));
    }

    public static Path normalize(Path path) {
        if (!path.isAbsolute()) throw new IllegalArgumentException("Shared list must be an absolute filesystem path");
        if (path.toString().indexOf('$') >= 0 || path.toString().codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Shared list must not contain variables or controls");
        }
        return path.normalize();
    }
}
