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

    ApplyProgress(PrintWriter output, int actionCount) {
        this.output = output;
        progressBar = Clique.progressBar(actionCount);
        output.println("Applying " + actionCount + (actionCount == 1 ? " step" : " steps") + "…");
    }

    @Override
    public void started(RelocationPlan relocation, ReconciliationAction action) {
        if (action instanceof ReconciliationAction.Move) {
            output.println("Relocating " + relocation.relocation().sourcePath() + "…");
        }
    }

    @Override
    public void finished(RelocationPlan relocation, ReconciliationExecutor.ActionExecution action) {
        progressBar.tick();
        output.print("\r" + progressBar.get());
        output.flush();
    }

    void complete() {
        output.println();
    }
}
