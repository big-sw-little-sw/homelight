package io.github.bigswlittlesw.homelight.application;

import io.github.bigswlittlesw.homelight.config.CandidateDefinition;
import io.github.bigswlittlesw.homelight.config.ConfigurationDraft;
import io.github.bigswlittlesw.homelight.config.ConfigurationValidator;
import io.github.bigswlittlesw.homelight.config.DiscoverySetting;
import io.github.bigswlittlesw.homelight.config.Relocation;
import io.github.bigswlittlesw.homelight.config.WhenAdoptingTarget;
import io.github.bigswlittlesw.homelight.config.WhenOnlyTargetExists;
import io.github.bigswlittlesw.homelight.config.WhenSourceAndTargetDirectoriesExist;
import io.github.bigswlittlesw.homelight.discovery.CandidateDiscovery;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/// Presentation-neutral, single-threaded setup state. Discovery never edits rows.
/// Configured relocations are read-only join/validation inputs, not an existing-file
/// editor. The caller owns discovery lifecycle and explicit create-only publication.
public final class SetupDraft {
    private Path sourceRoot;
    private Path targetRoot;
    private Optional<Path> sharedList;
    private final List<Relocation> configured;
    private final List<RowOccurrence> rows = new ArrayList<>();
    private Optional<CandidateDiscovery.Result> discovery = Optional.empty();
    private long generation = -1;

    public SetupDraft(Path sourceRoot, Path targetRoot, Optional<Path> sharedList, List<Relocation> configured) {
        this.sourceRoot = absolute(sourceRoot);
        this.targetRoot = absolute(targetRoot);
        this.sharedList = sharedList.map(DiscoverySetting::normalize);
        this.configured = List.copyOf(configured);
    }

    public Path sourceRoot() { return sourceRoot; }
    public Path targetRoot() { return targetRoot; }
    public Optional<Path> sharedList() { return sharedList; }
    public List<Row> rows() { return rows.stream().map(RowOccurrence::value).toList(); }
    public Optional<CandidateDiscovery.Result> discovery() { return discovery; }

    /// Explicit root edits re-resolve relative row values. Historical attribution
    /// stays historical; observations from the previous request cannot reattach.
    public void roots(Path source, Path target) {
        var nextSource = absolute(source);
        var nextTarget = absolute(target);
        sourceRoot = nextSource;
        targetRoot = nextTarget;
        invalidateDiscovery();
    }

    public void sharedList(String value) {
        sharedList = DiscoverySetting.parse(value);
        invalidateDiscovery();
    }

    /// Nonblocking. Use one discovery instance per setup session; call accept with
    /// its snapshots on the same thread that edits this draft.
    public void refresh(CandidateDiscovery worker) {
        generation = worker.refresh(sourceRoot, sharedList);
        discovery = Optional.empty();
    }

    public boolean accept(CandidateDiscovery.Result result) {
        if (generation < 0 || result.generation() != generation
                || !result.request().equals(Optional.of(new CandidateDiscovery.Request(sourceRoot, sharedList)))) return false;
        discovery = Optional.of(result);
        var candidates = candidates();
        rows.replaceAll(row -> remember(row, candidates));
        return true;
    }

    /// Manual rows may be incomplete while edited. Validate/Save checks all rows;
    /// no duplicate or malformed row is silently discarded by the join.
    public void append(Row row) {
        rows.add(remember(new RowOccurrence(row, List.of()), candidates()));
    }

    public void edit(int index, Row row) {
        var previous = rows.get(index);
        rows.set(index, remember(new RowOccurrence(row, previous.history()), candidates()));
    }

    public void remove(int index) { rows.remove(index); }

    /// Eligibility requires an unselected candidate currently observed as a directory
    /// or missing. This does not replace whole-draft validation at Add/Save.
    public boolean canAdd(Entry entry) {
        if (entry.configured().isPresent() || entry.draft().isPresent()) return false;
        var candidate = entry.discovery().orElse(null);
        // Source attribution may be stale even after fresh metadata inspection.
        if (candidate == null || candidate.observation().generation() != generation) return false;
        return switch (candidate.observation().kind()) {
            case DIRECTORY, MISSING -> true;
            case PENDING, LINK, REGULAR_FILE, OTHER, INACCESSIBLE,
                 BLOCKED_BY_LINK, BLOCKED_BY_NON_DIRECTORY, UNKNOWN -> false;
        };
    }

    /// Currently observed directories and missing paths can be added through discovery.
    /// Advice never participates. Validate before mutation so rejected additions
    /// preserve all prior selections, including temporarily invalid manual edits.
    public void add(Path source) {
        var identity = source.toAbsolutePath().normalize();
        var entry = entries().stream().filter(e -> e.sourcePath().equals(Optional.of(identity))).findFirst();
        if (entry.filter(this::canAdd).isEmpty()) {
            throw new IllegalArgumentException("Add requires an unselected path currently observed as a directory or missing: " + identity);
        }
        var relative = sourceRoot.relativize(identity).toString();
        var row = new Row(relative, relative);
        var proposed = new ArrayList<>(rows());
        proposed.add(row);
        validate(proposed);
        rows.add(new RowOccurrence(row, entry.orElseThrow().discovery().orElseThrow().catalog().definitions()));
    }

