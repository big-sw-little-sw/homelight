package io.github.bigswlittlesw.homelight.reconcile;

import io.github.bigswlittlesw.homelight.domain.RelocationSourceState;

import java.util.List;

/// Computes safe filesystem actions from observations and never mutates the filesystem.
public final class ReconciliationPlanner {
    public ReconciliationPlan plan(List<RelocationState> states) {
        var actions = states.stream()
                .flatMap(state -> plan(state).stream())
                .toList();
        return new ReconciliationPlan(actions);
    }

    private List<ReconciliationAction> plan(RelocationState state) {
        var relocation = state.relocation();
        var source = relocation.sourcePath();
        var target = relocation.targetPath();

        return switch (sourceState(state)) {
            case CORRECT_SYMLINK -> switch (state.target().kind()) {
                case DIRECTORY -> List.of(new ReconciliationAction.NoOp(source));
                default -> blocked(target, "relocation target is not a real directory");
            };
            case ABSENT -> switch (state.target().kind()) {
                case ABSENT -> List.of(
                        new ReconciliationAction.CreateDirectory(target),
                        new ReconciliationAction.CreateSymlink(source, target));
                case DIRECTORY -> List.of(new ReconciliationAction.CreateSymlink(source, target));
                default -> blocked(source, "destination is not an available directory");
            };
            case DIRECTORY, FILE -> switch (state.target().kind()) {
                case ABSENT -> List.of(
                        new ReconciliationAction.Move(source, target),
                        new ReconciliationAction.CreateSymlink(source, target));
                default -> blocked(source, "source and destination both contain filesystem state");
            };
            case WRONG_SYMLINK, BROKEN_SYMLINK -> switch (state.target().kind()) {
                case ABSENT -> List.of(
                        new ReconciliationAction.CreateDirectory(target),
                        new ReconciliationAction.ReplaceSymlink(source, target));
                case DIRECTORY -> List.of(new ReconciliationAction.ReplaceSymlink(source, target));
                default -> blocked(source, "destination is not an available directory");
            };
            case OTHER -> blocked(source, "source has an unsupported filesystem state");
        };
    }

    private RelocationSourceState sourceState(RelocationState state) {
        return state.source().asRelocationSource(state.relocation().targetPath());
    }

    private static List<ReconciliationAction> blocked(java.nio.file.Path path, String reason) {
        return List.of(new ReconciliationAction.Blocked(path, reason));
    }
}
