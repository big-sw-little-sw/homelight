package io.github.bigswlittlesw.homelight.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Loads one JSON configuration rooted at `homelight`.
 *
 * kotlinx.serialization owns the format; see [decodeJson] for what it rejects. This class applies the domain
 * rules: paths must not be blank, expand `~`, `~/` and `${USER}`, then become absolute and normalized; a
 * missing target is the source's path under `source-root` (default `~`) placed under `target-root`; a missing
 * archive root is [defaultArchiveRoot]; staging-root must be under target-root; relocations must not overlap
 * through a symlink ([aliasedRelocationProblem]). Environment variables and system properties never override values.
 */
class ConfigurationLoader {
    /**
     * Replaces the first relocation's source and target paths for one command; the file is unchanged.
     * With no configured relocations it supplies the first one.
     */
    data class PathOverride(val sourcePath: Path, val targetPath: Path)

    fun load(path: Path, override: PathOverride? = null): HomeLightConfiguration = configuration(read(path).file, override)

    /**
     * The file at [path] in its own shape, with the bytes it was read from, so a later replace can tell whether the
     * file changed since (see [ConfigurationPublisher.replace]). Only the JSON is checked here; [configuration]
     * checks the values.
     */
    internal fun read(path: Path): LoadedFile {
        if (!Files.isRegularFile(path)) {
            throw ConfigurationException("Configuration file does not exist: $path")
        }
        val bytes = try {
            Files.readAllBytes(path)
        } catch (exception: IOException) {
            throw ConfigurationException("Unable to read configuration $path", exception)
        }
        val text = try {
            // Strict, as Files.readString is: malformed UTF-8 is an error, not replacement characters.
            StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        } catch (exception: CharacterCodingException) {
            throw ConfigurationException("Unable to read configuration $path", exception)
        }
        val file = try {
            decodeJson(ConfigurationFile.serializer(), text)
        } catch (exception: JsonInputException) {
            // The message is complete; the cause would only repeat it.
            val position = if (exception.line > 0) "Line ${exception.line}, column ${exception.column}: " else ""
            throw ConfigurationException(position + exception.message)
        }
        return LoadedFile(file.homelight, bytes)
    }

    /** The file's values resolved and checked, as [load] does after reading them. */
    internal fun configuration(homelight: HomeLightFile, override: PathOverride? = null): HomeLightConfiguration {
        val sourceRoot = resolvePath(homelight.sourceRoot, "homelight.source-root")
        val targetRoot = resolvePath(homelight.targetRoot, "homelight.target-root")
        val stagingRoot = homelight.stagingRoot?.let { resolvePath(it, "homelight.staging-root") }
        if (stagingRoot != null && !stagingRoot.startsWith(targetRoot)) {
            throw ConfigurationException("staging-root must be under target-root")
        }
        // The override replaces the first relocation's paths, or supplies it when none is configured.
        val relocations = homelight.relocations.mapIndexed { i, fields ->
            relocation(fields, "homelight.relocations[$i]", sourceRoot, targetRoot, stagingRoot, if (i == 0) override else null)
        }.ifEmpty {
            listOfNotNull(override?.let {
                Relocation(overridden(it.sourcePath), overridden(it.targetPath), stagingRoot = stagingRoot)
            })
        }
        aliasedRelocationProblem(relocations)?.let { problem -> throw ConfigurationException(problem.message) }
        val ignoredSourcePaths = homelight.ignoredSourcePaths.mapIndexed { i, value ->
            resolvePath(value, "homelight.ignored-source-paths[$i]")
        }
        val sharedList = homelight.suggestionList?.let { value ->
            convert("homelight.suggestion-list") { parseSharedList(value) }
        }
        return HomeLightConfiguration.of(targetRoot, relocations, ignoredSourcePaths, sharedList)
    }

    private fun relocation(
        fields: RelocationFile, key: String, sourceRoot: Path, targetRoot: Path, stagingRoot: Path?,
        override: PathOverride?,
    ): Relocation {
        val sourcePath = override?.let { overridden(it.sourcePath) } ?: resolvePath(fields.sourcePath, "$key.source-path")
        val targetPath = override?.let { overridden(it.targetPath) }
            ?: fields.targetPath?.let { resolvePath(it, "$key.target-path") }
            ?: derivedTarget(sourceRoot, targetRoot, sourcePath)
            ?: throw ConfigurationException(
                "A source outside source-root $sourceRoot requires an explicit target-path: $sourcePath")
        return Relocation(
            sourcePath, targetPath, fields.whenSourceAndTargetDirectoriesExist, fields.whenOnlyTargetExists,
            fields.whenAdoptingTarget,
            fields.archiveRoot?.let { resolvePath(it, "$key.archive-root") } ?: defaultArchiveRoot(sourcePath),
            stagingRoot,
        )
    }

