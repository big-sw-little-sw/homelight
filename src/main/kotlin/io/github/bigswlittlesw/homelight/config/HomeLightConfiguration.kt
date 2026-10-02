package io.github.bigswlittlesw.homelight.config

import java.nio.file.Path

/** Resolved paths used by the status and reconciliation adapters. */
@ConsistentCopyVisibility
data class HomeLightConfiguration private constructor(
    val targetRoot: Path,
    val relocations: List<Relocation>,
    val ignoredSourcePaths: List<Path>,
    val sharedList: Path?,
) {
    companion object {
        /** Normalizes `sharedList` and copies the lists. */
        fun of(
            targetRoot: Path,
            relocations: List<Relocation>,
            ignoredSourcePaths: List<Path>,
            sharedList: Path? = null,
        ): HomeLightConfiguration = HomeLightConfiguration(
            targetRoot, relocations.toList(), ignoredSourcePaths.toList(), sharedList?.let(::normalizeSharedList),
        )
    }
}
