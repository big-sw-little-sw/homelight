package io.github.bigswlittlesw.homelight.discovery;

import io.github.bigswlittlesw.homelight.config.CandidateCatalog;
import io.github.bigswlittlesw.homelight.config.CandidateDiagnostic;
import io.github.bigswlittlesw.homelight.config.CandidateParser;
import io.github.bigswlittlesw.homelight.config.CandidateSource;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.function.LongSupplier;

import static io.github.bigswlittlesw.homelight.discovery.CandidateObservation.*;

/// Session-scoped discovery. `refresh`, `snapshot`, `cancel` and `close` perform
/// no filesystem I/O and never wait for workers. Candidates are inspected serially
/// in the background; `snapshot` reads evidence and applies response deadlines.
/// Source reads and each root/candidate inspection have a five-second deadline.
/// An expired result is rejected even if no snapshot was requested at the deadline.
///
/// A refresh replaces the request generation. Only an unchanged root AND shared
/// location retain stale evidence. Cancellation/closure discard all session data.
/// Production worker capacity is process-wide, including across closed sessions:
/// one shared read, one bundled load, and one serial metadata inspection. Permits are
/// released only when work actually returns, never on timeout or interruption.
public final class CandidateDiscovery implements AutoCloseable {
    private static final long SOURCE_NANOS = 5_000_000_000L;
    static final long METADATA_NANOS = 5_000_000_000L;
    private static final Lanes PROCESS_LANES = new Lanes();
    private final Lanes lanes;
    private final LongSupplier clock;
    private final SharedReader sharedReader;
    private final Function<Path, CandidateCatalog.Snapshot> bundledReader;
    private final CandidateMetadata metadata;
    private long generation;
    private boolean closed;
    private Request request;
    private final Map<CandidateSource, SourceOutcome> sources = new LinkedHashMap<>();
    private final Map<CandidateSource, Work<CandidateCatalog.Snapshot>> sourceWork = new LinkedHashMap<>();
    private Work<CandidateMetadata.Anchor> anchorWork;
    private CandidateMetadata.Anchor anchor;
    private Optional<Diagnostic> rootFailure = Optional.empty();
    private Work<CandidateObservation> inspectionWork;
    private Path inspectionPath;
    private final Map<Path, CandidateObservation> observations = new HashMap<>();
    private final HashSet<Path> attempted = new HashSet<>();
    private final LinkedHashSet<Path> pending = new LinkedHashSet<>();

    public CandidateDiscovery() {
        this(PROCESS_LANES, System::nanoTime, CandidateDiscovery::readShared,
                CandidateCatalog::bundled, new CandidateMetadata());
    }

    CandidateDiscovery(Lanes lanes, LongSupplier clock, SharedReader sharedReader,
                       Function<Path, CandidateCatalog.Snapshot> bundledReader, CandidateMetadata metadata) {
        this.lanes = lanes;
        this.clock = clock;
        this.sharedReader = sharedReader;
        this.bundledReader = bundledReader;
        this.metadata = metadata;
    }

    /// Paths must already be absolute; no home expansion or location persistence.
    public synchronized long refresh(Path root, Optional<Path> sharedLocation) {
        if (closed) throw new IllegalStateException("Discovery is closed");
        var next = new Request(normalized(root), sharedLocation.map(CandidateDiscovery::normalized));
        boolean retain = next.equals(request);
        generation++;
        if (!retain) {
            sources.clear();
            observations.clear();
        } else {
            observations.replaceAll((path, value) -> value.retained());
        }
        request = next;
        sourceWork.clear();
        inspectionWork = null;
        inspectionPath = null;
        attempted.clear();
        pending.clear();
        anchor = null;
        rootFailure = Optional.empty();
        var currentRoot = next.root();
        startSource(CandidateCatalog.BUNDLED, lanes.bundled, () -> bundledReader.apply(currentRoot));
        next.sharedLocation().ifPresent(location -> {
            var source = new CandidateSource(CandidateSource.Kind.SHARED, location.toString());
            startSource(source, lanes.shared, () -> new CandidateParser().parse(source, currentRoot,
                    sharedReader.read(location)));
        });
        anchorWork = start(lanes.filesystem, () -> metadata.anchor(currentRoot));
        if (anchorWork == null) {
            rootFailure = Optional.of(new Diagnostic(root, Reason.CAPACITY,
                    "Filesystem capacity occupied; refresh when earlier operations finish"));
        }
        return generation;
    }

