package io.github.bigswlittlesw.homelight.application

/** Summary statistics for dry-run planned relocations and actions. */
data class PlanSummary(
    val total: Int,
    val ready: Int,
    val migrate: Int,
    val adopt: Int,
    val link: Int,
    val backup: Int,
    val discard: Int,
    val inSync: Int,
    val skipped: Int,
    val conflicts: Int,
    val blocked: Int,
    val warnings: Int,
    val destructive: Int,
    val actionsCount: Int,
) {
    companion object {
        fun from(items: List<PlanRelocationItem>): PlanSummary {
            var ready = 0
            var migrate = 0
            var adopt = 0
            var link = 0
            var backup = 0
            var discard = 0
            var inSync = 0
            var skipped = 0
            var conflicts = 0
            var blocked = 0
            var warnings = 0
            var destructive = 0
            var actionsCount = 0

            for (item in items) {
                actionsCount += item.plan.actions.size
                if (item.hasDestructiveActions()) {
                    destructive++
                }
                if (item.hasWarnings()) {
                    warnings++
                }
                if (item.badge() != PlanBadge.CONFLICT && item.badge() != PlanBadge.BLOCKED && item.badge() != PlanBadge.INACCESSIBLE) {
                    ready++
                }
                when (item.badge()) {
                    PlanBadge.MIGRATE -> migrate++
                    PlanBadge.ADOPT -> adopt++
                    PlanBadge.LINK -> link++
                    PlanBadge.BACKUP -> backup++
                    PlanBadge.DISCARD -> discard++
                    PlanBadge.IN_SYNC -> inSync++
                    PlanBadge.SKIPPED -> skipped++
                    PlanBadge.CONFLICT -> conflicts++
                    PlanBadge.BLOCKED, PlanBadge.INACCESSIBLE -> blocked++
                    PlanBadge.WARNING -> {}
                }
            }
            return PlanSummary(
                items.size,
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
                actionsCount,
            )
        }
    }
}
