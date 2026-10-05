package io.github.bigswlittlesw.homelight.config

import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path

/** Validates and atomically creates a configuration. Existing paths are never replaced. */
class ConfigurationPublisher {
    fun saveNew(path: Path, draft: ConfigurationDraft) {
        validateConfiguration(draft)
        val destination = path.toAbsolutePath().normalize()
        try {
            val parent = destination.parent ?: throw IOException("Configuration path has no parent: $destination")
            Files.createDirectories(parent)
            val temporary = Files.createTempFile(parent, ".homelight-", ".json")
            try {
                Files.writeString(temporary, encodeConfiguration(configurationFile(draft)), StandardCharsets.UTF_8)
                // A hard-link creation is an atomic create-if-absent operation. Unlike move(REPLACE_EXISTING),
                // it cannot replace a configuration created concurrently.
                Files.createLink(destination, temporary)
            } finally {
                Files.deleteIfExists(temporary)
            }
        } catch (exception: FileAlreadyExistsException) {
            throw ConfigurationException("Configuration already exists and was not replaced: $destination", exception)
        } catch (exception: IOException) {
            throw ConfigurationException("Unable to create configuration $destination", exception)
        }
    }
}

/**
 * Pretty-printed for hand editing. Keys keep declaration order, and values equal to their defaults (settings
 * left unset) are omitted, so written files stay minimal.
 */
private val OUTPUT = Json {
    prettyPrint = true
    encodeDefaults = false
}

internal fun encodeConfiguration(file: ConfigurationFile): String =
    OUTPUT.encodeToString(ConfigurationFile.serializer(), file) + "\n"

/** A draft holds resolved paths, so they are written absolute. A source root at the home directory is left out. */
internal fun configurationFile(draft: ConfigurationDraft): ConfigurationFile = ConfigurationFile(
    HomeLightFile(
        sourceRoot = if (draft.sourceRoot == home()) DEFAULT_SOURCE_ROOT else draft.sourceRoot.toString(),
        targetRoot = draft.targetRoot.toString(),
        discovery = draft.sharedList?.let { DiscoveryFile(it.toString()) },
        relocations = draft.relocations.map { relocation ->
            RelocationFile(
                relocation.sourcePath.toString(), relocation.targetPath.toString(),
                relocation.whenSourceAndTargetDirectoriesExist, relocation.whenOnlyTargetExists,
                relocation.whenAdoptingTarget,
                // The default root is left out, so it keeps following the source.
                relocation.archiveRoot.takeIf { it != defaultArchiveRoot(relocation.sourcePath) }?.toString(),
            )
        },
    ),
)

private fun home(): Path = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()
