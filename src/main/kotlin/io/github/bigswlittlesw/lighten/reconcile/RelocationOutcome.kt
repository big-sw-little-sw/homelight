package io.github.bigswlittlesw.lighten.reconcile

import java.util.Locale

/** The user-visible reconciliation state for one relocation. */
enum class RelocationOutcome {
    CONVERGED,
    UNCHANGED,
    UNRESOLVED;

    /** The stable machine-readable outcome name. */
    val value: String = name.lowercase(Locale.ROOT).replace('_', '-')
}
