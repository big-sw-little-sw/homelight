package io.github.bigswlittlesw.lighten.tui

import io.github.bigswlittlesw.lighten.application.DecisionChoice
import io.github.bigswlittlesw.lighten.application.userGuide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Text people follow outside the app uses the words the app shows (#207). */
class WalkthroughWordingTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun theGuideSaysEveryCommandWithoutJsonOpensTheWorkspace() {
        val guide = userGuide().replace(Regex("\\s+"), " ")
        assertFalse(guide.contains("opens the Review screen"), "the guide still says apply opens Review")
        assertTrue(guide.contains("Without `--json`, `status`, `plan` and `apply` open Lighten as `lighten` does"))
    }

    @Test
    fun theSmokeFixturePrintsTheAppsWords() {
        val process = ProcessBuilder("bash", "scripts/setup-smoke-fixture.sh")
            .redirectErrorStream(true)
            .apply { environment()["TMPDIR"] = temporary.toString() }
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue(), output)
        for (shown in listOf(choiceLabel(DecisionChoice.ADOPT_AND_DISCARD_SOURCE), "check again", "[Choose]", "[In sync]")) {
            assertTrue(output.contains(shown), "the walkthrough does not say \"$shown\"")
        }
        for (old in listOf("Adopt target", "opens Status", "opens Plan", "re-plan", "candidate", "Shared")) {
            assertFalse(output.contains(old), "the walkthrough still says \"$old\"")
        }
    }
}
