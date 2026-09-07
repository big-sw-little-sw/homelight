package io.github.bigswlittlesw.homelight.reconcile;

import java.util.List;

/// The complete, filesystem-independent result of reconciliation planning.
public record ReconciliationPlan(List<ReconciliationAction> actions) {
    public ReconciliationPlan {
        actions = List.copyOf(actions);
    }

    public boolean hasBlockedActions() {
        return actions.stream().anyMatch(ReconciliationAction.Blocked.class::isInstance);
    }
}
