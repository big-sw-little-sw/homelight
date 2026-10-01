package io.github.bigswlittlesw.homelight.config

import java.nio.file.Path
import java.util.Objects
import java.util.Optional

/**
 * The unsaved contents of a new configuration. It is deliberately separate from
 * loaded configuration: editing a draft cannot change an existing file.
 *
 * Not a `@JvmRecord data class`: the constructor normalizes its components, which a Kotlin record
 * cannot do. Accessors keep the record names; equality and `toString` match the record this replaces.
 */
class ConfigurationDraft(targetRoot: Path, relocations: List<Relocation>, sharedList: Optional<Path>) {
    @get:JvmName("targetRoot")
    val targetRoot: Path = targetRoot.toAbsolutePath().normalize()

    @get:JvmName("relocations")
    val relocations: List<Relocation> = java.util.List.copyOf(relocations)

    @get:JvmName("sharedList")
    val sharedList: Optional<Path> = sharedList.map(DiscoverySetting::normalize)

    constructor(targetRoot: Path, relocations: List<Relocation>) : this(targetRoot, relocations, Optional.empty())

    fun withTargetRoot(value: Path): ConfigurationDraft = ConfigurationDraft(value, relocations, sharedList)

    fun withRelocations(value: List<Relocation>): ConfigurationDraft = ConfigurationDraft(targetRoot, value, sharedList)

    override fun equals(other: Any?): Boolean = other is ConfigurationDraft
            && targetRoot == other.targetRoot
            && relocations == other.relocations
            && sharedList == other.sharedList

    override fun hashCode(): Int = Objects.hash(targetRoot, relocations, sharedList)

    override fun toString(): String =
        "ConfigurationDraft[targetRoot=$targetRoot, relocations=$relocations, sharedList=$sharedList]"
}
