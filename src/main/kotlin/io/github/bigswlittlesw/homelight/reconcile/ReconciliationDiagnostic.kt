package io.github.bigswlittlesw.homelight.reconcile

import java.nio.file.Path

/** Explains a notable condition associated with one relocation without adding a filesystem action. */
@JvmRecord
data class ReconciliationDiagnostic(val severity: Severity, val source: Path, val code: String, val message: String) {
    enum class Severity {
        WARNING,
        ERROR,
    }
}
