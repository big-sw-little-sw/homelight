package io.github.bigswlittlesw.lighten.reconcile

import io.github.bigswlittlesw.lighten.fs.PathText
import java.nio.file.Path

/** A warning or error about one relocation. It adds no action to the plan. */
data class ReconciliationDiagnostic(val severity: Severity, val source: Path, val code: String, val message: PathText) {
    enum class Severity {
        WARNING,
        ERROR,
    }
}
