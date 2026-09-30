package io.github.bigswlittlesw.homelight.config;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.SequenceNode;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/// Loads one YAML configuration rooted at `homelight`.
///
/// Unknown keys, missing required keys and values of the wrong shape are rejected with their line and
/// column, as [YamlMapping] reads them. Paths expand `~`, `~/` and `${USER}`, then become
/// absolute and normalized. Environment variables and system properties never override values.
public final class ConfigurationLoader {
    public static final Path DEFAULT_PATH = Path.of(System.getProperty("user.home"), ".homelight.yaml");

    private static final Set<String> ROOT_KEYS = Set.of("homelight");
    private static final Set<String> HOMELIGHT_KEYS = Set.of(
            "target-root", "staging-root", "relocations", "ignored-source-paths", "discovery");
    private static final Set<String> DISCOVERY_KEYS = Set.of("shared-list");
    private static final Set<String> RELOCATION_KEYS = Set.of(
            "source-path", "target-path", "when-source-and-target-directories-exist", "when-only-target-exists",
            "when-adopting-target", "source-archive-root");

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
        var document = document(path);
        try {
            return configuration(document, override);
        } catch (YamlMapping.Violation violation) {
            throw new ConfigurationException(at(violation.mark) + violation.getMessage());
        }
    }

    private static HomeLightConfiguration configuration(Node document, Optional<PathOverride> override) {
        var homelight = YamlMapping.of("", document, ROOT_KEYS).requiredMapping("homelight", HOMELIGHT_KEYS);
        var targetRoot = resolve(homelight.requiredString("target-root"));
        var stagingRoot = homelight.string("staging-root").map(ConfigurationLoader::resolve);
        if (stagingRoot.filter(root -> !root.startsWith(targetRoot)).isPresent()) {
            throw new ConfigurationException("staging-root must be under target-root");
        }
        // The override replaces the first relocation's paths, or supplies it when none is configured.
        var relocationNodes = homelight.list("relocations").map(SequenceNode::getValue).orElse(List.of());
        var relocations = new ArrayList<Relocation>();
        for (int i = 0; i < relocationNodes.size(); i++) {
            var fields = YamlMapping.of(homelight.qualify("relocations") + "[" + i + "]", relocationNodes.get(i),
                    RELOCATION_KEYS);
            relocations.add(relocation(fields, targetRoot, stagingRoot, i == 0 ? override : Optional.empty()));
        }
        if (relocations.isEmpty() && override.isPresent()) {
            relocations.add(relocation(YamlMapping.of("", null, RELOCATION_KEYS), targetRoot, stagingRoot, override));
        }
        var ignoredSourcePaths = homelight.strings("ignored-source-paths").stream()
                .map(ConfigurationLoader::resolve)
                .toList();
        var sharedList = homelight.mapping("discovery", DISCOVERY_KEYS)
                .flatMap(discovery -> discovery.string("shared-list"))
                .flatMap(DiscoverySetting::parse);
        return new HomeLightConfiguration(targetRoot, relocations, ignoredSourcePaths, sharedList);
    }

    private static Node document(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new ConfigurationException("Configuration file does not exist: " + path);
        }
        try {
            // compose() builds nodes only, so explicit tags never instantiate Java types.
            return new Yaml(new SafeConstructor(new LoaderOptions())).compose(new StringReader(Files.readString(path)));
        } catch (IOException exception) {
            throw new ConfigurationException("Unable to read configuration " + path, exception);
        } catch (MarkedYAMLException exception) {
            var problem = exception.getProblem() == null ? "invalid syntax" : exception.getProblem();
            throw new ConfigurationException(at(exception.getProblemMark()) + "Malformed YAML: " + problem, exception);
        } catch (YAMLException exception) {
            throw new ConfigurationException("Malformed YAML: " + exception.getMessage(), exception);
        }
    }

    private static Relocation relocation(YamlMapping fields, Path targetRoot, Optional<Path> stagingRoot,
            Optional<PathOverride> override) {
        var sourcePath = override.map(paths -> resolve(paths.sourcePath().toString()))
                .orElseGet(() -> resolve(fields.requiredString("source-path")));
        var targetPath = override.map(paths -> resolve(paths.targetPath().toString()))
                .or(() -> fields.string("target-path").map(ConfigurationLoader::resolve))
                .orElseGet(() -> deriveTarget(targetRoot, sourcePath));
        var whenAdoptingTarget = fields.choice("when-adopting-target",
                WhenAdoptingTarget.values(), WhenAdoptingTarget::value);
        var archiveRoot = fields.string("source-archive-root").map(ConfigurationLoader::resolve);
        if (whenAdoptingTarget.filter(WhenAdoptingTarget.ARCHIVE_SOURCE::equals).isPresent() && archiveRoot.isEmpty()) {
            throw new ConfigurationException("source-archive-root is required when when-adopting-target is archive-source");
        }
        return new Relocation(sourcePath, targetPath,
                fields.choice("when-source-and-target-directories-exist",
                        WhenSourceAndTargetDirectoriesExist.values(), WhenSourceAndTargetDirectoriesExist::value),
                fields.choice("when-only-target-exists", WhenOnlyTargetExists.values(), WhenOnlyTargetExists::value),
                whenAdoptingTarget, archiveRoot, stagingRoot);
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

    private static String at(Mark mark) {
        return mark == null ? "" : "Line " + (mark.getLine() + 1) + ", column " + (mark.getColumn() + 1) + ": ";
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
