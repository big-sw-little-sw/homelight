package io.github.bigswlittlesw.homelight.cli;

import io.github.kusoroadeolu.clique.Clique;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationExecutor;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlan;
import io.github.bigswlittlesw.homelight.reconcile.RelocationPlan;

import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/// Reports execution progress without coupling filesystem work to terminal output.
final class ApplyProgress implements ReconciliationExecutor.ProgressListener {
    private static final String[] FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};

    private final PrintWriter output;
    private final ReconciliationPlan plan;
    private final boolean noColor;
    private final boolean verbose;
    private final ScheduledExecutorService spinner;
    private final Map<ReconciliationAction, ActionState> states = new HashMap<>();
    private final Object lock = new Object();
    private String activity = "Preparing relocation…";
    private int frame;
    private int renderedLines;

    ApplyProgress(PrintWriter output, ReconciliationPlan plan, boolean noColor, boolean verbose) {
        this.output = output;
        this.plan = plan;
        this.noColor = noColor;
        this.verbose = verbose;
        spinner = Executors.newSingleThreadScheduledExecutor(
                runnable -> Thread.ofVirtual().name("homelight-spinner").unstarted(runnable));
        spinner.scheduleAtFixedRate(this::render, 0, 100, TimeUnit.MILLISECONDS);
    }

    static void renderResult(ReconciliationPlan plan, ReconciliationExecutor.ExecutionResult result,
            boolean noColor, PrintWriter output) {
        var states = new HashMap<ReconciliationAction, ActionState>();
        for (var relocation : result.relocations()) {
            for (var action : relocation.actions()) {
                states.put(action.action(), state(action.status()));
            }
        }
        output.print(tree(plan, states, 0, noColor));
    }

    @Override
    public void started(RelocationPlan relocation, ReconciliationAction action) {
        synchronized (lock) {
            states.put(action, ActionState.RUNNING);
            activity = activity(relocation, action);
        }
    }

    @Override
    public void finished(RelocationPlan relocation, ReconciliationExecutor.ActionExecution action) {
        synchronized (lock) {
            states.put(action.action(), state(action.status()));
        }
    }

    void complete() {
        spinner.shutdownNow();
        synchronized (lock) {
            if (verbose) {
                renderVerbose();
            } else {
                output.print("\r\u001B[2K");
            }
            output.flush();
        }
    }

    private void render() {
        synchronized (lock) {
            if (verbose) {
                renderVerbose();
            } else {
                var indicator = noColor ? FRAMES[frame++ % FRAMES.length] : Clique.ink().cyan().on(FRAMES[frame++ % FRAMES.length]);
                output.print("\r\u001B[2K" + indicator + " " + activity);
            }
            output.flush();
        }
    }

    private void renderVerbose() {
        if (renderedLines > 0) {
            output.print("\u001B[" + renderedLines + "A");
        }
        var rendered = tree(plan, states, frame++, noColor);
        renderedLines = (int) rendered.lines().count();
        for (var line : rendered.lines().toList()) {
            output.print("\r\u001B[2K" + line + "\n");
        }
    }

    private static String tree(ReconciliationPlan plan, Map<ReconciliationAction, ActionState> states,
            int frame, boolean noColor) {
        var output = new StringBuilder("Applying ").append(plan.relocations().size())
                .append(plan.relocations().size() == 1 ? " relocation…\n" : " relocations…\n");
        for (var relocation : plan.relocations()) {
            var configured = relocation.relocation();
            var tree = Clique.tree(configured.sourcePath() + " → " + configured.targetPath());
            for (var action : relocation.actions()) {
                tree.add(actionLabel(action, states.getOrDefault(action, ActionState.PENDING), frame, noColor));
            }
            output.append(tree.get());
        }
        return output.toString();
    }

    private static ActionState state(ReconciliationExecutor.ActionStatus status) {
        return switch (status) {
            case COMPLETED -> ActionState.COMPLETED;
            case FAILED -> ActionState.FAILED;
            case PENDING -> ActionState.PENDING;
        };
    }

    private static String activity(RelocationPlan relocation, ReconciliationAction action) {
        var name = relocation.relocation().sourcePath().getFileName();
        return switch (action) {
            case ReconciliationAction.Move ignored -> "Relocating " + name + "…";
            case ReconciliationAction.CreateSymlink ignored -> "Linking " + name + "…";
            default -> "Preparing " + name + "…";
        };
    }

    private static String actionLabel(ReconciliationAction action, ActionState state, int frame, boolean noColor) {
        var text = actionText(action);
        var style = new TerminalStyle(noColor);
        return switch (state) {
            case PENDING -> style.pending("○ " + text);
            case RUNNING -> style.active(FRAMES[frame % FRAMES.length] + " " + text);
            case COMPLETED -> style.success("✓ " + text);
            case FAILED -> style.error("✗ " + text);
        };
    }

    private static String actionText(ReconciliationAction action) {
        return switch (action) {
            case ReconciliationAction.EnsureDirectory ensure -> "Ensure " + ensure.path();
            case ReconciliationAction.CreateDirectory create -> "Create " + create.path();
            case ReconciliationAction.Move ignored -> "Move contents";
            case ReconciliationAction.CreateSymlink ignored -> "Create source link";
            case ReconciliationAction.ReplaceSymlink ignored -> "Replace source link";
            case ReconciliationAction.NoOp ignored -> "Already configured";
            case ReconciliationAction.Blocked blocked -> "Blocked: " + blocked.reason();
        };
    }

    private enum ActionState { PENDING, RUNNING, COMPLETED, FAILED }
}
