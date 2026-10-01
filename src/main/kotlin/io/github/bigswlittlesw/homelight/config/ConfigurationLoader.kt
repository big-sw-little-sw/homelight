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
import java.util.Optional

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
    @JvmRecord
    data class PathOverride(val sourcePath: Path, val targetPath: Path)

    fun load(path: Path): HomeLightConfiguration = load(path, Optional.empty())

    fun load(path: Path, override: Optional<PathOverride>): HomeLightConfiguration {
        val document = document(path)
        try {
            return configuration(document, override)
        } catch (violation: YamlMapping.Violation) {
            throw ConfigurationException(at(violation.mark) + violation.message)
        }
    }

    class ConfigurationException : RuntimeException {
        constructor(message: String) : super(message)

        constructor(message: String, cause: Throwable) : super(message, cause)
    }

    companion object {
        @JvmField
        val DEFAULT_PATH: Path = Path.of(System.getProperty("user.home"), ".homelight.yaml")

        private val ROOT_KEYS = setOf("homelight")
        private val HOMELIGHT_KEYS = setOf(
            "target-root", "staging-root", "relocations", "ignored-source-paths", "discovery",
        )
        private val DISCOVERY_KEYS = setOf("shared-list")
        private val RELOCATION_KEYS = setOf(
            "source-path", "target-path", "when-source-and-target-directories-exist", "when-only-target-exists",
            "when-adopting-target", "source-archive-root",
        )

        private fun configuration(document: Node?, override: Optional<PathOverride>): HomeLightConfiguration {
            val homelight = YamlMapping.of("", document, ROOT_KEYS).requiredMapping("homelight", HOMELIGHT_KEYS)
            val targetRoot = resolve(homelight.requiredString("target-root"))
            val stagingRoot = homelight.string("staging-root").map { resolve(it) }
            if (stagingRoot.filter { root -> !root.startsWith(targetRoot) }.isPresent) {
                throw ConfigurationException("staging-root must be under target-root")
            }
            // The override replaces the first relocation's paths, or supplies it when none is configured.
            val relocationNodes: List<Node> = homelight.list("relocations").map<List<Node>> { it.value }.orElse(listOf())
            val relocations = ArrayList<Relocation>()
            for (i in relocationNodes.indices) {
                val fields = YamlMapping.of(
                    homelight.qualify("relocations") + "[" + i + "]", relocationNodes[i],
                    RELOCATION_KEYS,
                )
                relocations.add(relocation(fields, targetRoot, stagingRoot, if (i == 0) override else Optional.empty()))
            }
            if (relocations.isEmpty() && override.isPresent) {
                relocations.add(relocation(YamlMapping.of("", null, RELOCATION_KEYS), targetRoot, stagingRoot, override))
            }
            val ignoredSourcePaths = homelight.strings("ignored-source-paths").stream()
                .map { resolve(it) }
                .toList()
            val sharedList = homelight.mapping("discovery", DISCOVERY_KEYS)
                .flatMap { discovery -> discovery.string("shared-list") }
                .flatMap { DiscoverySetting.parse(it) }
            return HomeLightConfiguration(targetRoot, relocations, ignoredSourcePaths, sharedList)
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
                val problem = if (exception.problem == null) "invalid syntax" else exception.problem
                throw ConfigurationException(at(exception.problemMark) + "Malformed YAML: " + problem, exception)
            } catch (exception: YAMLException) {
                throw ConfigurationException("Malformed YAML: " + exception.message, exception)
            }
        }

        private fun relocation(
            fields: YamlMapping, targetRoot: Path, stagingRoot: Optional<Path>,
            override: Optional<PathOverride>,
        ): Relocation {
            val sourcePath = override.map { paths -> resolve(paths.sourcePath.toString()) }
                .orElseGet { resolve(fields.requiredString("source-path")) }
            val targetPath = override.map { paths -> resolve(paths.targetPath.toString()) }
                .or { fields.string("target-path").map { resolve(it) } }
                .orElseGet { deriveTarget(targetRoot, sourcePath) }
            val whenAdoptingTarget = fields.choice("when-adopting-target", WhenAdoptingTarget.values()) { it.value() }
            val archiveRoot = fields.string("source-archive-root").map { resolve(it) }
            if (whenAdoptingTarget.filter { WhenAdoptingTarget.ARCHIVE_SOURCE == it }.isPresent && archiveRoot.isEmpty) {
                throw ConfigurationException("source-archive-root is required when when-adopting-target is archive-source")
            }
            return Relocation(
                sourcePath, targetPath,
                fields.choice(
                    "when-source-and-target-directories-exist",
                    WhenSourceAndTargetDirectoriesExist.values(),
                ) { it.value() },
                fields.choice("when-only-target-exists", WhenOnlyTargetExists.values()) { it.value() },
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
    }
}
