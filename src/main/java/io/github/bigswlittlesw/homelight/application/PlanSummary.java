package io.github.bigswlittlesw.homelight.application;

import java.util.List;

/// Summary statistics for dry-run planned relocations and actions.
public record PlanSummary(
        int total,
        int ready,
        int migrate,
        int adopt,
        int link,
        int backup,
        int discard,
        int inSync,
        int skipped,
        int conflicts,
        int blocked,
        int warnings,
        int destructive,
        int actionsCount
) {
    public static PlanSummary from(List<PlanRelocationItem> items) {
        int ready = 0;
        int migrate = 0;
        int adopt = 0;
        int link = 0;
        int backup = 0;
        int discard = 0;
        int inSync = 0;
        int skipped = 0;
        int conflicts = 0;
        int blocked = 0;
        int warnings = 0;
        int destructive = 0;
        int actionsCount = 0;

        for (var item : items) {
            actionsCount += item.plan().actions().size();
            if (item.hasDestructiveActions()) {
                destructive++;
            }
            if (item.badge() != PlanBadge.CONFLICT && item.badge() != PlanBadge.BLOCKED && item.badge() != PlanBadge.INACCESSIBLE) {
                ready++;
            }
            switch (item.badge()) {
                case MIGRATE -> migrate++;
                case ADOPT -> adopt++;
                case LINK -> link++;
                case BACKUP -> backup++;
                case DISCARD -> discard++;
                case IN_SYNC -> inSync++;
                case SKIPPED -> skipped++;
                case CONFLICT -> conflicts++;
                case BLOCKED, INACCESSIBLE -> blocked++;
                case WARNING -> warnings++;
            }
        }
        return new PlanSummary(
                items.size(),
                ready,
                migrate,
                adopt,
                link,
                backup,
                discard,
                inSync,
                skipped,
                conflicts,
                blocked,
                warnings,
                destructive,
                actionsCount
        );
    }
}
