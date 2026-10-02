package io.github.bigswlittlesw.homelight.application

/** Operation badge and urgency category for a planned relocation item. */
enum class PlanBadge(val label: String, val priority: Int) {
    CONFLICT("Conflict", 1),
    BLOCKED("Blocked", 1),
    INACCESSIBLE("Inaccessible", 1),
    WARNING("Warning", 2),
    MIGRATE("Migrate", 3),
    ADOPT("Adopt", 3),
    LINK("Link", 3),
    BACKUP("Backup", 3),
    DISCARD("Discard", 3),
    SKIPPED("Unchanged", 4),
    IN_SYNC("In Sync", 5)
}
