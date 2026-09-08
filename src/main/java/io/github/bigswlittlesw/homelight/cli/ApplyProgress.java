package io.github.bigswlittlesw.homelight.cli;

import io.github.kusoroadeolu.clique.Clique;
import io.github.kusoroadeolu.clique.components.ProgressBar;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationExecutor;
import io.github.bigswlittlesw.homelight.reconcile.RelocationPlan;

import java.io.PrintWriter;

/// Reports execution progress without coupling filesystem work to terminal output.
final class ApplyProgress implements ReconciliationExecutor.ProgressListener {
    private final PrintWriter output;
    private final ProgressBar progressBar;
    private String activity = "Preparing relocation…";

    ApplyProgress(PrintWriter output, int actionCount) {
        this.output = output;
        progressBar = Clique.progressBar(actionCount);
        output.println("Applying " + actionCount + (actionCount == 1 ? " step" : " steps") + "…");
    }

    @Override
    public void started(RelocationPlan relocation, ReconciliationAction action) {
        if (action instanceof ReconciliationAction.Move) {
            activity = "Relocating " + relocation.relocation().sourcePath() + "…";
        } else if (action instanceof ReconciliationAction.CreateSymlink) {
            activity = "Linking " + relocation.relocation().sourcePath() + "…";
        }
        render();
    }

    @Override
    public void finished(RelocationPlan relocation, ReconciliationExecutor.ActionExecution action) {
        progressBar.tick();
        render();
    }

    void complete() {
        output.print("\r\u001B[2K");
        output.flush();
    }

    private void render() {
        output.print("\r\u001B[2K" + activity + " " + progressBar.get());
        output.flush();
    }
}
