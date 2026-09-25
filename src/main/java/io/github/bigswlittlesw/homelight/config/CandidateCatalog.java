package io.github.bigswlittlesw.homelight.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/// Catalog contents only: no candidate filesystem access, selection, or policy.
public final class CandidateCatalog {
    public static final CandidateSource BUNDLED =
            new CandidateSource(CandidateSource.Kind.BUNDLED, "/candidates.yaml");

    private CandidateCatalog() {}

    public static Snapshot bundled(Path root) {
        var parser = new CandidateParser();
        try (var input = CandidateCatalog.class.getResourceAsStream(BUNDLED.location())) {
            if (input == null) {
                return parser.failure(BUNDLED, root, CandidateDiagnostic.Kind.RESOURCE,
                        "Bundled candidate resource is missing");
            }
            return parser.parse(BUNDLED, root, input.readNBytes(CandidateParser.MAX_BYTES + 1));
        } catch (IOException e) {
            return parser.failure(BUNDLED, root, CandidateDiagnostic.Kind.RESOURCE,
                    "Cannot read bundled candidate resource: " + e.getMessage());
        }
    }

    /// Input order stabilizes output order; it never gives advice precedence.
    /// Failed sources contribute diagnostics and no definitions.
    public static Merged merge(List<Snapshot> snapshots) {
        var paths = new LinkedHashMap<Path, List<CandidateDefinition>>();
        var diagnostics = new ArrayList<CandidateDiagnostic>();
        Path root = null;
        for (var snapshot : snapshots) {
            if (root != null && !root.equals(snapshot.root())) {
                throw new IllegalArgumentException("Cannot merge snapshots from different roots");
            }
            root = snapshot.root();
            diagnostics.addAll(snapshot.diagnostics());
            for (var definition : snapshot.definitions()) {
                paths.computeIfAbsent(definition.sourcePath(), ignored -> new ArrayList<>()).add(definition);
            }
        }
        var candidates = new ArrayList<Candidate>();
        paths.forEach((path, definitions) -> candidates.add(new Candidate(path, definitions)));
        return new Merged(candidates, diagnostics);
    }

    public record Snapshot(CandidateSource source, Path root, List<CandidateDefinition> definitions,
                           List<CandidateDiagnostic> diagnostics) {
        public Snapshot {
            Objects.requireNonNull(source);
            root = CandidateParser.normalizedRoot(root);
            definitions = List.copyOf(definitions);
            diagnostics = List.copyOf(diagnostics);
            if (!definitions.isEmpty() && !diagnostics.isEmpty()) {
                throw new IllegalArgumentException("A rejected source cannot contain definitions");
            }
            for (var definition : definitions) {
                if (!definition.source().equals(source)
                        || !definition.sourcePath().equals(CandidateParser.resolve(root, definition.originalPath()))) {
                    throw new IllegalArgumentException("Definition does not belong to this source/root");
                }
            }
            if (diagnostics.stream().anyMatch(diagnostic -> !diagnostic.source().equals(source))) {
                throw new IllegalArgumentException("Diagnostic does not belong to this source");
            }
        }

        public boolean accepted() { return diagnostics.isEmpty(); }
    }

    public record Candidate(Path sourcePath, List<CandidateDefinition> definitions) {
        public Candidate {
            Objects.requireNonNull(sourcePath);
            definitions = List.copyOf(definitions);
            if (definitions.isEmpty() || definitions.stream().anyMatch(d -> !d.sourcePath().equals(sourcePath))) {
                throw new IllegalArgumentException("Candidate needs matching definition occurrences");
            }
        }
    }

    public record Merged(List<Candidate> candidates, List<CandidateDiagnostic> diagnostics) {
        public Merged {
            candidates = List.copyOf(candidates);
            diagnostics = List.copyOf(diagnostics);
        }
    }
}
