package io.github.bigswlittlesw.lighten.config

import java.nio.file.Path

/** The configuration as loaded, with every path resolved and checked. */
@ConsistentCopyVisibility
data class LightenConfiguration private constructor(
    val targetRoot: Path,
    val relocations: List<Relocation>,
    val ignoredSourcePaths: List<Path>,
    val sharedList: Path?,
) {
    companion object {
        /** Copies the lists. */
        fun of(
            targetRoot: Path,
            relocations: List<Relocation>,
            ignoredSourcePaths: List<Path>,
            sharedList: Path? = null,
        ): LightenConfiguration = LightenConfiguration(
            targetRoot, relocations.toList(), ignoredSourcePaths.toList(), sharedList,
        )
    }
}
