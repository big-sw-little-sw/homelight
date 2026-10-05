package io.github.bigswlittlesw.homelight.tui

import io.github.bigswlittlesw.homelight.config.WhenAdoptingTarget
import io.github.bigswlittlesw.homelight.config.WhenOnlyTargetExists
import io.github.bigswlittlesw.homelight.config.WhenSourceAndTargetDirectoriesExist
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class PolicyLabelsTest {
    @ParameterizedTest
    @CsvSource("PROMPT, Prompt", "ADOPT, Adopt target", "LEAVE_UNCHANGED, Leave unchanged", "DISCARD, Discard both")
    fun bothDirectories(value: WhenSourceAndTargetDirectoriesExist, label: String) {
        assertEquals(label, bothLabel(value))
    }

    @ParameterizedTest
    @CsvSource("PROMPT, Prompt", "ADOPT_TARGET, Adopt target")
    fun onlyTarget(value: WhenOnlyTargetExists, label: String) {
        assertEquals(label, onlyTargetLabel(value))
    }

    @ParameterizedTest
    @CsvSource("PROMPT, Prompt", "DISCARD_SOURCE, Discard source", "ARCHIVE_SOURCE, Archive source")
    fun adopting(value: WhenAdoptingTarget, label: String) {
        assertEquals(label, adoptingLabel(value))
    }
}
