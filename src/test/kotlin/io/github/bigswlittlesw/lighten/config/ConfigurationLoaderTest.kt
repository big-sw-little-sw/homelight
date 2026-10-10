package io.github.bigswlittlesw.lighten.config

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
            {"lighten": {
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
            {"lighten": {"target-root": "/local", "relocations": [
              {"source-path": "/home/cache", "target-path": "/local/cache", "when-adopting-target": "archive-source"},
              {"source-path": "/home/b", "target-path": "/local/b", "archive-root": "~/archive"}]}}
            """).relocations
        assertEquals(Path.of("/home/.lighten-archive"), relocations[0].archiveRoot)
        assertEquals(home.resolve("archive"), relocations[1].archiveRoot)
    }

    @Test fun rejectsAPolicyObject() {
        assertEquals("Line 2: relocations[0].when-adopting-target should be text, but it is an object.", failure("""
            {"lighten": {"target-root": "/local", "relocations": [
              {"source-path": "/home/cache", "target-path": "/local/cache", "when-adopting-target": {"policy": "archive-source"}}]}}
            """))
    }

    @Test fun rejectsStagingOutsideTargetRoot() {
        assertEquals("staging-root must be under target-root",
            failure("""{"lighten": {"target-root": "/local", "staging-root": "/elsewhere"}}"""))
    }

    @Test fun reportsMissingRequiredKeysByPath() {
        assertEquals("target-root is missing. Add it under \"lighten\".",
            failure("""{"lighten": {"relocations": []}}"""))
        assertEquals("source-path is missing. Add it under \"relocations[0]\".",
            failure("""{"lighten": {"target-root": "/local", "relocations": [{"target-path": "/local/cache"}]}}"""))
        assertEquals("lighten is missing. Add it at the top of the file.", failure("{}"))
    }

    @Test fun rejectsNullForARequiredValue() {
        assertEquals("Line 1: target-root should be text, but it is null.",
            failure("""{"lighten": {"target-root": null}}"""))
        assertEquals("Line 1: lighten should be an object in { }, but it is null.", failure("""{"lighten": null}"""))
    }

    @Test fun rejectsBlankPaths() {
        assertEquals("lighten.target-root must not be blank", failure("""{"lighten": {"target-root": "  "}}"""))
        assertEquals("lighten.relocations[0].target-path must not be blank", failure("""
            {"lighten": {"target-root": "/local", "relocations": [{"source-path": "~/cache", "target-path": ""}]}}
            """))
        assertEquals("lighten.relocations[0].archive-root must not be blank", failure("""
            {"lighten": {"target-root": "/local", "relocations": [{"source-path": "~/cache", "archive-root": ""}]}}
            """))
        assertEquals("lighten.ignored-source-paths[1] must not be blank",
            failure("""{"lighten": {"target-root": "/local", "ignored-source-paths": ["~/a", " "]}}"""))
    }

    /** Lighten plans nothing for an ignored path, so the same path can't also be a relocation, however it is spelled. */
    @Test fun refusesAPathThatIsBothARelocationAndIgnored() {
        assertEquals(
            "relocations[1].source-path and ignored-source-paths[0] are both ~/b. A path can't be both a relocation " +
                "and ignored: remove it from one of the two lists.",
            shownFailure("""
                {"lighten": {"target-root": "/local",
                  "relocations": [{"source-path": "~/a"}, {"source-path": "~/b"}],
                  "ignored-source-paths": ["~/b/", "~/c"]}}
                """),
        )
        // A directory inside a relocation, or around one, may be ignored.
        val file = write("""
            {"lighten": {"target-root": "/local", "relocations": [{"source-path": "~/a/b"}], "ignored-source-paths": ["~/a", "~/a/b/c"]}}
            """)
        assertEquals(2, ConfigurationLoader().load(file).ignoredSourcePaths.size)
    }

    @Test fun reportsValuesOfTheWrongTypeWithPositionAndPath() {
        assertEquals("Line 2: target-root should be text, but it is a list.", failure("""
            {"lighten": {
              "target-root": ["/local"]}}
            """))
        assertEquals("Line 1: target-root should be text, but it is a number.",
            failure("""{"lighten": {"target-root": 5}}"""))
        assertEquals("Line 1: relocations should be a list in [ ], but it is text.",
            failure("""{"lighten": {"target-root": "/local", "relocations": "/home/cache"}}"""))
        assertEquals("Line 1: relocations[0] should be an object in { }, but it is text.",
            failure("""{"lighten": {"target-root": "/local", "relocations": ["/home/cache"]}}"""))
        assertEquals("Line 1: lighten should be an object in { }, but it is text.", failure("""{"lighten": "/local"}"""))
        assertEquals("Line 1: The file should be an object in { }, but it is a list.", failure("[]"))
    }

    @Test fun rejectsUnknownKeysAtEveryLevel() {
        assertEquals("Line 1: The file has an unknown setting \"other\". Check its spelling or remove it.",
            failure("""{"other": 1, "lighten": {"target-root": "/local"}}"""))
        assertEquals("Line 1: lighten has an unknown setting \"target\". Check its spelling or remove it.",
            failure("""{"lighten": {"target-root": "/local", "target": "/local"}}"""))
        assertEquals("Line 2: relocations[0] has an unknown setting \"existing\". Check its spelling or remove it.", failure("""
            {"lighten": {"target-root": "/local", "relocations": [
              {"source-path": "/home/cache", "target-path": "/local/cache", "existing": "move"}]}}
            """))
        // The suggestion list moved to the top level; the old `discovery` object is unknown.
        assertTrue(failure("""{"lighten": {"target-root": "/local", "discovery": {"suggestion-list": "/s.json"}}}""").orEmpty()
            .endsWith("lighten has an unknown setting \"discovery\". Check its spelling or remove it."))
        // The removed key is unknown too.
        assertEquals("Line 2: relocations[0] has an unknown setting \"source-archive-root\". Check its spelling or remove it.", failure("""
            {"lighten": {"target-root": "/local", "relocations": [
              {"source-path": "/home/cache", "target-path": "/local/cache", "source-archive-root": "/archive"}]}}
            """))
    }

    @Test fun keepsTheLastValueOfARepeatedKey() {
        val configuration = load("""
            {"lighten": {
              "target-root": "/local",
              "target-root": "/other",
              "relocations": [{"source-path": "/home/a", "source-path": "/home/b", "target-path": "/other/b"}]}}
            """)
        assertEquals(Path.of("/other"), configuration.targetRoot)
        assertEquals(Path.of("/home/b"), configuration.relocations.single().sourcePath)
    }

    @Test fun acceptsCommentsAndTrailingCommas() {
        val configuration = load("""
            // Lighten configuration
            {
              "lighten": {
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
            "lighten" to """line 1, column 1 should start with "{" but starts with "l"""",
            "" to """line 1, column 1 should have "{" but the file ends there""",
            """{"lighten": {"target-root": "/local"}} {}""" to
                """line 1, column 41 should be the end of the file but has "{"""",
            "{\"lighten\": {\"target-root\": \"unclosed\nmore" to
                """line 1, column 38 should have a double quote (") but the line ends there""",
            """{lighten: {"target-root": "/local"}}""" to
                """line 1, column 2 should start with a double quote (") but starts with "l"""",
            """{"lighten": {"target-root": tru}}""" to
                """line 1, column 29 should start with a double quote (") but starts with "t"""",
            """{"lighten": {"target-root" "/local"}}""" to
                """line 1, column 28 should start with ":" but starts with a double quote (")""",
            """{"lighten": {"target-root": "/local"}""" to """line 1, column 38 should have "}" but the file ends there""",
            """
            {"lighten": {"target-root": "/local", "relocations": [
              {"source-path": "/a"} {"source-path": "/b"}]}}
            """ to """line 2, column 25 should start with a comma or "]"""",
            """{"lighten": {"target-root": "C:\local"}}""" to
                """line 1, column 30 has a backslash before "l", which JSON does not allow; write \\ for one backslash""",
            """{"lighten": {"target-root": "/local"}} /* end""" to
                """line 1, column 46 should close a comment with "*/" but the file ends there""",
        )
        for ((text, problem) in cases) {
            assertEquals("It isn't valid JSON: $problem.", failure(text), text)
        }
    }

    /** Valid JSON of the wrong kind is not a syntax error: it names the key, and the line without a column. */
    @Test fun reportsJsonOfTheWrongKindByKeyAndLine() {
        assertEquals("Line 1: lighten should be an object in { }, but it is a list.", failure("{\"lighten\": [\n"))
        assertEquals("Line 1: target-root should be text, but it is true.", failure("""{"lighten": {"target-root": true}}"""))
        // kotlinx gives no line for an unknown rule value.
        assertEquals("relocations[0].when-only-target-exists can't be \"sometimes\". Use one of: prompt, adopt-target.",
            failure("""
            {"lighten": {"target-root": "/local", "relocations": [{"source-path": "/a", "when-only-target-exists": "sometimes"}]}}
            """))
    }

    @Test fun anInvalidFileNamesItsPathAndTheLineAtFault() {
        val syntax = write("{\"lighten\": {\n  \"target-root\" \"/local\"}}")
        val atLine = assertThrows<InvalidConfigurationException> { ConfigurationLoader().load(syntax) }
        assertEquals(syntax, atLine.path)
        assertEquals(2, atLine.line)
        // A value check names a setting, not a line.
        val relative = write("""{"lighten": {"target-root": "local"}}""")
        val atSetting = assertThrows<InvalidConfigurationException> { ConfigurationLoader().load(relative) }
        assertEquals(relative, atSetting.path)
        assertEquals(0, atSetting.line)
        assertEquals("lighten.target-root: $FULL_PATH", atSetting.message)
    }

    @Test fun acceptsOnlyTheKebabCasePolicyValues() {
        assertEquals("relocations[0].when-only-target-exists can't be \"ADOPT_TARGET\". Use one of: prompt, adopt-target.",
            failure("""
            {"lighten": {"target-root": "/local", "relocations": [{
              "source-path": "/home/cache", "target-path": "/local/cache", "when-only-target-exists": "ADOPT_TARGET"}]}}
            """))
        assertEquals("relocations[0].when-source-and-target-directories-exist can't be \"move\"." +
            " Use one of: prompt, adopt, leave-unchanged, discard.", failure("""
            {"lighten": {"target-root": "/local", "relocations": [{
              "source-path": "/home/cache", "target-path": "/local/cache", "when-source-and-target-directories-exist": "move"}]}}
            """))
        assertEquals("relocations[0].when-adopting-target can't be \"archive\"." +
            " Use one of: prompt, discard-source, archive-source.", failure("""
            {"lighten": {"target-root": "/local", "relocations": [{
              "source-path": "/home/cache", "target-path": "/local/cache", "when-adopting-target": "archive"}]}}
            """))
        val relocation = load("""
            {"lighten": {"target-root": "/local", "relocations": [{
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
            "{}", """{"lighten": {}}""", """{"lighten": {"target-root": "/l", "relocations": [{}]}}""",
            """{"lighten": {"target-root": "/l", "relocations": [{"source-path": "/s", "when-only-target-exists": "x"}]}}""",
            """{"lighten": {"target-root": "/l", "relocations": [{"source-path": "/s", "when-adopting-target": "x"}]}}""",
            """{"lighten": {"target-root": "/l", "relocations": [{"source-path": "/s", "when-adopting-target": {}}]}}""",
            """{"lighten": {"target-root": "/l", "relocations": [{"source-path": "/s", "archive-root": 5}]}}""",
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
            {"lighten": {
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
        // A blank shared list means none.
        assertNull(load("""{"lighten": {"target-root": "/local", "suggestion-list": ""}}""").sharedList)
    }

    @Test fun expandsUserAndRequiresAnExplicitTargetOutsideTheDefaultSourceRoot() {
        val home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()
        assertEquals(Path.of("/local/${userName()}"), load("""{"lighten": {"target-root": "/local/${'$'}{USER}"}}""").targetRoot)
        assertEquals("A source outside source-root $home requires an explicit target-path: /outside/cache", failure("""
            {"lighten": {"target-root": "/local", "relocations": [{"source-path": "/outside/cache"}]}}
            """))
    }

    @Test fun derivesTargetsUnderACustomSourceRoot() {
        val home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()
        val relocations = load("""
            {"lighten": {"source-root": "/data", "target-root": "/local", "relocations": [
              {"source-path": "/data/cache/a"},
              {"source-path": "/elsewhere/b", "target-path": "/local/b"}]}}
            """).relocations
        assertEquals(Path.of("/local/cache/a"), relocations[0].targetPath)
        assertEquals(Path.of("/local/b"), relocations[1].targetPath)
        // `~` expands in the root, and a source under the home directory but outside the root has no derived target.
        assertEquals(Path.of("/local/x"), load("""
            {"lighten": {"source-root": "~/work", "target-root": "/local", "relocations": [{"source-path": "~/work/x"}]}}
            """).relocations.single().targetPath)
        assertEquals("A source outside source-root /data requires an explicit target-path: $home/cache", failure("""
            {"lighten": {"source-root": "/data", "target-root": "/local", "relocations": [{"source-path": "~/cache"}]}}
            """))
        assertEquals("lighten.source-root must not be blank",
            failure("""{"lighten": {"source-root": " ", "target-root": "/local"}}"""))
    }

    @Test fun pathOverrideReplacesOnlyTheFirstRelocationPaths() {
        val file = write("""
            {"lighten": {"target-root": "/local", "relocations": [
              {"source-path": "/home/cache", "target-path": "/local/cache", "when-only-target-exists": "adopt-target"},
              {"source-path": "/home/second", "target-path": "/local/second"}]}}
            """)
        val override = ConfigurationLoader.PathOverride(Path.of("/override/source"), Path.of("/override/target"))
        val relocations = ConfigurationLoader().load(file, override).relocations
        assertEquals(Path.of("/override/source"), relocations.first().sourcePath)
        assertEquals(Path.of("/override/target"), relocations.first().targetPath)
        assertEquals(WhenOnlyTargetExists.ADOPT_TARGET, relocations.first().whenOnlyTargetExists)
        assertEquals(Relocation(Path.of("/home/second"), Path.of("/local/second")), relocations.last())

        val empty = write("""{"lighten": {"target-root": "/local"}}""")
        assertEquals(listOf(Relocation(Path.of("/override/source"), Path.of("/override/target"))),
            ConfigurationLoader().load(empty, override).relocations)
    }

    @Test fun roundTripsEveryPolicyAndKeepsPathsAsWritten() {
        val text = """
            {"lighten": {
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
        val relocations = decoded.lighten.relocations
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
            """{"lighten": {"source-root": "~/work", "target-root": "/local"}}"""))
        assertTrue(set.contains("\"source-root\": \"~/work\""), set)
        assertEquals("~/work", decodeJson(ConfigurationFile.serializer(), set).lighten.sourceRoot)
        // Absent and an explicit `~` are the same setting, and neither is written.
        for (text in listOf("""{"lighten": {"target-root": "/local"}}""",
                """{"lighten": {"source-root": "~", "target-root": "/local"}}""")) {
            val decoded = decodeJson(ConfigurationFile.serializer(), text)
            assertEquals(DEFAULT_SOURCE_ROOT, decoded.lighten.sourceRoot)
            assertFalse(encodeConfiguration(decoded).contains("source-root"), text)
        }
    }

    @Test fun omittedAndExplicitPromptRulesLoadAlikeAndAreNotWritten() {
        val omitted = """
            {"lighten": {"target-root": "/local", "relocations": [{"source-path": "/home/cache", "target-path": "/local/cache"}]}}
            """
        val explicit = """
            {"lighten": {"target-root": "/local", "relocations": [{"source-path": "/home/cache", "target-path": "/local/cache",
              "when-source-and-target-directories-exist": "prompt", "when-only-target-exists": "prompt",
              "when-adopting-target": "prompt"}]}}
            """
        for (text in listOf(omitted, explicit)) {
            val relocation = load(text).relocations.single()
            assertEquals(Relocation(relocation.sourcePath, relocation.targetPath), relocation, text)
            val decoded = decodeJson(ConfigurationFile.serializer(), text)
            assertEquals(RelocationFile("/home/cache", "/local/cache"), decoded.lighten.relocations.single(), text)
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
            val file = LightenFile(targetRoot = temporary.resolve("local").toString(), relocations = listOf(relocation))
            ConfigurationPublisher().saveNew(path, file)
            assertEquals(relocation == decided, Files.readString(path).contains("when-"), name)
            assertEquals(relocation, ConfigurationLoader().read(path).file.relocations.single(), name)
        }
    }

    /** A rule has a default instead of null, so `null` is a wrong value type, as for `source-root`. */
    @Test fun rejectsANullRule() {
        assertEquals("Line 1: relocations[0].when-adopting-target should be text, but it is null.", failure("""
            {"lighten": {"target-root": "/local", "relocations": [{"source-path": "/home/cache", "when-adopting-target": null}]}}
            """))
    }

    @Test fun writesOnlyTheSettingsThatAreSet() {
        val file = ConfigurationFile(LightenFile(targetRoot = "/local", relocations = listOf(
            RelocationFile("/home/cache", whenAdoptingTarget = WhenAdoptingTarget.ARCHIVE_SOURCE),
        )))
        assertEquals("""
            {
                "lighten": {
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
                val file = LightenFile(
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

    /** A relative path would depend on where Lighten runs, so every path in the file is full or starts with `~/`. */
    @Test fun refusesRelativePathsEverywhere() {
        val relocation = """"relocations": [{"source-path": "/home/cache", "target-path": "/local/cache"}]"""
        for ((key, text) in listOf(
            "lighten.source-root" to """{"lighten": {"source-root": "home", "target-root": "/local"}}""",
            "lighten.target-root" to """{"lighten": {"target-root": "local"}}""",
            "lighten.target-root" to """{"lighten": {"target-root": "${'$'}{USER}/local"}}""",
            "lighten.staging-root" to """{"lighten": {"target-root": "/local", "staging-root": "staging"}}""",
            "lighten.relocations[0].source-path" to """{"lighten": {"target-root": "/local", "relocations": [{"source-path": "cache"}]}}""",
            "lighten.relocations[0].target-path" to
                """{"lighten": {"target-root": "/local", "relocations": [{"source-path": "/home/cache", "target-path": "cache"}]}}""",
            "lighten.relocations[0].archive-root" to
                """{"lighten": {"target-root": "/local", "relocations": [{"source-path": "/home/cache", "target-path": "/local/cache", "archive-root": "a"}]}}""",
            "lighten.ignored-source-paths[0]" to """{"lighten": {"target-root": "/local", "ignored-source-paths": ["x"], $relocation}}""",
            "lighten.suggestion-list" to """{"lighten": {"target-root": "/local", "suggestion-list": "list.json"}}""",
        )) {
            assertEquals("$key: $FULL_PATH", failure(text), text)
        }
        assertEquals(Path.of(System.getProperty("user.home"), "local"), load("""{"lighten": {"target-root": "~/local"}}""").targetRoot)
    }

    @Test fun reportsRejectedPathValuesAgainstTheirKey() {
        assertEquals("lighten.suggestion-list: Use a full path, or one starting with ~/",
            failure("""{"lighten": {"target-root": "/local", "suggestion-list": "relative.json"}}"""))
        assertEquals("lighten.source-root: Nul character not allowed",
            failure("""{"lighten": {"source-root": "/a\u0000b", "target-root": "/local"}}"""))
        assertEquals("lighten.relocations[0].source-path: Nul character not allowed", failure("""
            {"lighten": {"target-root": "/local", "relocations": [{"source-path": "/a\u0000b", "target-path": "/local/b"}]}}
            """))
    }

    @Test fun reportsAMissingFile() {
        val missing = temporary.resolve("absent.json")
        assertEquals("Configuration file does not exist: $missing",
            assertThrows<ConfigurationException> { ConfigurationLoader().load(missing) }.message)
    }

    /**
     * `local` links to `real-local`, so each pair below names one place in two ways. The planner blocks only the
     * relocations that overlap, as written or through a link, and plans the rest. So such a file loads.
     */
    @Test fun loadsRelocationsThatOverlap() {
        val real = Files.createDirectory(temporary.resolve("real-local"))
        val local = Files.createSymbolicLink(temporary.resolve("local"), real)
        val home = temporary.resolve("home")
        fun config(vararg pairs: Pair<Path, Path>) = """{"lighten": {"target-root": "$local", "relocations": [""" +
            pairs.joinToString { (source, target) -> """{"source-path": "$source", "target-path": "$target"}""" } + "]}}"

        for (pairs in listOf(
            listOf(home.resolve("a") to local.resolve("x"), home.resolve("b") to real.resolve("x")),
            listOf(local.resolve("cache") to real.resolve("cache")),
            listOf(home.resolve("a") to local.resolve("x"), real.resolve("x/inner") to home.resolve("b")),
            listOf(home.resolve("a") to local.resolve("x"), home.resolve("b") to local.resolve("x")),
        )) {
            assertEquals(pairs.size, load(config(*pairs.toTypedArray())).relocations.size)
        }
    }

    /**
     * `${USER}` is the `USER` variable, else the OS account name, which also works where `USER` is unset. It is never
     * replaced with empty text: with no name, the setting is refused.
     */
    @Test fun userNameFallsBackToTheAccountNameAndIsNeverEmpty() {
        assertEquals("env", userName("env", "account"))
        assertEquals("account", userName(null, "account"))
        assertEquals("account", userName("", "account"))
        assertNull(userName(null, null))
        assertNull(userName("", ""))
        assertEquals("/local/me", withUser("/local/\${USER}", "lighten.target-root", "me"))
        assertEquals("/local", withUser("/local", "lighten.target-root", null))
        assertEquals(
            "lighten.target-root uses \${USER}, but Lighten can't find your user name: the USER environment variable " +
                "is not set and the system gives none. Write the name instead.",
            assertThrows<ConfigurationException> { withUser("/local/\${USER}", "lighten.target-root", null) }.text.toString(),
        )
    }

    /**
     * Without a home directory, `~` is refused with words that tell the user to set `HOME`; a full path still loads.
     * Tests run without `HOME`, so `user.home` decides here.
     */
    @Test fun withoutAHomeDirectoryTildeIsAUserError() {
        val real = System.getProperty("user.home")
        System.setProperty("user.home", "?")
        try {
            assertEquals(NO_HOME, assertThrows<ConfigurationException> { ConfigurationLoader.DEFAULT_PATH }.text.toString())
            assertEquals(NO_HOME, shownFailure("""{"lighten": {"target-root": "~/local"}}"""))
            assertEquals(Path.of("/local"), load("""{"lighten": {"target-root": "/local", "source-root": "/home/me"}}""").targetRoot)
        } finally {
            System.setProperty("user.home", real)
        }
    }

    private fun load(json: String): LightenConfiguration = ConfigurationLoader().load(write(json))

    /** The failure as the screen and the CLI show it, with `~` for home. */
    private fun shownFailure(json: String): String {
        val file = write(json)
        return assertThrows<ConfigurationException> { ConfigurationLoader().load(file) }.text.shown()
    }

    private fun failure(json: String): String? {
        val file = write(json)
        return assertThrows<ConfigurationException> { ConfigurationLoader().load(file) }
            .message
    }

    /** Raw-string literals are trimmed so that reported lines and columns count from the first character. */
    private fun write(json: String): Path =
        Files.writeString(Files.createTempFile(temporary, "lighten", ".json"), json.trimIndent())
}
