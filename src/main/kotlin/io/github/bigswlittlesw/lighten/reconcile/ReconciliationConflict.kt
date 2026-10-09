package io.github.bigswlittlesw.lighten.reconcile

import java.nio.file.Path

/**
 * A state that requires an explicit user decision before application. The choices that resolve it are the
 * application's (`relocationDecision`), as this package cannot name them.
 */
data class ReconciliationConflict(val path: Path, val reason: String)
