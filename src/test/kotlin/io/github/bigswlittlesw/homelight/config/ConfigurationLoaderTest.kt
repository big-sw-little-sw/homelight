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
        assertEquals("Line 2: relocations[0].when-adopting-target should be text, but it is an object.", failure("""
            {"homelight": {"target-root": "/local", "relocations": [
              {"source-path": "/home/cache", "target-path": "/local/cache", "when-adopting-target": {"policy": "archive-source"}}]}}
            """))
    }

    @Test fun rejectsStagingOutsideTargetRoot() {
        assertEquals("staging-root must be under target-root",
            failure("""{"homelight": {"target-root": "/local", "staging-root": "/elsewhere"}}"""))
    }

    @Test fun reportsMissingRequiredKeysByPath() {
        assertEquals("target-root is missing. Add it under \"homelight\".",
            failure("""{"homelight": {"relocations": []}}"""))
        assertEquals("source-path is missing. Add it under \"relocations[0]\".",
            failure("""{"homelight": {"target-root": "/local", "relocations": [{"target-path": "/local/cache"}]}}"""))
        assertEquals("homelight is missing. Add it at the top of the file.", failure("{}"))
    }

    @Test fun rejectsNullForARequiredValue() {
        assertEquals("Line 1: target-root should be text, but it is null.",
            failure("""{"homelight": {"target-root": null}}"""))
        assertEquals("Line 1: homelight should be an object in { }, but it is null.", failure("""{"homelight": null}"""))
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
        assertEquals("Line 2: target-root should be text, but it is a list.", failure("""
            {"homelight": {
              "target-root": ["/local"]}}
            """))
        assertEquals("Line 1: target-root should be text, but it is a number.",
            failure("""{"homelight": {"target-root": 5}}"""))
        assertEquals("Line 1: relocations should be a list in [ ], but it is text.",
            failure("""{"homelight": {"target-root": "/local", "relocations": "/home/cache"}}"""))
        assertEquals("Line 1: relocations[0] should be an object in { }, but it is text.",
            failure("""{"homelight": {"target-root": "/local", "relocations": ["/home/cache"]}}"""))
        assertEquals("Line 1: homelight should be an object in { }, but it is text.", failure("""{"homelight": "/local"}"""))
        assertEquals("Line 1: The file should be an object in { }, but it is a list.", failure("[]"))
    }

    @Test fun rejectsUnknownKeysAtEveryLevel() {
        assertEquals("Line 1: The file has an unknown setting \"other\". Check its spelling or remove it.",
            failure("""{"other": 1, "homelight": {"target-root": "/local"}}"""))
        assertEquals("Line 1: homelight has an unknown setting \"target\". Check its spelling or remove it.",
            failure("""{"homelight": {"target-root": "/local", "target": "/local"}}"""))
        assertEquals("Line 2: relocations[0] has an unknown setting \"existing\". Check its spelling or remove it.", failure("""
            {"homelight": {"target-root": "/local", "relocations": [
              {"source-path": "/home/cache", "target-path": "/local/cache", "existing": "move"}]}}
            """))
        // The suggestion list moved to the top level; the old `discovery` object is unknown.
        assertTrue(failure("""{"homelight": {"target-root": "/local", "discovery": {"suggestion-list": "/s.json"}}}""").orEmpty()
            .endsWith("homelight has an unknown setting \"discovery\". Check its spelling or remove it."))
        // The removed key is unknown too.
        assertEquals("Line 2: relocations[0] has an unknown setting \"source-archive-root\". Check its spelling or remove it.", failure("""
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

    @Test fun reportsTextThatIsNotJsonInPlainWordsWithPosition() {
        val cases = mapOf(
            "homelight" to """line 1, column 1 should start with "{" but starts with "h"""",
            "" to """line 1, column 1 should have "{" but the file ends there""",
            """{"homelight": {"target-root": "/local"}} {}""" to
                """line 1, column 43 should be the end of the file but has "{"""",
            "{\"homelight\": {\"target-root\": \"unclosed\nmore" to
                """line 1, column 40 should have a double quote (") but the line ends there""",
            """{homelight: {"target-root": "/local"}}""" to
                """line 1, column 2 should start with a double quote (") but starts with "h"""",
            """{"homelight": {"target-root": tru}}""" to
                """line 1, column 31 should start with a double quote (") but starts with "t"""",
            """{"homelight": {"target-root" "/local"}}""" to
                """line 1, column 30 should start with ":" but starts with a double quote (")""",
            """{"homelight": {"target-root": "/local"}""" to """line 1, column 40 should have "}" but the file ends there""",
            """
            {"homelight": {"target-root": "/local", "relocations": [
              {"source-path": "/a"} {"source-path": "/b"}]}}
            """ to """line 2, column 25 should start with a comma or "]"""",
            """{"homelight": {"target-root": "C:\local"}}""" to
                """line 1, column 32 has a backslash before "l", which JSON does not allow; write \\ for one backslash""",
            """{"homelight": {"target-root": "/local"}} /* end""" to
                """line 1, column 48 should close a comment with "*/" but the file ends there""",
        )
        for ((text, problem) in cases) {
            assertEquals("It isn't valid JSON: $problem.", failure(text), text)
        }
    }

    /** Valid JSON of the wrong kind is not a syntax error: it names the key, and the line without a column. */
    @Test fun reportsJsonOfTheWrongKindByKeyAndLine() {
        assertEquals("Line 1: homelight should be an object in { }, but it is a list.", failure("{\"homelight\": [\n"))
        assertEquals("Line 1: target-root should be text, but it is true.", failure("""{"homelight": {"target-root": true}}"""))
        // kotlinx gives no line for an unknown rule value.
        assertEquals("relocations[0].when-only-target-exists can't be \"sometimes\". Use one of: prompt, adopt-target.",
            failure("""
            {"homelight": {"target-root": "/local", "relocations": [{"source-path": "/a", "when-only-target-exists": "sometimes"}]}}
            """))
    }

    @Test fun anInvalidFileNamesItsPathAndTheLineAtFault() {
        val syntax = write("{\"homelight\": {\n  \"target-root\" \"/local\"}}")
        val atLine = assertThrows<InvalidConfigurationException> { ConfigurationLoader().load(syntax) }
        assertEquals(syntax, atLine.path)
        assertEquals(2, atLine.line)
        // A value check names a setting, not a line.
        val relative = write("""{"homelight": {"target-root": "local"}}""")
        val atSetting = assertThrows<InvalidConfigurationException> { ConfigurationLoader().load(relative) }
        assertEquals(relative, atSetting.path)
        assertEquals(0, atSetting.line)
        assertEquals("homelight.target-root: $FULL_PATH", atSetting.message)
    }

    @Test fun acceptsOnlyTheKebabCasePolicyValues() {
        assertEquals("relocations[0].when-only-target-exists can't be \"ADOPT_TARGET\". Use one of: prompt, adopt-target.",
            failure("""
            {"homelight": {"target-root": "/local", "relocations": [{
              "source-path": "/home/cache", "target-path": "/local/cache", "when-only-target-exists": "ADOPT_TARGET"}]}}
            """))
        assertEquals("relocations[0].when-source-and-target-directories-exist can't be \"move\"." +
            " Use one of: prompt, adopt, leave-unchanged, discard.", failure("""
            {"homelight": {"target-root": "/local", "relocations": [{
              "source-path": "/home/cache", "target-path": "/local/cache", "when-source-and-target-directories-exist": "move"}]}}
            """))
        assertEquals("relocations[0].when-adopting-target can't be \"archive\"." +
            " Use one of: prompt, discard-source, archive-source.", failure("""
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
              "suggestion-list": "/shared/candidates.json",
              "relocations": [{"source-path": "~/cache", "target-path": null, "archive-root": null}]
            }}
            """)
        val relocation = configuration.relocations.first()
        assertEquals(home.resolve("cache"), relocation.sourcePath)
        assertEquals(Path.of("/local").resolve(home.relativize(home.resolve("cache"))), relocation.targetPath)
        assertEquals(Path.of("/local/.staging"), relocation.stagingRoot)
        assertEquals(defaultArchiveRoot(relocation.sourcePath), relocation.archiveRoot)
        assertEquals(listOf(home.resolve("ignored")), configuration.ignoredSourcePaths)
        assertEquals(Path.of("/shared/candidates.json"), configuration.sharedList)
        // A blank shared list is the documented "none" of parseSharedList.
        assertNull(load("""{"homelight": {"target-root": "/local", "suggestion-list": ""}}""").sharedList)
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
              "suggestion-list": "~/shared.json",
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
        assertEquals(WhenSourceAndTargetDirectoriesExist.entries, relocations.map { it.whenSourceAndTargetDirectoriesExist })
        assertEquals(listOf(WhenOnlyTargetExists.PROMPT, WhenOnlyTargetExists.ADOPT_TARGET, WhenOnlyTargetExists.PROMPT,
            WhenOnlyTargetExists.PROMPT), relocations.map { it.whenOnlyTargetExists })
        assertEquals(listOf(WhenAdoptingTarget.PROMPT, WhenAdoptingTarget.DISCARD_SOURCE, WhenAdoptingTarget.ARCHIVE_SOURCE,
            WhenAdoptingTarget.PROMPT), relocations.map { it.whenAdoptingTarget })
        // The explicit prompts of `~/a` are dropped, with the same meaning.
        assertFalse(encoded.contains("\"prompt\""), encoded)
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

    @Test fun omittedAndExplicitPromptRulesLoadAlikeAndAreNotWritten() {
        val omitted = """
            {"homelight": {"target-root": "/local", "relocations": [{"source-path": "/home/cache", "target-path": "/local/cache"}]}}
            """
        val explicit = """
            {"homelight": {"target-root": "/local", "relocations": [{"source-path": "/home/cache", "target-path": "/local/cache",
              "when-source-and-target-directories-exist": "prompt", "when-only-target-exists": "prompt",
              "when-adopting-target": "prompt"}]}}
            """
        for (text in listOf(omitted, explicit)) {
            val relocation = load(text).relocations.single()
            assertEquals(Relocation(relocation.sourcePath, relocation.targetPath), relocation, text)
            val decoded = decodeJson(ConfigurationFile.serializer(), text)
            assertEquals(RelocationFile("/home/cache", "/local/cache"), decoded.homelight.relocations.single(), text)
            val written = encodeConfiguration(decoded)
            assertFalse(written.contains("when-"), written)
            assertEquals(relocation, load(written).relocations.single())
        }
    }

    @Test fun thePublisherLeavesOutPromptRulesAndWritesTheOthers() {
        val prompt = RelocationFile(temporary.resolve("home/cache").toString(), temporary.resolve("local/cache").toString())
        val decided = prompt.copy(
            whenSourceAndTargetDirectoriesExist = WhenSourceAndTargetDirectoriesExist.ADOPT,
            whenOnlyTargetExists = WhenOnlyTargetExists.ADOPT_TARGET,
            whenAdoptingTarget = WhenAdoptingTarget.DISCARD_SOURCE,
        )
        for ((name, relocation) in listOf("prompt" to prompt, "decided" to decided)) {
            val path = temporary.resolve("$name.json")
            val file = HomeLightFile(targetRoot = temporary.resolve("local").toString(), relocations = listOf(relocation))
            ConfigurationPublisher().saveNew(path, file)
            assertEquals(relocation == decided, Files.readString(path).contains("when-"), name)
            assertEquals(relocation, ConfigurationLoader().read(path).file.relocations.single(), name)
        }
    }

    /** A rule has a default instead of null, so `null` is a wrong value type, as for `source-root`. */
    @Test fun rejectsANullRule() {
        assertEquals("Line 1: relocations[0].when-adopting-target should be text, but it is null.", failure("""
            {"homelight": {"target-root": "/local", "relocations": [{"source-path": "/home/cache", "when-adopting-target": null}]}}
            """))
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

    @Test fun loadsWhatThePublisherWritesAndReadsTheBytesItLoaded() {
        val source = temporary.resolve("home/it's \"quoted\"").toString()
        for (policy in WhenAdoptingTarget.entries) {
            for (archiveRoot in listOf(null, temporary.resolve("archive").toString())) {
                val file = HomeLightFile(
                    targetRoot = temporary.resolve("local").toString(),
                    suggestionList = temporary.resolve("shared.json").toString(),
                    relocations = listOf(RelocationFile(source, temporary.resolve("local/it's").toString(),
                        WhenSourceAndTargetDirectoriesExist.ADOPT, WhenOnlyTargetExists.PROMPT, policy, archiveRoot)),
                )
                val path = temporary.resolve("written-$policy-${archiveRoot != null}.json")
                ConfigurationPublisher().saveNew(path, file)
                val read = ConfigurationLoader().read(path)
                assertEquals(file, read.file)
                assertTrue(read.bytes.contentEquals(Files.readAllBytes(path)))
                assertEquals(ConfigurationLoader().configuration(file), ConfigurationLoader().load(path))
            }
        }
    }

    /** A relative path would depend on where HomeLight runs, so every path in the file is full or starts with `~/`. */
    @Test fun refusesRelativePathsEverywhere() {
        val relocation = """"relocations": [{"source-path": "/home/cache", "target-path": "/local/cache"}]"""
        for ((key, text) in listOf(
            "homelight.source-root" to """{"homelight": {"source-root": "home", "target-root": "/local"}}""",
            "homelight.target-root" to """{"homelight": {"target-root": "local"}}""",
            "homelight.target-root" to """{"homelight": {"target-root": "${'$'}{USER}/local"}}""",
            "homelight.staging-root" to """{"homelight": {"target-root": "/local", "staging-root": "staging"}}""",
            "homelight.relocations[0].source-path" to """{"homelight": {"target-root": "/local", "relocations": [{"source-path": "cache"}]}}""",
            "homelight.relocations[0].target-path" to
                """{"homelight": {"target-root": "/local", "relocations": [{"source-path": "/home/cache", "target-path": "cache"}]}}""",
            "homelight.relocations[0].archive-root" to
                """{"homelight": {"target-root": "/local", "relocations": [{"source-path": "/home/cache", "target-path": "/local/cache", "archive-root": "a"}]}}""",
            "homelight.ignored-source-paths[0]" to """{"homelight": {"target-root": "/local", "ignored-source-paths": ["x"], $relocation}}""",
            "homelight.suggestion-list" to """{"homelight": {"target-root": "/local", "suggestion-list": "list.json"}}""",
        )) {
            assertEquals("$key: $FULL_PATH", failure(text), text)
        }
        assertEquals(Path.of(System.getProperty("user.home"), "local"), load("""{"homelight": {"target-root": "~/local"}}""").targetRoot)
    }

    @Test fun reportsRejectedPathValuesAgainstTheirKey() {
        assertEquals("homelight.suggestion-list: Use a full path, or one starting with ~/",
            failure("""{"homelight": {"target-root": "/local", "suggestion-list": "relative.json"}}"""))
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

    /**
     * `local` links to `real-local`, so each pair below is one place under two spellings (#128). Overlap visible as
     * written stays with the planner, so such a file still loads. The fixture is not resolved with `toRealPath()`.
     */
    @Test fun rejectsRelocationsThatOverlapThroughASymlink() {
        val real = Files.createDirectory(temporary.resolve("real-local"))
        val local = Files.createSymbolicLink(temporary.resolve("local"), real)
        val home = temporary.resolve("home")
        fun config(vararg pairs: Pair<Path, Path>) = """{"homelight": {"target-root": "$local", "relocations": [""" +
            pairs.joinToString { (source, target) -> """{"source-path": "$source", "target-path": "$target"}""" } + "]}}"

        val sameTarget = failure(config(home.resolve("a") to local.resolve("x"), home.resolve("b") to real.resolve("x"))).orEmpty()
        val sourceIsTarget = failure(config(local.resolve("cache") to real.resolve("cache"))).orEmpty()
        val nested = failure(config(home.resolve("a") to local.resolve("x"), real.resolve("x/inner") to home.resolve("b"))).orEmpty()

        assertTrue(sameTarget.matches(Regex("duplicate target path: .*/real-local/x \\(through a symlink\\)")), sameTarget)
        assertTrue(sourceIsTarget.matches(Regex("source and target paths overlap: .*/real-local/cache \\(through a symlink\\)")),
            sourceIsTarget)
        assertTrue(nested.startsWith("relocation paths overlap: ") && nested.endsWith("(through a symlink)"), nested)
        assertEquals(2, load(config(home.resolve("a") to local.resolve("x"), home.resolve("b") to local.resolve("x"))).relocations.size)
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
