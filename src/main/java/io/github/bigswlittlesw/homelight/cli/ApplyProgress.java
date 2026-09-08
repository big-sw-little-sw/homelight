package io.github.bigswlittlesw.homelight.cli;

import io.github.kusoroadeolu.clique.Clique;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationExecutor;
import io.github.bigswlittlesw.homelight.reconcile.RelocationPlan;

import java.io.PrintWriter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/// Reports execution progress without coupling filesystem work to terminal output.
final class ApplyProgress implements ReconciliationExecutor.ProgressListener {
    private static final String[] FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};

    private final PrintWriter output;
    private final boolean noColor;
    private final ScheduledExecutorService spinner;
    private final Object lock = new Object();
    private String activity = "Preparing relocation…";
    private int frame;

    ApplyProgress(PrintWriter output, int relocationCount, boolean noColor) {
        this.output = output;
        this.noColor = noColor;
        spinner = Executors.newSingleThreadScheduledExecutor(
                runnable -> Thread.ofVirtual().name("homelight-spinner").unstarted(runnable));
        output.println("Applying " + relocationCount + (relocationCount == 1 ? " relocation" : " relocations") + "…");
        spinner.scheduleAtFixedRate(this::render, 0, 100, TimeUnit.MILLISECONDS);
    }

    @Override
    public void started(RelocationPlan relocation, ReconciliationAction action) {
        if (action instanceof ReconciliationAction.Move) {
            activity = "Relocating " + relocation.relocation().sourcePath().getFileName() + "…";
        } else if (action instanceof ReconciliationAction.CreateSymlink) {
            activity = "Linking " + relocation.relocation().sourcePath().getFileName() + "…";
        }
    }

    @Override
    public void finished(RelocationPlan relocation, ReconciliationExecutor.ActionExecution action) {
    }

    void complete() {
        spinner.shutdownNow();
        synchronized (lock) {
            output.print("\r\u001B[2K");
            output.flush();
        }
    }

    private void render() {
        synchronized (lock) {
            var currentFrame = FRAMES[frame++ % FRAMES.length];
            var indicator = noColor ? currentFrame : Clique.ink().cyan().on(currentFrame);
            output.print("\r\u001B[2K" + indicator + " " + activity);
            output.flush();
        }
    }
}
