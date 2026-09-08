package io.github.bigswlittlesw.homelight.reconcile;

import io.github.bigswlittlesw.homelight.config.ExistingContentPolicy;
import io.github.bigswlittlesw.homelight.domain.RelocationSourceState;
import io.github.bigswlittlesw.homelight.fs.PathState;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/// Computes safe filesystem actions from observations and never mutates the filesystem.
public final class ReconciliationPlanner {
    public ReconciliationPlan plan(List<RelocationState> states) {
        var configurationDiagnostics = validateConfiguration(states);
        if (!configurationDiagnostics.isEmpty()) {
            var outcomes = states.stream()
                    .map(state -> blockedOutcome(state, "relocation configuration overlaps another relocation"))
                    .toList();
            return new ReconciliationPlan(outcomes, configurationDiagnostics);
        }
        return new ReconciliationPlan(states.stream().map(this::plan).toList(), List.of());
    }

    private RelocationPlan plan(RelocationState state) {
        var relocation = state.relocation();
        var source = relocation.sourcePath();
        var target = relocation.targetPath();

        return switch (sourceState(state)) {
            case CORRECT_SYMLINK -> switch (state.target().state()) {
                case DIRECTORY -> outcome(state, List.of(new ReconciliationAction.NoOp(source)));
                case SYMLINK -> conflict(state, target, "relocation target is a symlink",
                        ReconciliationConflict.Resolution.CHOOSE_DIFFERENT_TARGET,
                        ReconciliationConflict.Resolution.LEAVE_UNMANAGED);
                default -> blockedOutcome(state, "relocation target is not a real directory");
            };
            case ABSENT -> switch (state.target().state()) {
                case ABSENT -> outcome(state, List.of(
                        new ReconciliationAction.EnsureDirectory(target.getParent()),
                        new ReconciliationAction.CreateDirectory(target),
                        new ReconciliationAction.EnsureDirectory(source.getParent()),
                        new ReconciliationAction.CreateSymlink(source, target)));
                case DIRECTORY -> planExistingTarget(state);
                case SYMLINK -> conflict(state, target, "relocation target is a symlink",
                        ReconciliationConflict.Resolution.CHOOSE_DIFFERENT_TARGET,
                        ReconciliationConflict.Resolution.LEAVE_UNMANAGED);
                default -> blockedOutcome(state, "destination is not an available directory");
            };
            case DIRECTORY -> switch (state.target().state()) {
                case ABSENT, DIRECTORY -> planExistingSource(state);
                case SYMLINK -> conflict(state, target, "relocation target is a symlink",
                        ReconciliationConflict.Resolution.CHOOSE_DIFFERENT_TARGET,
                        ReconciliationConflict.Resolution.LEAVE_UNMANAGED);
                default -> blockedOutcome(state, "destination is not an available directory");
            };
            case FILE -> blockedOutcome(state, "source is a file; relocations currently require directories");
            case WRONG_SYMLINK -> conflict(state, source, "source points to a live, non-configured destination",
                    ReconciliationConflict.Resolution.REPLACE_SOURCE_LINK,
                    ReconciliationConflict.Resolution.LEAVE_UNMANAGED);
            case BROKEN_SYMLINK -> switch (state.target().state()) {
                case ABSENT -> repairedBrokenLink(state, List.of(
                        new ReconciliationAction.EnsureDirectory(target.getParent()),
                        new ReconciliationAction.CreateDirectory(target),
                        replacementLink(state)));
                case DIRECTORY -> repairedBrokenLink(state, List.of(replacementLink(state)));
                case SYMLINK -> conflict(state, target, "relocation target is a symlink",
                        ReconciliationConflict.Resolution.CHOOSE_DIFFERENT_TARGET,
                        ReconciliationConflict.Resolution.LEAVE_UNMANAGED);
                default -> blockedOutcome(state, "destination is not an available directory");
            };
            case INACCESSIBLE -> blockedOutcome(state, "source cannot be inspected");
            case OTHER -> blockedOutcome(state, "source has an unsupported filesystem state");
        };
    }

    private RelocationPlan planExistingSource(RelocationState state) {
        var targetState = state.target().state();
        return state.relocation().existingContentPolicy().map(policy -> switch (policy) {
            case MOVE -> targetState == PathState.ABSENT
                    ? outcome(state, List.of(
                            new ReconciliationAction.EnsureDirectory(state.relocation().targetPath().getParent()),
                            new ReconciliationAction.Move(state.relocation().sourcePath(), state.relocation().targetPath()),
                            new ReconciliationAction.CreateSymlink(state.relocation().sourcePath(), state.relocation().targetPath())))
                    : targetState == PathState.DIRECTORY && state.target().emptyDirectory()
                    ? outcome(state, List.of(
                            new ReconciliationAction.DeleteDirectory(state.relocation().targetPath(), PathState.DIRECTORY, true),
                            new ReconciliationAction.Move(state.relocation().sourcePath(), state.relocation().targetPath()),
                            new ReconciliationAction.CreateSymlink(state.relocation().sourcePath(), state.relocation().targetPath())))
                    : conflict(state, state.relocation().targetPath(),
                    "move policy cannot merge source content into an existing destination",
                    ReconciliationConflict.Resolution.CHOOSE_DIFFERENT_TARGET,
                    ReconciliationConflict.Resolution.LEAVE_UNMANAGED);
            case DISCARD -> discardExistingDirectories(state);
            case PRESERVE -> outcome(state, List.of(new ReconciliationAction.Skip(state.relocation().sourcePath())));
        }).orElseGet(() -> policyRequired(state, state.relocation().sourcePath()));
    }

