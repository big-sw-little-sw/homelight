package io.github.bigswlittlesw.homelight.config

import io.github.bigswlittlesw.homelight.config.CandidateDiagnostic.Kind
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.Mark
import org.yaml.snakeyaml.error.MarkedYAMLException
import org.yaml.snakeyaml.error.YAMLException
import org.yaml.snakeyaml.events.AliasEvent
import org.yaml.snakeyaml.events.CollectionEndEvent
import org.yaml.snakeyaml.events.CollectionStartEvent
import org.yaml.snakeyaml.events.DocumentStartEvent
import org.yaml.snakeyaml.events.NodeEvent
import org.yaml.snakeyaml.events.ScalarEvent
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.SequenceNode
import org.yaml.snakeyaml.nodes.Tag
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/**
 * Strict, atomic parsing of already-read UTF-8 contents for either source kind.
 * Shared I/O and its deadlines belong to the caller, not this lexical boundary.
 */
class CandidateParser {
    fun parse(source: CandidateSource, root: Path, contents: ByteArray): CandidateCatalog.Snapshot {
        @Suppress("NAME_SHADOWING")
        val root = normalizedRoot(root)
        if (contents.size > MAX_BYTES) {
            return failure(source, root, Kind.LIMIT, "Input exceeds 1 MiB UTF-8 limit")
        }
        try {
            val text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(contents)).toString()
            val options = LoaderOptions()
            options.setAllowDuplicateKeys(false)
            options.setMaxAliasesForCollections(0)
            options.setNestingDepthLimit(MAX_DEPTH)
            options.setCodePointLimit(MAX_BYTES)
            val yaml = Yaml(SafeConstructor(options))
            // Events retain anchors and explicit tags which composition can otherwise erase.
            // Bound depth before composition; never construct arbitrary Java objects.
            validateEvents(source, yaml, text)
            val document: Node? = yaml.compose(StringReader(text))
            val top = YamlMapping.of("", document, TOP_KEYS)
            val apps = top.list("apps")
            val directories = top.list("directories")
            if (apps == null && directories == null) {
                throw invalid(
                    source, Kind.SCHEMA, document?.startMark, 0, "", "",
                    "At least one of apps or directories is required",
                )
            }
            val definitions = ArrayList<CandidateDefinition>()
            val groups: List<Node> = apps?.value.orEmpty()
            if (groups.size > MAX_APPS) {
                throw invalid(source, Kind.LIMIT, apps?.startMark, 0, "", "apps", "Too many app groups")
            }
            for (i in groups.indices) {
                val fields = YamlMapping.of("apps[$i]", groups[i], APP_KEYS)
                val name = fields.requiredString("name")
                if (name != name.javaStrip()) {
                    throw invalid(
                        source, Kind.SCHEMA, fields.values.getValue("name").startMark, 0, fields.path,
                        "name", "App label must not have leading or trailing whitespace",
                    )
                }
                appendDirectories(
                    fields.requiredList("directories"), root, source, name,
                    fields.qualify("directories"), definitions,
                )
            }
            if (directories != null) {
                appendDirectories(directories, root, source, null, "directories", definitions)
            }
            return CandidateCatalog.Snapshot.of(source, root, definitions, listOf())
        } catch (e: CharacterCodingException) {
            return failure(source, root, Kind.ENCODING, "Input is not valid UTF-8")
        } catch (e: YamlMapping.Violation) {
            return rejected(root, schema(source, e, 0))
        } catch (e: Invalid) {
            return rejected(root, e)
        } catch (e: MarkedYAMLException) {
            return rejected(
                root, invalid(
                    source, Kind.SYNTAX, e.problemMark, 0, "", "",
                    e.problem ?: "Invalid YAML",
                ),
            )
        } catch (e: YAMLException) {
            return failure(source, root, Kind.SYNTAX, "Invalid YAML: " + e.message)
        }
    }

    internal fun failure(source: CandidateSource, root: Path, kind: Kind, message: String): CandidateCatalog.Snapshot =
        rejected(root, invalid(source, kind, null, 0, "", "", message))

    /** Rejects the whole source with one diagnostic. */
    private class Invalid(val diagnostic: CandidateDiagnostic) : RuntimeException(diagnostic.message)

    companion object {
        const val MAX_BYTES = 1_048_576
        const val MAX_RECORDS = 10_000
        const val MAX_APPS = 10_000
        const val MAX_DEPTH = 8
        const val MAX_STRING_CHARACTERS = 4_096
        private val TOP_KEYS = setOf("apps", "directories")
        private val APP_KEYS = setOf("name", "directories")
        private val RECORD_KEYS = setOf("path", "advice", "reason")
        private val CORE_TAGS = setOf(
            Tag.STR.value, Tag.MAP.value, Tag.SEQ.value, Tag.NULL.value,
            Tag.BOOL.value, Tag.INT.value, Tag.FLOAT.value, Tag.TIMESTAMP.value,
        )
        private val EXPANSION = Regex("\\$(?:\\{|[A-Za-z_])")

        private fun appendDirectories(
            sequence: SequenceNode, root: Path, source: CandidateSource,
            app: String?, location: String, definitions: MutableList<CandidateDefinition>,
        ) {
            if (sequence.value.size > MAX_RECORDS - definitions.size) {
                throw invalid(
                    source, Kind.LIMIT, sequence.startMark, 0, location, "directories",
                    "Too many records across catalog",
                )
            }
            for (i in sequence.value.indices) {
                val node = sequence.value[i]
                val index = definitions.size + 1
                try {
                    val fields = YamlMapping.of("$location[$i]", node, RECORD_KEYS)
                    val path = fields.requiredString("path")
                    val resolved = try {
                        resolve(root, path)
                    } catch (e: IllegalArgumentException) {
                        // Both resolve's own failures and Path.of's InvalidPathException carry a message.
                        throw invalid(
                            source, Kind.UNSAFE_PATH, fields.values.getValue("path").startMark, index,
                            fields.path, "path", e.message!!,
                        )
                    }
                    val advice = fields.choice("advice", CandidateDefinition.Advice.entries) { choice ->
                        if (choice == CandidateDefinition.Advice.CONSIDER) "consider" else "usually-unnecessary"
                    }
                    val mark = node.startMark
                    definitions.add(
                        CandidateDefinition(
                            resolved, source, index, mark.line + 1,
                            mark.column + 1, fields.path, path, app, advice, fields.string("reason"),
                        ),
                    )
                } catch (e: YamlMapping.Violation) {
                    throw schema(source, e, index)
                }
            }
        }

        internal fun normalizedRoot(root: Path): Path {
            require(root.isAbsolute) { "Source root must be absolute" }
            return root.normalize()
        }

        internal fun validatePath(path: String) {
            if (path.isJavaBlank() || path.startsWith("/") || path.startsWith("~") || path.contains("\\")
                || path.matches(Regex("^[A-Za-z][A-Za-z0-9+.-]*:.*"))
                || path.indexOf('*') >= 0 || path.indexOf('?') >= 0 || path.indexOf('[') >= 0 || path.indexOf(']') >= 0
                || path.codePoints().anyMatch { Character.isISOControl(it) }
                || EXPANSION.containsMatchIn(path)
            ) {
                throw IllegalArgumentException("Path must be a literal portable relative path")
            }
            for (component in path.split("/")) {
                if (component == "..") throw IllegalArgumentException("Parent path components are forbidden")
            }
            val relative = Path.of(path)
            if (relative.isAbsolute || relative.normalize().toString().isEmpty()) {
                throw IllegalArgumentException("Path must name a strict descendant of the source root")
            }
        }

        internal fun resolve(root: Path, path: String): Path {
            validatePath(path)
            val resolved = root.resolve(path).normalize()
            if (resolved == root || !resolved.startsWith(root)) {
                throw IllegalArgumentException("Path must name a strict descendant of the source root")
            }
            return resolved
        }

        /**
         * Rules beyond [YamlMapping], because a shared candidate list is written by someone else: one
         * document, no anchors or aliases (no expansion bombs), only core tags, and bounded depth and strings.
         * Events are checked because composed nodes no longer show anchors, and tags only as resolved.
         */
        private fun validateEvents(source: CandidateSource, yaml: Yaml, text: String) {
            var depth = 0
            var documents = 0
            for (event in yaml.parse(StringReader(text))) {
                if (event is DocumentStartEvent && ++documents > 1) {
                    throw invalid(source, Kind.SCHEMA, event.startMark, 0, "", "", "Only one document is allowed")
                }
                if (event is AliasEvent || event is NodeEvent && event.anchor != null) {
                    throw invalid(source, Kind.SCHEMA, event.startMark, 0, "", "", "Aliases and anchors are forbidden")
                }
                var tag: String? = null
                if (event is CollectionStartEvent) {
                    depth++
                    tag = event.tag
                } else if (event is CollectionEndEvent) {
                    depth--
                } else if (event is ScalarEvent) {
                    tag = event.tag
                    if (event.value.codePointCount(0, event.value.length) > MAX_STRING_CHARACTERS) {
                        throw invalid(source, Kind.LIMIT, event.startMark, 0, "", "", "String exceeds 4096 characters")
                    }
                }
                if (depth > MAX_DEPTH) {
                    throw invalid(source, Kind.LIMIT, event.startMark, 0, "", "", "Nesting exceeds depth 8")
                }
                if (tag != null && !CORE_TAGS.contains(tag)) {
                    throw invalid(source, Kind.SCHEMA, event.startMark, 0, "", "", "Unsupported YAML tag")
                }
            }
        }

        private fun rejected(root: Path, e: Invalid): CandidateCatalog.Snapshot =
            CandidateCatalog.Snapshot.of(e.diagnostic.source, root, listOf(), listOf(e.diagnostic))

        /** `mark` is null when there is no position. */
        private fun invalid(
            source: CandidateSource, kind: Kind, mark: Mark?, index: Int, location: String,
            key: String, message: String,
        ): Invalid = Invalid(
            CandidateDiagnostic(
                source, kind, index, if (mark == null) 0 else mark.line + 1,
                if (mark == null) 0 else mark.column + 1, location, key, message,
            ),
        )

        /** The reader's message already names the value; the diagnostic adds the record index and location. */
        private fun schema(source: CandidateSource, violation: YamlMapping.Violation, index: Int): Invalid =
            invalid(source, Kind.SCHEMA, violation.mark, index, violation.path, violation.key, violation.message)
    }
}
