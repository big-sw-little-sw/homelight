package io.github.bigswlittlesw.lighten.reconcile

import java.nio.file.Path

/**
 * A relocation whose rule is "Ask each time" in its current state, so it needs a decision before the plan can be
 * applied. The choices for it are in the application package (`relocationDecision`), because this package cannot name
 * them.
 */
data class ReconciliationConflict(val path: Path, val reason: String)
