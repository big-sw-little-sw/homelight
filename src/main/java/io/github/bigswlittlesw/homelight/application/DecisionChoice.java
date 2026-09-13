package io.github.bigswlittlesw.homelight.application;

import java.util.Map;

/// Typed reconciliation decisions for unresolved conflicts.
public enum DecisionChoice {
    ADOPT_TARGET(
            "Adopt target and create source link",
            "Use the existing target directory as authoritative and create the symlink in source.",
            Map.of("when-only-target-exists", "adopt-target")),

    ADOPT_AND_DISCARD_SOURCE(
            "Adopt target and discard source",
            "Use existing target directory as authoritative and delete the existing source directory.",
            Map.of("when-source-and-target-directories-exist", "adopt",
                    "when-adopting-target", "discard-source")),

    ADOPT_AND_ARCHIVE_SOURCE(
            "Adopt target and archive source",
            "Use existing target directory as authoritative and move existing source directory to archive root.",
            Map.of("when-source-and-target-directories-exist", "adopt",
                    "when-adopting-target", "archive-source")),

    LEAVE_UNCHANGED(
            "Leave source and target unmanaged",
            "Leave existing source and target directories in place without managing them.",
            Map.of("when-source-and-target-directories-exist", "leave-unchanged")),

    DISCARD_BOTH(
            "Discard source and target contents",
            "Delete existing contents in both locations, then recreate empty target and source link.",
            Map.of("when-source-and-target-directories-exist", "discard"));

    private final String label;
    private final String description;
    private final Map<String, String> configurationOverrides;

    DecisionChoice(String label, String description, Map<String, String> configurationOverrides) {
        this.label = label;
        this.description = description;
        this.configurationOverrides = Map.copyOf(configurationOverrides);
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    public Map<String, String> configurationOverrides() {
        return configurationOverrides;
    }
}
