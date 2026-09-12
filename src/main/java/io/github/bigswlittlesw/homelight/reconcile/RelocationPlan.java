package io.github.bigswlittlesw.homelight.reconcile;

import io.github.bigswlittlesw.homelight.config.Relocation;

import java.util.List;
import java.util.Optional;

/// The planned outcome for one configured relocation.
public record RelocationPlan(
        Relocation relocation,
        RelocationOutcome outcome,
        List<ReconciliationAction> actions,
        List<ReconciliationDiagnostic> diagnostics,
        Optional<ReconciliationConflict> conflict) {
    public RelocationPlan {
        actions = List.copyOf(actions);
        diagnostics = List.copyOf(diagnostics);
        conflict = conflict == null ? Optional.empty() : conflict;
    }
}
