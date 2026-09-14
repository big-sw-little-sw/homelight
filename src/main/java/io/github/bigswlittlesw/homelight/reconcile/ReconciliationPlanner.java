package io.github.bigswlittlesw.homelight.reconcile;

import io.github.bigswlittlesw.homelight.config.WhenAdoptingTarget;
import io.github.bigswlittlesw.homelight.config.WhenOnlyTargetExists;
import io.github.bigswlittlesw.homelight.config.WhenSourceAndTargetDirectoriesExist;
import io.github.bigswlittlesw.homelight.domain.RelocationSourceState;
import io.github.bigswlittlesw.homelight.fs.PathState;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/// Computes safe filesystem actions from observations and never mutates the filesystem.
public final class ReconciliationPlanner {
    public ReconciliationPlan plan(List<RelocationState> states) {
        var diagnostics = validateConfiguration(states);
        if (!diagnostics.isEmpty()) {
            return new ReconciliationPlan(states.stream()
                    .map(state -> blocked(state, "relocation configuration is invalid")).toList(), diagnostics, states);
        }
        return new ReconciliationPlan(states.stream().map(this::plan).toList(), List.of(), states);
    }

    private RelocationPlan plan(RelocationState state) {
        var relocation = state.relocation();
        var source = relocation.sourcePath();
        var target = relocation.targetPath();
        return switch (sourceState(state)) {
            case CORRECT_SYMLINK -> state.target().state() == PathState.DIRECTORY
                    ? outcome(state, List.of(new ReconciliationAction.NoOp(source))) : unsupportedTarget(state);
            case ABSENT -> switch (state.target().state()) {
                case ABSENT -> outcome(state, List.of(new ReconciliationAction.EnsureDirectory(target.getParent()),
                        new ReconciliationAction.CreateDirectory(target), new ReconciliationAction.EnsureDirectory(source.getParent()),
                        new ReconciliationAction.CreateSymlink(source, target)));
                case DIRECTORY -> onlyTargetExists(state);
                default -> unsupportedTarget(state);
            };
            case DIRECTORY -> switch (state.target().state()) {
                case ABSENT -> migrateSourceForPublication(state);
                case DIRECTORY -> bothDirectoriesExist(state);
                default -> unsupportedTarget(state);
            };
            case FILE -> blocked(state, "source is a file; relocations require directories");
            case WRONG_SYMLINK -> conflict(state, source, "source points to a live, non-configured destination",
                    ReconciliationConflict.Resolution.REPLACE_SOURCE_LINK, ReconciliationConflict.Resolution.LEAVE_UNMANAGED);
            case BROKEN_SYMLINK -> switch (state.target().state()) {
                case DIRECTORY -> outcome(state, List.of(replacementLink(state)));
                case ABSENT -> blocked(state, "broken source link has no target directory");
                default -> unsupportedTarget(state);
            };
            case INACCESSIBLE -> blocked(state, "source cannot be inspected");
            case OTHER -> blocked(state, "source has an unsupported filesystem state");
        };
    }

    private static RelocationPlan migrateSourceForPublication(RelocationState state) {
        var relocation = state.relocation();
        return new RelocationPlan(relocation, RelocationOutcome.CONVERGED, List.of(
                new ReconciliationAction.MigrateDirectoryForPublication(relocation.sourcePath(), relocation.targetPath(),
                        relocation.stagingRoot()),
                new ReconciliationAction.ReplaceDirectoryWithSymlink(relocation.sourcePath(), relocation.targetPath())),
                List.of(), Optional.empty());
    }

    private static RelocationPlan onlyTargetExists(RelocationState state) {
        return state.relocation().whenOnlyTargetExists().orElse(WhenOnlyTargetExists.PROMPT) == WhenOnlyTargetExists.ADOPT_TARGET
                ? outcome(state, List.of(new ReconciliationAction.EnsureDirectory(state.relocation().sourcePath().getParent()),
                        new ReconciliationAction.CreateSymlink(state.relocation().sourcePath(), state.relocation().targetPath())))
                : unresolved(state, state.relocation().targetPath(), "a real target directory requires an adopt-target decision");
    }

    private static RelocationPlan bothDirectoriesExist(RelocationState state) {
        return switch (state.relocation().whenSourceAndTargetDirectoriesExist()
                .orElse(WhenSourceAndTargetDirectoriesExist.PROMPT)) {
            case PROMPT -> unresolved(state, state.relocation().sourcePath(), "both source and target directories exist; choose which directory is authoritative");
            case ADOPT -> adoptTarget(state);
            case LEAVE_UNCHANGED -> unchanged(state);
            case DISCARD -> discardDirectories(state);
        };
    }

    private static RelocationPlan adoptTarget(RelocationState state) {
        return switch (state.relocation().whenAdoptingTarget().orElse(WhenAdoptingTarget.PROMPT)) {
            case PROMPT -> unresolved(state, state.relocation().sourcePath(), "adopting the target requires a source disposition");
            case DISCARD_SOURCE -> outcome(state, List.of(new ReconciliationAction.ReplaceDirectoryWithSymlink(
                    state.relocation().sourcePath(), state.relocation().targetPath())));
            case ARCHIVE_SOURCE -> archiveSource(state);
        };
    }

