package io.github.bigswlittlesw.homelight.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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
                "when-adopting-target": {"policy": "archive-source", "archive-root": "/archive"}
              }]
            }}
            """).relocations.first()
        assertEquals(WhenSourceAndTargetDirectoriesExist.ADOPT, relocation.whenSourceAndTargetDirectoriesExist)
        assertEquals(WhenAdoptingTarget.ArchiveSource(Path.of("/archive")), relocation.whenAdoptingTarget)
    }

    @Test fun loadsEveryAdoptingPolicy() {
        val home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()
        val policies = mapOf(
            """{"policy": "prompt"}""" to WhenAdoptingTarget.Prompt(),
            """{"policy": "prompt", "archive-root": "~/archive"}""" to WhenAdoptingTarget.Prompt(home.resolve("archive")),
            """{"policy": "discard-source"}""" to WhenAdoptingTarget.DiscardSource,
            // The discriminator need not come first.
            """{"archive-root": "/archive", "policy": "archive-source"}""" to WhenAdoptingTarget.ArchiveSource(Path.of("/archive")),
        )
        for ((json, expected) in policies) {
            assertEquals(expected, load("""
                {"homelight": {"target-root": "/local", "relocations": [
                  {"source-path": "/home/cache", "target-path": "/local/cache", "when-adopting-target": $json}]}}
                """).relocations.first().whenAdoptingTarget, json)
        }
    }

    @Test fun rejectsArchiveWithoutRoot() {
        assertEquals("Field 'archive-root' is required for type with serial name 'archive-source', but it was missing"
            + " at homelight.relocations[0].when-adopting-target", failure("""
            {"homelight": {"target-root": "/local", "relocations": [
              {"source-path": "/home/cache", "target-path": "/local/cache", "when-adopting-target": {"policy": "archive-source"}}
            ]}}
            """))
    }

    @Test fun rejectsAMissingUnknownOrMistypedPolicy() {
        fun policy(json: String) = failure("""
            {"homelight": {"target-root": "/local", "relocations": [
              {"source-path": "/home/cache", "target-path": "/local/cache", "when-adopting-target": $json}]}}
            """)
        assertEquals("Line 2, column 89: Serializer for subclass 'archive' is not found"
            + " at homelight.relocations[0].when-adopting-target", policy("""{"policy": "archive"}"""))
        // kotlinx decodes a policy whose discriminator is missing or not a string from a tree, without position or path.
        assertEquals("Class discriminator was missing.", policy("""{"archive-root": "/archive"}"""))
        assertEquals("Serializer for subclass '5' is not found.", policy("""{"policy": 5}"""))
        assertEquals("Expected object, but had literal as the serialized body of when-adopting-target"
            + " at homelight.relocations[0].when-adopting-target", policy("\"discard-source\""))
        assertEquals("Line 2, column 119: Encountered an unknown key 'archive-root'"
            + " at homelight.relocations[0].when-adopting-target", policy("""{"policy": "discard-source", "archive-root": "/a"}"""))
    }

    @Test fun rejectsStagingOutsideTargetRoot() {
        assertEquals("staging-root must be under target-root",
            failure("""{"homelight": {"target-root": "/local", "staging-root": "/elsewhere"}}"""))
    }

    @Test fun reportsMissingRequiredKeysByPath() {
        assertEquals("Field 'target-root' is required for type with serial name 'homelight', but it was missing at homelight",
            failure("""{"homelight": {"relocations": []}}"""))
        assertEquals("Field 'source-path' is required for type with serial name 'relocation', but it was missing"
            + " at homelight.relocations[0]",
            failure("""{"homelight": {"target-root": "/local", "relocations": [{"target-path": "/local/cache"}]}}"""))
        assertEquals("Field 'homelight' is required for type with serial name 'configuration', but it was missing",
            failure("{}"))
    }

    @Test fun rejectsNullForARequiredValue() {
        assertEquals("Line 1, column 31: Expected string literal but 'null' literal was found at homelight.target-root",
            failure("""{"homelight": {"target-root": null}}"""))
        assertEquals("Line 1, column 15: Expected start of the object '{', but had 'n' instead at homelight",
            failure("""{"homelight": null}"""))
    }

    @Test fun rejectsBlankPaths() {
        assertEquals("homelight.target-root must not be blank", failure("""{"homelight": {"target-root": "  "}}"""))
        assertEquals("homelight.relocations[0].target-path must not be blank", failure("""
            {"homelight": {"target-root": "/local", "relocations": [{"source-path": "~/cache", "target-path": ""}]}}
            """))
        assertEquals("homelight.relocations[0].when-adopting-target.archive-root must not be blank", failure("""
            {"homelight": {"target-root": "/local", "relocations": [{"source-path": "~/cache",
              "when-adopting-target": {"policy": "archive-source", "archive-root": ""}}]}}
            """))
        assertEquals("homelight.ignored-source-paths[1] must not be blank",
            failure("""{"homelight": {"target-root": "/local", "ignored-source-paths": ["~/a", " "]}}"""))
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
        // The removed key is unknown too.
        assertEquals("Line 2, column 66: Encountered an unknown key 'source-archive-root' at homelight.relocations[0]", failure("""
            {"homelight": {"target-root": "/local", "relocations": [
              {"source-path": "/home/cache", "target-path": "/local/cache", "source-archive-root": "/archive"}]}}
            """))
    }

    @Test fun keepsTheLastValueOfARepeatedKey() {
        val configuration = load("""
            {"homelight": {
              "target-root": "/local",
              "target-root": "/other",
              "relocations": [{"source-path": "/home/a", "source-path": "/home/b", "target-path": "/other/b"}]}}
            """)
        assertEquals(Path.of("/other"), configuration.targetRoot)
        assertEquals(Path.of("/home/b"), configuration.relocations.single().sourcePath)
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
        assertEquals("when-only-target-exists does not contain element with name 'ADOPT_TARGET'"
            + " at homelight.relocations[0].when-only-target-exists", failure("""
            {"homelight": {"target-root": "/local", "relocations": [{
              "source-path": "/home/cache", "target-path": "/local/cache", "when-only-target-exists": "ADOPT_TARGET"}]}}
            """))
        assertEquals("when-source-and-target-directories-exist does not contain element with name 'move'"
            + " at homelight.relocations[0].when-source-and-target-directories-exist", failure("""
            {"homelight": {"target-root": "/local", "relocations": [{
              "source-path": "/home/cache", "target-path": "/local/cache", "when-source-and-target-directories-exist": "move"}]}}
            """))
        val relocation = load("""
            {"homelight": {"target-root": "/local", "relocations": [{
              "source-path": "/home/cache",
              "target-path": "/local/cache",
              "when-source-and-target-directories-exist": "leave-unchanged",
              "when-only-target-exists": "adopt-target",
              "when-adopting-target": {"policy": "discard-source"}}]}}
            """).relocations.first()
        assertEquals(WhenSourceAndTargetDirectoriesExist.LEAVE_UNCHANGED, relocation.whenSourceAndTargetDirectoriesExist)
        assertEquals(WhenOnlyTargetExists.ADOPT_TARGET, relocation.whenOnlyTargetExists)
        assertEquals(WhenAdoptingTarget.DiscardSource, relocation.whenAdoptingTarget)
    }

    @Test fun errorMessagesNameNoKotlinTypes() {
        val inputs = listOf(
            "{}", """{"homelight": {}}""", """{"homelight": {"target-root": "/l", "relocations": [{}]}}""",
            """{"homelight": {"target-root": "/l", "relocations": [{"source-path": "/s", "when-only-target-exists": "x"}]}}""",
            """{"homelight": {"target-root": "/l", "relocations": [{"source-path": "/s", "when-adopting-target": {}}]}}""",
            """{"homelight": {"target-root": "/l", "relocations": [{"source-path": "/s", "when-adopting-target": {"policy": "x"}}]}}""",
            """{"homelight": {"target-root": "/l", "relocations": [{"source-path": "/s", "when-adopting-target": []}]}}""",
            """{"homelight": {"target-root": "/l", "relocations": [{"source-path": "/s",
              "when-adopting-target": {"policy": "archive-source"}}]}}""",
        )
        for (input in inputs) {
            val message = failure(input).orEmpty()
            for (name in listOf("File", "Adopting", "Json", "kotlin", "io.github", "When", "\n", "Check if", "Use '")) {
                assertFalse(message.contains(name), "$input: $message")
            }
        }
    }

    @Test fun treatsNullOptionalValuesAsAbsent() {
        val home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()
        val configuration = load("""
            {"homelight": {
              "target-root": "/local",
              "staging-root": "/local/staging/../.staging",
              "ignored-source-paths": ["~/ignored"],
              "discovery": {"shared-list": "/shared/candidates.json"},
              "relocations": [{"source-path": "~/cache", "target-path": null, "when-adopting-target": null,
                               "when-only-target-exists": null}]
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
        // A blank shared list is the documented "none" of parseSharedList.
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

    @Test fun roundTripsEveryPolicyAndKeepsPathsAsWritten() {
        val text = """
            {"homelight": {
              "target-root": "~/local/${'$'}{USER}",
              "staging-root": "~/local/.staging",
              "discovery": {"shared-list": "~/shared.json"},
              "ignored-source-paths": ["~/ignored"],
              "relocations": [
                {"source-path": "~/a", "when-source-and-target-directories-exist": "prompt", "when-only-target-exists": "prompt",
                 "when-adopting-target": {"policy": "prompt"}},
                {"source-path": "~/b", "when-source-and-target-directories-exist": "adopt", "when-only-target-exists": "adopt-target",
                 "when-adopting-target": {"policy": "prompt", "archive-root": "~/archive"}},
                {"source-path": "~/c", "target-path": "/t/c", "when-source-and-target-directories-exist": "leave-unchanged",
                 "when-adopting-target": {"policy": "discard-source"}},
                {"source-path": "~/d", "when-source-and-target-directories-exist": "discard",
                 "when-adopting-target": {"policy": "archive-source", "archive-root": "~/archive"}},
                {"source-path": "~/e"}
              ]}}
            """.trimIndent()
        val decoded = decodeJson(ConfigurationFile.serializer(), text)
        val encoded = encodeConfiguration(decoded)
        assertEquals(decoded, decodeJson(ConfigurationFile.serializer(), encoded))
        // Every enum value and policy case is present, and written back in the user's form.
        val relocations = decoded.homelight.relocations
        assertEquals(WhenSourceAndTargetDirectoriesExist.entries, relocations.mapNotNull { it.whenSourceAndTargetDirectoriesExist })
        assertEquals(WhenOnlyTargetExists.entries, relocations.mapNotNull { it.whenOnlyTargetExists })
        assertEquals(
            listOf(AdoptingFile.Prompt(), AdoptingFile.Prompt("~/archive"), AdoptingFile.DiscardSource, AdoptingFile.ArchiveSource("~/archive")),
            relocations.mapNotNull { it.whenAdoptingTarget },
        )
        assertEquals(true, encoded.contains("\"target-root\": \"~/local/\${USER}\""), encoded)
    }

    @Test fun writesOnlyTheSettingsThatAreSet() {
        val file = ConfigurationFile(HomeLightFile("/local", relocations = listOf(
            RelocationFile("/home/cache", whenAdoptingTarget = AdoptingFile.ArchiveSource("/archive")),
        )))
        assertEquals("""
            {
                "homelight": {
                    "target-root": "/local",
                    "relocations": [
                        {
                            "source-path": "/home/cache",
                            "when-adopting-target": {
                                "policy": "archive-source",
                                "archive-root": "/archive"
                            }
                        }
                    ]
                }
            }

            """.trimIndent(), encodeConfiguration(file))
    }

    @Test fun loadsWhatThePublisherWrites() {
        for (policy in listOf(WhenAdoptingTarget.Prompt(), WhenAdoptingTarget.Prompt(temporary.resolve("archive")),
            WhenAdoptingTarget.DiscardSource, WhenAdoptingTarget.ArchiveSource(temporary.resolve("archive")), null)) {
            val relocation = Relocation(temporary.resolve("home/it's \"quoted\""), temporary.resolve("local/it's"),
                WhenSourceAndTargetDirectoriesExist.ADOPT, WhenOnlyTargetExists.PROMPT, policy)
            val draft = ConfigurationDraft.of(temporary.resolve("local"), listOf(relocation), temporary.resolve("shared.json"))
            val loaded = ConfigurationLoader().load(write(encodeConfiguration(configurationFile(draft))))
            assertEquals(HomeLightConfiguration.of(draft.targetRoot, draft.relocations, listOf(), draft.sharedList), loaded)
        }
    }

    @Test fun reportsAMissingFile() {
        val missing = temporary.resolve("absent.json")
        assertEquals("Configuration file does not exist: $missing",
            assertThrows<ConfigurationException> { ConfigurationLoader().load(missing) }.message)
    }

    private fun load(json: String): HomeLightConfiguration = ConfigurationLoader().load(write(json))

    private fun failure(json: String): String? {
        val file = write(json)
        return assertThrows<ConfigurationException> { ConfigurationLoader().load(file) }
            .message
    }

    /** Raw-string literals are trimmed so that reported lines and columns count from the first character. */
    private fun write(json: String): Path =
        Files.writeString(Files.createTempFile(temporary, "homelight", ".json"), json.trimIndent())
}
