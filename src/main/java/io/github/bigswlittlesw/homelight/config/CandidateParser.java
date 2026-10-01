package io.github.bigswlittlesw.homelight.config;

import io.github.bigswlittlesw.homelight.config.CandidateDiagnostic.Kind;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.events.*;
import org.yaml.snakeyaml.nodes.SequenceNode;
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
            return failure(source, root, Kind.LIMIT, "Input exceeds 1 MiB UTF-8 limit");
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
            validateEvents(source, yaml, text);
            var document = yaml.compose(new StringReader(text));
            var top = YamlMapping.of("", document, TOP_KEYS);
            var apps = top.list("apps");
            var directories = top.list("directories");
            if (apps.isEmpty() && directories.isEmpty()) {
                throw invalid(source, Kind.SCHEMA, document == null ? null : document.getStartMark(), 0, "", "",
                        "At least one of apps or directories is required");
            }
            var definitions = new ArrayList<CandidateDefinition>();
            var groups = apps.map(SequenceNode::getValue).orElse(List.of());
            if (groups.size() > MAX_APPS) {
                throw invalid(source, Kind.LIMIT, apps.get().getStartMark(), 0, "", "apps", "Too many app groups");
            }
            for (int i = 0; i < groups.size(); i++) {
                var fields = YamlMapping.of("apps[" + i + "]", groups.get(i), APP_KEYS);
                var name = fields.requiredString("name");
                if (!name.equals(name.strip())) {
                    throw invalid(source, Kind.SCHEMA, fields.values().get("name").getStartMark(), 0, fields.path(),
                            "name", "App label must not have leading or trailing whitespace");
                }
                appendDirectories(fields.requiredList("directories"), root, source, Optional.of(name),
                        fields.qualify("directories"), definitions);
            }
            if (directories.isPresent()) {
                appendDirectories(directories.get(), root, source, Optional.empty(), "directories", definitions);
            }
            return new CandidateCatalog.Snapshot(source, root, definitions, List.of());
        } catch (CharacterCodingException e) {
            return failure(source, root, Kind.ENCODING, "Input is not valid UTF-8");
        } catch (YamlMapping.Violation e) {
            return rejected(root, schema(source, e, 0));
        } catch (Invalid e) {
            return rejected(root, e);
        } catch (MarkedYAMLException e) {
            return rejected(root, invalid(source, Kind.SYNTAX, e.getProblemMark(), 0, "", "",
                    e.getProblem() == null ? "Invalid YAML" : e.getProblem()));
        } catch (YAMLException e) {
            return failure(source, root, Kind.SYNTAX, "Invalid YAML: " + e.getMessage());
        }
    }

    private static void appendDirectories(SequenceNode sequence, Path root, CandidateSource source,
                                          Optional<String> app, String location, List<CandidateDefinition> definitions) {
        if (sequence.getValue().size() > MAX_RECORDS - definitions.size()) {
            throw invalid(source, Kind.LIMIT, sequence.getStartMark(), 0, location, "directories",
                    "Too many records across catalog");
        }
        for (int i = 0; i < sequence.getValue().size(); i++) {
            var node = sequence.getValue().get(i);
            int index = definitions.size() + 1;
            try {
                var fields = YamlMapping.of(location + "[" + i + "]", node, RECORD_KEYS);
                var path = fields.requiredString("path");
                Path resolved;
                try {
                    resolved = resolve(root, path);
                } catch (IllegalArgumentException e) {
                    throw invalid(source, Kind.UNSAFE_PATH, fields.values().get("path").getStartMark(), index,
                            fields.path(), "path", e.getMessage());
                }
                var advice = fields.choice("advice", CandidateDefinition.Advice.values(),
                        choice -> choice == CandidateDefinition.Advice.CONSIDER ? "consider" : "usually-unnecessary");
                var mark = node.getStartMark();
                definitions.add(new CandidateDefinition(resolved, source, index, mark.getLine() + 1,
                        mark.getColumn() + 1, fields.path(), path, app, advice, fields.string("reason")));
            } catch (YamlMapping.Violation e) {
                throw schema(source, e, index);
            }
        }
    }

    CandidateCatalog.Snapshot failure(CandidateSource source, Path root, Kind kind, String message) {
        return rejected(root, invalid(source, kind, null, 0, "", "", message));
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
    private static void validateEvents(CandidateSource source, Yaml yaml, String text) {
        int depth = 0;
        int documents = 0;
        for (var event : yaml.parse(new StringReader(text))) {
            if (event instanceof DocumentStartEvent && ++documents > 1) {
                throw invalid(source, Kind.SCHEMA, event.getStartMark(), 0, "", "", "Only one document is allowed");
            }
            if (event instanceof AliasEvent || event instanceof NodeEvent node && node.getAnchor() != null) {
                throw invalid(source, Kind.SCHEMA, event.getStartMark(), 0, "", "", "Aliases and anchors are forbidden");
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
                    throw invalid(source, Kind.LIMIT, event.getStartMark(), 0, "", "", "String exceeds 4096 characters");
                }
            }
            if (depth > MAX_DEPTH) {
                throw invalid(source, Kind.LIMIT, event.getStartMark(), 0, "", "", "Nesting exceeds depth 8");
            }
            if (tag != null && !CORE_TAGS.contains(tag)) {
                throw invalid(source, Kind.SCHEMA, event.getStartMark(), 0, "", "", "Unsupported YAML tag");
            }
        }
    }

    private static CandidateCatalog.Snapshot rejected(Path root, Invalid e) {
        return new CandidateCatalog.Snapshot(e.diagnostic.source(), root, List.of(), List.of(e.diagnostic));
    }

    /// `mark` is null when there is no position.
    private static Invalid invalid(CandidateSource source, Kind kind, Mark mark, int index, String location,
                                   String key, String message) {
        return new Invalid(new CandidateDiagnostic(source, kind, index, mark == null ? 0 : mark.getLine() + 1,
                mark == null ? 0 : mark.getColumn() + 1, location, key, message));
    }

    /// The reader's message already names the value; the diagnostic adds the record index and location.
    private static Invalid schema(CandidateSource source, YamlMapping.Violation violation, int index) {
        return invalid(source, Kind.SCHEMA, violation.mark, index, violation.path, violation.key, violation.getMessage());
    }

    /// Rejects the whole source with one diagnostic.
    private static final class Invalid extends RuntimeException {
        private final CandidateDiagnostic diagnostic;

        private Invalid(CandidateDiagnostic diagnostic) {
            super(diagnostic.message());
            this.diagnostic = diagnostic;
        }
    }
}
