package io.github.bigswlittlesw.homelight.config

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional

/** Validates and atomically creates a configuration. Existing paths are never replaced. */
class ConfigurationPublisher {
    fun saveNew(path: Path, draft: ConfigurationDraft) {
        ConfigurationValidator.validate(draft)
        val destination = path.toAbsolutePath().normalize()
        try {
            val parent = destination.parent ?: throw IOException("Configuration path has no parent: $destination")
            Files.createDirectories(parent)
            val temporary = Files.createTempFile(parent, ".homelight-", ".yaml")
            try {
                Files.writeString(temporary, yaml(draft), StandardCharsets.UTF_8)
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
        // Package-private in Java; the Java tests call it, so it stays static and unmangled.
        @JvmStatic
        fun yaml(draft: ConfigurationDraft): String {
            val text = StringBuilder("homelight:\n  target-root: ").append(value(draft.targetRoot)).append("\n")
            draft.sharedList.ifPresent { location ->
                text.append("  discovery:\n    shared-list: ").append(value(location)).append("\n")
            }
            text.append("  relocations:\n")
            for (relocation in draft.relocations) {
                text.append("    - source-path: ").append(value(relocation.sourcePath)).append("\n")
                    .append("      target-path: ").append(value(relocation.targetPath)).append("\n")
                append(text, "when-source-and-target-directories-exist", relocation.whenSourceAndTargetDirectoriesExist.map { it.value() })
                append(text, "when-only-target-exists", relocation.whenOnlyTargetExists.map { it.value() })
                append(text, "when-adopting-target", relocation.whenAdoptingTarget.map { it.value() })
                append(text, "source-archive-root", relocation.sourceArchiveRoot.map(Path::toString))
            }
            return text.toString()
        }

        private fun append(text: StringBuilder, key: String, value: Optional<String>) {
            value.ifPresent { entry -> text.append("      ").append(key).append(": ").append(value(entry)).append("\n") }
        }

        private fun value(value: Path): String = value(value.toString())

        private fun value(value: String): String = "'" + value.replace("'", "''") + "'"
    }
}
