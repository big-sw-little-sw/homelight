package io.github.bigswlittlesw.homelight.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Loads one JSON configuration rooted at `homelight`.
 *
 * Malformed JSON, unknown and duplicate keys and values of the wrong type are rejected with their line and
 * column; see [decodeJson]. Missing required keys and invalid policy values are rejected by dotted path. A
 * null, empty or blank string is absent. Paths expand `~`, `~/` and `${USER}`, then become absolute and
 * normalized. Environment variables and system properties never override values.
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
            throw ConfigurationException("Line ${exception.line}, column ${exception.column}: " + exception.message)
        }
        return configuration(file.homelight ?: throw missing("homelight"), override)
    }

    private fun configuration(homelight: HomeLightFile, override: PathOverride?): HomeLightConfiguration {
        val targetRoot = resolve(homelight.targetRoot.present() ?: throw missing("homelight.target-root"))
        val stagingRoot = homelight.stagingRoot.present()?.let(::resolve)
        if (stagingRoot != null && !stagingRoot.startsWith(targetRoot)) {
            throw ConfigurationException("staging-root must be under target-root")
        }
        // The override replaces the first relocation's paths, or supplies it when none is configured.
        val relocations = homelight.relocations.orEmpty().mapIndexed { i, fields ->
            relocation(fields, "homelight.relocations[$i]", targetRoot, stagingRoot, if (i == 0) override else null)
        }.ifEmpty {
            listOfNotNull(override?.let { relocation(RelocationFile(), "", targetRoot, stagingRoot, it) })
        }
        val ignoredSourcePaths = homelight.ignoredSourcePaths.orEmpty().mapNotNull { it.present()?.let(::resolve) }
        val sharedList = homelight.discovery?.sharedList.present()?.let(::parseSharedList)
        return HomeLightConfiguration.of(targetRoot, relocations, ignoredSourcePaths, sharedList)
    }

    private fun relocation(
        fields: RelocationFile, path: String, targetRoot: Path, stagingRoot: Path?, override: PathOverride?,
    ): Relocation {
        val sourcePath = resolve(
            override?.sourcePath?.toString() ?: fields.sourcePath.present() ?: throw missing("$path.source-path"),
        )
        val targetPath = override?.let { resolve(it.targetPath.toString()) }
            ?: fields.targetPath.present()?.let(::resolve)
            ?: deriveTarget(targetRoot, sourcePath)
        val whenAdoptingTarget =
            choice(fields.whenAdoptingTarget, "$path.when-adopting-target", WhenAdoptingTarget.entries) { it.value }
        val archiveRoot = fields.sourceArchiveRoot.present()?.let(::resolve)
        if (lacksArchiveRoot(whenAdoptingTarget, archiveRoot)) {
            throw ConfigurationException("source-archive-root is required when when-adopting-target is archive-source")
        }
        return Relocation(
            sourcePath, targetPath,
            choice(
                fields.whenSourceAndTargetDirectoriesExist, "$path.when-source-and-target-directories-exist",
                WhenSourceAndTargetDirectoriesExist.entries,
            ) { it.value },
            choice(fields.whenOnlyTargetExists, "$path.when-only-target-exists", WhenOnlyTargetExists.entries) { it.value },
            whenAdoptingTarget, archiveRoot, stagingRoot,
        )
    }

    /** A policy given by the `name` of one of `choices`. */
    private fun <E> choice(text: String?, path: String, choices: List<E>, name: (E) -> String): E? {
        val value = text.present() ?: return null
        return choices.firstOrNull { name(it) == value } ?: throw ConfigurationException(
            "Invalid value '$value' for $path; expected one of " + choices.joinToString(", ", transform = name),
        )
    }

    private fun resolve(value: String): Path {
        var expanded = value.replace("\${USER}", System.getenv().getOrDefault("USER", ""))
        if (expanded == "~") {
            expanded = System.getProperty("user.home")
        } else if (expanded.startsWith("~/")) {
            expanded = System.getProperty("user.home") + expanded.substring(1)
        }
        return Path.of(expanded).toAbsolutePath().normalize()
    }

    private fun deriveTarget(targetRoot: Path, sourcePath: Path): Path {
        val home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()
        if (!sourcePath.startsWith(home)) {
            throw ConfigurationException("A source outside \$HOME requires an explicit target-path: $sourcePath")
        }
        return targetRoot.resolve(home.relativize(sourcePath)).normalize()
    }

    private fun missing(path: String) = ConfigurationException("Missing required key $path")

    class ConfigurationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

    companion object {
        val DEFAULT_PATH: Path = Path.of(System.getProperty("user.home"), ".homelight.json")
    }
}

/** A null, empty or blank value is absent. */
private fun String?.present(): String? = this?.takeUnless { it.isJavaBlank() }

// File shape of the configuration, shared with ConfigurationPublisher. Every value is optional here so that a
// missing key and a blank value are reported alike, by ConfigurationLoader. A null value is omitted on output.

@Serializable
internal data class ConfigurationFile(val homelight: HomeLightFile? = null)

@Serializable
internal data class HomeLightFile(
    @SerialName("target-root") val targetRoot: String? = null,
    @SerialName("staging-root") val stagingRoot: String? = null,
    val discovery: DiscoveryFile? = null,
    val relocations: List<RelocationFile>? = null,
    // Null items are skipped, as blank ones are.
    @SerialName("ignored-source-paths") val ignoredSourcePaths: List<String?>? = null,
)

@Serializable
internal data class DiscoveryFile(@SerialName("shared-list") val sharedList: String? = null)

@Serializable
internal data class RelocationFile(
    @SerialName("source-path") val sourcePath: String? = null,
    @SerialName("target-path") val targetPath: String? = null,
    @SerialName("when-source-and-target-directories-exist") val whenSourceAndTargetDirectoriesExist: String? = null,
    @SerialName("when-only-target-exists") val whenOnlyTargetExists: String? = null,
    @SerialName("when-adopting-target") val whenAdoptingTarget: String? = null,
    @SerialName("source-archive-root") val sourceArchiveRoot: String? = null,
)
