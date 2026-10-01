package io.github.bigswlittlesw.homelight.config;

import io.github.bigswlittlesw.homelight.config.YamlDocument.Required;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/// Loads one YAML configuration rooted at `homelight`.
///
/// Unknown keys, missing required keys and values of the wrong shape are rejected with their line and
/// column, as [YamlDocument] binds them. Paths expand `~`, `~/` and `${USER}`, then become
/// absolute and normalized. Environment variables and system properties never override values.
public final class ConfigurationLoader {
    public static final Path DEFAULT_PATH = Path.of(System.getProperty("user.home"), ".homelight.yaml");

    /// Replaces the first relocation's source and target paths for one command; the file is unchanged.
    /// With no configured relocations it supplies the first one.
    public record PathOverride(Path sourcePath, Path targetPath) {
        public PathOverride {
            Objects.requireNonNull(sourcePath, "sourcePath");
            Objects.requireNonNull(targetPath, "targetPath");
        }
    }

    public HomeLightConfiguration load(Path path) {
        return load(path, Optional.empty());
    }

    public HomeLightConfiguration load(Path path, Optional<PathOverride> override) {
        if (!Files.isRegularFile(path)) {
            throw new ConfigurationException("Configuration file does not exist: " + path);
        }
        ConfigurationFile file;
        try {
            file = YamlDocument.parse(Files.readString(path)).bind(ConfigurationFile.class);
        } catch (IOException exception) {
            throw new ConfigurationException("Unable to read configuration " + path, exception);
        } catch (YamlDocument.Violation violation) {
            throw new ConfigurationException(violation.positioned());
        }
        if (file == null) {
            throw new ConfigurationException("Missing required key homelight");
        }
        return configuration(file.homelight(), override);
    }

    private static HomeLightConfiguration configuration(Settings settings, Optional<PathOverride> override) {
        var targetRoot = resolve(settings.targetRoot());
        var stagingRoot = Optional.ofNullable(settings.stagingRoot()).map(ConfigurationLoader::resolve);
        if (stagingRoot.filter(root -> !root.startsWith(targetRoot)).isPresent()) {
            throw new ConfigurationException("staging-root must be under target-root");
        }
        // The override replaces the first relocation's paths, or supplies it when none is configured.
        var entries = Objects.requireNonNullElse(settings.relocations(), List.<RelocationEntry>of());
        var relocations = new ArrayList<Relocation>();
        for (int i = 0; i < entries.size(); i++) {
            relocations.add(relocation(entries.get(i), targetRoot, stagingRoot, i == 0 ? override : Optional.empty()));
        }
        if (relocations.isEmpty() && override.isPresent()) {
            var empty = new RelocationEntry(null, null, null, null, null, null);
            relocations.add(relocation(empty, targetRoot, stagingRoot, override));
        }
        var ignoredSourcePaths = Objects.requireNonNullElse(settings.ignoredSourcePaths(), List.<String>of()).stream()
                .map(ConfigurationLoader::resolve)
                .toList();
        var sharedList = Optional.ofNullable(settings.discovery())
                .map(Discovery::sharedList)
                .flatMap(DiscoverySetting::parse);
        return new HomeLightConfiguration(targetRoot, relocations, ignoredSourcePaths, sharedList);
    }

    private static Relocation relocation(RelocationEntry entry, Path targetRoot, Optional<Path> stagingRoot,
            Optional<PathOverride> override) {
        var sourcePath = override.map(paths -> resolve(paths.sourcePath().toString()))
                .orElseGet(() -> resolve(entry.sourcePath()));
        var targetPath = override.map(paths -> resolve(paths.targetPath().toString()))
                .or(() -> Optional.ofNullable(entry.targetPath()).map(ConfigurationLoader::resolve))
                .orElseGet(() -> deriveTarget(targetRoot, sourcePath));
        var whenAdoptingTarget = Optional.ofNullable(entry.whenAdoptingTarget());
        var archiveRoot = Optional.ofNullable(entry.sourceArchiveRoot()).map(ConfigurationLoader::resolve);
        if (whenAdoptingTarget.filter(WhenAdoptingTarget.ARCHIVE_SOURCE::equals).isPresent() && archiveRoot.isEmpty()) {
            throw new ConfigurationException("source-archive-root is required when when-adopting-target is archive-source");
        }
        return new Relocation(sourcePath, targetPath, Optional.ofNullable(entry.whenSourceAndTargetDirectoriesExist()),
                Optional.ofNullable(entry.whenOnlyTargetExists()), whenAdoptingTarget, archiveRoot, stagingRoot);
    }

    private static Path resolve(String value) {
        var expanded = value.replace("${USER}", System.getenv().getOrDefault("USER", ""));
        if (expanded.equals("~")) {
            expanded = System.getProperty("user.home");
        } else if (expanded.startsWith("~/")) {
            expanded = System.getProperty("user.home") + expanded.substring(1);
        }
        return Path.of(expanded).toAbsolutePath().normalize();
    }

    private static Path deriveTarget(Path targetRoot, Path sourcePath) {
        var home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        if (!sourcePath.startsWith(home)) {
            throw new ConfigurationException("A source outside $HOME requires an explicit target-path: " + sourcePath);
        }
        return targetRoot.resolve(home.relativize(sourcePath)).normalize();
    }

    public static final class ConfigurationException extends RuntimeException {
        public ConfigurationException(String message) {
            super(message);
        }

        public ConfigurationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /// The file as written, bound by [YamlDocument]: absent values are null until `configuration` resolves them.
    private record ConfigurationFile(@Required Settings homelight) {
    }

    private record Settings(@Required String targetRoot, String stagingRoot,
            List<RelocationEntry> relocations,
            List<String> ignoredSourcePaths, Discovery discovery) {
    }

    private record Discovery(String sharedList) {
    }

    private record RelocationEntry(@Required String sourcePath, String targetPath,
            WhenSourceAndTargetDirectoriesExist whenSourceAndTargetDirectoriesExist,
            WhenOnlyTargetExists whenOnlyTargetExists, WhenAdoptingTarget whenAdoptingTarget,
            String sourceArchiveRoot) {
    }
}
