package io.github.bigswlittlesw.homelight.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/// Validates and atomically creates a configuration. Existing paths are never replaced.
public final class ConfigurationPublisher {
    public void saveNew(Path path, ConfigurationDraft draft) {
        ConfigurationValidator.validate(draft);
        var destination = path.toAbsolutePath().normalize();
        try {
            var parent = destination.getParent();
            if (parent == null) throw new IOException("Configuration path has no parent: " + destination);
            Files.createDirectories(parent);
            var temporary = Files.createTempFile(parent, ".homelight-", ".yaml");
            try {
                Files.writeString(temporary, yaml(draft), StandardCharsets.UTF_8);
                // A hard-link creation is an atomic create-if-absent operation. Unlike move(REPLACE_EXISTING),
                // it cannot replace a configuration created concurrently.
                Files.createLink(destination, temporary);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (FileAlreadyExistsException exception) {
            throw new ConfigurationException("Configuration already exists and was not replaced: " + destination, exception);
        } catch (IOException exception) {
            throw new ConfigurationException("Unable to create configuration " + destination, exception);
        }
    }

    static String yaml(ConfigurationDraft draft) {
        var text = new StringBuilder("homelight:\n  target-root: ").append(value(draft.targetRoot())).append("\n");
        draft.sharedList().ifPresent(location -> text.append("  discovery:\n    shared-list: ")
                .append(value(location)).append("\n"));
        text.append("  relocations:\n");
        for (var relocation : draft.relocations()) {
            text.append("    - source-path: ").append(value(relocation.sourcePath())).append("\n")
                    .append("      target-path: ").append(value(relocation.targetPath())).append("\n");
            append(text, "when-source-and-target-directories-exist", relocation.whenSourceAndTargetDirectoriesExist().map(WhenSourceAndTargetDirectoriesExist::value));
            append(text, "when-only-target-exists", relocation.whenOnlyTargetExists().map(WhenOnlyTargetExists::value));
            append(text, "when-adopting-target", relocation.whenAdoptingTarget().map(WhenAdoptingTarget::value));
            append(text, "source-archive-root", relocation.sourceArchiveRoot().map(Path::toString));
        }
        return text.toString();
    }

    private static void append(StringBuilder text, String key, Optional<String> value) {
        value.ifPresent(entry -> text.append("      ").append(key).append(": ").append(value(entry)).append("\n"));
    }
    private static String value(Path value) { return value(value.toString()); }
    private static String value(String value) { return "'" + value.replace("'", "''") + "'"; }

    public static final class ConfigurationException extends RuntimeException {
        public ConfigurationException(String message, Throwable cause) { super(message, cause); }
    }
}
