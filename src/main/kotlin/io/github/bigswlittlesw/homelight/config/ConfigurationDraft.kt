package io.github.bigswlittlesw.homelight.config

import java.nio.file.Path

/**
 * The unsaved contents of a new configuration. It is deliberately separate from
 * loaded configuration: editing a draft cannot change an existing file.
 */
@ConsistentCopyVisibility
data class ConfigurationDraft private constructor(
    val sourceRoot: Path,
    val targetRoot: Path,
    val relocations: List<Relocation>,
    val sharedList: Path?,
) {
    companion object {
        /** Normalizes the roots and `sharedList`, and copies `relocations`. */
        fun of(
            targetRoot: Path,
            relocations: List<Relocation>,
            sharedList: Path? = null,
            sourceRoot: Path = Path.of(System.getProperty("user.home")),
        ): ConfigurationDraft = ConfigurationDraft(
            sourceRoot.toAbsolutePath().normalize(), targetRoot.toAbsolutePath().normalize(), relocations.toList(),
            sharedList?.let(::normalizeSharedList),
        )
    }
}
