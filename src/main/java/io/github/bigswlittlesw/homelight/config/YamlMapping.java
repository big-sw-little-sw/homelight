package io.github.bigswlittlesw.homelight.config;

import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/// A strict view of one YAML mapping node, read by key as strings, lists or child mappings.
///
/// - Keys must be allowed and unique; [#of] checks them.
/// - Any scalar reads as its string. A missing key or a null, empty or blank scalar is absent, and an
///   absent mapping reads as empty. A value of another shape, or an absent required value, fails when read.
/// - A failure is a [Violation] whose message names the value by dotted path, e.g.
///   `Unknown key homelight.relocations[0].x`.
///
/// Nodes come from `Yaml.compose`, not `load`: composed nodes keep their marks, and explicit tags never
/// construct Java objects.
record YamlMapping(String path, Node node, Map<String, Node> values) {

    /// `mark` is null when there is no node to point at, as in an empty document. `path` is the mapping's;
    /// `key` is empty when the failure concerns the mapping itself.
    static final class Violation extends RuntimeException {
        final Mark mark;
        final String path, key;

        private Violation(Node node, String path, String key, String message) {
            super(message);
            this.mark = node == null ? null : node.getStartMark();
            this.path = path;
            this.key = key;
        }
    }

    /// `path` names the mapping in messages; it is empty for the document root.
    static YamlMapping of(String path, Node node, Set<String> allowed) {
        if (absent(node)) {
            return new YamlMapping(path, node, Map.of());
        }
        var name = path.isEmpty() ? "the document" : path;
        if (!(node instanceof MappingNode mapping)) {
            throw new Violation(node, path, "", "Expected a mapping for " + name);
        }
        var values = new LinkedHashMap<String, Node>();
        for (var tuple : mapping.getValue()) {
            var keyNode = tuple.getKeyNode();
            // A key that is not a scalar is never allowed.
            var key = keyNode instanceof ScalarNode scalar ? scalar.getValue() : "";
            if (!allowed.contains(key)) {
                throw new Violation(keyNode, path, key, "Unknown key " + (key.isEmpty() ? "in " + name : qualify(path, key)));
            }
            if (values.putIfAbsent(key, tuple.getValueNode()) != null) {
                throw new Violation(keyNode, path, key, "Duplicate key " + qualify(path, key));
            }
        }
        return new YamlMapping(path, node, Collections.unmodifiableMap(values));
    }

    String qualify(String key) {
        return qualify(path, key);
    }

    Optional<YamlMapping> mapping(String key, Set<String> allowed) {
        return value(key).map(child -> of(qualify(key), child, allowed));
    }

    YamlMapping requiredMapping(String key, Set<String> allowed) {
        return mapping(key, allowed).orElseThrow(() -> missing(key));
    }

    Optional<String> string(String key) {
        return value(key).map(child -> expect(key, child, ScalarNode.class, "string").getValue());
    }

    String requiredString(String key) {
        return string(key).orElseThrow(() -> missing(key));
    }

    /// A string that must be the `name` of one of `choices`.
    <E> Optional<E> choice(String key, E[] choices, Function<E, String> name) {
        return string(key).map(text -> Arrays.stream(choices)
                .filter(choice -> name.apply(choice).equals(text))
                .findFirst()
                .orElseThrow(() -> new Violation(values.get(key), path, key, "Invalid value '" + text + "' for "
                        + qualify(key) + "; expected one of "
                        + Arrays.stream(choices).map(name).collect(Collectors.joining(", ")))));
    }

    Optional<SequenceNode> list(String key) {
        return value(key).map(child -> expect(key, child, SequenceNode.class, "list"));
    }

    SequenceNode requiredList(String key) {
        return list(key).orElseThrow(() -> missing(key));
    }

    /// The strings of an optional list, skipping absent items.
    List<String> strings(String key) {
        return list(key).stream()
                .flatMap(sequence -> sequence.getValue().stream())
                .filter(item -> !absent(item))
                .map(item -> expect(key, item, ScalarNode.class, "string").getValue())
                .toList();
    }

    private Optional<Node> value(String key) {
        return Optional.ofNullable(values.get(key)).filter(child -> !absent(child));
    }

    /// A missing value is reported at the mapping that should have held it.
    private Violation missing(String key) {
        return new Violation(node, path, key, "Missing required key " + qualify(key));
    }

    private <T extends Node> T expect(String key, Node child, Class<T> type, String shape) {
        if (!type.isInstance(child)) {
            throw new Violation(child, path, key, "Expected a " + shape + " for " + qualify(key));
        }
        return type.cast(child);
    }

    private static boolean absent(Node node) {
        return node == null || node instanceof ScalarNode scalar
                && (Tag.NULL.equals(scalar.getTag()) || scalar.getValue().isBlank());
    }

    private static String qualify(String path, String key) {
        return path.isEmpty() ? key : path + "." + key;
    }
}
