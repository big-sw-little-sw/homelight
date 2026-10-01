package io.github.bigswlittlesw.homelight.config

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
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

// Java text blocks end with the newline before the closing delimiter, and `trimIndent` drops it,
// so each block appends "\n" to keep the YAML byte-identical.
class CandidateCatalogTest {
    private val parser = CandidateParser()

    @TempDir lateinit var temporary: Path

    @Test fun sameSchemaAndLexicalResolutionUnderBothRoots() {
        for (root in listOf(HOME, OTHER)) {
            for (source in listOf(CandidateCatalog.BUNDLED, SHARED)) {
                val snapshot = parser.parse(source, root, Files.readAllBytes(FIXTURES.resolve("nested/bundled.yaml")))
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
                    assertTrue(definition.line > 0)
                    assertTrue(definition.column > 0)
                }
            }
        }
        assertEquals(HOME, parse("directories: []", Path.of("/home/./alex")).root)
        assertThrows(IllegalArgumentException::class.java) { parse("directories: []", Path.of("relative")) }
    }

    @Test fun rejectsEveryUnsafePathAtomicallyWithLocation() {
        val paths = listOf("", "  ", ".", "./", "a/..", "/tmp/cache", "../cache", "a/../cache",
                "a/../../alex-other/cache", "~/cache", "~alex/cache", "\${HOME}/cache", "\$HOME/cache",
                "a/\$NAME/cache", "C:/cache", "C:cache", "c:\\cache", "\\\\server\\share", "//server/share",
                "a\u0000b", "a\nb", "a\tb", "a\u007fb", "a\u0085b", "https://example.com/cache",
                "file:/tmp/cache", "cache/*", "cache/?")
        for (path in paths) {
            val snapshot = parse("directories:\n  - path: safe\n  - path: " + quoted(path))
            assertFalse(snapshot.accepted(), path)
            assertTrue(snapshot.definitions.isEmpty(), path)
            val diagnostic = snapshot.diagnostics.first()
            assertEquals(SHARED, diagnostic.source)
            assertEquals(2, diagnostic.recordIndex, path)
            assertEquals("path", diagnostic.key, path)
            assertEquals("directories[1]", diagnostic.location, path)
            assertEquals(3, diagnostic.line, path)
        }
    }

    @Test fun acceptsLiteralSpacesAndNormalizesOnlyLexically() {
        val snapshot = parse("""
                directories:
                  - path: .cache//uv
                  - path: .cache/./uv
                  - path: cache with spaces
                  - path: .local/share/uv
                  - path: .local/share/uv/tools
                """.trimIndent() + "\n")
        assertTrue(snapshot.accepted())
        assertEquals(snapshot.definitions.get(0).sourcePath, snapshot.definitions.get(1).sourcePath)
        val merged = CandidateCatalog.merge(listOf(snapshot))
        assertEquals(4, merged.candidates.size)
        assertEquals(2, merged.candidates.first().definitions.size)
        assertEquals(HOME.resolve("cache with spaces"), merged.candidates.get(1).sourcePath)
        assertTrue(merged.candidates.get(3).sourcePath.startsWith(merged.candidates.get(2).sourcePath))
    }

    @Test fun rejectsStrictSchemaViolationsForEitherSourceKind() {
        val inputs = listOf("", "# empty", "[]", "{}", "directories: null", "directories: {}",
                "directories: []\nunknown: yes", "directories: []\ndirectories: []",
                "directories: []\n---\ndirectories: []", "directories: [null]",
                "directories: [{app: Maven}]", "directories: [{path: null}]",
                "directories: [{path: cache, app: null}]", "directories: [{path: cache, app: ' Maven'}]", "directories: [{path: cache, app: 'Maven '}]",
                "directories: [{path: cache, advice: Consider}]", "directories: [{path: cache, advice: safe}]",
                "directories: [{path: cache, selected: true}]", "directories: [{path: cache, policy: move}]",
                "directories: [{path: cache, path: other}]", "directories: &list []",
                "directories: [{path: &p cache}, {path: *p}]", "directories: [{path: *p}]",
                "directories: [{path: !custom cache}]", "directories: !custom []",
                "directories: [{path: cache, <<: {app: Maven}}]", "directories: [{[path]: cache}]",
                "directories: [{path: [cache]}]", "directories: [{path: cache, app: {name: Maven}}]")
        for (source in listOf(CandidateCatalog.BUNDLED, SHARED)) {
            for (input in inputs) {
                val snapshot = parser.parse(source, HOME, input.toByteArray(StandardCharsets.UTF_8))
                assertFalse(snapshot.accepted(), input)
                assertTrue(snapshot.definitions.isEmpty(), input)
                assertEquals(source, snapshot.diagnostics.first().source)
            }
        }
        assertTrue(parse("directories: []").accepted())
        assertTrue(parse("directories: [{path: !!str 12}]").accepted())
    }

    @Test fun readsAnyScalarAsTextAndNullOrBlankValuesAsAbsent() {
        val snapshot = parse("""
                apps:
                  - name: 12
                    directories:
                      - {path: 2026-09-23, reason: 5, advice: null}
                      - {path: 'true', reason: '  '}
                """.trimIndent() + "\n")
        assertTrue(snapshot.accepted(), snapshot.diagnostics.toString())
        assertEquals(listOf("2026-09-23", "true"),
                snapshot.definitions.stream().map(CandidateDefinition::originalPath).toList())
        assertEquals(listOf("12", "12"),
                snapshot.definitions.stream().map(CandidateDefinition::app).toList())
        assertEquals(listOf("5", null),
                snapshot.definitions.stream().map(CandidateDefinition::reason).toList())
        assertNull(snapshot.definitions.first().advice)
        val missing = parse("directories: [{path: '  '}]").diagnostics.first()
        assertEquals(CandidateDiagnostic(SHARED, CandidateDiagnostic.Kind.SCHEMA, 1, 1, 15,
                "directories[0]", "path", "Missing required key directories[0].path"), missing)
    }

    @Test fun enforcesByteRecordDepthAndStringLimitsAtTheirBoundaries() {
        val prefix = "directories: []\n#"
        val atByteLimit = prefix + "x".repeat(CandidateParser.MAX_BYTES - prefix.length)
        assertTrue(parse(atByteLimit).accepted())
        assertKind(CandidateDiagnostic.Kind.LIMIT, parse(atByteLimit + "x"))
        val multibyte = prefix + "é".repeat(CandidateParser.MAX_BYTES / 2)
        assertKind(CandidateDiagnostic.Kind.LIMIT, parse(multibyte))

        val atRecordLimit = "directories:\n" + "- path: cache\n".repeat(CandidateParser.MAX_RECORDS)
        assertEquals(CandidateParser.MAX_RECORDS, parse(atRecordLimit).definitions.size)
        assertKind(CandidateDiagnostic.Kind.LIMIT, parse(atRecordLimit + "- path: cache\n"))

        // Root map is depth 1; nested collections at depth 8 pass the limit but fail schema.
        assertKind(CandidateDiagnostic.Kind.SCHEMA, parse("directories: " + "[".repeat(7) + "]".repeat(7)))
        assertKind(CandidateDiagnostic.Kind.LIMIT, parse("directories: " + "[".repeat(8) + "]".repeat(8)))
        val reason = "😀".repeat(CandidateParser.MAX_STRING_CHARACTERS)
        assertEquals(reason, parse("directories: [{path: cache, reason: " + quoted(reason) + "}]")
                .definitions.first().reason)
        assertKind(CandidateDiagnostic.Kind.LIMIT,
                parse("directories: [{path: cache, reason: " + quoted(reason + "x") + "}]"))
    }

    @Test fun acceptsEmptyAndOptionalSequences() {
        for (input in listOf("apps: []", "directories: []", "apps: []\ndirectories: []",
                "apps: [{name: Empty, directories: []}]")) {
            val snapshot = parse(input)
            assertTrue(snapshot.accepted(), snapshot.diagnostics.toString())
            assertTrue(snapshot.definitions.isEmpty())
        }
        val appsOnly = parse("apps: [{name: App, directories: [{path: cache}]}]")
        assertTrue(appsOnly.accepted())
        assertEquals("App", appsOnly.definitions.first().app)
        assertNull(appsOnly.definitions.first().advice)
        assertNull(appsOnly.definitions.first().reason)
    }

    @Test fun preservesRepeatedGroupsAndCrossGroupOccurrencesInAppFirstOrder() {
        val snapshot = parse("""
                directories: [{path: .cache/./uv, reason: Ungrouped}]
                apps:
                  - name: uv
                    directories: []
                  - name: uv
                    directories: [{path: .cache/uv, advice: consider, reason: First}]
                  - name: uv
                    directories: [{path: .cache//uv, advice: usually-unnecessary, reason: Second}]
                  - name: UV
                    directories: [{path: .cache/uv}]
                """.trimIndent() + "\n")
        assertTrue(snapshot.accepted(), snapshot.diagnostics.toString())
        val candidates = CandidateCatalog.merge(listOf(snapshot)).candidates
        assertEquals(1, candidates.size)
        val definitions = candidates.first().definitions
        assertEquals(snapshot.definitions, definitions)
        assertEquals(listOf(1, 2, 3, 4), definitions.stream().map(CandidateDefinition::recordIndex).toList())
        assertEquals(listOf("apps[1].directories[0]", "apps[2].directories[0]",
                "apps[3].directories[0]", "directories[0]"),
                definitions.stream().map(CandidateDefinition::location).toList())
        assertEquals(listOf("uv", "uv", "UV", null),
                definitions.stream().map(CandidateDefinition::app).toList())
        assertEquals(listOf("First", "Second", null, "Ungrouped"),
                definitions.stream().map(CandidateDefinition::reason).toList())
        assertEquals(listOf(CandidateDefinition.Advice.CONSIDER,
                CandidateDefinition.Advice.USUALLY_UNNECESSARY, null, null),
                definitions.stream().map(CandidateDefinition::advice).toList())
    }

    @Test fun rejectsInvalidGroupsAndNestedRecordsWithStructuralLocations() {
        val groups = listOf("null", "[]", "{}", "{name: App}", "{directories: []}",
                "{name: null, directories: []}",
                "{name: '', directories: []}", "{name: ' App', directories: []}",
                "{name: 'App ', directories: []}", "{name: App, directories: null}",
                "{name: App, directories: {}}", "{name: App, directories: [], advice: consider}",
                "{name: App, directories: [], reason: Description}",
                "{name: App, directories: [], selected: true}",
                "{name: App, name: Other, directories: []}",
                "{name: App, directories: [], directories: []}")
        for (group in groups) {
            val snapshot = parse("apps: [{name: Valid, directories: [{path: safe}]}, $group]")
            assertKind(CandidateDiagnostic.Kind.SCHEMA, snapshot)
            assertTrue(snapshot.diagnostics.first().location.startsWith("apps[1]"), group)
            assertTrue(snapshot.diagnostics.first().line > 0, group)
        }
        for (input in listOf("apps: null", "apps: {}", "apps: []\napps: []")) {
            assertKind(CandidateDiagnostic.Kind.SCHEMA, parse(input))
        }
        for (directory in listOf("{path: cache, app: Legacy}", "{path: cache, unknown: value}",
                "{path: cache, path: other}", "{path: ../escape}", "{reason: Missing}")) {
            val snapshot = parse("apps: [{name: App, directories: [{path: safe}, $directory]}]")
            assertFalse(snapshot.accepted())
            assertTrue(snapshot.definitions.isEmpty())
            val diagnostic = snapshot.diagnostics.first()
            assertEquals("apps[0].directories[1]", diagnostic.location)
            assertEquals(2, diagnostic.recordIndex)
            assertTrue(diagnostic.line > 0)
            assertTrue(diagnostic.column > 0)
            assertFalse(diagnostic.key.isEmpty())
        }
        assertKind(CandidateDiagnostic.Kind.SCHEMA, parse("directories: [{path: cache, app: Legacy}]"))
    }

    @Test fun boundsGroupsAndAggregateRecordsAndNestedDepth() {
        val emptyGroups = "apps:\n" + "- {name: App, directories: []}\n".repeat(CandidateParser.MAX_APPS)
        assertTrue(parse(emptyGroups).accepted())
        assertKind(CandidateDiagnostic.Kind.LIMIT, parse(emptyGroups + "- {name: App, directories: []}\n"))
        val group = "  - name: App\n    directories:\n" + "      - path: cache\n".repeat(5_000)
        val atLimit = "apps:\n" + group.repeat(2)
        assertEquals(CandidateParser.MAX_RECORDS, parse(atLimit).definitions.size)
        val mixed = parse(atLimit + "directories: [{path: extra}]")
        assertKind(CandidateDiagnostic.Kind.LIMIT, mixed)
        assertEquals("directories", mixed.diagnostics.first().location)
        val grouped = parse(atLimit + "  - name: Extra\n    directories: [{path: extra}]\n")
        assertKind(CandidateDiagnostic.Kind.LIMIT, grouped)
        assertEquals("apps[2].directories", grouped.diagnostics.first().location)
        // The app record adds two collection levels compared with top-level directories.
        val prefix = "apps: [{name: App, directories: "
        assertKind(CandidateDiagnostic.Kind.SCHEMA, parse(prefix + "[".repeat(5) + "]".repeat(5) + "}]"))
        assertKind(CandidateDiagnostic.Kind.LIMIT, parse(prefix + "[".repeat(6) + "]".repeat(6) + "}]"))
    }

    @Test fun rejectsMalformedUtf8AndSyntax() {
        assertKind(CandidateDiagnostic.Kind.ENCODING, parser.parse(SHARED, HOME, byteArrayOf(0xc3.toByte(), 0x28)))
        val malformed = parser.parse(SHARED, HOME, Files.readAllBytes(FIXTURES.resolve("malformed.yaml")))
        assertKind(CandidateDiagnostic.Kind.SYNTAX, malformed)
        assertTrue(malformed.diagnostics.first().line > 0)
    }

    @Test fun retainsAllAttributionAndLiteralReasonsWithoutPrecedence() {
        val bundled = fixture(CandidateCatalog.BUNDLED, "bundled.yaml")
        val shared = fixture(SHARED, "shared.yaml")
        val merged = CandidateCatalog.merge(listOf(bundled, shared))
        assertEquals(11, merged.candidates.size)
        val maven = merged.candidates.first()
        assertEquals(listOf(bundled.definitions.first(), shared.definitions.first()), maven.definitions)
        assertEquals(listOf("Maven", "Build tools"),
                maven.definitions.stream().map(CandidateDefinition::app).toList())
        assertEquals(listOf(CandidateDefinition.Advice.CONSIDER,
                        CandidateDefinition.Advice.USUALLY_UNNECESSARY),
                maven.definitions.stream().map(CandidateDefinition::advice).toList())
        val uv = merged.candidates.get(1)
        assertEquals(".cache//uv", uv.definitions.get(1).originalPath)
        assertNull(uv.definitions.first().advice)
        assertEquals(2, uv.definitions.get(1).recordIndex)
        assertEquals(9, uv.definitions.get(1).line)
        assertEquals("apps[1].directories[0]", uv.definitions.get(1).location)
        assertEquals(13, merged.candidates.stream().mapToInt { c -> c.definitions.size }.sum())

        val reason = "  literal \${HOME} <b>reason</b>\n\u001b[31m  "
        val duplicate = parse("directories:\n" + ("- path: cache\n  reason: " + quoted(reason) + "\n").repeat(2))
        val occurrences = CandidateCatalog.merge(listOf(duplicate)).candidates.first().definitions
        assertEquals(2, occurrences.size)
        assertEquals(reason, occurrences.first().reason)
        assertEquals(reason, occurrences.get(1).reason)
        assertEquals(1, occurrences.first().recordIndex)
        assertEquals(2, occurrences.get(1).recordIndex)
        assertNotEquals(occurrences.first().line, occurrences.get(1).line)

        val refreshed = CandidateCatalog.merge(listOf(bundled, fixture(SHARED, "shared-refreshed.yaml")))
        assertEquals(7, refreshed.candidates.size)
        assertEquals(8, refreshed.candidates.stream().mapToInt { c -> c.definitions.size }.sum())
        assertEquals(11, merged.candidates.size)
    }

    @Test fun isolatesRejectedSourcesInEitherDirectionAndRejectsMixedRoots() {
        val valid = fixture(CandidateCatalog.BUNDLED, "bundled.yaml")
        val invalid = fixture(SHARED, "unsafe.yaml")
        assertKind(CandidateDiagnostic.Kind.UNSAFE_PATH, invalid)
        val merged = CandidateCatalog.merge(listOf(valid, invalid))
        assertEquals(6, merged.candidates.size)
        assertEquals(invalid.diagnostics, merged.diagnostics)
        val badBundled = parser.parse(CandidateCatalog.BUNDLED, HOME, ByteArray(0))
        assertEquals(7, CandidateCatalog.merge(listOf(badBundled, fixture(SHARED, "shared.yaml"))).candidates.size)
        assertThrows(IllegalArgumentException::class.java) {
            CandidateCatalog.merge(listOf(valid, parse("directories: []", OTHER))) }
    }

    @Test fun returnedCollectionsAreImmutableAndDefensivelyCopied() {
        val parsed = parse("directories: [{path: cache}]")
        val definitions = ArrayList(parsed.definitions)
        val copied = CandidateCatalog.Snapshot.of(SHARED, HOME, definitions, listOf())
        definitions.clear()
        assertEquals(1, copied.definitions.size)
        // Kotlin's read-only `List` has no `clear`; the casts reach the JDK lists' mutators.
        assertThrows(UnsupportedOperationException::class.java) { (copied.definitions as MutableList<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (copied.diagnostics as MutableList<*>).clear() }
        val merged = CandidateCatalog.merge(listOf(copied))
        assertThrows(UnsupportedOperationException::class.java) { (merged.candidates as MutableList<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (merged.diagnostics as MutableList<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) {
            (merged.candidates.first().definitions as MutableList<*>).clear() }
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
        ), snapshot.definitions.stream()
                .map { d -> d.originalPath + "|" + d.app + "|" + d.reason }.toList())
        assertEquals(".m2", snapshot.definitions.first().originalPath)
        assertEquals("Maven local repository", snapshot.definitions.first().reason)
        assertEquals(".vscode-server", snapshot.definitions.last().originalPath)
        assertEquals("VS Code server", snapshot.definitions.last().reason)
        assertTrue(snapshot.definitions.stream().allMatch { d ->
                d.advice == CandidateDefinition.Advice.CONSIDER && d.reason != null })
        assertTrue(snapshot.definitions.stream().allMatch { d -> d.app != null })
        val jbang = snapshot.definitions.stream().filter { d -> d.app == "JBang" }.toList()
        assertEquals(1, jbang.size)
        assertEquals(".jbang/cache", jbang.first().originalPath)
        assertEquals(HOME.resolve(".jbang/cache"), jbang.first().sourcePath)
        for (app in listOf("Gradle", "Yarn", "pnpm", "uv")) {
            val indices = java.util.stream.IntStream.range(0, snapshot.definitions.size)
                    .filter { i -> snapshot.definitions.get(i).app == app }.toArray()
            assertTrue(indices.size > 1, app)
            assertEquals(indices.size, indices[indices.size - 1] - indices[0] + 1, app)
        }
        assertTrue(snapshot.definitions.stream().anyMatch { d -> d.originalPath.equals(".local/share/uv") })
        assertTrue(snapshot.definitions.stream().anyMatch { d -> d.originalPath.equals(".local/share/uv/tools") })
        CandidateCatalog::class.java.getResourceAsStream("/candidates.yaml").use { input ->
            assertNotNull(input)
            assertArrayEquals(Files.readAllBytes(Path.of("src/main/resources/candidates.yaml")), input.readAllBytes())
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
                if (!variant.equals("missing")) {
                    output.putNextEntry(JarEntry("candidates.yaml"))
                    output.write(if (variant.equals("valid")) Files.readAllBytes(Path.of("src/main/resources/candidates.yaml"))
                            else "directories: [".toByteArray(StandardCharsets.UTF_8))
                    output.closeEntry()
                }
            } }
            val yamlJar = org.yaml.snakeyaml.Yaml::class.java.protectionDomain.codeSource.location
            val kotlinJar = kotlin.Unit::class.java.protectionDomain.codeSource.location
            URLClassLoader(arrayOf(jar.toUri().toURL(), yamlJar, kotlinJar), ClassLoader.getPlatformClassLoader()).use { loader ->
                val catalogClass = loader.loadClass(CandidateCatalog::class.java.name)
                assertEquals("jar", catalogClass.getResource("CandidateCatalog.class").protocol)
                val catalog = catalogClass.getField("INSTANCE").get(null)
                val result = catalogClass.getMethod("bundled", Path::class.java).invoke(catalog, HOME)
                assertEquals(variant.equals("valid"), result.javaClass.getMethod("accepted").invoke(result))
                val definitions = result.javaClass.getMethod("getDefinitions").invoke(result) as List<*>
                assertEquals(if (variant.equals("valid")) 26 else 0, definitions.size)
            }
        }
    }

    private fun fixture(source: CandidateSource, name: String): CandidateCatalog.Snapshot {
        val directory = if (name.equals("unsafe.yaml")) FIXTURES else FIXTURES.resolve("nested")
        return parser.parse(source, HOME, Files.readAllBytes(directory.resolve(name)))
    }

    private fun parse(text: String): CandidateCatalog.Snapshot { return parse(text, HOME) }

    private fun parse(text: String, root: Path): CandidateCatalog.Snapshot {
        return parser.parse(SHARED, root, text.toByteArray(StandardCharsets.UTF_8))
    }

    companion object {
        private val HOME = Path.of("/home/alex")
        private val OTHER = Path.of("/srv/build/alex")
        private val FIXTURES = Path.of("docs/research/session-b-fixtures")
        private val SHARED = CandidateSource(
                CandidateSource.Kind.SHARED, "/net/team/homelight/candidates.yaml")

        private fun assertKind(kind: CandidateDiagnostic.Kind, snapshot: CandidateCatalog.Snapshot) {
            assertFalse(snapshot.accepted())
            assertTrue(snapshot.definitions.isEmpty())
            assertEquals(kind, snapshot.diagnostics.first().kind, snapshot.diagnostics.toString())
        }

        private fun quoted(value: String): String {
            val result = StringBuilder("\"")
            for (i in 0 until value.length) {
                val character = value[i]
                if (character == '\\' || character == '"') result.append('\\').append(character)
                else if (Character.isISOControl(character)) result.append(String.format("\\u%04x", character.code))
                else result.append(character)
            }
            return result.append('"').toString()
        }
    }
}
