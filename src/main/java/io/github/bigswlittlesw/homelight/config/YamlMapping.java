package io.github.bigswlittlesw.homelight.config;

import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/// A strict view of one YAML mapping node, read by key as strings, lists or child mappings.
///
/// - Every key is one of the allowed keys and appears once; otherwise [#of] fails.
/// - A value of the wrong shape, or a required value that is absent, fails when it is read.
/// - Absent means a missing key; under [Typing#LENIENT] a null or empty scalar is absent too.
/// - Every failure is a [Violation] with the offending node's mark (line and column), the mapping's
///   dotted `path` and the key.
///
/// Callers state the schema through the keys they allow and read, then apply their domain rules to the
/// values. Each caller catches [Violation] and words it as its own exception or diagnostic, so this
/// type holds no user-facing text.
///
/// Nodes come from `Yaml.compose`, not `load`: composed nodes keep their marks, and explicit tags never
/// construct Java objects.
record YamlMapping(Typing typing, String path, Node node, Map<String, Node> values) {

    enum Typing {
        /// Hand-written configuration: any scalar reads as a string, and `key:` with no value is the
        /// same as leaving the key out.
        LENIENT,
        /// Shared candidate lists: mappings, lists and strings must carry their core tag, so `5` or
        /// `true` is not a string; strings must be nonblank; only a missing key is absent.
        CORE;

        boolean absent(Node node) {
            return node == null || this == LENIENT && node instanceof ScalarNode scalar
                    && (Tag.NULL.equals(scalar.getTag()) || scalar.getValue().isEmpty());
        }

        boolean tagged(Node node, Tag tag) {
            return this == LENIENT || tag.equals(node.getTag());
        }
    }

    /// A missing required value is its own problem so each caller can word it differently from a
    /// value of the wrong shape.
    enum Problem {
        NOT_A_MAPPING, NOT_A_LIST, NOT_A_STRING,
        MISSING_MAPPING, MISSING_LIST, MISSING_STRING,
        UNKNOWN_KEY, DUPLICATE_KEY
    }

    /// `key` is empty when the problem concerns the mapping itself, or a key that is not a scalar.
    static final class Violation extends RuntimeException {
        private final Problem problem;
        private final Optional<Mark> mark;
        private final Optional<String> key;
        private final String name;

        private Violation(Problem problem, Node node, String path, Optional<String> key) {
            var name = key.map(k -> qualify(path, k)).orElse(path);
            super(problem + " at " + name);
            this.problem = problem;
            this.mark = Optional.ofNullable(node).map(Node::getStartMark);
            this.key = key;
            this.name = name;
        }

        Problem problem() {
            return problem;
        }

        Optional<Mark> mark() {
            return mark;
        }

        Optional<String> key() {
            return key;
        }

        /// The dotted name of the offending value, or of the mapping when there is no key.
        String name() {
            return name;
        }
    }

    /// Checks `node`'s keys against `allowed`. `path` names the mapping in violations; it may be empty.
    static YamlMapping of(Typing typing, String path, Node node, Set<String> allowed) {
        // An absent configuration section reads as empty; candidate data must be a mapping.
        if (typing == Typing.LENIENT && typing.absent(node)) {
            return new YamlMapping(typing, path, node, Map.of());
        }
        if (!(node instanceof MappingNode mapping) || !typing.tagged(node, Tag.MAP)) {
            throw new Violation(Problem.NOT_A_MAPPING, node, path, Optional.empty());
        }
        var values = new LinkedHashMap<String, Node>();
        for (var tuple : mapping.getValue()) {
            var keyNode = tuple.getKeyNode();
            // A lenient key that is not a scalar is simply unknown; a core key must itself be a valid string.
            var key = switch (typing) {
                case LENIENT -> keyNode instanceof ScalarNode scalar ? Optional.of(scalar.getValue()) : Optional.<String>empty();
                case CORE -> Optional.of(string(typing, path, keyNode, Optional.empty()));
            };
            if (key.filter(allowed::contains).isEmpty()) {
                throw new Violation(Problem.UNKNOWN_KEY, keyNode, path, key);
            }
            if (values.putIfAbsent(key.get(), tuple.getValueNode()) != null) {
                throw new Violation(Problem.DUPLICATE_KEY, keyNode, path, key);
            }
        }
        return new YamlMapping(typing, path, node, Collections.unmodifiableMap(values));
    }

    String qualify(String key) {
        return qualify(path, key);
    }

    Optional<YamlMapping> mapping(String key, Set<String> allowed) {
        return value(key).map(child -> of(typing, qualify(key), child, allowed));
    }

    YamlMapping requiredMapping(String key, Set<String> allowed) {
        return mapping(key, allowed).orElseThrow(() -> missing(Problem.MISSING_MAPPING, key));
    }

    Optional<String> string(String key) {
        return value(key).map(child -> string(typing, path, child, Optional.of(key)));
    }

    String requiredString(String key) {
        return string(key).orElseThrow(() -> missing(Problem.MISSING_STRING, key));
    }

    Optional<SequenceNode> list(String key) {
        return value(key).map(child -> {
            if (!(child instanceof SequenceNode sequence) || !typing.tagged(child, Tag.SEQ)) {
                throw new Violation(Problem.NOT_A_LIST, child, path, Optional.of(key));
            }
            return sequence;
        });
    }

    SequenceNode requiredList(String key) {
        return list(key).orElseThrow(() -> missing(Problem.MISSING_LIST, key));
    }

    /// The strings of an optional list, skipping absent items.
    List<String> strings(String key) {
        return list(key).stream()
                .flatMap(sequence -> sequence.getValue().stream())
                .filter(item -> !typing.absent(item))
                .map(item -> string(typing, path, item, Optional.of(key)))
                .toList();
    }

    private Optional<Node> value(String key) {
        return Optional.ofNullable(values.get(key)).filter(child -> !typing.absent(child));
    }

    /// A missing value is reported at the mapping that should have held it.
    private Violation missing(Problem problem, String key) {
        return new Violation(problem, node, path, Optional.of(key));
    }

    private static String string(Typing typing, String path, Node node, Optional<String> key) {
        if (!(node instanceof ScalarNode scalar) || !typing.tagged(node, Tag.STR)
                || typing == Typing.CORE && scalar.getValue().isBlank()) {
            throw new Violation(Problem.NOT_A_STRING, node, path, key);
        }
        return scalar.getValue();
    }

    private static String qualify(String path, String key) {
        return path.isEmpty() ? key : path + "." + key;
    }
}
