package io.github.bigswlittlesw.homelight.config;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/// Loads one YAML configuration rooted at `homelight`.
///
/// Unknown keys, missing required keys and values of the wrong shape are rejected with their line and
/// column. A null or empty scalar counts as absent. Paths expand `~`, `~/` and `${USER}`, then become
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
        var homelight = Fields.of("", document(path), ROOT_KEYS).mapping("homelight", HOMELIGHT_KEYS);
        var targetRoot = resolve(homelight.requiredString("target-root"));
        var stagingRoot = homelight.optionalString("staging-root").map(ConfigurationLoader::resolve);
        if (stagingRoot.filter(root -> !root.startsWith(targetRoot)).isPresent()) {
            throw new ConfigurationException("staging-root must be under target-root");
        }
        var relocationNodes = homelight.list("relocations");
        var relocations = new ArrayList<Relocation>();
        for (int i = 0; i < relocationNodes.size(); i++) {
            var fields = Fields.of(homelight.key("relocations") + "[" + i + "]", relocationNodes.get(i), RELOCATION_KEYS);
            relocations.add(relocation(fields, targetRoot, stagingRoot, i == 0 ? override : Optional.empty()));
        }
        if (relocations.isEmpty() && override.isPresent()) {
            var fields = Fields.of(homelight.key("relocations") + "[0]", null, RELOCATION_KEYS);
            relocations.add(relocation(fields, targetRoot, stagingRoot, override));
        }
        var ignoredSourcePaths = homelight.list("ignored-source-paths").stream()
                .filter(ConfigurationLoader::present)
                .map(node -> resolve(string(node, homelight.key("ignored-source-paths"))))
                .toList();
        var sharedList = homelight.optionalMapping("discovery", DISCOVERY_KEYS)
                .flatMap(discovery -> discovery.optionalString("shared-list"))
                .flatMap(DiscoverySetting::parse);
        return new HomeLightConfiguration(targetRoot, relocations, ignoredSourcePaths, sharedList);
    }

    private static Node document(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new ConfigurationException("Configuration file does not exist: " + path);
        }
        String text;
        try {
            text = Files.readString(path);
        } catch (IOException exception) {
            throw new ConfigurationException("Unable to read configuration " + path, exception);
        }
        try {
            // compose() builds nodes only, so explicit tags never instantiate Java types.
            return new Yaml(new SafeConstructor(new LoaderOptions())).compose(new StringReader(text));
        } catch (MarkedYAMLException exception) {
            var problem = exception.getProblem() == null ? "invalid syntax" : exception.getProblem();
            throw new ConfigurationException(at(exception.getProblemMark()) + "Malformed YAML: " + problem, exception);
        } catch (YAMLException exception) {
            throw new ConfigurationException("Malformed YAML: " + exception.getMessage(), exception);
        }
    }

    private static Relocation relocation(Fields fields, Path targetRoot, Optional<Path> stagingRoot,
            Optional<PathOverride> override) {
        var sourcePath = override.map(paths -> resolve(paths.sourcePath().toString()))
                .orElseGet(() -> resolve(fields.requiredString("source-path")));
        var targetPath = override.map(paths -> resolve(paths.targetPath().toString()))
                .or(() -> fields.optionalString("target-path").map(ConfigurationLoader::resolve))
                .orElseGet(() -> deriveTarget(targetRoot, sourcePath));
        var whenAdoptingTarget = fields.policy("when-adopting-target",
                WhenAdoptingTarget.values(), WhenAdoptingTarget::value);
        var archiveRoot = fields.optionalString("source-archive-root").map(ConfigurationLoader::resolve);
        if (whenAdoptingTarget.filter(WhenAdoptingTarget.ARCHIVE_SOURCE::equals).isPresent() && archiveRoot.isEmpty()) {
            throw new ConfigurationException("source-archive-root is required when when-adopting-target is archive-source");
        }
        return new Relocation(sourcePath, targetPath,
                fields.policy("when-source-and-target-directories-exist",
                        WhenSourceAndTargetDirectoriesExist.values(), WhenSourceAndTargetDirectoriesExist::value),
                fields.policy("when-only-target-exists", WhenOnlyTargetExists.values(), WhenOnlyTargetExists::value),
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

    /// The keys of one YAML mapping, checked against the allowed set. `name` is the dotted key used in messages;
    /// `node` is null for a mapping that is absent.
    private record Fields(String name, Node node, Map<String, Node> values) {
        static Fields of(String name, Node node, Set<String> allowed) {
            var values = new LinkedHashMap<String, Node>();
            if (present(node)) {
                if (!(node instanceof MappingNode mapping)) {
                    throw invalid(node, "Expected a mapping for " + name);
                }
                for (var tuple : mapping.getValue()) {
                    var key = tuple.getKeyNode() instanceof ScalarNode scalar ? scalar.getValue() : null;
                    var qualified = name.isEmpty() ? key : name + "." + key;
                    if (key == null || !allowed.contains(key)) {
                        throw invalid(tuple.getKeyNode(), "Unknown key " + (key == null ? "in " + name : qualified));
                    }
                    if (values.putIfAbsent(key, tuple.getValueNode()) != null) {
                        throw invalid(tuple.getKeyNode(), "Duplicate key " + qualified);
                    }
                }
            }
            return new Fields(name, node, values);
        }

        String key(String key) {
            return name.isEmpty() ? key : name + "." + key;
        }

        Optional<Node> optional(String key) {
            return Optional.ofNullable(values.get(key)).filter(ConfigurationLoader::present);
        }

        Fields mapping(String key, Set<String> allowed) {
            return optionalMapping(key, allowed).orElseThrow(() -> missing(key));
        }

        Optional<Fields> optionalMapping(String key, Set<String> allowed) {
            return optional(key).map(child -> of(key(key), child, allowed));
        }

        String requiredString(String key) {
            return optionalString(key).orElseThrow(() -> missing(key));
        }

        Optional<String> optionalString(String key) {
            return optional(key).map(child -> string(child, key(key)));
        }

        List<Node> list(String key) {
            return optional(key).map(child -> {
                if (!(child instanceof SequenceNode sequence)) {
                    throw invalid(child, "Expected a list for " + key(key));
                }
                return sequence.getValue();
            }).orElse(List.of());
        }

        <E extends Enum<E>> Optional<E> policy(String key, E[] choices, Function<E, String> value) {
            return optionalString(key).map(text -> Arrays.stream(choices)
                    .filter(choice -> value.apply(choice).equals(text))
                    .findFirst()
                    .orElseThrow(() -> invalid(values.get(key), "Invalid value '" + text + "' for " + key(key)
                            + "; expected one of " + Arrays.stream(choices).map(value).collect(Collectors.joining(", ")))));
        }

        private ConfigurationException missing(String key) {
            return invalid(node, "Missing required key " + key(key));
        }
    }

    private static boolean present(Node node) {
        return node != null
                && !(node instanceof ScalarNode scalar && (Tag.NULL.equals(scalar.getTag()) || scalar.getValue().isEmpty()));
    }

    private static String string(Node node, String name) {
        if (!(node instanceof ScalarNode scalar)) {
            throw invalid(node, "Expected a string for " + name);
        }
        return scalar.getValue();
    }

    private static ConfigurationException invalid(Node node, String message) {
        return new ConfigurationException((node == null ? "" : at(node.getStartMark())) + message);
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
