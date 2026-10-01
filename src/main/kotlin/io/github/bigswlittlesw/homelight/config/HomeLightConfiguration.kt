package io.github.bigswlittlesw.homelight.config

import java.nio.file.Path
import java.util.Objects
import java.util.Optional

/**
 * Resolved paths used by the status and reconciliation adapters.
 *
 * Not a `@JvmRecord data class`: the constructor normalizes its components, which a Kotlin record
 * cannot do. Accessors keep the record names; equality and `toString` match the record this replaces.
 */
class HomeLightConfiguration(
    targetRoot: Path,
    relocations: List<Relocation>,
    ignoredSourcePaths: List<Path>,
    sharedList: Optional<Path>,
) {
    @get:JvmName("targetRoot")
    val targetRoot: Path = targetRoot

    @get:JvmName("relocations")
    val relocations: List<Relocation> = java.util.List.copyOf(relocations)

    @get:JvmName("ignoredSourcePaths")
    val ignoredSourcePaths: List<Path> = java.util.List.copyOf(ignoredSourcePaths)

    @get:JvmName("sharedList")
    val sharedList: Optional<Path> = sharedList.map(DiscoverySetting::normalize)

    constructor(targetRoot: Path, relocations: List<Relocation>, ignoredSourcePaths: List<Path>) :
            this(targetRoot, relocations, ignoredSourcePaths, Optional.empty())

    override fun equals(other: Any?): Boolean = other is HomeLightConfiguration
            && targetRoot == other.targetRoot
            && relocations == other.relocations
            && ignoredSourcePaths == other.ignoredSourcePaths
            && sharedList == other.sharedList

    override fun hashCode(): Int = Objects.hash(targetRoot, relocations, ignoredSourcePaths, sharedList)

    override fun toString(): String = "HomeLightConfiguration[targetRoot=$targetRoot, relocations=$relocations, " +
            "ignoredSourcePaths=$ignoredSourcePaths, sharedList=$sharedList]"
}
