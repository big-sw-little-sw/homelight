package io.github.bigswlittlesw.lighten.application

/** The badge a relocation's row shows, with its urgency. A lower `priority` sorts first. */
enum class PlanBadge(val priority: Int) {
    CONFLICT(1),
    BLOCKED(1),
    INACCESSIBLE(1),
    WARNING(2),
    MIGRATE(3),
    ADOPT(3),
    LINK(3),
    BACKUP(3),
    DISCARD(3),
    SKIPPED(4),
    IN_SYNC(5)
}
