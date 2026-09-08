package io.github.bigswlittlesw.homelight.reconcile;

import java.util.List;

/// The complete, filesystem-independent result of reconciliation planning.
public record ReconciliationPlan(List<RelocationPlan> relocations, List<ReconciliationDiagnostic> diagnostics) {
    public ReconciliationPlan {
        relocations = List.copyOf(relocations);
        diagnostics = List.copyOf(diagnostics);
    }

    public List<ReconciliationAction> actions() {
        return relocations.stream().flatMap(relocation -> relocation.actions().stream()).toList();
    }

    public boolean hasBlockedActions() {
        return actions().stream().anyMatch(ReconciliationAction.Blocked.class::isInstance);
    }

    public boolean hasConflicts() {
        return relocations.stream().anyMatch(relocation -> relocation.conflict().isPresent());
    }
}
