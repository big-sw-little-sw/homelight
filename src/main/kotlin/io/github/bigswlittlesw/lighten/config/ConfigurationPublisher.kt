package io.github.bigswlittlesw.lighten.config

import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE

/**
 * Checks a configuration in the file's own shape and writes it atomically: [saveNew] never replaces an existing
 * file, and [replace] replaces only the file as it was loaded. Paths are written as given, so `~` and `${USER}`
 * stay as the user wrote them.
 *
 * The check is the loader's own ([ConfigurationLoader.configuration]), so a saved file always loads, plus
 * [validateConfiguration]: at least one relocation or ignored path, no relocations overlapping. Either failure throws before anything is
 * written: a [ConfigurationException] for a value the loader rejects, an [IllegalArgumentException] for the rest.
 */
class ConfigurationPublisher {
    internal fun saveNew(path: Path, file: LightenFile) {
        check(file)
        val destination = path.toAbsolutePath().normalize()
        try {
            writeThrough(destination, file) { temporary ->
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
     * Replaces the configuration at [path] with [file] only if it still holds [loaded], the bytes read when it was
     * loaded. A changed or deleted file is left as it is and a [ConfigurationChangedException] says so.
     *
     * A symlinked configuration (for example from a dotfiles checkout) is replaced at its target, so the link
     * stays. The replaced file keeps its POSIX permissions (Linux and macOS only). Another writer that changes the
     * file between the comparison and the move still loses its change; closing that window needs a lock that every
     * writer honors, which hand edits do not.
     */
    internal fun replace(path: Path, file: LightenFile, loaded: ByteArray) {
        check(file)
        val shown = path.toAbsolutePath().normalize()
        try {
            val destination = path.toRealPath()
            if (!Files.readAllBytes(destination).contentEquals(loaded)) throw ConfigurationChangedException(shown)
            // On Linux and macOS an atomic move is rename(2), which replaces the destination in one step.
            writeThrough(destination, file) { temporary ->
                // The temp file starts at 0600; a replace keeps the file's own permissions.
                Files.setPosixFilePermissions(temporary, Files.getPosixFilePermissions(destination))
                Files.move(temporary, destination, ATOMIC_MOVE)
            }
        } catch (exception: NoSuchFileException) {
            throw ConfigurationChangedException(shown, exception)
        } catch (exception: IOException) {
            throw ConfigurationException("Unable to replace configuration $shown", exception)
        }
    }

    private fun check(file: LightenFile) {
        val configuration = ConfigurationLoader().configuration(file)
        validateConfiguration(configuration.relocations, configuration.ignoredSourcePaths)
    }

    /** Writes [file] to a temporary file beside [destination] and hands it to [publish]; the file never outlives it. */
    private fun writeThrough(destination: Path, file: LightenFile, publish: (Path) -> Unit) {
        val parent = destination.parent ?: throw IOException("Configuration path has no parent: $destination")
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, ".lighten-", ".json")
        try {
            Files.writeString(temporary, encodeConfiguration(ConfigurationFile(file)), StandardCharsets.UTF_8)
            publish(temporary)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

/** The file changed, or was deleted, since it was loaded, so [ConfigurationPublisher.replace] left it as it is. */
class ConfigurationChangedException(path: Path, cause: Throwable? = null) :
    ConfigurationException("Configuration changed since it was loaded and was not replaced: $path", cause)

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
