package io.github.bigswlittlesw.homelight.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

// Java text blocks end with the newline before the closing delimiter, and `trimIndent` drops it,
// so each block appends "\n" to keep the YAML byte-identical.
class ConfigurationLoaderTest {
    @Test fun loadsStateSpecificDecisions() { val file=Files.createTempFile("homelight", ".yaml"); Files.writeString(file, """
        homelight:
          target-root: /local
          relocations:
            - source-path: /home/cache
              target-path: /local/cache
              when-source-and-target-directories-exist: adopt
              when-adopting-target: archive-source
              source-archive-root: /archive
        """.trimIndent() + "\n"); val relocation=ConfigurationLoader().load(file).relocations.first(); assertEquals(WhenSourceAndTargetDirectoriesExist.ADOPT,relocation.whenSourceAndTargetDirectoriesExist); assertEquals(WhenAdoptingTarget.ARCHIVE_SOURCE,relocation.whenAdoptingTarget) }
    @Test fun rejectsArchiveWithoutRoot() { val file=Files.createTempFile("homelight", ".yaml"); Files.writeString(file, """
        homelight:
          target-root: /local
          relocations:
            - source-path: /home/cache
              target-path: /local/cache
              when-adopting-target: archive-source
        """.trimIndent() + "\n"); assertThrows(ConfigurationLoader.ConfigurationException::class.java) { ConfigurationLoader().load(file) } }

    @Test fun rejectsTheRemovedExistingSetting() { val file=Files.createTempFile("homelight", ".yaml"); Files.writeString(file, """
        homelight:
          target-root: /local
          relocations:
            - source-path: /home/cache
              target-path: /local/cache
              existing: move
        """.trimIndent() + "\n"); assertThrows(RuntimeException::class.java) { ConfigurationLoader().load(file) } }

    @TempDir lateinit var temporary: Path

    @Test fun reportsMissingRequiredKeysWithLocation() {
        assertEquals("Line 2, column 3: Missing required key homelight.target-root", failure("""
                homelight:
                  relocations: []
                """.trimIndent() + "\n"))
        assertEquals("Line 2, column 3: Missing required key homelight.target-root", failure("""
                homelight:
                  target-root:
                """.trimIndent() + "\n"))
        assertEquals("Line 4, column 7: Missing required key homelight.relocations[0].source-path", failure("""
                homelight:
                  target-root: /local
                  relocations:
                    - target-path: /local/cache
                """.trimIndent() + "\n"))
        assertEquals("Missing required key homelight", failure(""))
    }

    @Test fun reportsValuesOfTheWrongShape() {
        assertEquals("Line 2, column 16: Expected a string for homelight.target-root", failure("""
                homelight:
                  target-root: [/local]
                """.trimIndent() + "\n"))
        assertEquals("Line 3, column 16: Expected a list for homelight.relocations", failure("""
                homelight:
                  target-root: /local
                  relocations: /home/cache
                """.trimIndent() + "\n"))
        assertEquals("Line 4, column 7: Expected a mapping for homelight.relocations[0]", failure("""
                homelight:
                  target-root: /local
                  relocations:
                    - /home/cache
                """.trimIndent() + "\n"))
        assertEquals("Line 1, column 12: Expected a mapping for homelight", failure("""
                homelight: /local
                """.trimIndent() + "\n"))
    }

    @Test fun rejectsUnknownAndDuplicateKeysAtEveryLevel() {
        assertEquals("Line 1, column 1: Unknown key other", failure("""
                other: 1
                homelight:
                  target-root: /local
                """.trimIndent() + "\n"))
        assertEquals("Line 3, column 3: Unknown key homelight.target", failure("""
                homelight:
                  target-root: /local
                  target: /local
                """.trimIndent() + "\n"))
        assertEquals("Line 6, column 7: Unknown key homelight.relocations[0].existing", failure("""
                homelight:
                  target-root: /local
                  relocations:
                    - source-path: /home/cache
                      target-path: /local/cache
                      existing: move
                """.trimIndent() + "\n"))
        assertEquals("Line 4, column 5: Unknown key homelight.discovery.shared", failure("""
                homelight:
                  target-root: /local
                  discovery:
                    shared: /shared.yaml
                """.trimIndent() + "\n"))
        assertEquals("Line 3, column 3: Duplicate key homelight.target-root", failure("""
                homelight:
                  target-root: /local
                  target-root: /other
                """.trimIndent() + "\n"))
    }

