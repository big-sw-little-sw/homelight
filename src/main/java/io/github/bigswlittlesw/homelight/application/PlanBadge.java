package io.github.bigswlittlesw.homelight.application;

/// Operation badge and urgency category for a planned relocation item.
public enum PlanBadge {
    CONFLICT("Conflict", 1),
    BLOCKED("Blocked", 1),
    INACCESSIBLE("Inaccessible", 1),
    WARNING("Warning", 2),
    STAGE("Stage", 3),
    ADOPT("Adopt", 3),
    LINK("Link", 3),
    BACKUP("Backup", 3),
    DISCARD("Discard", 3),
    UNCHANGED("Unchanged", 4),
    CONVERGED("Converged", 5);

    private final String label;
    private final int priority;

    PlanBadge(String label, int priority) {
        this.label = label;
        this.priority = priority;
    }

    public String label() {
        return label;
    }

    public int priority() {
        return priority;
    }
}
