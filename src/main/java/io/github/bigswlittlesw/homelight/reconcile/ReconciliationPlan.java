package io.github.bigswlittlesw.homelight.reconcile;

import java.util.List;

/// The complete, filesystem-independent result of reconciliation planning.
public record ReconciliationPlan(List<RelocationPlan> relocations, List<ReconciliationDiagnostic> diagnostics,
        List<RelocationState> expectedStates) {
    public ReconciliationPlan {
        relocations = List.copyOf(relocations);
        diagnostics = List.copyOf(diagnostics);
        expectedStates = List.copyOf(expectedStates);
    }

    /// Hand-assembled plans have no review snapshot and cannot pass whole-plan preflight.
    public ReconciliationPlan(List<RelocationPlan> relocations, List<ReconciliationDiagnostic> diagnostics) {
        this(relocations, diagnostics, List.of());
    }

    public List<ReconciliationAction> actions() {
        return relocations.stream().flatMap(relocation -> relocation.actions().stream()).toList();
    }

    public boolean hasBlockedActions() {
        return actions().stream().anyMatch(ReconciliationAction.Blocked.class::isInstance);
    }

    public boolean hasChanges() {
        return actions().stream().anyMatch(ReconciliationAction::mutatesFilesystem);
    }

    public boolean hasConflicts() {
        return relocations.stream().anyMatch(relocation -> relocation.conflict().isPresent());
    }
}
