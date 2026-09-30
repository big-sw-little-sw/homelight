package io.github.bigswlittlesw.homelight.config;

import org.junit.jupiter.api.Test;

import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class CandidateCatalogTest {
    private static final Path HOME = Path.of("/home/alex");
    private static final Path OTHER = Path.of("/srv/build/alex");
    private static final Path FIXTURES = Path.of("docs/research/session-b-fixtures");
    private static final CandidateSource SHARED = new CandidateSource(
            CandidateSource.Kind.SHARED, "/net/team/homelight/candidates.yaml");
    private final CandidateParser parser = new CandidateParser();

    @TempDir Path temporary;

    @Test void sameSchemaAndLexicalResolutionUnderBothRoots() throws Exception {
        for (var root : List.of(HOME, OTHER)) {
            for (var source : List.of(CandidateCatalog.BUNDLED, SHARED)) {
                var snapshot = parser.parse(source, root, Files.readAllBytes(FIXTURES.resolve("nested/bundled.yaml")));
                assertTrue(snapshot.accepted(), snapshot.diagnostics().toString());
                assertEquals(6, snapshot.definitions().size());
                assertEquals(root.resolve(".m2"), snapshot.definitions().getFirst().sourcePath());
                assertEquals(Optional.of("Maven"), snapshot.definitions().getFirst().app());
                assertEquals(Optional.of(CandidateDefinition.Advice.CONSIDER), snapshot.definitions().getFirst().advice());
                assertEquals(Optional.of(CandidateDefinition.Advice.USUALLY_UNNECESSARY), snapshot.definitions().get(4).advice());
                assertTrue(snapshot.definitions().get(1).advice().isEmpty());
                assertTrue(snapshot.definitions().get(1).reason().isEmpty());
                assertTrue(snapshot.definitions().get(5).app().isEmpty());
                for (var definition : snapshot.definitions()) {
                    assertEquals(source, definition.source());
                    assertEquals(root.resolve(definition.originalPath()).normalize(), definition.sourcePath());
                    assertTrue(definition.line() > 0);
                    assertTrue(definition.column() > 0);
                }
            }
        }
        assertEquals(HOME, parse("directories: []", Path.of("/home/./alex")).root());
        assertThrows(IllegalArgumentException.class, () -> parse("directories: []", Path.of("relative")));
    }

    @Test void rejectsEveryUnsafePathAtomicallyWithLocation() {
        var paths = List.of("", "  ", ".", "./", "a/..", "/tmp/cache", "../cache", "a/../cache",
                "a/../../alex-other/cache", "~/cache", "~alex/cache", "${HOME}/cache", "$HOME/cache",
                "a/$NAME/cache", "C:/cache", "C:cache", "c:\\cache", "\\\\server\\share", "//server/share",
                "a\u0000b", "a\nb", "a\tb", "a\u007fb", "a\u0085b", "https://example.com/cache",
                "file:/tmp/cache", "cache/*", "cache/?");
        for (var path : paths) {
            var snapshot = parse("directories:\n  - path: safe\n  - path: " + quoted(path));
            assertFalse(snapshot.accepted(), path);
            assertTrue(snapshot.definitions().isEmpty(), path);
            var diagnostic = snapshot.diagnostics().getFirst();
            assertEquals(SHARED, diagnostic.source());
            assertEquals(2, diagnostic.recordIndex(), path);
            assertEquals("path", diagnostic.key(), path);
            assertEquals("directories[1]", diagnostic.location(), path);
            assertEquals(3, diagnostic.line(), path);
        }
    }

    @Test void acceptsLiteralSpacesAndNormalizesOnlyLexically() {
        var snapshot = parse("""
                directories:
                  - path: .cache//uv
                  - path: .cache/./uv
                  - path: cache with spaces
                  - path: .local/share/uv
                  - path: .local/share/uv/tools
                """);
        assertTrue(snapshot.accepted());
        assertEquals(snapshot.definitions().get(0).sourcePath(), snapshot.definitions().get(1).sourcePath());
        var merged = CandidateCatalog.merge(List.of(snapshot));
        assertEquals(4, merged.candidates().size());
        assertEquals(2, merged.candidates().getFirst().definitions().size());
        assertEquals(HOME.resolve("cache with spaces"), merged.candidates().get(1).sourcePath());
        assertTrue(merged.candidates().get(3).sourcePath().startsWith(merged.candidates().get(2).sourcePath()));
    }

    @Test void rejectsStrictSchemaViolationsForEitherSourceKind() {
        var inputs = List.of("", "# empty", "[]", "{}", "directories: null", "directories: {}",
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
                "directories: [{path: [cache]}]", "directories: [{path: cache, app: {name: Maven}}]");
        for (var source : List.of(CandidateCatalog.BUNDLED, SHARED)) {
            for (var input : inputs) {
                var snapshot = parser.parse(source, HOME, input.getBytes(StandardCharsets.UTF_8));
                assertFalse(snapshot.accepted(), input);
                assertTrue(snapshot.definitions().isEmpty(), input);
                assertEquals(source, snapshot.diagnostics().getFirst().source());
            }
        }
        assertTrue(parse("directories: []").accepted());
        assertTrue(parse("directories: [{path: !!str 12}]").accepted());
    }

    @Test void readsAnyScalarAsTextAndNullOrBlankValuesAsAbsent() {
        var snapshot = parse("""
                apps:
                  - name: 12
                    directories:
                      - {path: 2026-09-23, reason: 5, advice: null}
                      - {path: 'true', reason: '  '}
                """);
        assertTrue(snapshot.accepted(), snapshot.diagnostics().toString());
        assertEquals(List.of("2026-09-23", "true"),
                snapshot.definitions().stream().map(CandidateDefinition::originalPath).toList());
        assertEquals(List.of(Optional.of("12"), Optional.of("12")),
                snapshot.definitions().stream().map(CandidateDefinition::app).toList());
        assertEquals(List.of(Optional.of("5"), Optional.empty()),
                snapshot.definitions().stream().map(CandidateDefinition::reason).toList());
        assertTrue(snapshot.definitions().getFirst().advice().isEmpty());
        var missing = parse("directories: [{path: '  '}]").diagnostics().getFirst();
        assertEquals(new CandidateDiagnostic(SHARED, CandidateDiagnostic.Kind.SCHEMA, 1, 1, 15,
                "directories[0]", "path", "Missing required key directories[0].path"), missing);
    }

    @Test void enforcesByteRecordDepthAndStringLimitsAtTheirBoundaries() {
        var prefix = "directories: []\n#";
        var atByteLimit = prefix + "x".repeat(CandidateParser.MAX_BYTES - prefix.length());
        assertTrue(parse(atByteLimit).accepted());
        assertKind(CandidateDiagnostic.Kind.LIMIT, parse(atByteLimit + "x"));
        var multibyte = prefix + "é".repeat(CandidateParser.MAX_BYTES / 2);
        assertKind(CandidateDiagnostic.Kind.LIMIT, parse(multibyte));

        var atRecordLimit = "directories:\n" + "- path: cache\n".repeat(CandidateParser.MAX_RECORDS);
        assertEquals(CandidateParser.MAX_RECORDS, parse(atRecordLimit).definitions().size());
        assertKind(CandidateDiagnostic.Kind.LIMIT, parse(atRecordLimit + "- path: cache\n"));

        // Root map is depth 1; nested collections at depth 8 pass the limit but fail schema.
        assertKind(CandidateDiagnostic.Kind.SCHEMA, parse("directories: " + "[".repeat(7) + "]".repeat(7)));
        assertKind(CandidateDiagnostic.Kind.LIMIT, parse("directories: " + "[".repeat(8) + "]".repeat(8)));
        var reason = "😀".repeat(CandidateParser.MAX_STRING_CHARACTERS);
        assertEquals(Optional.of(reason), parse("directories: [{path: cache, reason: " + quoted(reason) + "}]")
                .definitions().getFirst().reason());
        assertKind(CandidateDiagnostic.Kind.LIMIT,
                parse("directories: [{path: cache, reason: " + quoted(reason + "x") + "}]"));
    }

    @Test void acceptsEmptyAndOptionalSequences() {
        for (var input : List.of("apps: []", "directories: []", "apps: []\ndirectories: []",
                "apps: [{name: Empty, directories: []}]")) {
            var snapshot = parse(input);
            assertTrue(snapshot.accepted(), snapshot.diagnostics().toString());
            assertTrue(snapshot.definitions().isEmpty());
        }
        var appsOnly = parse("apps: [{name: App, directories: [{path: cache}]}]");
        assertTrue(appsOnly.accepted());
        assertEquals(Optional.of("App"), appsOnly.definitions().getFirst().app());
        assertTrue(appsOnly.definitions().getFirst().advice().isEmpty());
        assertTrue(appsOnly.definitions().getFirst().reason().isEmpty());
    }

    @Test void preservesRepeatedGroupsAndCrossGroupOccurrencesInAppFirstOrder() {
        var snapshot = parse("""
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
                """);
        assertTrue(snapshot.accepted(), snapshot.diagnostics().toString());
        var candidates = CandidateCatalog.merge(List.of(snapshot)).candidates();
        assertEquals(1, candidates.size());
        var definitions = candidates.getFirst().definitions();
        assertEquals(snapshot.definitions(), definitions);
        assertEquals(List.of(1, 2, 3, 4), definitions.stream().map(CandidateDefinition::recordIndex).toList());
        assertEquals(List.of("apps[1].directories[0]", "apps[2].directories[0]",
                "apps[3].directories[0]", "directories[0]"),
                definitions.stream().map(CandidateDefinition::location).toList());
        assertEquals(List.of(Optional.of("uv"), Optional.of("uv"), Optional.of("UV"), Optional.empty()),
                definitions.stream().map(CandidateDefinition::app).toList());
        assertEquals(List.of(Optional.of("First"), Optional.of("Second"), Optional.empty(), Optional.of("Ungrouped")),
                definitions.stream().map(CandidateDefinition::reason).toList());
        assertEquals(List.of(Optional.of(CandidateDefinition.Advice.CONSIDER),
                Optional.of(CandidateDefinition.Advice.USUALLY_UNNECESSARY), Optional.empty(), Optional.empty()),
                definitions.stream().map(CandidateDefinition::advice).toList());
    }

    @Test void rejectsInvalidGroupsAndNestedRecordsWithStructuralLocations() {
        var groups = List.of("null", "[]", "{}", "{name: App}", "{directories: []}",
                "{name: null, directories: []}",
                "{name: '', directories: []}", "{name: ' App', directories: []}",
                "{name: 'App ', directories: []}", "{name: App, directories: null}",
                "{name: App, directories: {}}", "{name: App, directories: [], advice: consider}",
                "{name: App, directories: [], reason: Description}",
                "{name: App, directories: [], selected: true}",
                "{name: App, name: Other, directories: []}",
                "{name: App, directories: [], directories: []}");
        for (var group : groups) {
            var snapshot = parse("apps: [{name: Valid, directories: [{path: safe}]}, " + group + "]");
            assertKind(CandidateDiagnostic.Kind.SCHEMA, snapshot);
            assertTrue(snapshot.diagnostics().getFirst().location().startsWith("apps[1]"), group);
            assertTrue(snapshot.diagnostics().getFirst().line() > 0, group);
        }
        for (var input : List.of("apps: null", "apps: {}", "apps: []\napps: []")) {
            assertKind(CandidateDiagnostic.Kind.SCHEMA, parse(input));
        }
        for (var directory : List.of("{path: cache, app: Legacy}", "{path: cache, unknown: value}",
                "{path: cache, path: other}", "{path: ../escape}", "{reason: Missing}")) {
            var snapshot = parse("apps: [{name: App, directories: [{path: safe}, " + directory + "]}]");
            assertFalse(snapshot.accepted());
            assertTrue(snapshot.definitions().isEmpty());
            var diagnostic = snapshot.diagnostics().getFirst();
            assertEquals("apps[0].directories[1]", diagnostic.location());
            assertEquals(2, diagnostic.recordIndex());
            assertTrue(diagnostic.line() > 0);
            assertTrue(diagnostic.column() > 0);
            assertFalse(diagnostic.key().isEmpty());
        }
        assertKind(CandidateDiagnostic.Kind.SCHEMA, parse("directories: [{path: cache, app: Legacy}]"));
    }

    @Test void boundsGroupsAndAggregateRecordsAndNestedDepth() {
        var emptyGroups = "apps:\n" + "- {name: App, directories: []}\n".repeat(CandidateParser.MAX_APPS);
        assertTrue(parse(emptyGroups).accepted());
        assertKind(CandidateDiagnostic.Kind.LIMIT, parse(emptyGroups + "- {name: App, directories: []}\n"));
        var group = "  - name: App\n    directories:\n" + "      - path: cache\n".repeat(5_000);
        var atLimit = "apps:\n" + group.repeat(2);
        assertEquals(CandidateParser.MAX_RECORDS, parse(atLimit).definitions().size());
        var mixed = parse(atLimit + "directories: [{path: extra}]");
        assertKind(CandidateDiagnostic.Kind.LIMIT, mixed);
        assertEquals("directories", mixed.diagnostics().getFirst().location());
        var grouped = parse(atLimit + "  - name: Extra\n    directories: [{path: extra}]\n");
        assertKind(CandidateDiagnostic.Kind.LIMIT, grouped);
        assertEquals("apps[2].directories", grouped.diagnostics().getFirst().location());
        // The app record adds two collection levels compared with top-level directories.
        var prefix = "apps: [{name: App, directories: ";
        assertKind(CandidateDiagnostic.Kind.SCHEMA, parse(prefix + "[".repeat(5) + "]".repeat(5) + "}]"));
        assertKind(CandidateDiagnostic.Kind.LIMIT, parse(prefix + "[".repeat(6) + "]".repeat(6) + "}]"));
    }

    @Test void rejectsMalformedUtf8AndSyntax() throws Exception {
        assertKind(CandidateDiagnostic.Kind.ENCODING, parser.parse(SHARED, HOME, new byte[] {(byte) 0xc3, 0x28}));
        var malformed = parser.parse(SHARED, HOME, Files.readAllBytes(FIXTURES.resolve("malformed.yaml")));
        assertKind(CandidateDiagnostic.Kind.SYNTAX, malformed);
        assertTrue(malformed.diagnostics().getFirst().line() > 0);
    }

    @Test void retainsAllAttributionAndLiteralReasonsWithoutPrecedence() throws Exception {
        var bundled = fixture(CandidateCatalog.BUNDLED, "bundled.yaml");
        var shared = fixture(SHARED, "shared.yaml");
        var merged = CandidateCatalog.merge(List.of(bundled, shared));
        assertEquals(11, merged.candidates().size());
        var maven = merged.candidates().getFirst();
        assertEquals(List.of(bundled.definitions().getFirst(), shared.definitions().getFirst()), maven.definitions());
        assertEquals(List.of(Optional.of("Maven"), Optional.of("Build tools")),
                maven.definitions().stream().map(CandidateDefinition::app).toList());
        assertEquals(List.of(Optional.of(CandidateDefinition.Advice.CONSIDER),
                        Optional.of(CandidateDefinition.Advice.USUALLY_UNNECESSARY)),
                maven.definitions().stream().map(CandidateDefinition::advice).toList());
        var uv = merged.candidates().get(1);
        assertEquals(".cache//uv", uv.definitions().get(1).originalPath());
        assertTrue(uv.definitions().getFirst().advice().isEmpty());
        assertEquals(2, uv.definitions().get(1).recordIndex());
        assertEquals(9, uv.definitions().get(1).line());
        assertEquals("apps[1].directories[0]", uv.definitions().get(1).location());
        assertEquals(13, merged.candidates().stream().mapToInt(c -> c.definitions().size()).sum());

        var reason = "  literal ${HOME} <b>reason</b>\n\u001b[31m  ";
        var duplicate = parse("directories:\n" + ("- path: cache\n  reason: " + quoted(reason) + "\n").repeat(2));
        var occurrences = CandidateCatalog.merge(List.of(duplicate)).candidates().getFirst().definitions();
        assertEquals(2, occurrences.size());
        assertEquals(Optional.of(reason), occurrences.getFirst().reason());
        assertEquals(Optional.of(reason), occurrences.get(1).reason());
        assertEquals(1, occurrences.getFirst().recordIndex());
        assertEquals(2, occurrences.get(1).recordIndex());
        assertNotEquals(occurrences.getFirst().line(), occurrences.get(1).line());

        var refreshed = CandidateCatalog.merge(List.of(bundled, fixture(SHARED, "shared-refreshed.yaml")));
        assertEquals(7, refreshed.candidates().size());
        assertEquals(8, refreshed.candidates().stream().mapToInt(c -> c.definitions().size()).sum());
        assertEquals(11, merged.candidates().size());
    }

    @Test void isolatesRejectedSourcesInEitherDirectionAndRejectsMixedRoots() throws Exception {
        var valid = fixture(CandidateCatalog.BUNDLED, "bundled.yaml");
        var invalid = fixture(SHARED, "unsafe.yaml");
        assertKind(CandidateDiagnostic.Kind.UNSAFE_PATH, invalid);
        var merged = CandidateCatalog.merge(List.of(valid, invalid));
        assertEquals(6, merged.candidates().size());
        assertEquals(invalid.diagnostics(), merged.diagnostics());
        var badBundled = parser.parse(CandidateCatalog.BUNDLED, HOME, new byte[0]);
        assertEquals(7, CandidateCatalog.merge(List.of(badBundled, fixture(SHARED, "shared.yaml"))).candidates().size());
        assertThrows(IllegalArgumentException.class,
                () -> CandidateCatalog.merge(List.of(valid, parse("directories: []", OTHER))));
    }

    @Test void returnedCollectionsAreImmutableAndDefensivelyCopied() {
        var parsed = parse("directories: [{path: cache}]");
        var definitions = new ArrayList<>(parsed.definitions());
        var copied = new CandidateCatalog.Snapshot(SHARED, HOME, definitions, List.of());
        definitions.clear();
        assertEquals(1, copied.definitions().size());
        assertThrows(UnsupportedOperationException.class, () -> copied.definitions().clear());
        assertThrows(UnsupportedOperationException.class, () -> copied.diagnostics().clear());
        var merged = CandidateCatalog.merge(List.of(copied));
        assertThrows(UnsupportedOperationException.class, () -> merged.candidates().clear());
        assertThrows(UnsupportedOperationException.class, () -> merged.diagnostics().clear());
        assertThrows(UnsupportedOperationException.class, () -> merged.candidates().getFirst().definitions().clear());
        assertThrows(IllegalArgumentException.class, () -> new CandidateCatalog.Snapshot(SHARED, HOME,
                parsed.definitions(), parse("").diagnostics()));
    }

    @Test void bundledResourcePreservesPathsAndDescriptionsWithExplicitConsiderAdvice() throws Exception {
        var snapshot = CandidateCatalog.bundled(HOME);
        assertTrue(snapshot.accepted(), snapshot.diagnostics().toString());
        assertEquals(26, snapshot.definitions().size());
        assertEquals(List.of(
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
        ), snapshot.definitions().stream()
                .map(d -> d.originalPath() + "|" + d.app().orElseThrow() + "|" + d.reason().orElseThrow()).toList());
        assertEquals(".m2", snapshot.definitions().getFirst().originalPath());
        assertEquals(Optional.of("Maven local repository"), snapshot.definitions().getFirst().reason());
        assertEquals(".vscode-server", snapshot.definitions().getLast().originalPath());
        assertEquals(Optional.of("VS Code server"), snapshot.definitions().getLast().reason());
        assertTrue(snapshot.definitions().stream().allMatch(d ->
                d.advice().equals(Optional.of(CandidateDefinition.Advice.CONSIDER)) && d.reason().isPresent()));
        assertTrue(snapshot.definitions().stream().allMatch(d -> d.app().isPresent()));
        var jbang = snapshot.definitions().stream().filter(d -> d.app().equals(Optional.of("JBang"))).toList();
        assertEquals(1, jbang.size());
        assertEquals(".jbang/cache", jbang.getFirst().originalPath());
        assertEquals(HOME.resolve(".jbang/cache"), jbang.getFirst().sourcePath());
        for (var app : List.of("Gradle", "Yarn", "pnpm", "uv")) {
            var indices = java.util.stream.IntStream.range(0, snapshot.definitions().size())
                    .filter(i -> snapshot.definitions().get(i).app().equals(Optional.of(app))).toArray();
            assertTrue(indices.length > 1, app);
            assertEquals(indices.length, indices[indices.length - 1] - indices[0] + 1, app);
        }
        assertTrue(snapshot.definitions().stream().anyMatch(d -> d.originalPath().equals(".local/share/uv")));
        assertTrue(snapshot.definitions().stream().anyMatch(d -> d.originalPath().equals(".local/share/uv/tools")));
        try (var input = CandidateCatalog.class.getResourceAsStream("/candidates.yaml")) {
            assertNotNull(input);
            assertArrayEquals(Files.readAllBytes(Path.of("src/main/resources/candidates.yaml")), input.readAllBytes());
        }
    }

    @Test void loadsBundledContentsFromJarAndReportsMissingOrMalformedPackagedResource() throws Exception {
        // Isolate application classes in a jar so the normal test classpath cannot supply its resource.
        for (var variant : List.of("valid", "missing", "malformed")) {
            var jar = temporary.resolve(variant + ".jar");
            var classes = Path.of(CandidateCatalog.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            try (var output = new JarOutputStream(Files.newOutputStream(jar)); var files = Files.walk(classes)) {
                for (var file : files.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".class")).toList()) {
                    output.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                    Files.copy(file, output);
                    output.closeEntry();
                }
                if (!variant.equals("missing")) {
                    output.putNextEntry(new JarEntry("candidates.yaml"));
                    output.write(variant.equals("valid") ? Files.readAllBytes(Path.of("src/main/resources/candidates.yaml"))
                            : "directories: [".getBytes(StandardCharsets.UTF_8));
                    output.closeEntry();
                }
            }
            var yamlJar = org.yaml.snakeyaml.Yaml.class.getProtectionDomain().getCodeSource().getLocation();
            try (var loader = new URLClassLoader(new java.net.URL[] {jar.toUri().toURL(), yamlJar}, ClassLoader.getPlatformClassLoader())) {
                var catalogClass = loader.loadClass(CandidateCatalog.class.getName());
                assertEquals("jar", catalogClass.getResource("CandidateCatalog.class").getProtocol());
                var result = catalogClass.getMethod("bundled", Path.class).invoke(null, HOME);
                assertEquals(variant.equals("valid"), result.getClass().getMethod("accepted").invoke(result));
                var definitions = (List<?>) result.getClass().getMethod("definitions").invoke(result);
                assertEquals(variant.equals("valid") ? 26 : 0, definitions.size());
            }
        }
    }

    private CandidateCatalog.Snapshot fixture(CandidateSource source, String name) throws Exception {
        var directory = name.equals("unsafe.yaml") ? FIXTURES : FIXTURES.resolve("nested");
        return parser.parse(source, HOME, Files.readAllBytes(directory.resolve(name)));
    }

    private CandidateCatalog.Snapshot parse(String text) { return parse(text, HOME); }

    private CandidateCatalog.Snapshot parse(String text, Path root) {
        return parser.parse(SHARED, root, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertKind(CandidateDiagnostic.Kind kind, CandidateCatalog.Snapshot snapshot) {
        assertFalse(snapshot.accepted());
        assertTrue(snapshot.definitions().isEmpty());
        assertEquals(kind, snapshot.diagnostics().getFirst().kind(), snapshot.diagnostics().toString());
    }

    private static String quoted(String value) {
        var result = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character == '\\' || character == '"') result.append('\\').append(character);
            else if (Character.isISOControl(character)) result.append(String.format("\\u%04x", (int) character));
            else result.append(character);
        }
        return result.append('"').toString();
    }
}
