package io.github.bigswlittlesw.homelight.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Loads one JSON configuration rooted at `homelight`.
 *
 * kotlinx.serialization owns the format; see [decodeJson] for what it rejects. This class applies the domain
 * rules: paths must not be blank, expand `~`, `~/` and `${USER}`, then become absolute and normalized; a
 * missing target is the source's path under `source-root` (default `~`) placed under `target-root`; a missing
 * archive root is [defaultArchiveRoot]; staging-root must be under target-root. Environment variables and
 * system properties never override values.
 */
class ConfigurationLoader {
    /**
     * Replaces the first relocation's source and target paths for one command; the file is unchanged.
     * With no configured relocations it supplies the first one.
     */
    data class PathOverride(val sourcePath: Path, val targetPath: Path)

    fun load(path: Path, override: PathOverride? = null): HomeLightConfiguration {
        if (!Files.isRegularFile(path)) {
            throw ConfigurationException("Configuration file does not exist: $path")
        }
        val text = try {
            Files.readString(path)
        } catch (exception: IOException) {
            throw ConfigurationException("Unable to read configuration $path", exception)
        }
        val file = try {
            decodeJson(ConfigurationFile.serializer(), text)
        } catch (exception: JsonInputException) {
            // The message is complete; the cause would only repeat it.
            val position = if (exception.line > 0) "Line ${exception.line}, column ${exception.column}: " else ""
            throw ConfigurationException(position + exception.message)
        }
        return configuration(file.homelight, override)
    }

    private fun configuration(homelight: HomeLightFile, override: PathOverride?): HomeLightConfiguration {
        val sourceRoot = resolve(homelight.sourceRoot, "homelight.source-root")
        val targetRoot = resolve(homelight.targetRoot, "homelight.target-root")
        val stagingRoot = homelight.stagingRoot?.let { resolve(it, "homelight.staging-root") }
        if (stagingRoot != null && !stagingRoot.startsWith(targetRoot)) {
            throw ConfigurationException("staging-root must be under target-root")
        }
        // The override replaces the first relocation's paths, or supplies it when none is configured.
        val relocations = homelight.relocations.mapIndexed { i, fields ->
            relocation(fields, "homelight.relocations[$i]", sourceRoot, targetRoot, stagingRoot, if (i == 0) override else null)
        }.ifEmpty {
            listOfNotNull(override?.let {
                Relocation(expand(it.sourcePath.toString()), expand(it.targetPath.toString()), stagingRoot = stagingRoot)
            })
        }
        val ignoredSourcePaths = homelight.ignoredSourcePaths.mapIndexed { i, value ->
            resolve(value, "homelight.ignored-source-paths[$i]")
        }
        val sharedList = homelight.discovery?.sharedList?.let(::parseSharedList)
        return HomeLightConfiguration.of(targetRoot, relocations, ignoredSourcePaths, sharedList)
    }

    private fun relocation(
        fields: RelocationFile, key: String, sourceRoot: Path, targetRoot: Path, stagingRoot: Path?,
        override: PathOverride?,
    ): Relocation {
        val sourcePath = override?.let { expand(it.sourcePath.toString()) } ?: resolve(fields.sourcePath, "$key.source-path")
        val targetPath = override?.let { expand(it.targetPath.toString()) }
            ?: fields.targetPath?.let { resolve(it, "$key.target-path") }
            ?: deriveTarget(sourceRoot, targetRoot, sourcePath)
        return Relocation(
            sourcePath, targetPath, fields.whenSourceAndTargetDirectoriesExist, fields.whenOnlyTargetExists,
            fields.whenAdoptingTarget,
            fields.archiveRoot?.let { resolve(it, "$key.archive-root") } ?: defaultArchiveRoot(sourcePath),
            stagingRoot,
        )
    }

    /** A path from the file. A blank one is rejected, as it would expand to the working directory. */
    private fun resolve(value: String, key: String): Path {
        if (value.isJavaBlank()) throw ConfigurationException("$key must not be blank")
        return expand(value)
    }

    private fun expand(value: String): Path {
        val substituted = value.replace("\${USER}", System.getenv("USER").orEmpty())
        val expanded = when {
            substituted == "~" -> System.getProperty("user.home")
            substituted.startsWith("~/") -> System.getProperty("user.home") + substituted.substring(1)
            else -> substituted
        }
        return Path.of(expanded).toAbsolutePath().normalize()
    }

    private fun deriveTarget(sourceRoot: Path, targetRoot: Path, sourcePath: Path): Path {
        if (!sourcePath.startsWith(sourceRoot)) {
            throw ConfigurationException(
                "A source outside source-root $sourceRoot requires an explicit target-path: $sourcePath")
        }
        return targetRoot.resolve(sourceRoot.relativize(sourcePath)).normalize()
    }

    companion object {
        val DEFAULT_PATH: Path = Path.of(System.getProperty("user.home"), ".homelight.json")
    }
}

// The configuration file format, shared by ConfigurationLoader and ConfigurationPublisher. Paths stay as
// written (`~/x`, `${USER}`) and expand only in the loader. Optional values default to null, empty or
// `~` for source-root, and are omitted on output. Every class has a serial name because kotlinx puts it in its
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
    val discovery: DiscoveryFile? = null,
    val relocations: List<RelocationFile> = listOf(),
    @SerialName("ignored-source-paths") val ignoredSourcePaths: List<String> = listOf(),
)

/** A blank `shared-list` means none; see [parseSharedList]. */
@Serializable
@SerialName("discovery")
internal data class DiscoveryFile(@SerialName("shared-list") val sharedList: String? = null)

@Serializable
@SerialName("relocation")
internal data class RelocationFile(
    @SerialName("source-path") val sourcePath: String,
    @SerialName("target-path") val targetPath: String? = null,
    @SerialName("when-source-and-target-directories-exist")
    val whenSourceAndTargetDirectoriesExist: WhenSourceAndTargetDirectoriesExist? = null,
    @SerialName("when-only-target-exists") val whenOnlyTargetExists: WhenOnlyTargetExists? = null,
    @SerialName("when-adopting-target") val whenAdoptingTarget: WhenAdoptingTarget? = null,
    /** Absent means [defaultArchiveRoot]. */
    @SerialName("archive-root") val archiveRoot: String? = null,
)
