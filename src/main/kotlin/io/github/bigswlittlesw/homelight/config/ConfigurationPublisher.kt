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
                Files.writeString(temporary, json(draft), StandardCharsets.UTF_8)
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

    class ConfigurationException(message: String, cause: Throwable) : RuntimeException(message, cause)

    companion object {
        /** Pretty-printed for hand editing. Keys keep declaration order, and absent (null) values are omitted. */
        private val OUTPUT = Json { prettyPrint = true }

        internal fun json(draft: ConfigurationDraft): String {
            val relocations = draft.relocations.map { relocation ->
                RelocationFile(
                    relocation.sourcePath.toString(), relocation.targetPath.toString(),
                    relocation.whenSourceAndTargetDirectoriesExist?.value, relocation.whenOnlyTargetExists?.value,
                    relocation.whenAdoptingTarget?.value, relocation.sourceArchiveRoot?.toString(),
                )
            }
            val homelight = HomeLightFile(
                targetRoot = draft.targetRoot.toString(),
                discovery = draft.sharedList?.let { DiscoveryFile(it.toString()) },
                relocations = relocations,
            )
            return OUTPUT.encodeToString(ConfigurationFile.serializer(), ConfigurationFile(homelight)) + "\n"
        }
    }
}
