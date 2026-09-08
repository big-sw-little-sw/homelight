package io.github.bigswlittlesw.homelight.config;

import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.config.common.MapBackedConfigSource;
import io.smallrye.config.source.yaml.YamlConfigSource;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/// Loads one YAML configuration and applies SmallRye's standard environment and
/// system-property sources, with explicit command-line values at the highest ordinal.
public final class ConfigurationLoader {
    public static final Path DEFAULT_PATH = Path.of(System.getProperty("user.home"), ".homelight.yaml");

    public HomeLightConfiguration load(Path path) {
        return load(path, Map.of());
    }

    public HomeLightConfiguration load(Path path, Map<String, String> overrides) {
        try {
            var builder = new SmallRyeConfigBuilder()
                    .addDefaultSources()
                    .withSources(new YamlConfigSource(yamlUrl(path), 100));
            if (!overrides.isEmpty()) {
                builder.withSources(new MapBackedConfigSource("command line", overrides, 500) {
                });
            }
            SmallRyeConfig config = builder.withMapping(HomeLightMapping.class).build();
            var mapping = config.getConfigMapping(HomeLightMapping.class);
            var targetRoot = resolve(mapping.targetRoot());
            var relocations = mapping.relocations().stream()
                    .map(relocation -> resolveRelocation(targetRoot, relocation))
                    .toList();
            var ignoredSourcePaths = mapping.ignoredSourcePaths()
                    .orElse(List.of()).stream()
                    .map(ConfigurationLoader::resolve)
                    .toList();
            return new HomeLightConfiguration(targetRoot, relocations, ignoredSourcePaths);
        } catch (IOException exception) {
            throw new ConfigurationException("Unable to read configuration " + path, exception);
        }
    }

    private static URL yamlUrl(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new ConfigurationException("Configuration file does not exist: " + path);
        }
        return path.toUri().toURL();
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

    private static Relocation resolveRelocation(Path targetRoot, RelocationMapping mapping) {
        var sourcePath = resolve(mapping.sourcePath());
        var targetPath = mapping.targetPath()
                .map(ConfigurationLoader::resolve)
                .orElseGet(() -> deriveTarget(targetRoot, sourcePath));
        return new Relocation(sourcePath, targetPath);
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
}
