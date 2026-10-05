package io.github.bigswlittlesw.homelight.tui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class UserGuideTest {
    /** The guide copies the `?` overlay's steps word for word; this fails when either is edited alone. */
    @Test
    fun theGuideHasTheOverlaysFiveSteps() {
        // Gradle runs tests from the project directory.
        val guide = Files.readAllLines(Path.of("docs/user-guide.md"))
        val steps = guide.dropWhile { it != "## How it works" }.drop(1).dropWhile { it.isBlank() }
            .takeWhile { it.isNotBlank() }
        assertEquals(HOW_IT_WORKS.mapIndexed { i, step -> "${i + 1}. $step" }, steps)
    }
}
