package io.github.bigswlittlesw.homelight.reconcile;

import java.nio.file.Path;
import java.util.Objects;

/// Explains a notable condition associated with one relocation without adding a filesystem action.
public record ReconciliationDiagnostic(Severity severity, Path source, String code, String message) {
    public ReconciliationDiagnostic {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }

    public enum Severity {
        WARNING,
        ERROR
    }
}
