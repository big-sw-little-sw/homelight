package io.github.bigswlittlesw.homelight.config

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class CandidateCatalogTest {
    private val parser = CandidateParser()

    @TempDir lateinit var temporary: Path

    @Test fun sameSchemaAndLexicalResolutionUnderBothRoots() {
        for (root in listOf(HOME, OTHER)) {
            for (source in listOf(CandidateCatalog.BUNDLED, SHARED)) {
                val snapshot = parser.parse(source, root, Files.readAllBytes(FIXTURES.resolve("nested/bundled.json")))
                assertTrue(snapshot.accepted(), snapshot.diagnostics.toString())
                assertEquals(6, snapshot.definitions.size)
                assertEquals(root.resolve(".m2"), snapshot.definitions.first().sourcePath)
                assertEquals("Maven", snapshot.definitions.first().app)
                assertEquals(CandidateDefinition.Advice.CONSIDER, snapshot.definitions.first().advice)
                assertEquals(CandidateDefinition.Advice.USUALLY_UNNECESSARY, snapshot.definitions.get(4).advice)
                assertNull(snapshot.definitions.get(1).advice)
                assertNull(snapshot.definitions.get(1).reason)
                assertNull(snapshot.definitions.get(5).app)
                for (definition in snapshot.definitions) {
                    assertEquals(source, definition.source)
                    assertEquals(root.resolve(definition.originalPath).normalize(), definition.sourcePath)
                }
            }
        }
        assertEquals(HOME, parse(EMPTY, Path.of("/home/./alex")).root)
        assertThrows(IllegalArgumentException::class.java) { parse(EMPTY, Path.of("relative")) }
    }

    @Test fun rejectsEveryUnsafePathAtomicallyWithLocation() {
        val paths = listOf("", "  ", ".", "./", "a/..", "/tmp/cache", "../cache", "a/../cache",
                "a/../../alex-other/cache", "~/cache", "~alex/cache", "\${HOME}/cache", "\$HOME/cache",
                "a/\$NAME/cache", "C:/cache", "C:cache", "c:\\cache", "\\\\server\\share", "//server/share",
                "a\u0000b", "a\nb", "a\tb", "a\u007fb", "a\u0085b", "https://example.com/cache",
                "file:/tmp/cache", "cache/*", "cache/?")
        for (path in paths) {
            val snapshot = parse("""{"directories": [{"path": "safe"}, {"path": ${quoted(path)}}]}""")
            assertFalse(snapshot.accepted(), path)
            assertTrue(snapshot.definitions.isEmpty(), path)
            val diagnostic = snapshot.diagnostics.first()
            assertEquals(SHARED, diagnostic.source)
            assertEquals(2, diagnostic.recordIndex, path)
            assertEquals("path", diagnostic.key, path)
            assertEquals("directories[1]", diagnostic.location, path)
        }
    }

    @Test fun acceptsLiteralSpacesAndNormalizesOnlyLexically() {
        val snapshot = parse("""
                {"directories": [
                  {"path": ".cache//uv"},
                  {"path": ".cache/./uv"},
                  {"path": "cache with spaces"},
                  {"path": ".local/share/uv"},
                  {"path": ".local/share/uv/tools"}
                ]}
                """)
        assertTrue(snapshot.accepted())
        assertEquals(snapshot.definitions.get(0).sourcePath, snapshot.definitions.get(1).sourcePath)
        val merged = CandidateCatalog.merge(listOf(snapshot))
        assertEquals(4, merged.candidates.size)
        assertEquals(2, merged.candidates.first().definitions.size)
        assertEquals(HOME.resolve("cache with spaces"), merged.candidates.get(1).sourcePath)
        assertTrue(merged.candidates.get(3).sourcePath.startsWith(merged.candidates.get(2).sourcePath))
    }

    @Test fun rejectsStrictSchemaViolationsForEitherSourceKind() {
        val inputs = listOf("", "// empty", "[]", "{}", """{"directories": null}""", """{"directories": {}}""",
                """{"directories": [], "unknown": true}""", """{"directories": [], "directories": []}""",
                """{"directories": []} {"directories": []}""", """{"directories": [null]}""",
                """{"directories": [{"app": "Maven"}]}""", """{"directories": [{"path": null}]}""",
                """{"directories": [{"path": "cache", "app": null}]}""",
                """{"apps": [{"name": " Maven", "directories": []}]}""", """{"apps": [{"name": "Maven ", "directories": []}]}""",
                """{"directories": [{"path": "cache", "advice": "Consider"}]}""",
                """{"directories": [{"path": "cache", "advice": "safe"}]}""",
                """{"directories": [{"path": "cache", "selected": true}]}""",
                """{"directories": [{"path": "cache", "policy": "move"}]}""",
                """{"directories": [{"path": "cache", "path": "other"}]}""",
                """{"directories": [{"path": 12}]}""", """{"directories": [{"path": true}]}""",
                """{"directories": [{"path": ["cache"]}]}""", """{"directories": [{"path": "cache", "reason": {"text": "x"}}]}""",
                """{'directories': []}""", """{directories: []}""")
        for (source in listOf(CandidateCatalog.BUNDLED, SHARED)) {
            for (input in inputs) {
                val snapshot = parser.parse(source, HOME, input.toByteArray(StandardCharsets.UTF_8))
                assertFalse(snapshot.accepted(), input)
                assertTrue(snapshot.definitions.isEmpty(), input)
                assertEquals(source, snapshot.diagnostics.first().source)
            }
        }
        assertTrue(parse(EMPTY).accepted())
    }

    @Test fun reportsDecodingFailuresWithPositionAndPath() {
        assertEquals(CandidateDiagnostic(SHARED, CandidateDiagnostic.Kind.SYNTAX, 0, 3, 24, "apps[0].directories[0]", "",
                "Encountered an unknown key 'app' at apps[0].directories[0]"), parse("""
                {"apps": [
                  {"name": "App", "directories": [
                    {"path": "cache", "app": "Legacy"}]}]}
                """).diagnostics.single())
        assertEquals(CandidateDiagnostic(SHARED, CandidateDiagnostic.Kind.SYNTAX, 0, 2, 12, "directories[0].path", "",
                "Expected quotation mark '\"', but had '1' instead at directories[0].path"), parse("""
                {"directories": [
                  {"path": 12}]}
                """).diagnostics.single())
        assertEquals(CandidateDiagnostic(SHARED, CandidateDiagnostic.Kind.SYNTAX, 0, 2, 18, "", "",
                "Duplicate key 'path'"), parse("""
                {"directories": [
                  {"path": "a", "path": "b"}]}
                """).diagnostics.single())
        assertEquals(CandidateDiagnostic(SHARED, CandidateDiagnostic.Kind.SYNTAX, 0, 1, 1, "", "",
                "Expected start of the object '{', but had 'EOF' instead"), parse("").diagnostics.single())
    }

    @Test fun acceptsCommentsAndTrailingCommas() {
        val snapshot = parse("""
                // Team candidates
                {
                  "directories": [
                    /* Maven */ {"path": ".m2", "reason": "Maven // not a comment",},
                    {"path": "datasets"},
                  ],
                }
                """)
        assertTrue(snapshot.accepted(), snapshot.diagnostics.toString())
        assertEquals(listOf(".m2", "datasets"), snapshot.definitions.map { it.originalPath })
        assertEquals("Maven // not a comment", snapshot.definitions.first().reason)
    }

    @Test fun treatsNullOrBlankOptionalValuesAsAbsent() {
        val snapshot = parse("""
                {"apps": [{"name": "12", "directories": [
                  {"path": "2026-09-23", "reason": "5", "advice": null},
                  {"path": "true", "reason": "  ", "advice": ""}]}]}
                """)
        assertTrue(snapshot.accepted(), snapshot.diagnostics.toString())
        assertEquals(listOf("2026-09-23", "true"), snapshot.definitions.map(CandidateDefinition::originalPath))
        assertEquals(listOf("12", "12"), snapshot.definitions.map(CandidateDefinition::app))
        assertEquals(listOf("5", null), snapshot.definitions.map(CandidateDefinition::reason))
        assertNull(snapshot.definitions.first().advice)
        assertNull(snapshot.definitions.last().advice)
        assertEquals(CandidateDiagnostic(SHARED, CandidateDiagnostic.Kind.SCHEMA, 1, 0, 0,
                "directories[0]", "path", "Missing required key directories[0].path"),
                parse("""{"directories": [{"path": "  "}]}""").diagnostics.single())
        assertEquals(CandidateDiagnostic(SHARED, CandidateDiagnostic.Kind.SCHEMA, 1, 0, 0, "directories[0]", "advice",
                "Invalid value 'safe' for directories[0].advice; expected one of consider, usually-unnecessary"),
                parse("""{"directories": [{"path": "cache", "advice": "safe"}]}""").diagnostics.single())
    }

    @Test fun enforcesByteRecordAndStringLimitsAtTheirBoundaries() {
        val prefix = "$EMPTY\n//"
        val atByteLimit = prefix + "x".repeat(CandidateParser.MAX_BYTES - prefix.length)
        assertTrue(parse(atByteLimit).accepted())
        assertKind(CandidateDiagnostic.Kind.LIMIT, parse(atByteLimit + "x"))
        val multibyte = prefix + "é".repeat(CandidateParser.MAX_BYTES / 2)
        assertKind(CandidateDiagnostic.Kind.LIMIT, parse(multibyte))

        assertEquals(CandidateParser.MAX_RECORDS, parse(directories(CandidateParser.MAX_RECORDS)).definitions.size)
        assertKind(CandidateDiagnostic.Kind.LIMIT, parse(directories(CandidateParser.MAX_RECORDS + 1)))

        val reason = "😀".repeat(CandidateParser.MAX_STRING_CHARACTERS)
        assertEquals(reason, parse("""{"directories": [{"path": "cache", "reason": ${quoted(reason)}}]}""")
                .definitions.first().reason)
        val long = parse("""{"directories": [{"path": "cache", "reason": ${quoted(reason + "x")}}]}""")
        assertKind(CandidateDiagnostic.Kind.LIMIT, long)
        assertEquals("directories[0]", long.diagnostics.first().location)
        assertEquals("reason", long.diagnostics.first().key)
        assertKind(CandidateDiagnostic.Kind.LIMIT,
                parse("""{"apps": [{"name": ${quoted("n".repeat(4097))}, "directories": []}]}"""))
    }

    @Test fun boundsNestingThroughTheFixedShape() {
        // A record is the deepest object; anything nested deeper is a wrong type, rejected before it is read.
        val deep = parse("""{"directories": """ + "[".repeat(100_000) + "]".repeat(100_000) + "}")
        assertKind(CandidateDiagnostic.Kind.SYNTAX, deep)
        assertEquals("directories[0]", deep.diagnostics.first().location)
        assertKind(CandidateDiagnostic.Kind.SYNTAX,
                parse("""{"directories": [{"path": "cache", "reason": [[["x"]]]}]}"""))
    }

    @Test fun acceptsEmptyAndOptionalSequences() {
        for (input in listOf("""{"apps": []}""", EMPTY, """{"apps": [], "directories": []}""",
                """{"apps": [{"name": "Empty", "directories": []}]}""")) {
            val snapshot = parse(input)
            assertTrue(snapshot.accepted(), snapshot.diagnostics.toString())
            assertTrue(snapshot.definitions.isEmpty())
        }
        val appsOnly = parse("""{"apps": [{"name": "App", "directories": [{"path": "cache"}]}]}""")
        assertTrue(appsOnly.accepted())
        assertEquals("App", appsOnly.definitions.first().app)
        assertNull(appsOnly.definitions.first().advice)
        assertNull(appsOnly.definitions.first().reason)
    }

    @Test fun preservesRepeatedGroupsAndCrossGroupOccurrencesInAppFirstOrder() {
        val snapshot = parse("""
                {"directories": [{"path": ".cache/./uv", "reason": "Ungrouped"}],
                 "apps": [
                  {"name": "uv", "directories": []},
                  {"name": "uv", "directories": [{"path": ".cache/uv", "advice": "consider", "reason": "First"}]},
                  {"name": "uv", "directories": [{"path": ".cache//uv", "advice": "usually-unnecessary", "reason": "Second"}]},
                  {"name": "UV", "directories": [{"path": ".cache/uv"}]}]}
                """)
        assertTrue(snapshot.accepted(), snapshot.diagnostics.toString())
        val candidates = CandidateCatalog.merge(listOf(snapshot)).candidates
        assertEquals(1, candidates.size)
        val definitions = candidates.first().definitions
        assertEquals(snapshot.definitions, definitions)
        assertEquals(listOf(1, 2, 3, 4), definitions.map(CandidateDefinition::recordIndex))
        assertEquals(listOf("apps[1].directories[0]", "apps[2].directories[0]",
                "apps[3].directories[0]", "directories[0]"), definitions.map(CandidateDefinition::location))
        assertEquals(listOf("uv", "uv", "UV", null), definitions.map(CandidateDefinition::app))
        assertEquals(listOf("First", "Second", null, "Ungrouped"), definitions.map(CandidateDefinition::reason))
        assertEquals(listOf(CandidateDefinition.Advice.CONSIDER,
                CandidateDefinition.Advice.USUALLY_UNNECESSARY, null, null), definitions.map(CandidateDefinition::advice))
    }

    @Test fun rejectsInvalidGroupsAndNestedRecordsWithStructuralLocations() {
        val groups = listOf("null", "[]", "{}", """{"name": "App"}""", """{"directories": []}""",
                """{"name": null, "directories": []}""",
                """{"name": "", "directories": []}""", """{"name": " App", "directories": []}""",
                """{"name": "App ", "directories": []}""", """{"name": "App", "directories": null}""",
                """{"name": "App", "directories": {}}""", """{"name": "App", "directories": [], "advice": "consider"}""",
                """{"name": "App", "directories": [], "reason": "Description"}""",
                """{"name": "App", "directories": [], "selected": true}""")
        for (group in groups) {
            val snapshot = parse("""{"apps": [{"name": "Valid", "directories": [{"path": "safe"}]}, $group]}""")
            assertFalse(snapshot.accepted(), group)
            val diagnostic = snapshot.diagnostics.first()
            assertTrue(diagnostic.location.startsWith("apps[1]"), group)
            assertEquals(diagnostic.kind == CandidateDiagnostic.Kind.SYNTAX, diagnostic.line > 0, group)
        }
        for (group in listOf("""{"name": "App", "name": "Other", "directories": []}""",
                """{"name": "App", "directories": [], "directories": []}""")) {
            assertKind(CandidateDiagnostic.Kind.SYNTAX, parse("""{"apps": [$group]}"""))
        }
        assertKind(CandidateDiagnostic.Kind.SCHEMA, parse("""{"apps": null}"""))
        assertKind(CandidateDiagnostic.Kind.SYNTAX, parse("""{"apps": {}}"""))
        assertKind(CandidateDiagnostic.Kind.SYNTAX, parse("""{"apps": [], "apps": []}"""))
        // Rejected by the reader: located by path and position.
        for (directory in listOf("""{"path": "cache", "app": "Legacy"}""", """{"path": "cache", "unknown": "value"}""")) {
            val diagnostic = parse("""{"apps": [{"name": "App", "directories": [{"path": "safe"}, $directory]}]}""")
                    .diagnostics.single()
            assertEquals("apps[0].directories[1]", diagnostic.location)
            assertTrue(diagnostic.line > 0 && diagnostic.column > 0)
        }
        // Rejected after decoding: located by record index, path and key.
        for (directory in listOf("""{"path": "../escape"}""", """{"reason": "Missing"}""")) {
            val diagnostic = parse("""{"apps": [{"name": "App", "directories": [{"path": "safe"}, $directory]}]}""")
                    .diagnostics.single()
            assertEquals("apps[0].directories[1]", diagnostic.location)
            assertEquals(2, diagnostic.recordIndex)
            assertEquals("path", diagnostic.key)
        }
    }

    @Test fun boundsGroupsAndAggregateRecords() {
        val app = """{"name": "App", "directories": []}"""
        val emptyGroups = """{"apps": [""" + List(CandidateParser.MAX_APPS) { app }.joinToString(",")
        assertTrue(parse("$emptyGroups]}").accepted())
        assertKind(CandidateDiagnostic.Kind.LIMIT, parse("$emptyGroups, $app]}"))
        val records = List(5_000) { """{"path": "cache"}""" }.joinToString(",")
        val group = """{"name": "App", "directories": [$records]}"""
        val atLimit = """{"apps": [$group, $group]"""
        assertEquals(CandidateParser.MAX_RECORDS, parse("$atLimit}").definitions.size)
        val mixed = parse("""$atLimit, "directories": [{"path": "extra"}]}""")
        assertKind(CandidateDiagnostic.Kind.LIMIT, mixed)
        assertEquals("directories", mixed.diagnostics.first().location)
        val grouped = parse("""{"apps": [$group, $group, {"name": "Extra", "directories": [{"path": "extra"}]}]}""")
        assertKind(CandidateDiagnostic.Kind.LIMIT, grouped)
        assertEquals("apps[2].directories", grouped.diagnostics.first().location)
    }

    @Test fun rejectsMalformedUtf8AndSyntax() {
        assertKind(CandidateDiagnostic.Kind.ENCODING, parser.parse(SHARED, HOME, byteArrayOf(0xc3.toByte(), 0x28)))
        val malformed = parser.parse(SHARED, HOME, Files.readAllBytes(FIXTURES.resolve("malformed.json")))
        assertKind(CandidateDiagnostic.Kind.SYNTAX, malformed)
        assertTrue(malformed.diagnostics.first().line > 0)
    }

    @Test fun retainsAllAttributionAndLiteralReasonsWithoutPrecedence() {
        val bundled = fixture(CandidateCatalog.BUNDLED, "bundled.json")
        val shared = fixture(SHARED, "shared.json")
        val merged = CandidateCatalog.merge(listOf(bundled, shared))
        assertEquals(11, merged.candidates.size)
        val maven = merged.candidates.first()
        assertEquals(listOf(bundled.definitions.first(), shared.definitions.first()), maven.definitions)
        assertEquals(listOf("Maven", "Build tools"), maven.definitions.map(CandidateDefinition::app))
        assertEquals(listOf(CandidateDefinition.Advice.CONSIDER, CandidateDefinition.Advice.USUALLY_UNNECESSARY),
                maven.definitions.map(CandidateDefinition::advice))
        val uv = merged.candidates.get(1)
        assertEquals(".cache//uv", uv.definitions.get(1).originalPath)
        assertNull(uv.definitions.first().advice)
        assertEquals(2, uv.definitions.get(1).recordIndex)
        assertEquals("apps[1].directories[0]", uv.definitions.get(1).location)
        assertEquals(13, merged.candidates.sumOf { c -> c.definitions.size })

        val reason = "  literal \${HOME} <b>reason</b>\n\u001b[31m  "
        val record = """{"path": "cache", "reason": ${quoted(reason)}}"""
        val duplicate = parse("""{"directories": [$record, $record]}""")
        val occurrences = CandidateCatalog.merge(listOf(duplicate)).candidates.first().definitions
        assertEquals(2, occurrences.size)
        assertEquals(reason, occurrences.first().reason)
        assertEquals(reason, occurrences.get(1).reason)
        assertEquals(1, occurrences.first().recordIndex)
        assertEquals(2, occurrences.get(1).recordIndex)
        assertEquals(listOf("directories[0]", "directories[1]"), occurrences.map(CandidateDefinition::location))

        val refreshed = CandidateCatalog.merge(listOf(bundled, fixture(SHARED, "shared-refreshed.json")))
        assertEquals(7, refreshed.candidates.size)
        assertEquals(8, refreshed.candidates.sumOf { c -> c.definitions.size })
        assertEquals(11, merged.candidates.size)
    }

    @Test fun isolatesRejectedSourcesInEitherDirectionAndRejectsMixedRoots() {
        val valid = fixture(CandidateCatalog.BUNDLED, "bundled.json")
        val invalid = fixture(SHARED, "unsafe.json")
        assertKind(CandidateDiagnostic.Kind.UNSAFE_PATH, invalid)
        val merged = CandidateCatalog.merge(listOf(valid, invalid))
        assertEquals(6, merged.candidates.size)
        assertEquals(invalid.diagnostics, merged.diagnostics)
        val badBundled = parser.parse(CandidateCatalog.BUNDLED, HOME, ByteArray(0))
        assertEquals(7, CandidateCatalog.merge(listOf(badBundled, fixture(SHARED, "shared.json"))).candidates.size)
        assertThrows(IllegalArgumentException::class.java) {
            CandidateCatalog.merge(listOf(valid, parse(EMPTY, OTHER))) }
    }

    @Test fun snapshotCopiesItsListsAndRejectsDefinitionsWithDiagnostics() {
        val parsed = parse("""{"directories": [{"path": "cache"}]}""")
        val definitions = parsed.definitions.toMutableList()
        val copied = CandidateCatalog.Snapshot.of(SHARED, HOME, definitions, listOf())
        definitions.clear()
        assertEquals(1, copied.definitions.size)
        assertThrows(IllegalArgumentException::class.java) { CandidateCatalog.Snapshot.of(SHARED, HOME,
                parsed.definitions, parse("").diagnostics) }
    }

    @Test fun bundledResourcePreservesPathsAndDescriptionsWithExplicitConsiderAdvice() {
        val snapshot = CandidateCatalog.bundled(HOME)
        assertTrue(snapshot.accepted(), snapshot.diagnostics.toString())
        assertEquals(26, snapshot.definitions.size)
        assertEquals(listOf(
                ".m2|Maven|Maven local repository",
                ".gradle/caches|Gradle|Gradle caches",
                ".gradle/wrapper|Gradle|Gradle wrapper distributions",
                ".jbang/cache|JBang|JBang compiled scripts, downloaded content, and cached JDKs",
                ".cargo|Cargo|Rust toolchain and package state",
                ".rustup|rustup|Rust toolchains",
                ".npm|npm|npm cache",
                ".cache/yarn|Yarn|Yarn cache",
                ".yarn/berry/cache|Yarn|Yarn Berry cache",
                ".cache/pnpm|pnpm|pnpm cache",
                ".local/share/pnpm/store|pnpm|pnpm package store",
                ".pnpm-store|pnpm|legacy pnpm package store",
                ".cache/pip|pip|pip cache",
                ".cache/uv|uv|uv cache",
                ".local/share/uv|uv|uv-managed Python installations",
                ".local/share/uv/tools|uv|uv tools and uvx environments",
                ".cache/pypoetry|Poetry|Poetry cache",
                ".cache/pdm|PDM|PDM cache",
                ".cache/virtualenv|virtualenv|virtualenv cache",
                ".local/pipx/venvs|pipx|pipx virtual environments",
                ".cache/go-build|Go|Go build cache",
                ".cache/node-gyp|node-gyp|node-gyp cache",
                ".nvm|nvm|Node.js versions managed by nvm",
                ".bun/install/cache|Bun|Bun package cache",
                ".cache/JetBrains|JetBrains|JetBrains caches",
                ".vscode-server|VS Code|VS Code server"
        ), snapshot.definitions.map { d -> d.originalPath + "|" + d.app + "|" + d.reason })
        assertTrue(snapshot.definitions.all { d -> d.advice == CandidateDefinition.Advice.CONSIDER && d.reason != null })
        assertTrue(snapshot.definitions.all { d -> d.app != null })
        assertEquals(HOME.resolve(".jbang/cache"), snapshot.definitions.single { d -> d.app == "JBang" }.sourcePath)
        for (app in listOf("Gradle", "Yarn", "pnpm", "uv")) {
            val indices = snapshot.definitions.indices.filter { i -> snapshot.definitions.get(i).app == app }
            assertTrue(indices.size > 1, app)
            assertEquals(indices.size, indices.last() - indices.first() + 1, app)
        }
        CandidateCatalog::class.java.getResourceAsStream("/candidates.json").use { input ->
            assertNotNull(input)
            assertArrayEquals(Files.readAllBytes(Path.of("src/main/resources/candidates.json")), input!!.readAllBytes())
        }
    }

    @Test fun loadsBundledContentsFromJarAndReportsMissingOrMalformedPackagedResource() {
        // Isolate application classes in a jar so the normal test classpath cannot supply its resource.
        for (variant in listOf("valid", "missing", "malformed")) {
            val jar = temporary.resolve("$variant.jar")
            val classes = Path.of(CandidateCatalog::class.java.protectionDomain.codeSource.location.toURI())
            JarOutputStream(Files.newOutputStream(jar)).use { output -> Files.walk(classes).use { files ->
                for (file in files.filter(Files::isRegularFile).filter { p -> p.toString().endsWith(".class") }.toList()) {
                    output.putNextEntry(JarEntry(classes.relativize(file).toString().replace('\\', '/')))
                    Files.copy(file, output)
                    output.closeEntry()
                }
                if (variant != "missing") {
                    output.putNextEntry(JarEntry("candidates.json"))
                    output.write(if (variant == "valid") Files.readAllBytes(Path.of("src/main/resources/candidates.json"))
                            else """{"directories": [""".toByteArray(StandardCharsets.UTF_8))
                    output.closeEntry()
                }
            } }
            val dependencies = listOf(kotlin.Unit::class.java, kotlinx.serialization.KSerializer::class.java,
                    kotlinx.serialization.json.Json::class.java).map { type -> type.protectionDomain.codeSource.location }
            URLClassLoader((listOf(jar.toUri().toURL()) + dependencies).toTypedArray(), ClassLoader.getPlatformClassLoader()).use { loader ->
                val catalogClass = loader.loadClass(CandidateCatalog::class.java.name)
                assertEquals("jar", catalogClass.getResource("CandidateCatalog.class").protocol)
                val catalog = catalogClass.getField("INSTANCE").get(null)
                val result = catalogClass.getMethod("bundled", Path::class.java).invoke(catalog, HOME)
                assertEquals(variant == "valid", result.javaClass.getMethod("accepted").invoke(result))
                val definitions = result.javaClass.getMethod("getDefinitions").invoke(result) as List<*>
                assertEquals(if (variant == "valid") 26 else 0, definitions.size)
            }
        }
    }

    private fun fixture(source: CandidateSource, name: String): CandidateCatalog.Snapshot {
        val directory = if (name == "unsafe.json") FIXTURES else FIXTURES.resolve("nested")
        return parser.parse(source, HOME, Files.readAllBytes(directory.resolve(name)))
    }

    /** Raw-string literals are trimmed so that reported lines and columns count from the first character. */
    private fun parse(text: String, root: Path = HOME): CandidateCatalog.Snapshot =
        parser.parse(SHARED, root, text.trimIndent().toByteArray(StandardCharsets.UTF_8))

    companion object {
        private val HOME = Path.of("/home/alex")
        private val OTHER = Path.of("/srv/build/alex")
        private val FIXTURES = Path.of("docs/research/session-b-fixtures")
        private val SHARED = CandidateSource(CandidateSource.Kind.SHARED, "/net/team/homelight/candidates.json")
        private const val EMPTY = """{"directories": []}"""

        private fun directories(count: Int): String =
            """{"directories": [""" + List(count) { """{"path": "cache"}""" }.joinToString(",") + "]}"

        private fun assertKind(kind: CandidateDiagnostic.Kind, snapshot: CandidateCatalog.Snapshot) {
            assertFalse(snapshot.accepted())
            assertTrue(snapshot.definitions.isEmpty())
            assertEquals(kind, snapshot.diagnostics.first().kind, snapshot.diagnostics.toString())
        }

        /** A JSON string literal. */
        private fun quoted(value: String): String {
            val result = StringBuilder("\"")
            for (character in value) {
                if (character == '\\' || character == '"') result.append('\\').append(character)
                else if (Character.isISOControl(character)) result.append(String.format("\\u%04x", character.code))
                else result.append(character)
            }
            return result.append('"').toString()
        }
    }
}
