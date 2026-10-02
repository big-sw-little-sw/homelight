package io.github.bigswlittlesw.homelight.application

import io.github.bigswlittlesw.homelight.config.Relocation
import io.github.bigswlittlesw.homelight.config.WhenAdoptingTarget
import io.github.bigswlittlesw.homelight.config.WhenOnlyTargetExists
import io.github.bigswlittlesw.homelight.config.WhenSourceAndTargetDirectoriesExist

/** Typed reconciliation decisions for unresolved conflicts. */
enum class DecisionChoice(val label: String, val description: String) {
    ADOPT_TARGET(
        "Adopt target and create source link",
        "Use the existing target directory as authoritative and create the symlink in source.",
    ),

    ADOPT_AND_DISCARD_SOURCE(
        "Adopt target and discard source",
        "Use existing target directory as authoritative and delete the existing source directory.",
    ),

    ADOPT_AND_ARCHIVE_SOURCE(
        "Adopt target and archive source",
        "Use existing target directory as authoritative and move existing source directory to archive root.",
    ),

    LEAVE_UNCHANGED(
        "Leave source and target unchanged",
        "Leave existing source and target directories in place, unchanged.",
    ),

    DISCARD_BOTH(
        "Discard source and target contents",
        "Delete existing contents in both locations, then recreate empty target and source link.",
    );

    internal fun applyTo(saved: Relocation): Relocation {
        val both = when (this) {
            ADOPT_TARGET -> saved.whenSourceAndTargetDirectoriesExist
            ADOPT_AND_DISCARD_SOURCE, ADOPT_AND_ARCHIVE_SOURCE -> WhenSourceAndTargetDirectoriesExist.ADOPT
            LEAVE_UNCHANGED -> WhenSourceAndTargetDirectoriesExist.LEAVE_UNCHANGED
            DISCARD_BOTH -> WhenSourceAndTargetDirectoriesExist.DISCARD
        }
        val adopting = when (this) {
            ADOPT_AND_DISCARD_SOURCE -> WhenAdoptingTarget.DiscardSource
            // Evaluation offers this choice only when the saved policy has an archive root.
            ADOPT_AND_ARCHIVE_SOURCE -> WhenAdoptingTarget.ArchiveSource(
                requireNotNull(saved.whenAdoptingTarget?.archiveRoot) { "Archiving requires an archive root" },
            )
            ADOPT_TARGET, LEAVE_UNCHANGED, DISCARD_BOTH -> saved.whenAdoptingTarget
        }
        return saved.copy(
            whenSourceAndTargetDirectoriesExist = both,
            whenOnlyTargetExists =
                if (this == ADOPT_TARGET) WhenOnlyTargetExists.ADOPT_TARGET else saved.whenOnlyTargetExists,
            whenAdoptingTarget = adopting,
        )
    }
}