    /// Returned collections and observations are immutable. No aggregate byte
    /// total is supplied. Ancestors identify overlapping catalog candidates.
    public synchronized Result snapshot() {
        if (request == null) return new Result(generation, Optional.empty(), List.of(), List.of(), Optional.empty());
        collectSources();
        collectAnchor();
        collectInspection();
        var catalogs = sources.values().stream().flatMap(s -> s.catalog().stream()).toList();
        var candidates = CandidateCatalog.merge(catalogs).candidates();
        var identities = new HashSet<Path>();
        candidates.forEach(c -> identities.add(c.sourcePath()));
        observations.keySet().retainAll(identities);
        var rows = new ArrayList<Candidate>();
        for (var candidate : candidates) {
            var path = candidate.sourcePath();
            boolean sourceStale = candidate.definitions().stream()
                    .anyMatch(d -> sources.get(d.source()).status() != SourceStatus.CURRENT);
            var observation = observations.get(path);
            if (rootFailure.isPresent()) {
                var failure = rootFailure.orElseThrow();
                observation = failedObservation(path, failure.reason(), failure.detail());
            } else if (observation == null) {
                observation = new CandidateObservation(path, Kind.PENDING,
                        Optional.empty(), generation,
                        Instant.now(), false, path.equals(inspectionPath) ? List.of()
                        : List.of(new Diagnostic(path, Reason.CAPACITY, "Waiting for serial inspection")));
            }
            if (sourceStale) observation = observation.retained();
            var ancestors = new ArrayList<Path>();
            for (var parent = path.getParent(); parent != null && parent.startsWith(request.root());
                 parent = parent.getParent()) {
                if (identities.contains(parent)) ancestors.add(parent);
            }
            rows.add(new Candidate(candidate, observation, ancestors));
        }
        return new Result(generation, Optional.of(request), new ArrayList<>(sources.values()), rows, rootFailure);
    }

    public synchronized void cancel() {
        generation++;
        request = null;
        sources.clear();
        sourceWork.clear();
        observations.clear();
        inspectionWork = null;
        inspectionPath = null;
        attempted.clear();
        pending.clear();
        anchorWork = null;
        anchor = null;
        rootFailure = Optional.empty();
    }

    @Override public synchronized void close() {
        if (closed) return;
        cancel();
        closed = true;
    }

    private void startSource(CandidateSource source, Semaphore lane, Callable<CandidateCatalog.Snapshot> read) {
        var old = sources.get(source);
        sources.put(source, new SourceOutcome(source, old == null ? Optional.empty() : old.catalog(),
                SourceStatus.PENDING, List.of(), List.of()));
        var work = start(lane, read);
        if (work == null) {
            sourceFailure(source, List.of(), new SourceProblem(SourceProblem.Kind.PREVIOUS_PENDING,
                    "Previous read still pending; manual setup remains available"));
        } else sourceWork.put(source, work);
    }

