package io.github.bigswlittlesw.homelight.config;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.events.*;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.Tag;

import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static io.github.bigswlittlesw.homelight.config.YamlMapping.Typing.CORE;

/// Strict, atomic parsing of already-read UTF-8 contents for either source kind.
/// Shared I/O and its deadlines belong to the caller, not this lexical boundary.
public final class CandidateParser {
    public static final int MAX_BYTES = 1_048_576;
    public static final int MAX_RECORDS = 10_000;
    public static final int MAX_APPS = 10_000;
    public static final int MAX_DEPTH = 8;
    public static final int MAX_STRING_CHARACTERS = 4_096;
    private static final Set<String> TOP_KEYS = Set.of("apps", "directories");
    private static final Set<String> APP_KEYS = Set.of("name", "directories");
    private static final Set<String> RECORD_KEYS = Set.of("path", "advice", "reason");
    private static final Set<String> CORE_TAGS = Set.of(
            Tag.STR.getValue(), Tag.MAP.getValue(), Tag.SEQ.getValue(), Tag.NULL.getValue(),
            Tag.BOOL.getValue(), Tag.INT.getValue(), Tag.FLOAT.getValue(), Tag.TIMESTAMP.getValue());
    private static final Pattern EXPANSION = Pattern.compile("\\$(?:\\{|[A-Za-z_])");