    @Test fun acceptsOnlyTheKebabCasePolicyValues() {
        assertEquals("Line 6, column 34: Invalid value 'ADOPT_TARGET' for homelight.relocations[0].when-only-target-exists;"
                + " expected one of prompt, adopt-target", failure("""
                homelight:
                  target-root: /local
                  relocations:
                    - source-path: /home/cache
                      target-path: /local/cache
                      when-only-target-exists:   ADOPT_TARGET
                """.trimIndent() + "\n"))
        val relocation = load("""
                homelight:
                  target-root: /local
                  relocations:
                    - source-path: /home/cache
                      target-path: /local/cache
                      when-source-and-target-directories-exist: leave-unchanged
                      when-only-target-exists: adopt-target
                      when-adopting-target: discard-source
                """.trimIndent() + "\n").relocations.first()
        assertEquals(WhenSourceAndTargetDirectoriesExist.LEAVE_UNCHANGED, relocation.whenSourceAndTargetDirectoriesExist)
        assertEquals(WhenOnlyTargetExists.ADOPT_TARGET, relocation.whenOnlyTargetExists)
        assertEquals(WhenAdoptingTarget.DISCARD_SOURCE, relocation.whenAdoptingTarget)
    }

    @Test fun reportsMalformedYamlWithLocation() {
        assertEquals("Line 2, column 1: Malformed YAML: expected the node content, but found '<stream end>'",
                failure("homelight: [\n"))
    }

    @Test fun resolvesOptionalSettingsAndTreatsEmptyValuesAsAbsent() {
        val home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()
        val configuration = load("""
                homelight:
                  target-root: /local
                  staging-root: /local/staging/../.staging
                  ignored-source-paths:
                    - ~/ignored
                  discovery:
                    shared-list: /shared/candidates.yaml
                  relocations:
                    - source-path: ~/cache
                      target-path: ""
                      when-adopting-target:
                """.trimIndent() + "\n")
        val relocation = configuration.relocations.first()
        assertEquals(home.resolve("cache"), relocation.sourcePath)
        assertEquals(Path.of("/local").resolve(home.relativize(home.resolve("cache"))), relocation.targetPath)
        assertEquals(Path.of("/local/.staging"), relocation.stagingRoot)
        assertNull(relocation.whenAdoptingTarget)
        assertEquals(listOf(home.resolve("ignored")), configuration.ignoredSourcePaths)
        assertEquals(Path.of("/shared/candidates.yaml"), configuration.sharedList)
        assertEquals(listOf<Relocation>(), load("homelight:\n  target-root: /local\n  relocations:\n").relocations)
    }

    @Test fun pathOverrideReplacesOnlyTheFirstRelocationPaths() {
        val file = write("""
                homelight:
                  target-root: /local
                  relocations:
                    - source-path: /home/cache
                      target-path: /local/cache
                      when-only-target-exists: adopt-target
                    - source-path: /home/second
                      target-path: /local/second
                """.trimIndent() + "\n")
        val override = ConfigurationLoader.PathOverride(Path.of("/override/source"), Path.of("/override/target"))
        val relocations = ConfigurationLoader().load(file, override).relocations
        assertEquals(Path.of("/override/source"), relocations.first().sourcePath)
        assertEquals(Path.of("/override/target"), relocations.first().targetPath)
        assertEquals(WhenOnlyTargetExists.ADOPT_TARGET, relocations.first().whenOnlyTargetExists)
        assertEquals(Relocation(Path.of("/home/second"), Path.of("/local/second")), relocations.last())

        val empty = write("homelight:\n  target-root: /local\n")
        assertEquals(listOf(Relocation(Path.of("/override/source"), Path.of("/override/target"))),
                ConfigurationLoader().load(empty, override).relocations)
    }

    @Test fun loadsWhatThePublisherWrites() {
        val relocation = Relocation(temporary.resolve("home/it's"), temporary.resolve("local/it's"),
                WhenSourceAndTargetDirectoriesExist.ADOPT, WhenOnlyTargetExists.PROMPT,
                WhenAdoptingTarget.ARCHIVE_SOURCE, temporary.resolve("archive"))
        val draft = ConfigurationDraft.of(temporary.resolve("local"), listOf(relocation),
                temporary.resolve("shared.yaml"))
        val loaded = ConfigurationLoader().load(write(ConfigurationPublisher.yaml(draft)))
        assertEquals(HomeLightConfiguration.of(draft.targetRoot, draft.relocations, listOf(), draft.sharedList), loaded)
    }

    private fun load(yaml: String): HomeLightConfiguration {
        return ConfigurationLoader().load(write(yaml))
    }

    private fun failure(yaml: String): String? {
        val file = write(yaml)
        return assertThrows(ConfigurationLoader.ConfigurationException::class.java) { ConfigurationLoader().load(file) }
                .message
    }

    private fun write(yaml: String): Path {
        return Files.writeString(Files.createTempFile(temporary, "homelight", ".yaml"), yaml)
    }
}
