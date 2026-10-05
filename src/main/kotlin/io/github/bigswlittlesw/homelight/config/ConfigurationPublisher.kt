package io.github.bigswlittlesw.homelight.config

import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE

/**
 * Validates a draft and writes it atomically: [saveNew] never replaces an existing file, and [replace] replaces
 * only the file as it was loaded.
 */
class ConfigurationPublisher {
    fun saveNew(path: Path, draft: ConfigurationDraft) {
        validateConfiguration(draft)
        val destination = path.toAbsolutePath().normalize()
        try {
            writeThrough(destination, draft) { temporary ->
                // A hard-link creation is an atomic create-if-absent operation. Unlike move(REPLACE_EXISTING),
                // it cannot replace a configuration created concurrently.
                Files.createLink(destination, temporary)
            }
        } catch (exception: FileAlreadyExistsException) {
            throw ConfigurationException("Configuration already exists and was not replaced: $destination", exception)
        } catch (exception: IOException) {
            throw ConfigurationException("Unable to create configuration $destination", exception)
        }
    }

    /**
     * Replaces the configuration at [path] with [draft] only if the file still holds [loaded], the bytes read
     * when it was loaded. A changed or deleted file is left as it is and a [ConfigurationException] says so.
     *
     * A symlinked configuration (for example from a dotfiles checkout) is replaced at its target, so the link
     * stays. The replaced file keeps its POSIX permissions (Linux and macOS only). Another writer that changes the file between the comparison and the move still loses its change;
     * closing that window needs a lock that every writer honors, which hand edits do not.
     */
    fun replace(path: Path, draft: ConfigurationDraft, loaded: ByteArray) {
        validateConfiguration(draft)
        val shown = path.toAbsolutePath().normalize()
        try {
            val destination = path.toRealPath()
            if (!Files.readAllBytes(destination).contentEquals(loaded)) throw changedSinceLoad(shown)
            // On Linux and macOS an atomic move is rename(2), which replaces the destination in one step.
            writeThrough(destination, draft) { temporary ->
                // The temp file starts at 0600; a replace keeps the file's own permissions.
                Files.setPosixFilePermissions(temporary, Files.getPosixFilePermissions(destination))
                Files.move(temporary, destination, ATOMIC_MOVE)
            }
        } catch (exception: NoSuchFileException) {
            throw changedSinceLoad(shown, exception)
        } catch (exception: IOException) {
            throw ConfigurationException("Unable to replace configuration $shown", exception)
        }
    }

    private fun changedSinceLoad(path: Path, cause: Throwable? = null) =
        ConfigurationException("Configuration changed since it was loaded and was not replaced: $path", cause)

    /** Writes [draft] to a temporary file beside [destination] and hands it to [publish]; the file never outlives it. */
    private fun writeThrough(destination: Path, draft: ConfigurationDraft, publish: (Path) -> Unit) {
        val parent = destination.parent ?: throw IOException("Configuration path has no parent: $destination")
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, ".homelight-", ".json")
        try {
            Files.writeString(temporary, encodeConfiguration(configurationFile(draft)), StandardCharsets.UTF_8)
            publish(temporary)
        } finally {
            Files.deleteIfExists(temporary)
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
