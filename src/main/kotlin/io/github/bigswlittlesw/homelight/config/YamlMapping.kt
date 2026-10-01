package io.github.bigswlittlesw.homelight.config

import org.yaml.snakeyaml.error.Mark
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import org.yaml.snakeyaml.nodes.Tag

/**
 * A strict view of one YAML mapping node, read by key as strings, lists or child mappings.
 *
 * - Keys must be allowed and unique; [of] checks them.
 * - Any scalar reads as its string. A missing key or a null, empty or blank scalar is absent (null), and
 *   an absent mapping reads as empty. A value of another shape, or an absent required value, fails when read.
 * - A failure is a [Violation] whose message names the value by dotted path, e.g.
 *   `Unknown key homelight.relocations[0].x`.
 *
 * Nodes come from `Yaml.compose`, not `load`: composed nodes keep their marks, and explicit tags never
 * construct Java objects.
 */
internal data class YamlMapping(val path: String, val node: Node?, val values: Map<String, Node>) {

    /**
     * `mark` is null when there is no node to point at, as in an empty document. `path` is the mapping's;
     * `key` is empty when the failure concerns the mapping itself.
     */
    class Violation(node: Node?, val path: String, val key: String, override val message: String) :
        RuntimeException(message) {
        val mark: Mark? = node?.startMark
    }

    fun qualify(key: String): String = qualify(path, key)

    fun mapping(key: String, allowed: Set<String>): YamlMapping? =
        value(key)?.let { child -> of(qualify(key), child, allowed) }

    fun requiredMapping(key: String, allowed: Set<String>): YamlMapping = mapping(key, allowed) ?: throw missing(key)

    fun string(key: String): String? = value(key)?.let { child -> expect<ScalarNode>(key, child, "string").value }

    fun requiredString(key: String): String = string(key) ?: throw missing(key)

    /** A string that must be the `name` of one of `choices`. */
    fun <E> choice(key: String, choices: List<E>, name: (E) -> String): E? {
        val text = string(key) ?: return null
        return choices.firstOrNull { choice -> name(choice) == text } ?: throw Violation(
            values[key], path, key,
            "Invalid value '$text' for ${qualify(key)}; expected one of " + choices.joinToString(", ", transform = name),
        )
    }

    fun list(key: String): SequenceNode? = value(key)?.let { child -> expect<SequenceNode>(key, child, "list") }

    fun requiredList(key: String): SequenceNode = list(key) ?: throw missing(key)

    /** The strings of an optional list, skipping absent items. */
    fun strings(key: String): List<String> = list(key)?.value.orEmpty()
        .filter { item -> !absent(item) }
        .map { item -> expect<ScalarNode>(key, item, "string").value }

    private fun value(key: String): Node? = values[key]?.takeIf { child -> !absent(child) }

    /** A missing value is reported at the mapping that should have held it. */
    private fun missing(key: String): Violation = Violation(node, path, key, "Missing required key " + qualify(key))

    private inline fun <reified T : Node> expect(key: String, child: Node, shape: String): T =
        child as? T ?: throw Violation(child, path, key, "Expected a $shape for ${qualify(key)}")

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
                if (key !in allowed) {
                    throw Violation(keyNode, path, key, "Unknown key " + (if (key.isEmpty()) "in $name" else qualify(path, key)))
                }
                if (values.putIfAbsent(key, tuple.valueNode) != null) {
                    throw Violation(keyNode, path, key, "Duplicate key " + qualify(path, key))
                }
            }
            return YamlMapping(path, node, values)
        }

        private fun absent(node: Node?): Boolean =
            node == null || node is ScalarNode && (Tag.NULL == node.tag || node.value.isJavaBlank())

        private fun qualify(path: String, key: String): String = if (path.isEmpty()) key else "$path.$key"
    }
}