    private static RelocationPlan archiveSource(RelocationState state) {
        var relocation = state.relocation();
        var archivePath = relocation.sourceArchiveRoot().orElseThrow()
                .resolve(sourceRelativePath(relocation.sourcePath())).normalize();
        if (intersects(archivePath, relocation.sourcePath()) || intersects(archivePath, relocation.targetPath())) {
            return blocked(state, "source archive path overlaps a relocation path");
        }
        if (state.archiveDestination().map(RelocationState.ArchiveDestination::observation)
                .map(observation -> observation.state() != PathState.ABSENT).orElse(true)) {
            return blocked(state, "source archive destination already exists");
        }
        return outcome(state, List.of(new ReconciliationAction.EnsureDirectory(archivePath.getParent()),
                new ReconciliationAction.ArchiveDirectory(relocation.sourcePath(), archivePath),
                new ReconciliationAction.CreateSymlink(relocation.sourcePath(), relocation.targetPath())));
    }

    private static Path sourceRelativePath(Path source) {
        var absolute = source.toAbsolutePath().normalize();
        return absolute.getRoot().relativize(absolute);
    }

    private static RelocationPlan unchanged(RelocationState state) {
        return new RelocationPlan(state.relocation(), RelocationOutcome.UNCHANGED,
                List.of(new ReconciliationAction.LeaveUnchanged(state.relocation().sourcePath())), List.of(), Optional.empty());
    }

    private static RelocationPlan discardDirectories(RelocationState state) {
        var relocation = state.relocation();
        List<ReconciliationAction> actions = List.of(new ReconciliationAction.DeleteDirectory(relocation.sourcePath()),
                new ReconciliationAction.DeleteDirectory(relocation.targetPath()),
                new ReconciliationAction.EnsureDirectory(relocation.targetPath().getParent()),
                new ReconciliationAction.CreateDirectory(relocation.targetPath()),
                new ReconciliationAction.EnsureDirectory(relocation.sourcePath().getParent()),
                new ReconciliationAction.CreateSymlink(relocation.sourcePath(), relocation.targetPath()));
        var warning = new ReconciliationDiagnostic(ReconciliationDiagnostic.Severity.WARNING, relocation.sourcePath(),
                "DIRECTORIES_DISCARDED", "discard will permanently remove both directory trees");
        return new RelocationPlan(relocation, RelocationOutcome.CONVERGED, actions, List.of(warning), Optional.empty());
    }

    private static RelocationPlan unsupportedTarget(RelocationState state) {
        return blocked(state, "target is not a real directory or an absent path");
    }

    private RelocationSourceState sourceState(RelocationState state) {
        return state.source().sourceStateForTarget(state.relocation().targetPath());
    }

    private static RelocationPlan outcome(RelocationState state, List<ReconciliationAction> actions) {
        return new RelocationPlan(state.relocation(), RelocationOutcome.CONVERGED, actions, List.of(), Optional.empty());
    }

    private static ReconciliationAction.ReplaceSymlink replacementLink(RelocationState state) {
        return new ReconciliationAction.ReplaceSymlink(state.relocation().sourcePath(), state.relocation().targetPath(),
                state.source().symlinkTarget().orElseThrow(), state.target().state());
    }

    private static RelocationPlan unresolved(RelocationState state, Path path, String reason) {
        return conflict(state, path, reason, ReconciliationConflict.Resolution.RESOLVE_EXISTING_CONTENT,
                ReconciliationConflict.Resolution.LEAVE_UNMANAGED, ReconciliationConflict.Resolution.CHOOSE_DIFFERENT_TARGET);
    }

    private static RelocationPlan conflict(RelocationState state, Path path, String reason,
            ReconciliationConflict.Resolution... resolutions) {
        return new RelocationPlan(state.relocation(), RelocationOutcome.UNRESOLVED, List.of(), List.of(),
                Optional.of(new ReconciliationConflict(path, reason, List.of(resolutions))));
    }

    private static RelocationPlan blocked(RelocationState state, String reason) {
        return new RelocationPlan(state.relocation(), RelocationOutcome.UNRESOLVED,
                List.of(new ReconciliationAction.Blocked(state.relocation().sourcePath(), reason)), List.of(), Optional.empty());
    }

    private static List<ReconciliationDiagnostic> validateConfiguration(List<RelocationState> states) {
        for (var state : states) {
            var relocation = state.relocation();
            if (relocation.whenAdoptingTarget().filter(WhenAdoptingTarget.ARCHIVE_SOURCE::equals).isPresent()
                    && relocation.sourceArchiveRoot().isEmpty()) {
                return List.of(configurationError(relocation.sourcePath(), "archive-source requires source-archive-root"));
            }
            if (intersects(relocation.sourcePath(), relocation.targetPath())) {
                return List.of(configurationError(relocation.sourcePath(), "source and target paths overlap"));
            }
        }
        for (var leftIndex = 0; leftIndex < states.size(); leftIndex++) {
            var left = states.get(leftIndex).relocation();
            for (var rightIndex = leftIndex + 1; rightIndex < states.size(); rightIndex++) {
                var right = states.get(rightIndex).relocation();
                if (intersects(left.sourcePath(), right.sourcePath()) || intersects(left.sourcePath(), right.targetPath())
                        || intersects(left.targetPath(), right.sourcePath()) || intersects(left.targetPath(), right.targetPath())) {
                    return List.of(configurationError(left.sourcePath(),
                            "relocation paths overlap: " + left.sourcePath() + " and " + right.sourcePath()));
                }
            }
        }
        return List.of();
    }

    private static boolean intersects(Path left, Path right) {
        var normalizedLeft = left.toAbsolutePath().normalize();
        var normalizedRight = right.toAbsolutePath().normalize();
        return normalizedLeft.startsWith(normalizedRight) || normalizedRight.startsWith(normalizedLeft);
    }

    private static ReconciliationDiagnostic configurationError(Path source, String message) {
        return new ReconciliationDiagnostic(ReconciliationDiagnostic.Severity.ERROR, source, "INVALID_RELOCATION", message);
    }
}
