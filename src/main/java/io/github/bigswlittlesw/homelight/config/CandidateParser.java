package io.github.bigswlittlesw.homelight.config;

import io.github.bigswlittlesw.homelight.config.CandidateDiagnostic.Kind;
import io.github.bigswlittlesw.homelight.config.YamlDocument.Required;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/// Strict, atomic parsing of already-read UTF-8 contents for either source kind.
/// Shared I/O and its deadlines belong to the caller, not this lexical boundary.
public final class CandidateParser {
    public static final int MAX_BYTES = 1_048_576;
    public static final int MAX_RECORDS = 10_000;
    public static final int MAX_APPS = 10_000;
    private static final Pattern RECORD = Pattern.compile("(?:apps\\[(\\d+)]\\.)?directories\\[(\\d+)]");
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
            var document = YamlDocument.parse(text);
            var file = document.bind(CandidateFile.class);
            if (file == null || file.apps() == null && file.directories() == null) {
                throw invalid(source, Kind.SCHEMA, document.at(""), 0, "", "",
                        "At least one of apps or directories is required");
            }
            var groups = Objects.requireNonNullElse(file.apps(), List.<AppGroup>of());
            if (groups.size() > MAX_APPS) {
                throw invalid(source, Kind.LIMIT, document.at("apps"), 0, "", "apps", "Too many app groups");
            }
            var definitions = new ArrayList<CandidateDefinition>();
            for (int i = 0; i < groups.size(); i++) {
                var location = "apps[" + i + "]";
                var name = groups.get(i).name();
                if (!name.equals(name.strip())) {
                    throw invalid(source, Kind.SCHEMA, document.at(location + ".name"), 0, location, "name",
                            "App label must not have leading or trailing whitespace");
                }
                appendDirectories(document, groups.get(i).directories(), root, source, Optional.of(name),
                        location + ".directories", definitions);
            }
            if (file.directories() != null) {
                appendDirectories(document, file.directories(), root, source, Optional.empty(), "directories",
                        definitions);
            }
            return new CandidateCatalog.Snapshot(source, root, definitions, List.of());
        } catch (CharacterCodingException e) {
            return failure(source, root, Kind.ENCODING, "Input is not valid UTF-8");
        } catch (YamlDocument.Violation e) {
            return rejected(root, new CandidateDiagnostic(source, e.kind, recordIndex(e.document, e.location),
                    e.position.line(), e.position.column(), e.location, e.key, e.getMessage()));
        } catch (Invalid e) {
            return rejected(root, e.diagnostic);
        }
    }

    private static void appendDirectories(YamlDocument document, List<DirectoryEntry> entries, Path root,
                                          CandidateSource source, Optional<String> app, String location,
                                          List<CandidateDefinition> definitions) {
        if (entries.size() > MAX_RECORDS - definitions.size()) {
            throw invalid(source, Kind.LIMIT, document.at(location), 0, location, "directories",
                    "Too many records across catalog");
        }
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            int index = definitions.size() + 1;
            var recordLocation = location + "[" + i + "]";
            Path resolved;
            try {
                resolved = resolve(root, entry.path());
            } catch (IllegalArgumentException e) {
                throw invalid(source, Kind.UNSAFE_PATH, document.at(recordLocation + ".path"), index, recordLocation,
                        "path", e.getMessage());
            }
            var at = document.at(recordLocation);
            definitions.add(new CandidateDefinition(resolved, source, index, at.line(), at.column(), recordLocation,
                    entry.path(), app, Optional.ofNullable(entry.advice()), Optional.ofNullable(entry.reason())));
        }
    }

    CandidateCatalog.Snapshot failure(CandidateSource source, Path root, Kind kind, String message) {
        return rejected(root, invalid(source, kind, YamlDocument.Position.NONE, 0, "", "", message).diagnostic);
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

    /// The one-based index, across the document, of the record a binding failure is in, or zero outside records.
    /// Records are numbered in reading order: every app's directories, then the top-level directories.
    private static int recordIndex(YamlDocument document, String location) {
        var record = RECORD.matcher(location);
        if (!record.lookingAt()) return 0;
        int apps = record.group(1) == null ? document.size("apps") : Integer.parseInt(record.group(1));
        int before = 0;
        for (int i = 0; i < apps; i++) {
            before += document.size("apps[" + i + "].directories");
        }
        return before + Integer.parseInt(record.group(2)) + 1;
    }

    private static CandidateCatalog.Snapshot rejected(Path root, CandidateDiagnostic diagnostic) {
        return new CandidateCatalog.Snapshot(diagnostic.source(), root, List.of(), List.of(diagnostic));
    }

    private static Invalid invalid(CandidateSource source, Kind kind, YamlDocument.Position at, int index,
                                   String location, String key, String message) {
        return new Invalid(new CandidateDiagnostic(source, kind, index, at.line(), at.column(), location, key, message));
    }

    /// Rejects the whole source with one diagnostic.
    private static final class Invalid extends RuntimeException {
        private final CandidateDiagnostic diagnostic;

        private Invalid(CandidateDiagnostic diagnostic) {
            super(diagnostic.message());
            this.diagnostic = diagnostic;
        }
    }

    /// The file as written, bound by [YamlDocument]. Absent values are null; `parse` turns records into
    /// [CandidateDefinition]s, which validate paths and text.
    private record CandidateFile(List<AppGroup> apps, List<DirectoryEntry> directories) {
    }

    private record AppGroup(@Required String name, @Required List<DirectoryEntry> directories) {
    }

    private record DirectoryEntry(@Required String path, CandidateDefinition.Advice advice, String reason) {
    }
}