    public CandidateCatalog.Snapshot parse(CandidateSource source, Path root, byte[] contents) {
        Objects.requireNonNull(source);
        root = normalizedRoot(root);
        Objects.requireNonNull(contents);
        if (contents.length > MAX_BYTES) {
            return failure(source, root, CandidateDiagnostic.Kind.LIMIT, "Input exceeds 1 MiB UTF-8 limit");
        }
        try {
            var text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(contents)).toString();
            var options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setMaxAliasesForCollections(0);
            options.setNestingDepthLimit(MAX_DEPTH);
            options.setCodePointLimit(MAX_BYTES);
            var yaml = new Yaml(new SafeConstructor(options));
            // Events retain anchors and explicit tags which composition can otherwise erase.
            // Bound depth before composition; never construct arbitrary Java objects.
            validateEvents(yaml, text);
            var document = yaml.compose(new StringReader(text));
            var top = YamlMapping.of(CORE, "", document, TOP_KEYS);
            if (top.values().isEmpty()) {
                throw invalid(CandidateDiagnostic.Kind.SCHEMA, document, 0, "",
                        "At least one of apps or directories is required");
            }
            var definitions = new ArrayList<CandidateDefinition>();
            if (top.values().containsKey("apps")) {
                var apps = top.requiredList("apps");
                if (apps.getValue().size() > MAX_APPS) {
                    throw invalid(CandidateDiagnostic.Kind.LIMIT, apps, 0, "apps", "Too many app groups");
                }
                for (int i = 0; i < apps.getValue().size(); i++) {
                    var node = apps.getValue().get(i);
                    var location = "apps[" + i + "]";
                    try {
                        var fields = YamlMapping.of(CORE, "", node, APP_KEYS);
                        var name = fields.requiredString("name");
                        if (!name.equals(name.strip())) {
                            throw invalid(CandidateDiagnostic.Kind.SCHEMA, fields.values().get("name"), 0, "name",
                                    "App label must not have leading or trailing whitespace");
                        }
                        appendDirectories(fields, root, source, Optional.of(name), location + ".directories", definitions);
                    } catch (YamlMapping.Violation e) {
                        throw schema(e, 0).at(location, node);
                    } catch (Invalid e) {
                        throw e.at(location, node);
                    }
                }
            }
            if (top.values().containsKey("directories")) {
                appendDirectories(top, root, source, Optional.empty(), "directories", definitions);
            }
            return new CandidateCatalog.Snapshot(source, root, definitions, List.of());
        } catch (CharacterCodingException e) {
            return failure(source, root, CandidateDiagnostic.Kind.ENCODING, "Input is not valid UTF-8");
        } catch (YamlMapping.Violation e) {
            return rejected(source, root, schema(e, 0));
        } catch (Invalid e) {
            return rejected(source, root, e);
        } catch (MarkedYAMLException e) {
            var mark = e.getProblemMark();
            var diagnostic = new CandidateDiagnostic(source, CandidateDiagnostic.Kind.SYNTAX, 0,
                    mark == null ? 0 : mark.getLine() + 1, mark == null ? 0 : mark.getColumn() + 1,
                    "", "", e.getProblem() == null ? "Invalid YAML" : e.getProblem());
            return new CandidateCatalog.Snapshot(source, root, List.of(), List.of(diagnostic));
        } catch (YAMLException e) {
            return failure(source, root, CandidateDiagnostic.Kind.SYNTAX, "Invalid YAML: " + e.getMessage());
        }
    }

    /// Appends the records listed under `owner`'s required `directories` key.
    private static void appendDirectories(YamlMapping owner, Path root, CandidateSource source, Optional<String> app,
                                          String location, List<CandidateDefinition> definitions) {
        try {
            var sequence = owner.requiredList("directories");
            if (sequence.getValue().size() > MAX_RECORDS - definitions.size()) {
                throw invalid(CandidateDiagnostic.Kind.LIMIT, sequence, 0, "directories", "Too many records across catalog");
            }
            for (int i = 0; i < sequence.getValue().size(); i++) {
                var node = sequence.getValue().get(i);
                var recordLocation = location + "[" + i + "]";
                int index = definitions.size() + 1;
                try {
                    var fields = YamlMapping.of(CORE, "", node, RECORD_KEYS);
                    var path = fields.string("path").orElseThrow(() ->
                            invalid(CandidateDiagnostic.Kind.SCHEMA, node, index, "path", "Required path is missing"));
                    Path resolved;
                    try {
                        resolved = resolve(root, path);
                    } catch (IllegalArgumentException e) {
                        throw invalid(CandidateDiagnostic.Kind.UNSAFE_PATH, fields.values().get("path"), index, "path",
                                e.getMessage());
                    }
                    Optional<CandidateDefinition.Advice> advice = fields.string("advice").map(value -> switch (value) {
                        case "consider" -> CandidateDefinition.Advice.CONSIDER;
                        case "usually-unnecessary" -> CandidateDefinition.Advice.USUALLY_UNNECESSARY;
                        default -> throw invalid(CandidateDiagnostic.Kind.SCHEMA, fields.values().get("advice"), index,
                                "advice", "Advice must be consider or usually-unnecessary");
                    });
                    var reason = fields.string("reason");
                    var mark = node.getStartMark();
                    definitions.add(new CandidateDefinition(resolved, source, index,
                            mark.getLine() + 1, mark.getColumn() + 1, recordLocation, path, app, advice, reason));
                } catch (YamlMapping.Violation e) {
                    throw schema(e, index).at(recordLocation, node);
                } catch (Invalid e) {
                    throw e.at(recordLocation, node);
                }
            }
        } catch (YamlMapping.Violation e) {
            throw schema(e, 0).at(location, owner.values().get("directories"));
        } catch (Invalid e) {
            throw e.at(location, owner.values().get("directories"));
        }
    }

    CandidateCatalog.Snapshot failure(CandidateSource source, Path root, CandidateDiagnostic.Kind kind, String message) {
        return new CandidateCatalog.Snapshot(source, root, List.of(),
                List.of(new CandidateDiagnostic(source, kind, 0, 0, 0, "", "", message)));
    }

    static Path normalizedRoot(Path root) {
        Objects.requireNonNull(root);
        if (!root.isAbsolute()) throw new IllegalArgumentException("Source root must be absolute");
        return root.normalize();
    }

    static void validatePath(String path) {
        if (path.isBlank() || path.startsWith("/") || path.startsWith("~") || path.contains("\\")
                || path.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*")
                || path.indexOf('*') >= 0 || path.indexOf('?') >= 0 || path.indexOf('[') >= 0 || path.indexOf(']') >= 0
                || path.codePoints().anyMatch(Character::isISOControl)
                || EXPANSION.matcher(path).find()) {
            throw new IllegalArgumentException("Path must be a literal portable relative path");
        }
        for (var component : path.split("/", -1)) {
            if (component.equals("..")) throw new IllegalArgumentException("Parent path components are forbidden");
        }
        var relative = Path.of(path);
        if (relative.isAbsolute() || relative.normalize().toString().isEmpty()) {
            throw new IllegalArgumentException("Path must name a strict descendant of the source root");
        }
    }

    static Path resolve(Path root, String path) {
        validatePath(path);
        var resolved = root.resolve(path).normalize();
        if (resolved.equals(root) || !resolved.startsWith(root)) {
            throw new IllegalArgumentException("Path must name a strict descendant of the source root");
        }
        return resolved;
    }

    /// Rules beyond [YamlMapping], because a shared candidate list is written by someone else: one
    /// document, no anchors or aliases (no expansion bombs), only core tags, and bounded depth and strings.
    /// Events are checked because composed nodes no longer show anchors, and tags only as resolved.
    private static void validateEvents(Yaml yaml, String text) {
        int depth = 0;
        int documents = 0;
        for (var event : yaml.parse(new StringReader(text))) {
            if (event instanceof DocumentStartEvent && ++documents > 1) {
                throw new Invalid(CandidateDiagnostic.Kind.SCHEMA, event.getStartMark(), 0, "", "Only one document is allowed");
            }
            if (event instanceof AliasEvent || event instanceof NodeEvent node && node.getAnchor() != null) {
                throw new Invalid(CandidateDiagnostic.Kind.SCHEMA, event.getStartMark(), 0, "", "Aliases and anchors are forbidden");
            }
            String tag = null;
            if (event instanceof CollectionStartEvent collection) {
                depth++;
                tag = collection.getTag();
            } else if (event instanceof CollectionEndEvent) {
                depth--;
            } else if (event instanceof ScalarEvent scalar) {
                tag = scalar.getTag();
                if (scalar.getValue().codePointCount(0, scalar.getValue().length()) > MAX_STRING_CHARACTERS) {
                    throw new Invalid(CandidateDiagnostic.Kind.LIMIT, event.getStartMark(), 0, "", "String exceeds 4096 characters");
                }
            }
            if (depth > MAX_DEPTH) {
                throw new Invalid(CandidateDiagnostic.Kind.LIMIT, event.getStartMark(), 0, "", "Nesting exceeds depth 8");
            }
            if (tag != null && !CORE_TAGS.contains(tag)) {
                throw new Invalid(CandidateDiagnostic.Kind.SCHEMA, event.getStartMark(), 0, "", "Unsupported YAML tag");
            }
        }
    }

    /// Words a shared-reader failure as a schema diagnostic. A missing required value is reported like a
    /// wrong-shaped one; only a missing `path` has its own message, raised by the caller.
    private static Invalid schema(YamlMapping.Violation violation, int index) {
        var key = violation.key().orElse("");
        var message = switch (violation.problem()) {
            case NOT_A_MAPPING, MISSING_MAPPING -> "Expected a mapping";
            case NOT_A_LIST, MISSING_LIST -> "Required sequence is missing or invalid";
            case NOT_A_STRING, MISSING_STRING -> "Expected a nonblank string";
            case UNKNOWN_KEY -> "Unknown key: " + key;
            case DUPLICATE_KEY -> "Duplicate key: " + key;
        };
        return new Invalid(CandidateDiagnostic.Kind.SCHEMA, violation.mark().orElse(null), index, key, message);
    }

    private static CandidateCatalog.Snapshot rejected(CandidateSource source, Path root, Invalid e) {
        var diagnostic = new CandidateDiagnostic(source, e.kind, e.recordIndex,
                e.mark == null ? 0 : e.mark.getLine() + 1,
                e.mark == null ? 0 : e.mark.getColumn() + 1, e.location, e.key, e.getMessage());
        return new CandidateCatalog.Snapshot(source, root, List.of(), List.of(diagnostic));
    }

    private static Invalid invalid(CandidateDiagnostic.Kind kind, Node node, int index, String key, String message) {
        return new Invalid(kind, node == null ? null : node.getStartMark(), index, key, message);
    }

    private static final class Invalid extends RuntimeException {
        private final CandidateDiagnostic.Kind kind;
        private Mark mark;
        private final int recordIndex;
        private final String key;
        private String location = "";

        private Invalid at(String location, Node fallback) {
            if (this.location.isEmpty()) this.location = location;
            if (mark == null && fallback != null) mark = fallback.getStartMark();
            return this;
        }

        private Invalid(CandidateDiagnostic.Kind kind, Mark mark, int recordIndex, String key, String message) {
            super(message);
            this.kind = kind;
            this.mark = mark;
            this.recordIndex = recordIndex;
            this.key = key;
        }
    }
}
