package io.github.bigswlittlesw.homelight.application;

import java.util.List;

/// Summary statistics for configured relocations.
public record StatusSummary(
        int total,
        int inSync,
        int pending,
        int conflicts,
        int blocked,
        int warnings,
        int inaccessible
) {
    public static StatusSummary from(List<RelocationStatusItem> items) {
        int inSync = 0;
        int pending = 0;
        int conflicts = 0;
        int blocked = 0;
        int warnings = 0;
        int inaccessible = 0;

        for (var item : items) {
            switch (item.badge()) {
                case IN_SYNC -> inSync++;
                case PENDING -> pending++;
                case CONFLICT -> conflicts++;
                case BLOCKED -> blocked++;
                case WARNING -> warnings++;
                case INACCESSIBLE -> inaccessible++;
                case SKIPPED -> { }
            }
        }
        return new StatusSummary(items.size(), inSync, pending, conflicts, blocked, warnings, inaccessible);
    }
}
