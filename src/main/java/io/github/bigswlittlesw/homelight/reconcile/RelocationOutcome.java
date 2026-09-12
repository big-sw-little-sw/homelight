package io.github.bigswlittlesw.homelight.reconcile;

import java.util.Locale;

/// The user-visible reconciliation state for one relocation.
public enum RelocationOutcome {
    CONVERGED,
    UNCHANGED,
    UNRESOLVED;

    /// Returns the stable machine-readable outcome name.
    public String value() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
