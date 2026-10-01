package io.github.bigswlittlesw.homelight.reconcile

import java.util.Locale

/** The user-visible reconciliation state for one relocation. */
enum class RelocationOutcome {
    CONVERGED,
    UNCHANGED,
    UNRESOLVED;

    /** Returns the stable machine-readable outcome name. */
    fun value(): String = name.lowercase(Locale.ROOT).replace('_', '-')
}
