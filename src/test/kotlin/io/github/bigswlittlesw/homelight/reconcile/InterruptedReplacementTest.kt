package io.github.bigswlittlesw.homelight.reconcile

import io.github.bigswlittlesw.homelight.application.ConfigurationEvaluation
import io.github.bigswlittlesw.homelight.cli.execute
import io.github.bigswlittlesw.homelight.cli.homeLightCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * A crash while a source directory is replaced with its link (#132). The crash is an [Error] thrown at a point in the
 * replacement, so nothing after it runs; what a real crash would also leave, a temporary link, does not matter here.
 */
class InterruptedReplacementTest {
    @TempDir lateinit var temporary: Path
    @TempDir lateinit var configDirectory: Path
    private lateinit var root: Path
    private lateinit var source: Path
    private lateinit var target: Path
    private lateinit var aside: Path
    private lateinit var config: Path

    @BeforeEach
    fun setUp() {
        root = temporary.toRealPath()
        source = root.resolve("home/cache")
        Files.createDirectories(source.resolve("sub"))
        Files.writeString(source.resolve("entry"), "source")
        Files.writeString(source.resolve("sub/nested"), "nested")
        target = root.resolve("local/cache")
        aside = replacedSourcePath(source, target)
        config = configDirectory.resolve("config.json")
    }

    enum class CrashPoint { BEFORE_SET_ASIDE, SOURCE_SET_ASIDE, LINKED, MID_DELETE }

    @ParameterizedTest
    @EnumSource(CrashPoint::class)
    fun aCrashNeverLeavesAPartialSourceBesideThePublishedTarget(crash: CrashPoint) {
        writeConfig()
        crashMigration(crash)

        assertWhole(target)
        val reasons = plan().relocations.single().let { listOfNotNull(it.conflict?.reason) + it.actions.map { a -> a.type } }
        when (crash) {
            // The replacement has not changed anything: both directories are whole, as after a failed publication.
            CrashPoint.BEFORE_SET_ASIDE -> {
                assertWhole(source)
                assertTrue(Files.notExists(aside))
            }
            // Nothing is at the source, so it is planned as only the target existing; what is aside is kept.
            CrashPoint.SOURCE_SET_ASIDE -> {
                assertTrue(Files.notExists(source, LinkOption.NOFOLLOW_LINKS))
                assertWhole(aside)
                assertEquals(listOf("a real target directory requires an adopt-target decision"), reasons)
            }
            CrashPoint.LINKED, CrashPoint.MID_DELETE -> {
                assertEquals(target, Files.readSymbolicLink(source))
                assertEquals(listOf("delete-directory"), reasons)
                assertEquals(listOf(ReconciliationAction.DeleteDirectory(aside)), plan().relocations.single().actions)
            }
        }
    }

    /** `apply --json --yes` with a saved `discard` rule recovers from each crash after the source changed. */
    @ParameterizedTest
    @EnumSource(CrashPoint::class, names = ["SOURCE_SET_ASIDE", "LINKED", "MID_DELETE"])
    fun withASavedDiscardRuleRecoveryKeepsTheTarget(crash: CrashPoint) {
        writeConfig(DISCARD_AND_ADOPT)
        crashMigration(crash)

        // Set aside: the first apply links the source, the second deletes what was set aside. Linked: one apply.
        repeat(if (crash == CrashPoint.SOURCE_SET_ASIDE) 2 else 1) {
            assertEquals(0, applyJsonYes(), "apply ${it + 1}")
            assertWhole(target)
        }

        assertEquals(target, Files.readSymbolicLink(source))
        assertTrue(Files.notExists(aside, LinkOption.NOFOLLOW_LINKS))
        assertEquals(listOf(ReconciliationAction.NoOp(source)), plan().relocations.single().actions)
    }

    /** An application may recreate its directory while the original is set aside; neither is touched then. */
    @Test
    fun aRecreatedSourceIsBlockedWhileTheOriginalIsSetAside() {
        writeConfig(DISCARD_AND_ADOPT)
        crashMigration(CrashPoint.SOURCE_SET_ASIDE)
        Files.writeString(Files.createDirectory(source).resolve("fresh"), "fresh")

        assertTrue(plan().hasBlockedActions())
        assertNotEquals(0, applyJsonYes())

        assertWhole(target)
        assertWhole(aside)
        assertEquals("fresh", Files.readString(source.resolve("fresh")))
    }

    /** Only the exact name for this source and this target, beside a link to that target, is deleted. */
    @Test
    fun otherNamesBesideTheLinkAreLeftAlone() {
        writeConfig()
        assertEquals(0, applyJsonYes())
        val others = listOf(
            replacedSourcePath(source, root.resolve("local/elsewhere")),
            replacedSourcePath(source.resolveSibling("other"), target),
            source.resolveSibling(".homelight-replaced-cache"),
        )
        others.forEach { Files.createDirectory(it) }

        assertEquals(listOf(ReconciliationAction.NoOp(source)), plan().relocations.single().actions)
        assertEquals(0, applyJsonYes())

        others.forEach { assertTrue(Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS), it.toString()) }
    }

    /** Runs the migration with a crash at [crash]; MID_DELETE deletes part of what is aside first. */
    private fun crashMigration(crash: CrashPoint) {
        val executor = ReconciliationExecutor(1) { step, path ->
            when {
                crash == CrashPoint.SOURCE_SET_ASIDE && step == ReconciliationExecutor.Step.SOURCE_SET_ASIDE -> throw Crash()
                crash == CrashPoint.LINKED && step == ReconciliationExecutor.Step.LINKED -> throw Crash()
                crash == CrashPoint.MID_DELETE && step == ReconciliationExecutor.Step.LINKED -> {
                    Files.delete(path.resolve("sub/nested"))
                    throw Crash()
                }
            }
        }
        val listener = object : ReconciliationExecutor.ProgressListener {
            override fun started(relocation: RelocationPlan, action: ReconciliationAction) {
                if (crash == CrashPoint.BEFORE_SET_ASIDE && action is ReconciliationAction.ReplaceDirectoryWithSymlink) {
                    throw Crash()
                }
            }
        }
        val plan = plan()
        assertEquals(listOf("migrate-directory-for-publication", "replace-directory-with-symlink"),
            plan.relocations.single().actions.map { it.type })
        assertThrows<Crash> { executor.execute(plan, listener) }
    }

    private fun plan(): ReconciliationPlan = ConfigurationEvaluation().loadRequired(config).plan

    private fun applyJsonYes(): Int {
        val command = homeLightCommand(PrintWriter(StringWriter(), true), PrintWriter(StringWriter(), true))
        return command.execute("apply", "--json", "--yes", "--config", config.toString())
    }

    private fun writeConfig(rules: String = "") {
        Files.writeString(config,
            "{\"homelight\": {\"target-root\": \"$root\", \"relocations\": [\n" +
                "  {\"source-path\": \"$source\", \"target-path\": \"$target\"$rules}\n]}}\n")
    }

    private fun assertWhole(directory: Path) {
        assertEquals("source", Files.readString(directory.resolve("entry")), directory.toString())
        assertEquals("nested", Files.readString(directory.resolve("sub/nested")), directory.toString())
    }

    private class Crash : Error("simulated crash")

    private companion object {
        const val DISCARD_AND_ADOPT =
            ", \"when-source-and-target-directories-exist\": \"discard\", \"when-only-target-exists\": \"adopt-target\""
    }
}
