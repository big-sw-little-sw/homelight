package io.github.bigswlittlesw.lighten.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

// `trimIndent` drops the last newline, so each block appends "\n". The file then ends with a newline, as the
// Java text blocks these tests came from did.
class WorkspaceObservationTest {

    @Test
    fun missingWhenNonDefaultPathDoesNotExist(@TempDir tempDir: Path) {
        val result = ConfigurationEvaluation().load(tempDir.resolve(".lighten.json"))

        assertInstanceOf(ConfigurationEvaluation.Missing::class.java, result)
    }

    @Test
    fun loadsConvergedStatus(@TempDir tempDir: Path) {
        val root = tempDir.resolve("target")
        val source = tempDir.resolve("source")
        Files.createDirectories(root)
        Files.createSymbolicLink(source, root)

        val config = tempDir.resolve("config.json")
        Files.writeString(config, ("""
                {
                  "lighten": {
                    "target-root": "%s",
                    "relocations": [
                      {"source-path": "%s", "target-path": "%s"}
                    ]
                  }
                }
                """.trimIndent() + "\n").format(root, source, root))

        val model = ConfigurationEvaluation().load(config)

        assertInstanceOf(ConfigurationEvaluation.Loaded::class.java, model)
        val configured = model as ConfigurationEvaluation.Loaded
        assertEquals(1, configured.items.size)
        assertEquals(PlanBadge.IN_SYNC, configured.items.first().badge())
    }

    @Test
    fun loadsPendingStatusWhenSymlinkNeeded(@TempDir tempDir: Path) {
        val root = tempDir.resolve("target")
        val source = tempDir.resolve("source")
        Files.createDirectories(root)

        val config = tempDir.resolve("config.json")
        Files.writeString(config, ("""
                {
                  "lighten": {
                    "target-root": "%s",
                    "relocations": [
                      {"source-path": "%s", "target-path": "%s", "when-only-target-exists": "adopt-target"}
                    ]
                  }
                }
                """.trimIndent() + "\n").format(root, source, root))

        val model = ConfigurationEvaluation().load(config)

        assertInstanceOf(ConfigurationEvaluation.Loaded::class.java, model)
        val configured = model as ConfigurationEvaluation.Loaded
        assertEquals(PlanBadge.LINK, configured.items.first().badge())
    }

    @Test
    fun loadsConflictStatusWhenBothDirectoriesExist(@TempDir tempDir: Path) {
        val root = tempDir.resolve("target")
        val source = tempDir.resolve("source")
        Files.createDirectories(root)
        Files.createDirectories(source)

        val config = tempDir.resolve("config.json")
        Files.writeString(config, ("""
                {
                  "lighten": {
                    "target-root": "%s",
                    "relocations": [
                      {"source-path": "%s", "target-path": "%s", "when-source-and-target-directories-exist": "prompt"}
                    ]
                  }
                }
                """.trimIndent() + "\n").format(root, source, root))

        val model = ConfigurationEvaluation().load(config)

        assertInstanceOf(ConfigurationEvaluation.Loaded::class.java, model)
        val configured = model as ConfigurationEvaluation.Loaded
        assertEquals(PlanBadge.CONFLICT, configured.items.first().badge())
    }

    @Test
    fun loadsBlockedStatusWhenSourceIsRegularFile(@TempDir tempDir: Path) {
        val root = tempDir.resolve("target")
        val source = tempDir.resolve("source")
        Files.writeString(source, "content")

        val config = tempDir.resolve("config.json")
        Files.writeString(config, ("""
                {
                  "lighten": {
                    "target-root": "%s",
                    "relocations": [
                      {"source-path": "%s", "target-path": "%s"}
                    ]
                  }
                }
                """.trimIndent() + "\n").format(root, source, root))

        val model = ConfigurationEvaluation().load(config)

        assertInstanceOf(ConfigurationEvaluation.Loaded::class.java, model)
        val configured = model as ConfigurationEvaluation.Loaded
        assertEquals(PlanBadge.BLOCKED, configured.items.first().badge())
    }

    @Test
    fun loadsWarningStatusWhenBrokenSymlink(@TempDir tempDir: Path) {
        val root = tempDir.resolve("target")
        val source = tempDir.resolve("source")
        val nonExistent = tempDir.resolve("does-not-exist")
        Files.createDirectories(root)
        Files.createSymbolicLink(source, nonExistent)

        val config = tempDir.resolve("config.json")
        Files.writeString(config, ("""
                {
                  "lighten": {
                    "target-root": "%s",
                    "relocations": [
                      {"source-path": "%s", "target-path": "%s"}
                    ]
                  }
                }
                """.trimIndent() + "\n").format(root, source, root))

        val model = ConfigurationEvaluation().load(config)

        assertInstanceOf(ConfigurationEvaluation.Loaded::class.java, model)
        val configured = model as ConfigurationEvaluation.Loaded
        assertEquals(PlanBadge.LINK, configured.items.first().badge())
        assertTrue(configured.items.first().hasWarnings())
    }

    @Test
    fun sortsItemsByUrgencyAndSourcePath(@TempDir tempDir: Path) {
        val root = tempDir.resolve("target")
        Files.createDirectories(root)

        // Item A: in sync
        val sourceA = tempDir.resolve("source-m2")
        Files.createDirectories(sourceA)
        val targetA = root.resolve("target-m2")
        Files.createDirectories(targetA)
        Files.delete(sourceA)
        Files.createSymbolicLink(sourceA, targetA)

        // Item B: needs a choice
        val sourceB = tempDir.resolve("source-gradle")
        val targetB = root.resolve("target-gradle")
        Files.createDirectories(sourceB)
        Files.createDirectories(targetB)

        // Item C: blocked, because the source is a file
        val sourceC = tempDir.resolve("source-blocked")
        val targetC = root.resolve("target-blocked")
        Files.writeString(sourceC, "file")
        Files.createDirectories(targetC)

        val config = tempDir.resolve("config.json")
        Files.writeString(config, ("""
                {
                  "lighten": {
                    "target-root": "%s",
                    "relocations": [
                      {"source-path": "%s", "target-path": "%s"},
                      {"source-path": "%s", "target-path": "%s", "when-source-and-target-directories-exist": "prompt"},
                      {"source-path": "%s", "target-path": "%s"}
                    ]
                  }
                }
                """.trimIndent() + "\n").format(root,
                sourceA, targetA,
                sourceB, targetB,
                sourceC, targetC))

        val model = ConfigurationEvaluation().load(config)

        assertInstanceOf(ConfigurationEvaluation.Loaded::class.java, model)
        val configured = model as ConfigurationEvaluation.Loaded
        assertEquals(3, configured.items.size)

        // Blocked and Conflict have priority 1 and keep the file's order. In sync has priority 5.
        val first = configured.items.get(0)
        val second = configured.items.get(1)
        val third = configured.items.get(2)

        assertTrue(first.badge() == PlanBadge.BLOCKED
                || first.badge() == PlanBadge.CONFLICT)
        assertTrue(second.badge() == PlanBadge.BLOCKED
                || second.badge() == PlanBadge.CONFLICT)
        assertEquals(PlanBadge.IN_SYNC, third.badge())
    }

    @Test
    fun invalidWhenConfigIsMalformed(@TempDir tempDir: Path) {
        val config = tempDir.resolve("config.json")
        Files.writeString(config, "{\"invalid\": : }")

        val model = ConfigurationEvaluation().load(config)

        assertInstanceOf(ConfigurationEvaluation.Invalid::class.java, model)
    }
}
