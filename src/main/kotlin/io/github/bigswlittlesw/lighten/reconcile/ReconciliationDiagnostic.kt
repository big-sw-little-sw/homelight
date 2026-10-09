package io.github.bigswlittlesw.lighten.reconcile

import io.github.bigswlittlesw.lighten.fs.PathText
import java.nio.file.Path

/** Explains a notable condition associated with one relocation without adding a filesystem action. */
data class ReconciliationDiagnostic(val severity: Severity, val source: Path, val code: String, val message: PathText) {
    enum class Severity {
        WARNING,
        ERROR,
    }
}
