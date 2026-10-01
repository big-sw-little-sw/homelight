package io.github.bigswlittlesw.homelight.config

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.Mark
import org.yaml.snakeyaml.error.MarkedYAMLException
import org.yaml.snakeyaml.error.YAMLException
import org.yaml.snakeyaml.nodes.Node
import java.io.IOException
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path

/**
 * Loads one YAML configuration rooted at `homelight`.
 *
 * Unknown keys, missing required keys and values of the wrong shape are rejected with their line and
 * column, as [YamlMapping] reads them. Paths expand `~`, `~/` and `${USER}`, then become
 * absolute and normalized. Environment variables and system properties never override values.
 */
class ConfigurationLoader {
    /**
     * Replaces the first relocation's source and target paths for one command; the file is unchanged.
     * With no configured relocations it supplies the first one.
     */
    data class PathOverride(val sourcePath: Path, val targetPath: Path)

    fun load(path: Path, override: PathOverride? = null): HomeLightConfiguration {
        val document = document(path)
        try {
            return configuration(document, override)
        } catch (violation: YamlMapping.Violation) {
            throw ConfigurationException(at(violation.mark) + violation.message)
        }
    }

    private fun configuration(document: Node?, override: PathOverride?): HomeLightConfiguration {
        val homelight = YamlMapping.of("", document, ROOT_KEYS).requiredMapping("homelight", HOMELIGHT_KEYS)
        val targetRoot = resolve(homelight.requiredString("target-root"))
        val stagingRoot = homelight.string("staging-root")?.let(::resolve)
        if (stagingRoot != null && !stagingRoot.startsWith(targetRoot)) {
            throw ConfigurationException("staging-root must be under target-root")
        }
        // The override replaces the first relocation's paths, or supplies it when none is configured.
        val relocationNodes: List<Node> = homelight.list("relocations")?.value.orEmpty()
        val relocations = ArrayList<Relocation>()
        for (i in relocationNodes.indices) {
            val fields = YamlMapping.of(homelight.qualify("relocations") + "[" + i + "]", relocationNodes[i], RELOCATION_KEYS)
            relocations.add(relocation(fields, targetRoot, stagingRoot, if (i == 0) override else null))
        }
        if (relocations.isEmpty() && override != null) {
            relocations.add(relocation(YamlMapping.of("", null, RELOCATION_KEYS), targetRoot, stagingRoot, override))
        }
        val ignoredSourcePaths = homelight.strings("ignored-source-paths").map(::resolve)
        val sharedList = homelight.mapping("discovery", DISCOVERY_KEYS)
            ?.string("shared-list")
            ?.let(::parseSharedList)
        return HomeLightConfiguration.of(targetRoot, relocations, ignoredSourcePaths, sharedList)
    }

    private fun document(path: Path): Node? {
        if (!Files.isRegularFile(path)) {
            throw ConfigurationException("Configuration file does not exist: $path")
        }
        try {
            // compose() builds nodes only, so explicit tags never instantiate Java types.
            return Yaml(SafeConstructor(LoaderOptions())).compose(StringReader(Files.readString(path)))
        } catch (exception: IOException) {
            throw ConfigurationException("Unable to read configuration $path", exception)
        } catch (exception: MarkedYAMLException) {
            val problem = exception.problem ?: "invalid syntax"
            throw ConfigurationException(at(exception.problemMark) + "Malformed YAML: " + problem, exception)
        } catch (exception: YAMLException) {
            throw ConfigurationException("Malformed YAML: " + exception.message, exception)
        }
    }

    private fun relocation(
        fields: YamlMapping, targetRoot: Path, stagingRoot: Path?, override: PathOverride?,
    ): Relocation {
        val sourcePath = resolve(override?.sourcePath?.toString() ?: fields.requiredString("source-path"))
        val targetPath = override?.let { resolve(it.targetPath.toString()) }
            ?: fields.string("target-path")?.let(::resolve)
            ?: deriveTarget(targetRoot, sourcePath)
        val whenAdoptingTarget = fields.choice("when-adopting-target", WhenAdoptingTarget.entries) { it.value }
        val archiveRoot = fields.string("source-archive-root")?.let(::resolve)
        if (whenAdoptingTarget == WhenAdoptingTarget.ARCHIVE_SOURCE && archiveRoot == null) {
            throw ConfigurationException("source-archive-root is required when when-adopting-target is archive-source")
        }
        return Relocation(
            sourcePath, targetPath,
            fields.choice("when-source-and-target-directories-exist", WhenSourceAndTargetDirectoriesExist.entries) { it.value },
            fields.choice("when-only-target-exists", WhenOnlyTargetExists.entries) { it.value },
            whenAdoptingTarget, archiveRoot, stagingRoot,
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

    private fun at(mark: Mark?): String =
        if (mark == null) "" else "Line " + (mark.line + 1) + ", column " + (mark.column + 1) + ": "

    class ConfigurationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

    companion object {
        val DEFAULT_PATH: Path = Path.of(System.getProperty("user.home"), ".homelight.yaml")
    }
}

private val ROOT_KEYS = setOf("homelight")
private val HOMELIGHT_KEYS = setOf("target-root", "staging-root", "relocations", "ignored-source-paths", "discovery")
private val DISCOVERY_KEYS = setOf("shared-list")
private val RELOCATION_KEYS = setOf(
    "source-path", "target-path", "when-source-and-target-directories-exist", "when-only-target-exists",
    "when-adopting-target", "source-archive-root",
)