    companion object {
        val DEFAULT_PATH: Path = Path.of(System.getProperty("user.home"), ".homelight.json")
    }
}

/**
 * A path from the file, named `name` in any error. It must be absolute, or start with `~/` (or be `~`), after
 * `${USER}` is filled in: a relative path would depend on the directory HomeLight happens to run in. Configuration
 * resolves its fields with this too, naming them as its screen does.
 */
internal fun resolvePath(value: String, name: String): Path {
    if (value.isJavaBlank()) throw ConfigurationException("$name must not be blank")
    return convert(name) { expand(value).also { require(it.isAbsolute) { FULL_PATH } }.normalize() }
}

/** The one error for a relative path, wherever a path is set. */
internal const val FULL_PATH = "Use a full path, or one starting with ~/"

/** A missing target: the source's path under the source root, placed under the target root; null outside it. */
internal fun derivedTarget(sourceRoot: Path, targetRoot: Path, sourcePath: Path): Path? =
    if (!sourcePath.startsWith(sourceRoot)) null else targetRoot.resolve(sourceRoot.relativize(sourcePath)).normalize()

/**
 * Reports a value that a conversion rejects (`require`, or `Path.of` with a NUL) as a [ConfigurationException]
 * naming its key, so the CLI prints one line instead of a stack trace.
 */
private fun <T> convert(key: String, conversion: () -> T): T = try {
    conversion()
} catch (exception: IllegalArgumentException) {
    // An InvalidPathException's message repeats the input, which may hold the control character itself.
    throw ConfigurationException("$key: ${(exception as? InvalidPathException)?.reason ?: exception.message}")
}

private fun expand(value: String): Path {
    val substituted = value.replace("\${USER}", System.getenv("USER").orEmpty())
    val expanded = when {
        substituted == "~" -> System.getProperty("user.home")
        substituted.startsWith("~/") -> System.getProperty("user.home") + substituted.substring(1)
        else -> substituted
    }
    return Path.of(expanded)
}

/** A command-line override: unlike the file, it may be relative to where the command runs. */
private fun overridden(path: Path): Path = expand(path.toString()).toAbsolutePath().normalize()

/** A configuration file as read: its contents, and its bytes for [ConfigurationPublisher.replace]. */
internal class LoadedFile(val file: HomeLightFile, val bytes: ByteArray)

// The configuration file format, shared by ConfigurationLoader and ConfigurationPublisher. Paths stay as
// written (`~/x`, `${USER}`) and expand only in the loader. Optional values default to null, empty, `~` for
// source-root or `prompt` for a rule, and are omitted on output, so an explicit default is dropped on the next
// write without changing its meaning. Every class has a serial name because kotlinx puts it in its
// error messages.

internal const val DEFAULT_SOURCE_ROOT = "~"

@Serializable
@SerialName("configuration")
internal data class ConfigurationFile(val homelight: HomeLightFile)

@Serializable
@SerialName("homelight")
internal data class HomeLightFile(
    /** Targets derive from a source's path under this root. */
    @SerialName("source-root") val sourceRoot: String = DEFAULT_SOURCE_ROOT,
    @SerialName("target-root") val targetRoot: String,
    @SerialName("staging-root") val stagingRoot: String? = null,
    /** A blank one means none; see [parseSharedList]. */
    @SerialName("suggestion-list") val suggestionList: String? = null,
    val relocations: List<RelocationFile> = listOf(),
    @SerialName("ignored-source-paths") val ignoredSourcePaths: List<String> = listOf(),
)

@Serializable
@SerialName("relocation")
internal data class RelocationFile(
    @SerialName("source-path") val sourcePath: String,
    @SerialName("target-path") val targetPath: String? = null,
    @SerialName("when-source-and-target-directories-exist")
    val whenSourceAndTargetDirectoriesExist: WhenSourceAndTargetDirectoriesExist = WhenSourceAndTargetDirectoriesExist.PROMPT,
    @SerialName("when-only-target-exists") val whenOnlyTargetExists: WhenOnlyTargetExists = WhenOnlyTargetExists.PROMPT,
    @SerialName("when-adopting-target") val whenAdoptingTarget: WhenAdoptingTarget = WhenAdoptingTarget.PROMPT,
    /** Absent means [defaultArchiveRoot]. */
    @SerialName("archive-root") val archiveRoot: String? = null,
)
