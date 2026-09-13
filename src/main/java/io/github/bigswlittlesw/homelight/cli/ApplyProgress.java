package io.github.bigswlittlesw.homelight.cli;

import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationExecutor;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlan;
import io.github.bigswlittlesw.homelight.reconcile.RelocationPlan;

import java.io.PrintWriter;
import java.util.IdentityHashMap;
import java.util.Map;

/// Reports execution progress without coupling filesystem work to terminal output.
final class ApplyProgress implements ReconciliationExecutor.ProgressListener {
    private final PrintWriter output;
    private final ReconciliationPlan plan;
    private final boolean noColor;
    private final long stepDelayMillis;
    private final InlineApplyProgress display;

    ApplyProgress(PrintWriter output, ReconciliationPlan plan, boolean noColor, boolean awaitingConfirmation,
            long stepDelayMillis) {
        this.output = output;
        this.plan = plan;
        this.noColor = noColor;
        this.stepDelayMillis = stepDelayMillis;
        display = InlineApplyProgress.start(plan, noColor, awaitingConfirmation).orElse(null);
    }

    boolean awaitConfirmation() {
        return display != null && display.awaitConfirmation();
    }

    static void renderResult(ReconciliationPlan plan, ReconciliationExecutor.ExecutionResult result,
            boolean noColor, PrintWriter output) {
        var states = new IdentityHashMap<ReconciliationAction, ReconciliationExecutor.ActionStatus>();
        for (var relocation : result.relocations()) {
            for (var action : relocation.actions()) {
                states.put(action.action(), action.status());
            }
        }
        output.print(tree(plan, states));
    }

    @Override
    public void started(RelocationPlan relocation, ReconciliationAction action) {
        if (display != null) {
            display.started(relocation, action);
        }
        pauseForVisualTesting();
    }

    @Override
    public void finished(RelocationPlan relocation, ReconciliationExecutor.ActionExecution action) {
        if (display != null) {
            display.finished(relocation, action);
        }
    }

    void complete(ReconciliationExecutor.ExecutionResult result) {
        if (display != null) {
            display.complete(result);
        } else {
            renderResult(plan, result, noColor, output);
        }
    }

    private void pauseForVisualTesting() {
        if (stepDelayMillis == 0) {
            return;
        }
        try {
            Thread.sleep(stepDelayMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static String tree(ReconciliationPlan plan,
            Map<ReconciliationAction, ReconciliationExecutor.ActionStatus> states) {
        var output = new StringBuilder("Applying ").append(plan.relocations().size())
                .append(plan.relocations().size() == 1 ? " relocation…\n" : " relocations…\n");
        for (var relocation : plan.relocations()) {
            var configured = relocation.relocation();
            output.append(configured.sourcePath()).append(" → ").append(configured.targetPath()).append('\n');
            for (var action : relocation.actions()) {
                output.append("  ").append(actionLabel(action, states.getOrDefault(action,
                        ReconciliationExecutor.ActionStatus.PENDING))).append('\n');
            }
        }
        return output.toString();
    }

    static String actionLabel(ReconciliationAction action, ReconciliationExecutor.ActionStatus status) {
        var marker = switch (status) {
            case PENDING -> "○ ";
            case COMPLETED -> "✓ ";
            case FAILED -> "✗ ";
        };
        return marker + actionText(action);
    }

    static String actionText(ReconciliationAction action) {
        return switch (action) {
            case ReconciliationAction.EnsureDirectory ensure -> "Ensure " + ensure.path();
            case ReconciliationAction.CreateDirectory create -> "Create " + create.path();
            case ReconciliationAction.CopyDirectory copy -> "Copy " + copy.path() + " → " + copy.target();
            case ReconciliationAction.MigrateDirectoryForPublication migrate ->
                    "Migrate and publish " + migrate.path() + " → " + migrate.target();
            case ReconciliationAction.ArchiveDirectory archive -> "Archive " + archive.path() + " → " + archive.target();
            case ReconciliationAction.DeleteDirectory delete -> "Discard contents at " + delete.path();
            case ReconciliationAction.CreateSymlink _ -> "Create source link";
            case ReconciliationAction.ReplaceDirectoryWithSymlink _ -> "Adopt target and replace source link";
            case ReconciliationAction.ReplaceSymlink _ -> "Replace source link";
            case ReconciliationAction.NoOp _ -> "Already configured";
            case ReconciliationAction.LeaveUnchanged _ -> "Leave source and target unmanaged";
            case ReconciliationAction.Blocked blocked -> "Blocked: " + blocked.reason();
        };
    }
}
