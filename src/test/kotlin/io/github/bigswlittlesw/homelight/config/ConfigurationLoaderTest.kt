package io.github.bigswlittlesw.homelight.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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
                "when-adopting-target": "archive-source",
                "archive-root": "/archive"
              }]
            }}
            """).relocations.first()
        assertEquals(WhenSourceAndTargetDirectoriesExist.ADOPT, relocation.whenSourceAndTargetDirectoriesExist)
        assertEquals(WhenAdoptingTarget.ARCHIVE_SOURCE, relocation.whenAdoptingTarget)
        assertEquals(Path.of("/archive"), relocation.archiveRoot)
    }

    @Test fun archiveRootDefaultsBesideTheSource() {
        val home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()
        val relocations = load("""
            {"homelight": {"target-root": "/local", "relocations": [
              {"source-path": "/home/cache", "target-path": "/local/cache", "when-adopting-target": "archive-source"},
              {"source-path": "/home/b", "target-path": "/local/b", "archive-root": "~/archive"}]}}
            """).relocations
        assertEquals(Path.of("/home/.homelight-archive"), relocations[0].archiveRoot)
        assertEquals(home.resolve("archive"), relocations[1].archiveRoot)
    }

    @Test fun rejectsAPolicyObject() {
        assertEquals("Line 2, column 89: Expected beginning of the string, but got {"
            + " at homelight.relocations[0].when-adopting-target", failure("""
            {"homelight": {"target-root": "/local", "relocations": [
              {"source-path": "/home/cache", "target-path": "/local/cache", "when-adopting-target": {"policy": "archive-source"}}]}}
            """))
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
        assertEquals("homelight.relocations[0].archive-root must not be blank", failure("""
            {"homelight": {"target-root": "/local", "relocations": [{"source-path": "~/cache", "archive-root": ""}]}}
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
        assertEquals("when-adopting-target does not contain element with name 'archive'"
            + " at homelight.relocations[0].when-adopting-target", failure("""
            {"homelight": {"target-root": "/local", "relocations": [{
              "source-path": "/home/cache", "target-path": "/local/cache", "when-adopting-target": "archive"}]}}
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

    @Test fun errorMessagesNameNoKotlinTypes() {
        val inputs = listOf(
            "{}", """{"homelight": {}}""", """{"homelight": {"target-root": "/l", "relocations": [{}]}}""",
            """{"homelight": {"target-root": "/l", "relocations": [{"source-path": "/s", "when-only-target-exists": "x"}]}}""",
            """{"homelight": {"target-root": "/l", "relocations": [{"source-path": "/s", "when-adopting-target": "x"}]}}""",
            """{"homelight": {"target-root": "/l", "relocations": [{"source-path": "/s", "when-adopting-target": {}}]}}""",
            """{"homelight": {"target-root": "/l", "relocations": [{"source-path": "/s", "archive-root": 5}]}}""",
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

    @Test fun expandsUserAndRequiresAnExplicitTargetOutsideTheDefaultSourceRoot() {
        val home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()
        val user = System.getenv().getOrDefault("USER", "")
        assertEquals(Path.of("/local/$user"), load("""{"homelight": {"target-root": "/local/${'$'}{USER}"}}""").targetRoot)
        assertEquals("A source outside source-root $home requires an explicit target-path: /outside/cache", failure("""
            {"homelight": {"target-root": "/local", "relocations": [{"source-path": "/outside/cache"}]}}
            """))
    }

    @Test fun derivesTargetsUnderACustomSourceRoot() {
        val home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()
        val relocations = load("""
            {"homelight": {"source-root": "/data", "target-root": "/local", "relocations": [
              {"source-path": "/data/cache/a"},
              {"source-path": "/elsewhere/b", "target-path": "/local/b"}]}}
            """).relocations
        assertEquals(Path.of("/local/cache/a"), relocations[0].targetPath)
        assertEquals(Path.of("/local/b"), relocations[1].targetPath)
        // `~` expands in the root, and a source under the home directory but outside the root has no derived target.
        assertEquals(Path.of("/local/x"), load("""
            {"homelight": {"source-root": "~/work", "target-root": "/local", "relocations": [{"source-path": "~/work/x"}]}}
            """).relocations.single().targetPath)
        assertEquals("A source outside source-root /data requires an explicit target-path: $home/cache", failure("""
            {"homelight": {"source-root": "/data", "target-root": "/local", "relocations": [{"source-path": "~/cache"}]}}
            """))
        assertEquals("homelight.source-root must not be blank",
            failure("""{"homelight": {"source-root": " ", "target-root": "/local"}}"""))
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
                 "when-adopting-target": "prompt"},
                {"source-path": "~/b", "when-source-and-target-directories-exist": "adopt", "when-only-target-exists": "adopt-target",
                 "when-adopting-target": "discard-source"},
                {"source-path": "~/c", "target-path": "/t/c", "when-source-and-target-directories-exist": "leave-unchanged",
                 "when-adopting-target": "archive-source", "archive-root": "~/archive"},
                {"source-path": "~/d", "when-source-and-target-directories-exist": "discard"}
              ]}}
            """.trimIndent()
        val decoded = decodeJson(ConfigurationFile.serializer(), text)
        val encoded = encodeConfiguration(decoded)
        assertEquals(decoded, decodeJson(ConfigurationFile.serializer(), encoded))
        val relocations = decoded.homelight.relocations
        assertEquals(WhenSourceAndTargetDirectoriesExist.entries, relocations.mapNotNull { it.whenSourceAndTargetDirectoriesExist })
        assertEquals(WhenOnlyTargetExists.entries, relocations.mapNotNull { it.whenOnlyTargetExists })
        assertEquals(WhenAdoptingTarget.entries, relocations.mapNotNull { it.whenAdoptingTarget })
        assertEquals(listOf(null, null, "~/archive", null), relocations.map { it.archiveRoot })
        // Paths are written back in the user's form.
        assertTrue(encoded.contains("\"target-root\": \"~/local/\${USER}\""), encoded)
        assertTrue(encoded.contains("\"archive-root\": \"~/archive\""), encoded)
    }

    @Test fun roundTripsTheSourceRootAndOmitsTheDefault() {
        val set = encodeConfiguration(decodeJson(ConfigurationFile.serializer(),
            """{"homelight": {"source-root": "~/work", "target-root": "/local"}}"""))
        assertTrue(set.contains("\"source-root\": \"~/work\""), set)
        assertEquals("~/work", decodeJson(ConfigurationFile.serializer(), set).homelight.sourceRoot)
        // Absent and an explicit `~` are the same setting, and neither is written.
        for (text in listOf("""{"homelight": {"target-root": "/local"}}""",
                """{"homelight": {"source-root": "~", "target-root": "/local"}}""")) {
            val decoded = decodeJson(ConfigurationFile.serializer(), text)
            assertEquals(DEFAULT_SOURCE_ROOT, decoded.homelight.sourceRoot)
            assertFalse(encodeConfiguration(decoded).contains("source-root"), text)
        }
    }

    @Test fun thePublisherWritesASourceRootOnlyWhenItIsNotTheHomeDirectory() {
        val relocation = Relocation(temporary.resolve("work/cache"), temporary.resolve("local/cache"))
        val home = Path.of(System.getProperty("user.home"))
        for (sourceRoot in listOf(home, temporary.resolve("work"))) {
            val draft = ConfigurationDraft.of(temporary.resolve("local"), listOf(relocation), sourceRoot = sourceRoot)
            val written = configurationFile(draft).homelight.sourceRoot
            assertEquals(if (sourceRoot == home) DEFAULT_SOURCE_ROOT else sourceRoot.toString(), written)
            val reloaded = decodeJson(ConfigurationFile.serializer(), encodeConfiguration(configurationFile(draft)))
            assertEquals(written, reloaded.homelight.sourceRoot)
        }
    }

    @Test fun writesOnlyTheSettingsThatAreSet() {
        val file = ConfigurationFile(HomeLightFile(targetRoot = "/local", relocations = listOf(
            RelocationFile("/home/cache", whenAdoptingTarget = WhenAdoptingTarget.ARCHIVE_SOURCE),
        )))
        assertEquals("""
            {
                "homelight": {
                    "target-root": "/local",
                    "relocations": [
                        {
                            "source-path": "/home/cache",
                            "when-adopting-target": "archive-source"
                        }
                    ]
                }
            }

            """.trimIndent(), encodeConfiguration(file))
    }

    @Test fun loadsWhatThePublisherWritesAndOmitsTheDefaultArchiveRoot() {
        val source = temporary.resolve("home/it's \"quoted\"")
        for (policy in WhenAdoptingTarget.entries + null) {
            for (archiveRoot in listOf(defaultArchiveRoot(source), temporary.resolve("archive"))) {
                val relocation = Relocation(source, temporary.resolve("local/it's"),
                    WhenSourceAndTargetDirectoriesExist.ADOPT, WhenOnlyTargetExists.PROMPT, policy, archiveRoot)
                val draft = ConfigurationDraft.of(temporary.resolve("local"), listOf(relocation), temporary.resolve("shared.json"))
                val file = configurationFile(draft)
                val written = file.homelight.relocations.single().archiveRoot
                assertEquals(archiveRoot.takeIf { it != defaultArchiveRoot(source) }?.toString(), written)
                val loaded = ConfigurationLoader().load(write(encodeConfiguration(file)))
                assertEquals(HomeLightConfiguration.of(draft.targetRoot, draft.relocations, listOf(), draft.sharedList), loaded)
            }
        }
    }

    @Test fun reportsRejectedPathValuesAgainstTheirKey() {
        assertEquals("homelight.discovery.shared-list: Shared list must be an absolute filesystem path",
            failure("""{"homelight": {"target-root": "/local", "discovery": {"shared-list": "relative.json"}}}"""))
        assertEquals("homelight.source-root: Nul character not allowed",
            failure("""{"homelight": {"source-root": "/a\u0000b", "target-root": "/local"}}"""))
        assertEquals("homelight.relocations[0].source-path: Nul character not allowed", failure("""
            {"homelight": {"target-root": "/local", "relocations": [{"source-path": "/a\u0000b", "target-path": "/local/b"}]}}
            """))
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