    private void collectSources() {
        var iterator = sourceWork.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var work = entry.getValue();
            var done = work.completion;
            if (expired(work, done, SOURCE_NANOS)) {
                sourceFailure(entry.getKey(), List.of(), new SourceProblem(SourceProblem.Kind.DEADLINE,
                        "Source response deadline exceeded"));
            } else if (done == null) continue;
            else if (done.failure() != null) {
                sourceFailure(entry.getKey(), List.of(), sourceProblem(done.failure()));
            } else if (!done.value().accepted()) {
                sourceFailure(entry.getKey(), done.value().diagnostics(), null);
            } else {
                sources.put(entry.getKey(), new SourceOutcome(entry.getKey(), Optional.of(done.value()),
                        SourceStatus.CURRENT, List.of(), List.of()));
                done.value().definitions().forEach(d -> {
                    if (!attempted.contains(d.sourcePath())) pending.add(d.sourcePath());
                });
            }
            iterator.remove();
        }
    }

    private void sourceFailure(CandidateSource source, List<CandidateDiagnostic> diagnostics, SourceProblem problem) {
        var catalog = sources.get(source).catalog();
        sources.put(source, new SourceOutcome(source, catalog,
                catalog.isPresent() ? SourceStatus.STALE : SourceStatus.FAILED,
                diagnostics, problem == null ? List.of() : List.of(problem)));
    }

    private void collectAnchor() {
        if (anchorWork == null) return;
        var done = anchorWork.completion;
        if (expired(anchorWork, done, METADATA_NANOS)) {
            rootFailure = Optional.of(new Diagnostic(request.root(), Reason.DEADLINE, "Root inspection timed out"));
        } else if (done == null) return;
        else if (done.failure() != null) {
            var reason = done.failure() instanceof AccessDeniedException ? Reason.ACCESS_DENIED : Reason.IO_ERROR;
            rootFailure = Optional.of(new Diagnostic(request.root(), reason, done.failure().toString()));
        } else anchor = done.value();
        anchorWork = null;
    }

    private void collectInspection() {
        if (inspectionWork == null) return;
        var done = inspectionWork.completion;
        if (expired(inspectionWork, done, METADATA_NANOS)) {
            observations.put(inspectionPath, failedObservation(inspectionPath, Reason.DEADLINE,
                    "Inspection response deadline exceeded"));
        } else if (done == null) return;
        else if (done.failure() != null) {
            observations.put(inspectionPath, failedObservation(inspectionPath, Reason.IO_ERROR, done.failure().toString()));
        } else {
            var value = done.value();
            if (value.kind() == Kind.INACCESSIBLE || value.kind() == Kind.UNKNOWN) {
                var prior = observations.get(inspectionPath);
                if (prior != null) value = retainedFailure(prior, value.diagnostics());
            }
            observations.put(inspectionPath, value);
        }
        inspectionWork = null;
        inspectionPath = null;
    }

    // Completion of one background operation starts the next. Reading a snapshot
    // never drives discovery. Pending paths are bounded by the catalog limits;
    // they are data, not submitted tasks waiting behind a blocked operation.
    private void continueInspection() {
        collectSources();
        collectAnchor();
        collectInspection();
        if (anchor == null || inspectionWork != null || rootFailure.isPresent()) return;
        if (!pending.isEmpty()) {
            var path = pending.getFirst();
            var currentAnchor = anchor;
            long currentGeneration = generation;
            var work = start(lanes.filesystem, () -> metadata.inspect(currentAnchor, path, currentGeneration));
            if (work != null) {
                attempted.add(path);
                pending.remove(path);
                inspectionPath = path;
                inspectionWork = work;
            }
        }
    }

    private CandidateObservation failedObservation(Path path, Reason reason, String detail) {
        var previous = observations.get(path);
        return previous == null ? CandidateObservation.unknown(path, generation, reason, detail)
                : retainedFailure(previous, List.of(new Diagnostic(path, reason, detail)));
    }

    private static CandidateObservation retainedFailure(CandidateObservation previous, List<Diagnostic> failures) {
        var diagnostics = new ArrayList<>(previous.diagnostics());
        for (var failure : failures) if (!diagnostics.contains(failure)) diagnostics.add(failure);
        return new CandidateObservation(previous.path(), previous.kind(), previous.rawLinkTarget(),
                previous.generation(), previous.observedAt(), true, diagnostics);
    }

    private <T> boolean expired(Work<T> work, Completion<T> completion, long limit) {
        return (completion == null ? clock.getAsLong() : completion.finished()) - work.started >= limit;
    }

    private <T> Work<T> start(Semaphore lane, Callable<T> action) {
        if (!lane.tryAcquire()) return null;
        var work = new Work<T>(clock.getAsLong());
        long startedGeneration = generation;
        try {
            Thread.ofPlatform().daemon().name("homelight-discovery").start(() -> {
                Completion<T> done;
                try {
                    var value = action.call();
                    done = new Completion<>(value, null, clock.getAsLong());
                } catch (Exception e) {
                    done = new Completion<>(null, e, clock.getAsLong());
                } catch (Error e) {
                    lane.release();
                    throw e;
                }
                synchronized (CandidateDiscovery.this) {
                    // Publish completion with availability so an immediate refresh
                    // cannot mistake an already completed read for stuck work.
                    lane.release();
                    work.completion = done;
                    if (request != null && generation == startedGeneration) continueInspection();
                }
            });
        } catch (RuntimeException | Error e) {
            lane.release();
            throw e;
        }
        return work;
    }

    private static byte[] readShared(Path location) throws IOException {
        // Following the explicitly selected shared-file location is permitted.
        // A later FIFO replacement may block open; the lane/deadline still bounds it.
        if (!Files.readAttributes(location, BasicFileAttributes.class).isRegularFile()) {
            throw new NotRegular(location);
        }
        try (var input = Files.newInputStream(location)) {
            return input.readNBytes(CandidateParser.MAX_BYTES + 1);
        }
    }

    private static SourceProblem sourceProblem(Exception failure) {
        var kind = switch (failure) {
            case NoSuchFileException ignored -> SourceProblem.Kind.MISSING;
            case NotRegular ignored -> SourceProblem.Kind.NOT_REGULAR;
            case AccessDeniedException ignored -> SourceProblem.Kind.UNREADABLE;
            case SecurityException ignored -> SourceProblem.Kind.UNREADABLE;
            default -> SourceProblem.Kind.IO_ERROR;
        };
        return new SourceProblem(kind, failure.toString());
    }

    private static Path normalized(Path path) {
        Objects.requireNonNull(path);
        if (!path.isAbsolute()) throw new IllegalArgumentException("Absolute path required");
        return path.normalize();
    }

    public record Request(Path root, Optional<Path> sharedLocation) {
        public Request {
            root = normalized(root);
            sharedLocation = sharedLocation.map(CandidateDiscovery::normalized);
        }
    }
    public enum SourceStatus { PENDING, CURRENT, STALE, FAILED }
    public record SourceProblem(Kind kind, String detail) {
        public SourceProblem { Objects.requireNonNull(kind); Objects.requireNonNull(detail); }
        public enum Kind { MISSING, UNREADABLE, NOT_REGULAR, IO_ERROR, DEADLINE, PREVIOUS_PENDING }
    }
    public record SourceOutcome(CandidateSource source, Optional<CandidateCatalog.Snapshot> catalog,
                                SourceStatus status, List<CandidateDiagnostic> diagnostics,
                                List<SourceProblem> problems) {
        public SourceOutcome {
            Objects.requireNonNull(source);
            Objects.requireNonNull(catalog);
            Objects.requireNonNull(status);
            diagnostics = List.copyOf(diagnostics);
            problems = List.copyOf(problems);
        }
    }
    public record Candidate(CandidateCatalog.Candidate catalog, CandidateObservation observation,
                            List<Path> ancestors) {
        public Candidate {
            Objects.requireNonNull(catalog);
            Objects.requireNonNull(observation);
            ancestors = List.copyOf(ancestors);
        }
    }
    public record Result(long generation, Optional<Request> request, List<SourceOutcome> sources,
                         List<Candidate> candidates, Optional<Diagnostic> rootFailure) {
        public Result {
            Objects.requireNonNull(request);
            sources = List.copyOf(sources);
            candidates = List.copyOf(candidates);
            Objects.requireNonNull(rootFailure);
        }
    }

    @FunctionalInterface interface SharedReader { byte[] read(Path location) throws IOException; }
    static final class Lanes {
        final Semaphore shared = new Semaphore(1);
        final Semaphore bundled = new Semaphore(1);
        final Semaphore filesystem = new Semaphore(1);
    }
    private static final class Work<T> {
        final long started;
        volatile Completion<T> completion;
        Work(long started) { this.started = started; }
    }
    private record Completion<T>(T value, Exception failure, long finished) {}
    private static final class NotRegular extends IOException {
        NotRegular(Path path) { super("Shared source is not a regular file: " + path); }
    }
}
