package io.github.bigswlittlesw.homelight.application

import io.github.bigswlittlesw.homelight.config.Relocation
import io.github.bigswlittlesw.homelight.config.WhenAdoptingTarget
import io.github.bigswlittlesw.homelight.config.WhenOnlyTargetExists
import io.github.bigswlittlesw.homelight.config.WhenSourceAndTargetDirectoriesExist
import java.util.Optional

/** Typed reconciliation decisions for unresolved conflicts. */
enum class DecisionChoice(private val label: String, private val description: String) {
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
        "Leave source and target unmanaged",
        "Leave existing source and target directories in place without managing them.",
    ),

    DISCARD_BOTH(
        "Discard source and target contents",
        "Delete existing contents in both locations, then recreate empty target and source link.",
    );

    fun label(): String = label

    fun description(): String = description

    internal fun applyTo(saved: Relocation): Relocation {
        val both = when (this) {
            ADOPT_TARGET -> saved.whenSourceAndTargetDirectoriesExist
            ADOPT_AND_DISCARD_SOURCE, ADOPT_AND_ARCHIVE_SOURCE -> Optional.of(WhenSourceAndTargetDirectoriesExist.ADOPT)
            LEAVE_UNCHANGED -> Optional.of(WhenSourceAndTargetDirectoriesExist.LEAVE_UNCHANGED)
            DISCARD_BOTH -> Optional.of(WhenSourceAndTargetDirectoriesExist.DISCARD)
        }
        val adopting = when (this) {
            ADOPT_AND_DISCARD_SOURCE -> Optional.of(WhenAdoptingTarget.DISCARD_SOURCE)
            ADOPT_AND_ARCHIVE_SOURCE -> Optional.of(WhenAdoptingTarget.ARCHIVE_SOURCE)
            ADOPT_TARGET, LEAVE_UNCHANGED, DISCARD_BOTH -> saved.whenAdoptingTarget
        }
        return Relocation(
            saved.sourcePath, saved.targetPath, both,
            if (this == ADOPT_TARGET) Optional.of(WhenOnlyTargetExists.ADOPT_TARGET) else saved.whenOnlyTargetExists,
            adopting, saved.sourceArchiveRoot, saved.stagingRoot,
        )
    }
}
