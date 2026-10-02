package io.github.bigswlittlesw.homelight.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ConfigurationLoaderTest {
    @TempDir lateinit var temporary: Path

    @Test fun loadsStateSpecificDecisions() {
        val relocation = load("""
            {"homelight": {
              "target-root": "/local",
              "relocations": [{
                "source-path": "/home/cache",
                "target-path": "/local/cache",
                "when-source-and-target-directories-exist": "adopt",
                "when-adopting-target": "archive-source",
                "source-archive-root": "/archive"
              }]
            }}
            """).relocations.first()
        assertEquals(WhenSourceAndTargetDirectoriesExist.ADOPT, relocation.whenSourceAndTargetDirectoriesExist)
        assertEquals(WhenAdoptingTarget.ARCHIVE_SOURCE, relocation.whenAdoptingTarget)
        assertEquals(Path.of("/archive"), relocation.sourceArchiveRoot)
    }

    @Test fun rejectsArchiveWithoutRoot() {
        assertEquals("source-archive-root is required when when-adopting-target is archive-source", failure("""
            {"homelight": {"target-root": "/local", "relocations": [
              {"source-path": "/home/cache", "target-path": "/local/cache", "when-adopting-target": "archive-source"}
            ]}}
            """))
    }

    @Test fun rejectsStagingOutsideTargetRoot() {
        assertEquals("staging-root must be under target-root",
            failure("""{"homelight": {"target-root": "/local", "staging-root": "/elsewhere"}}"""))
    }

    @Test fun reportsMissingRequiredKeysByPath() {
        assertEquals("Missing required key homelight.target-root",
            failure("""{"homelight": {"relocations": []}}"""))
        assertEquals("Missing required key homelight.target-root",
            failure("""{"homelight": {"target-root": "  "}}"""))
        assertEquals("Missing required key homelight.target-root",
            failure("""{"homelight": {"target-root": null}}"""))
        assertEquals("Missing required key homelight.relocations[0].source-path",
            failure("""{"homelight": {"target-root": "/local", "relocations": [{"target-path": "/local/cache"}]}}"""))
        assertEquals("Missing required key homelight", failure("{}"))
        assertEquals("Missing required key homelight", failure("""{"homelight": null}"""))
    }

    @Test fun reportsValuesOfTheWrongTypeWithPositionAndPath() {
        assertEquals("Line 2, column 18: Expected beginning of the string, but got [ at homelight.target-root", failure("""
            {"homelight": {
              "target-root": ["/local"]}}
            """))
        assertEquals("Line 1, column 31: Expected quotation mark '\"', but had '5' instead at homelight.target-root",
            failure("""{"homelight": {"target-root": 5}}"""))
        assertEquals("Line 1, column 56: Expected start of the array '[', but had '\"' instead at homelight.relocations",
            failure("""{"homelight": {"target-root": "/local", "relocations": "/home/cache"}}"""))
        assertEquals("Line 1, column 57: Expected start of the object '{', but had '\"' instead at homelight.relocations[0]",
            failure("""{"homelight": {"target-root": "/local", "relocations": ["/home/cache"]}}"""))
        assertEquals("Line 1, column 15: Expected start of the object '{', but had '\"' instead at homelight",
            failure("""{"homelight": "/local"}"""))
    }

    @Test fun rejectsUnknownKeysAtEveryLevel() {
        assertEquals("Line 1, column 3: Encountered an unknown key 'other'",
            failure("""{"other": 1, "homelight": {"target-root": "/local"}}"""))
        assertEquals("Line 1, column 42: Encountered an unknown key 'target' at homelight",
            failure("""{"homelight": {"target-root": "/local", "target": "/local"}}"""))
        assertEquals("Line 2, column 66: Encountered an unknown key 'existing' at homelight.relocations[0]", failure("""
            {"homelight": {"target-root": "/local", "relocations": [
              {"source-path": "/home/cache", "target-path": "/local/cache", "existing": "move"}]}}
            """))
        assertEquals("Line 1, column 56: Encountered an unknown key 'shared' at homelight.discovery",
            failure("""{"homelight": {"target-root": "/local", "discovery": {"shared": "/shared.json"}}}"""))
    }

    @Test fun rejectsDuplicateKeysAtEveryLevel() {
        assertEquals("Line 3, column 4: Duplicate key 'target-root'", failure("""
            {"homelight": {
              "target-root": "/local",
              "target-root": "/other"}}
            """))
        assertEquals("Line 1, column 43: Duplicate key 'homelight'",
            failure("""{"homelight": {"target-root": "/local"}, "homelight": {"target-root": "/other"}}"""))
        assertEquals("Line 2, column 31: Duplicate key 'source-path'", failure("""
            {"homelight": {"target-root": "/local", "relocations": [
              {"source-path": "/home/a", "source-path": "/home/b", "target-path": "/local/a"}]}}
            """))
        // The same key in sibling objects, in a string value or in a comment is not a duplicate.
        load("""
            {"homelight": {"target-root": "/local", "relocations": [
              {"source-path": "/home/a", "target-path": "/local/a"}, // "source-path": "x"
              {"source-path": "/home/\"source-path\"", "target-path": "/local/b"}]}}
            """)
    }

    @Test fun acceptsCommentsAndTrailingCommas() {
        val configuration = load("""
            // HomeLight configuration
            {
              "homelight": {
                /* Where relocated directories live. */
                "target-root": "/local",
                "relocations": [
                  {"source-path": "/home/cache", "target-path": "/local/cache",},
                ],
              },
            }
            """)
        assertEquals(listOf(Relocation(Path.of("/home/cache"), Path.of("/local/cache"))), configuration.relocations)
    }

    @Test fun reportsMalformedJsonWithPosition() {
        assertEquals("Line 1, column 15: Expected start of the object '{', but had 'EOF' instead at homelight",
            failure("{\"homelight\": [\n"))
        assertEquals("Line 1, column 43: Expected EOF after parsing, but had { instead",
            failure("""{"homelight": {"target-root": "/local"}} {}"""))
        // A control character in the message is escaped.
        assertEquals("Line 1, column 40: Expected quotation mark '\"', but had '\\u000a' instead at homelight.target-root",
            failure("{\"homelight\": {\"target-root\": \"unclosed\nmore"))
        assertEquals("Line 1, column 2: Expected quotation mark '\"', but had 'h' instead",
            failure("""{homelight: {"target-root": "/local"}}"""))
        assertEquals("Line 1, column 1: Expected start of the object '{', but had 'EOF' instead", failure(""))
    }

    @Test fun acceptsOnlyTheKebabCasePolicyValues() {
        assertEquals("Invalid value 'ADOPT_TARGET' for homelight.relocations[0].when-only-target-exists;"
            + " expected one of prompt, adopt-target", failure("""
            {"homelight": {"target-root": "/local", "relocations": [{
              "source-path": "/home/cache", "target-path": "/local/cache", "when-only-target-exists": "ADOPT_TARGET"}]}}
            """))
        val relocation = load("""
            {"homelight": {"target-root": "/local", "relocations": [{
              "source-path": "/home/cache",
              "target-path": "/local/cache",
              "when-source-and-target-directories-exist": "leave-unchanged",
              "when-only-target-exists": "adopt-target",
              "when-adopting-target": "discard-source"}]}}
            """).relocations.first()
        assertEquals(WhenSourceAndTargetDirectoriesExist.LEAVE_UNCHANGED, relocation.whenSourceAndTargetDirectoriesExist)
        assertEquals(WhenOnlyTargetExists.ADOPT_TARGET, relocation.whenOnlyTargetExists)
        assertEquals(WhenAdoptingTarget.DISCARD_SOURCE, relocation.whenAdoptingTarget)
    }

    @Test fun resolvesOptionalSettingsAndTreatsNullEmptyOrBlankValuesAsAbsent() {
        val home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()
        val configuration = load("""
            {"homelight": {
              "target-root": "/local",
              "staging-root": "/local/staging/../.staging",
              "ignored-source-paths": ["~/ignored", null, " "],
              "discovery": {"shared-list": "/shared/candidates.json"},
              "relocations": [{"source-path": "~/cache", "target-path": "", "when-adopting-target": null,
                               "when-only-target-exists": " "}]
            }}
            """)
        val relocation = configuration.relocations.first()
        assertEquals(home.resolve("cache"), relocation.sourcePath)
        assertEquals(Path.of("/local").resolve(home.relativize(home.resolve("cache"))), relocation.targetPath)
        assertEquals(Path.of("/local/.staging"), relocation.stagingRoot)
        assertNull(relocation.whenAdoptingTarget)
        assertNull(relocation.whenOnlyTargetExists)
        assertEquals(listOf(home.resolve("ignored")), configuration.ignoredSourcePaths)
        assertEquals(Path.of("/shared/candidates.json"), configuration.sharedList)
        assertEquals(listOf<Relocation>(), load("""{"homelight": {"target-root": "/local", "relocations": null}}""").relocations)
        assertNull(load("""{"homelight": {"target-root": "/local", "discovery": {"shared-list": ""}}}""").sharedList)
    }

    @Test fun expandsUserAndRequiresAnExplicitTargetOutsideHome() {
        val user = System.getenv().getOrDefault("USER", "")
        assertEquals(Path.of("/local/$user"), load("""{"homelight": {"target-root": "/local/${'$'}{USER}"}}""").targetRoot)
        assertEquals("A source outside \$HOME requires an explicit target-path: /outside/cache", failure("""
            {"homelight": {"target-root": "/local", "relocations": [{"source-path": "/outside/cache"}]}}
            """))
    }

    @Test fun pathOverrideReplacesOnlyTheFirstRelocationPaths() {
        val file = write("""
            {"homelight": {"target-root": "/local", "relocations": [
              {"source-path": "/home/cache", "target-path": "/local/cache", "when-only-target-exists": "adopt-target"},
              {"source-path": "/home/second", "target-path": "/local/second"}]}}
            """)
        val override = ConfigurationLoader.PathOverride(Path.of("/override/source"), Path.of("/override/target"))
        val relocations = ConfigurationLoader().load(file, override).relocations
        assertEquals(Path.of("/override/source"), relocations.first().sourcePath)
        assertEquals(Path.of("/override/target"), relocations.first().targetPath)
        assertEquals(WhenOnlyTargetExists.ADOPT_TARGET, relocations.first().whenOnlyTargetExists)
        assertEquals(Relocation(Path.of("/home/second"), Path.of("/local/second")), relocations.last())

        val empty = write("""{"homelight": {"target-root": "/local"}}""")
        assertEquals(listOf(Relocation(Path.of("/override/source"), Path.of("/override/target"))),
            ConfigurationLoader().load(empty, override).relocations)
    }

    @Test fun loadsWhatThePublisherWrites() {
        val relocation = Relocation(temporary.resolve("home/it's \"quoted\""), temporary.resolve("local/it's"),
            WhenSourceAndTargetDirectoriesExist.ADOPT, WhenOnlyTargetExists.PROMPT,
            WhenAdoptingTarget.ARCHIVE_SOURCE, temporary.resolve("archive"))
        val draft = ConfigurationDraft.of(temporary.resolve("local"), listOf(relocation), temporary.resolve("shared.json"))
        val loaded = ConfigurationLoader().load(write(ConfigurationPublisher.json(draft)))
        assertEquals(HomeLightConfiguration.of(draft.targetRoot, draft.relocations, listOf(), draft.sharedList), loaded)
    }

    @Test fun reportsAMissingFile() {
        val missing = temporary.resolve("absent.json")
        assertEquals("Configuration file does not exist: $missing",
            assertThrows(ConfigurationLoader.ConfigurationException::class.java) { ConfigurationLoader().load(missing) }.message)
    }

    private fun load(json: String): HomeLightConfiguration = ConfigurationLoader().load(write(json))

    private fun failure(json: String): String? {
        val file = write(json)
        return assertThrows(ConfigurationLoader.ConfigurationException::class.java) { ConfigurationLoader().load(file) }
            .message
    }

    /** Raw-string literals are trimmed so that reported lines and columns count from the first character. */
    private fun write(json: String): Path =
        Files.writeString(Files.createTempFile(temporary, "homelight", ".json"), json.trimIndent())
}