    private RelocationPlan planExistingTarget(RelocationState state) {
        return state.relocation().existingContentPolicy().map(policy -> switch (policy) {
            case MOVE, PRESERVE -> outcome(state, List.of(
                    new ReconciliationAction.EnsureDirectory(state.relocation().sourcePath().getParent()),
                    new ReconciliationAction.CreateSymlink(state.relocation().sourcePath(), state.relocation().targetPath())));
            case DISCARD -> discardExistingDirectories(state);
        }).orElseGet(() -> policyRequired(state, state.relocation().targetPath()));
    }

    private static RelocationPlan discardExistingDirectories(RelocationState state) {
        var relocation = state.relocation();
        var actions = new java.util.ArrayList<ReconciliationAction>();
        if (state.source().state() == PathState.DIRECTORY) {
            actions.add(new ReconciliationAction.DeleteDirectory(relocation.sourcePath()));
        }
        if (state.target().state() == PathState.DIRECTORY) {
            actions.add(new ReconciliationAction.DeleteDirectory(relocation.targetPath()));
        }
        actions.add(new ReconciliationAction.EnsureDirectory(relocation.targetPath().getParent()));
        actions.add(new ReconciliationAction.CreateDirectory(relocation.targetPath()));
        actions.add(new ReconciliationAction.EnsureDirectory(relocation.sourcePath().getParent()));
        actions.add(new ReconciliationAction.CreateSymlink(relocation.sourcePath(), relocation.targetPath()));
        var warning = new ReconciliationDiagnostic(ReconciliationDiagnostic.Severity.WARNING,
                relocation.sourcePath(), "EXISTING_CONTENT_DISCARDED",
                "discard policy will permanently remove existing directory content");
        return new RelocationPlan(relocation, actions, List.of(warning), Optional.empty());
    }

    private static RelocationPlan policyRequired(RelocationState state, Path path) {
        return conflict(state, path, "existing content requires an explicit move, discard, or preserve policy",
                ReconciliationConflict.Resolution.RESOLVE_EXISTING_CONTENT,
                ReconciliationConflict.Resolution.LEAVE_UNMANAGED,
                ReconciliationConflict.Resolution.CHOOSE_DIFFERENT_TARGET);
    }

    private RelocationSourceState sourceState(RelocationState state) {
        return state.source().sourceStateForTarget(state.relocation().targetPath());
    }

    private static RelocationPlan outcome(RelocationState state, List<ReconciliationAction> actions) {
        return new RelocationPlan(state.relocation(), actions, List.of(), Optional.empty());
    }

    private static RelocationPlan repairedBrokenLink(RelocationState state, List<ReconciliationAction> actions) {
        var relocation = state.relocation();
        var warning = new ReconciliationDiagnostic(ReconciliationDiagnostic.Severity.WARNING,
                relocation.sourcePath(), "BROKEN_SOURCE_LINK_REPAIRED",
                "source was a broken symlink and will be replaced with the configured target");
        return new RelocationPlan(relocation, actions, List.of(warning), Optional.empty());
    }

    private static ReconciliationAction.ReplaceSymlink replacementLink(RelocationState state) {
        var sourceTarget = state.source().symlinkTarget().orElseThrow();
        return new ReconciliationAction.ReplaceSymlink(state.relocation().sourcePath(),
                state.relocation().targetPath(), sourceTarget, state.target().state());
    }

    private static RelocationPlan conflict(
            RelocationState state, Path path, String reason, ReconciliationConflict.Resolution... resolutions) {
        return new RelocationPlan(state.relocation(), List.of(), List.of(),
                Optional.of(new ReconciliationConflict(path, reason, List.of(resolutions))));
    }

    private static RelocationPlan blockedOutcome(RelocationState state, String reason) {
        var path = state.relocation().sourcePath();
        return new RelocationPlan(state.relocation(), List.of(new ReconciliationAction.Blocked(path, reason)),
                List.of(), Optional.empty());
    }

    private static List<ReconciliationDiagnostic> validateConfiguration(List<RelocationState> states) {
        return states.stream()
                .map(RelocationState::relocation)
                .filter(relocation -> intersects(relocation.sourcePath(), relocation.targetPath()))
                .findFirst()
                .map(relocation -> List.of(configurationError(relocation.sourcePath(), "source and target paths overlap")))
                .orElseGet(() -> validateInterRelocationOverlaps(states));
    }

    private static List<ReconciliationDiagnostic> validateInterRelocationOverlaps(List<RelocationState> states) {
        for (var leftIndex = 0; leftIndex < states.size(); leftIndex++) {
            var left = states.get(leftIndex).relocation();
            for (var rightIndex = leftIndex + 1; rightIndex < states.size(); rightIndex++) {
                var right = states.get(rightIndex).relocation();
                if (intersects(left.sourcePath(), right.sourcePath())
                        || intersects(left.sourcePath(), right.targetPath())
                        || intersects(left.targetPath(), right.sourcePath())
                        || intersects(left.targetPath(), right.targetPath())) {
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
        return new ReconciliationDiagnostic(ReconciliationDiagnostic.Severity.ERROR, source,
                "OVERLAPPING_RELOCATION", message);
    }
}
