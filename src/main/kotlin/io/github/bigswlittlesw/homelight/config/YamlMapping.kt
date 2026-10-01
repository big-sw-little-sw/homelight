package io.github.bigswlittlesw.homelight.config

import org.yaml.snakeyaml.error.Mark
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import org.yaml.snakeyaml.nodes.Tag
import java.util.Arrays
import java.util.Collections
import java.util.Optional
import java.util.function.Function
import java.util.stream.Collectors

/**
 * A strict view of one YAML mapping node, read by key as strings, lists or child mappings.
 *
 * - Keys must be allowed and unique; [of] checks them.
 * - Any scalar reads as its string. A missing key or a null, empty or blank scalar is absent, and an
 *   absent mapping reads as empty. A value of another shape, or an absent required value, fails when read.
 * - A failure is a [Violation] whose message names the value by dotted path, e.g.
 *   `Unknown key homelight.relocations[0].x`.
 *
 * Nodes come from `Yaml.compose`, not `load`: composed nodes keep their marks, and explicit tags never
 * construct Java objects.
 */
@JvmRecord
internal data class YamlMapping(val path: String, val node: Node?, val values: Map<String, Node>) {

    /**
     * `mark` is null when there is no node to point at, as in an empty document. `path` is the mapping's;
     * `key` is empty when the failure concerns the mapping itself.
     */
    class Violation(node: Node?, val path: String, val key: String, message: String) : RuntimeException(message) {
        val mark: Mark? = node?.startMark
    }

    fun qualify(key: String): String = qualify(path, key)

    fun mapping(key: String, allowed: Set<String>): Optional<YamlMapping> =
        value(key).map { child -> of(qualify(key), child, allowed) }

    fun requiredMapping(key: String, allowed: Set<String>): YamlMapping =
        mapping(key, allowed).orElseThrow { missing(key) }

    fun string(key: String): Optional<String> =
        value(key).map { child -> expect(key, child, ScalarNode::class.java, "string").value }

    fun requiredString(key: String): String = string(key).orElseThrow { missing(key) }

    /** A string that must be the `name` of one of `choices`. */
    fun <E> choice(key: String, choices: Array<E>, name: Function<E, String>): Optional<E> =
        string(key).map { text ->
            Arrays.stream(choices)
                .filter { choice -> name.apply(choice) == text }
                .findFirst()
                .orElseThrow {
                    Violation(
                        values[key], path, key, "Invalid value '" + text + "' for " +
                                qualify(key) + "; expected one of " +
                                Arrays.stream(choices).map(name).collect(Collectors.joining(", ")),
                    )
                }
        }

    fun list(key: String): Optional<SequenceNode> =
        value(key).map { child -> expect(key, child, SequenceNode::class.java, "list") }

    fun requiredList(key: String): SequenceNode = list(key).orElseThrow { missing(key) }

    /** The strings of an optional list, skipping absent items. */
    fun strings(key: String): List<String> = list(key).stream()
        .flatMap { sequence -> sequence.value.stream() }
        .filter { item -> !absent(item) }
        .map { item -> expect(key, item, ScalarNode::class.java, "string").value }
        .toList()

    private fun value(key: String): Optional<Node> = Optional.ofNullable(values[key]).filter { child -> !absent(child) }

    /** A missing value is reported at the mapping that should have held it. */
    private fun missing(key: String): Violation = Violation(node, path, key, "Missing required key " + qualify(key))

    private fun <T : Node> expect(key: String, child: Node, type: Class<T>, shape: String): T {
        if (!type.isInstance(child)) {
            throw Violation(child, path, key, "Expected a " + shape + " for " + qualify(key))
        }
        return type.cast(child)
    }

    companion object {
        /** `path` names the mapping in messages; it is empty for the document root. */
        fun of(path: String, node: Node?, allowed: Set<String>): YamlMapping {
            if (absent(node)) {
                return YamlMapping(path, node, mapOf())
            }
            val name = if (path.isEmpty()) "the document" else path
            if (node !is MappingNode) {
                throw Violation(node, path, "", "Expected a mapping for $name")
            }
            val values = LinkedHashMap<String, Node>()
            for (tuple in node.value) {
                val keyNode = tuple.keyNode
                // A key that is not a scalar is never allowed.
                val key = if (keyNode is ScalarNode) keyNode.value else ""
                if (!allowed.contains(key)) {
                    throw Violation(keyNode, path, key, "Unknown key " + (if (key.isEmpty()) "in $name" else qualify(path, key)))
                }
                if (values.putIfAbsent(key, tuple.valueNode) != null) {
                    throw Violation(keyNode, path, key, "Duplicate key " + qualify(path, key))
                }
            }
            return YamlMapping(path, node, Collections.unmodifiableMap(values))
        }

        private fun absent(node: Node?): Boolean =
            node == null || node is ScalarNode && (Tag.NULL == node.tag || node.value.isJavaBlank())

        private fun qualify(path: String, key: String): String = if (path.isEmpty()) key else "$path.$key"
    }
}
