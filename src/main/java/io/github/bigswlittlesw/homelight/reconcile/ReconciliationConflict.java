package io.github.bigswlittlesw.homelight.reconcile;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/// A state that requires an explicit, state-appropriate user decision before application.
public record ReconciliationConflict(Path path, String reason, List<Resolution> resolutions) {
    public ReconciliationConflict {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(reason, "reason");
        resolutions = List.copyOf(resolutions);
        if (resolutions.isEmpty()) {
            throw new IllegalArgumentException("A conflict needs at least one resolution");
        }
    }

    public enum Resolution {
        REPLACE_SOURCE_LINK,
        RESOLVE_EXISTING_CONTENT,
        LEAVE_UNMANAGED,
        CHOOSE_DIFFERENT_TARGET
    }
}