    public ConfigurationDraft validate() { return validate(rows()); }

    private ConfigurationDraft validate(List<Row> proposed) {
        var relocations = new ArrayList<>(configured);
        proposed.forEach(row -> relocations.add(row.resolve(sourceRoot, targetRoot)));
        var draft = new ConfigurationDraft(targetRoot, relocations, sharedList);
        ConfigurationValidator.validate(draft);
        return draft;
    }

    /// Configured rows, draft rows (including invalid/duplicate edits), then new
    /// candidates. An empty source identity denotes an invalid relative source.
    /// Draft membership is selection; candidates without a row are unselected.
    public List<Entry> entries() {
        var candidates = candidates();
        var used = new HashSet<Path>();
        var entries = new ArrayList<Entry>();
        for (var relocation : configured) {
            var path = relocation.sourcePath().toAbsolutePath().normalize();
            used.add(path);
            entries.add(new Entry(Optional.of(path), Optional.of(relocation), Optional.empty(),
                    Optional.ofNullable(candidates.get(path)), List.of(), !path.startsWith(sourceRoot)));
        }
        for (var occurrence : rows) {
            var row = occurrence.value();
            var path = source(row);
            path.ifPresent(used::add);
            entries.add(new Entry(path, Optional.empty(), Optional.of(row), path.map(candidates::get),
                    occurrence.history(), false));
        }
        candidates.forEach((path, candidate) -> {
            if (!used.contains(path)) entries.add(new Entry(Optional.of(path), Optional.empty(), Optional.empty(),
                    Optional.of(candidate), List.of(), false));
        });
        return List.copyOf(entries);
    }

    private Map<Path, CandidateDiscovery.Candidate> candidates() {
        var result = new LinkedHashMap<Path, CandidateDiscovery.Candidate>();
        discovery.ifPresent(snapshot -> snapshot.candidates().forEach(candidate ->
                result.put(candidate.catalog().sourcePath(), candidate)));
        return result;
    }

    private Optional<Path> source(Row row) {
        try { return Optional.of(relative(sourceRoot, row.sourceRelative())); }
        catch (IllegalArgumentException exception) { return Optional.empty(); }
    }

    private RowOccurrence remember(RowOccurrence row, Map<Path, CandidateDiscovery.Candidate> candidates) {
        return source(row.value()).map(candidates::get).map(candidate -> {
            var definitions = new LinkedHashSet<>(row.history());
            definitions.addAll(candidate.catalog().definitions());
            return new RowOccurrence(row.value(), List.copyOf(definitions));
        }).orElse(row);
    }

    // Each list position owns its history, even when row values or object references match.
    private record RowOccurrence(Row value, List<CandidateDefinition> history) {
        private RowOccurrence {
            Objects.requireNonNull(value);
            history = List.copyOf(history);
        }
    }

    private void invalidateDiscovery() { generation = -1; discovery = Optional.empty(); }

    private static Path absolute(Path path) {
        if (!path.isAbsolute()) throw new IllegalArgumentException("Storage roots must be absolute");
        return path.normalize();
    }

    private static Path relative(Path root, String value) {
        if (value.isBlank()) throw new IllegalArgumentException("Relocation paths cannot be blank");
        var path = Path.of(value);
        var resolved = root.resolve(path).normalize();
        if (path.isAbsolute() || resolved.equals(root) || !resolved.startsWith(root)) {
            throw new IllegalArgumentException("Relocation paths must be relative and nested below their root");
        }
        return resolved;
    }

    /// Partial form data: path validity is checked at Add/Validate/Save, not while
    /// typing. Original relative text and omitted policies survive refresh/root edits.
    public record Row(String sourceRelative, String targetRelative,
                      Optional<WhenSourceAndTargetDirectoriesExist> both,
                      Optional<WhenOnlyTargetExists> onlyTarget,
                      Optional<WhenAdoptingTarget> adopting, Optional<Path> archiveRoot) {
        public Row {
            Objects.requireNonNull(sourceRelative);
            Objects.requireNonNull(targetRelative);
            Objects.requireNonNull(both);
            Objects.requireNonNull(onlyTarget);
            Objects.requireNonNull(adopting);
            Objects.requireNonNull(archiveRoot);
        }

        public Row(String sourceRelative, String targetRelative) {
            this(sourceRelative, targetRelative, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        }

        private Relocation resolve(Path sourceRoot, Path targetRoot) {
            if (archiveRoot.filter(path -> !path.isAbsolute()).isPresent()) {
                throw new IllegalArgumentException("Archive root must be an absolute path");
            }
            return new Relocation(relative(sourceRoot, sourceRelative), relative(targetRoot, targetRelative),
                    both, onlyTarget, adopting, archiveRoot.map(Path::normalize));
        }
    }

    /// `lastKnownDefinitions` is session history, never a current assertion or
    /// persisted configuration. Use discovery/source outcomes for current freshness.
    public record Entry(Optional<Path> sourcePath, Optional<Relocation> configured, Optional<Row> draft,
                        Optional<CandidateDiscovery.Candidate> discovery,
                        List<CandidateDefinition> lastKnownDefinitions, boolean outsideRoot) {
        public Entry { lastKnownDefinitions = List.copyOf(lastKnownDefinitions); }
    }
}
