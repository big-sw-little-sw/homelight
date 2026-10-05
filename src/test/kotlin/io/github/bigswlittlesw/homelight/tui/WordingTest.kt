package io.github.bigswlittlesw.homelight.tui

import io.github.bigswlittlesw.homelight.application.DecisionChoice
import io.github.bigswlittlesw.homelight.application.PlanBadge
import io.github.bigswlittlesw.homelight.config.WhenAdoptingTarget
import io.github.bigswlittlesw.homelight.config.WhenOnlyTargetExists
import io.github.bigswlittlesw.homelight.config.WhenSourceAndTargetDirectoriesExist
import io.github.bigswlittlesw.homelight.discovery.CandidateObservation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Path
import java.time.Instant

class WordingTest {
    @ParameterizedTest
    @CsvSource(
        "PROMPT, PROMPT, Ask each time", "PROMPT, DISCARD_SOURCE, Ask each time",
        "ADOPT, PROMPT, 'Keep target, ask about source'", "ADOPT, DISCARD_SOURCE, 'Keep target, delete source'",
        "ADOPT, ARCHIVE_SOURCE, 'Keep target, archive source'", "LEAVE_UNCHANGED, PROMPT, Leave both as they are",
        "DISCARD, ARCHIVE_SOURCE, 'Delete both, start empty'",
    )
    fun bothExist(both: WhenSourceAndTargetDirectoriesExist, adopting: WhenAdoptingTarget, label: String) {
        assertEquals(label, bothExistLabel(both, adopting))
    }

    @ParameterizedTest
    @CsvSource("PROMPT, Ask each time", "ADOPT, Keep target", "LEAVE_UNCHANGED, Leave both as they are", "DISCARD, 'Delete both, start empty'")
    fun bothDirectories(value: WhenSourceAndTargetDirectoriesExist, label: String) {
        assertEquals(label, bothLabel(value))
    }

    @ParameterizedTest
    @CsvSource("PROMPT, Ask each time", "ADOPT_TARGET, 'Keep target, link source'")
    fun onlyTarget(value: WhenOnlyTargetExists, label: String) {
        assertEquals(label, onlyTargetLabel(value))
    }

    @ParameterizedTest
    @CsvSource("PROMPT, Ask each time", "DISCARD_SOURCE, Delete source", "ARCHIVE_SOURCE, Archive source")
    fun adopting(value: WhenAdoptingTarget, label: String) {
        assertEquals(label, adoptingLabel(value))
    }

    @ParameterizedTest
    @CsvSource(
        "ADOPT_TARGET, 'Keep target, link source'", "ADOPT_AND_DISCARD_SOURCE, 'Keep target, delete source'",
        "ADOPT_AND_ARCHIVE_SOURCE, 'Keep target, archive source'", "LEAVE_UNCHANGED, Leave both as they are",
        "DISCARD_BOTH, 'Delete both, start empty'",
    )
    fun choicesReadAsTheRuleValueTheyStandFor(choice: DecisionChoice, label: String) {
        assertEquals(label, choiceLabel(choice))
    }

    @Test
    fun badgesUseTheDesignVocabulary() {
        assertEquals(
            listOf("Choose", "Blocked", "Can't read", "Warning", "Move", "Keep target", "Link", "Archive", "Delete", "Left as is", "In sync"),
            PlanBadge.entries.map(::badgeLabel),
        )
    }

    @Test
    fun homeShowsAsTilde() {
        val home = Path.of("/home/me")
        assertEquals("~/.cache/uv", displayPath(Path.of("/home/me/.cache/uv"), home))
        assertEquals("~", displayPath(home, home))
        // A sibling that only shares the prefix as text is not under home.
        assertEquals("/home/meadow/.m2", displayPath(Path.of("/home/meadow/.m2"), home))
        assertEquals("/srv/cache", displayPath(Path.of("/srv/cache"), home))
        assertEquals("/srv/cache", displayPath(Path.of("/srv/cache"), Path.of("/")))
    }

    @Test
    fun replaceDialogNamesTheFileAsShownAndSaysCommentsAreLost() {
        assertEquals("Replace ~/.homelight.json?",
            replaceConfigurationTitle(Path.of(System.getProperty("user.home"), ".homelight.json")))
        assertEquals(1, REPLACE_CONFIGURATION_BODY.count { "Comments" in it })
    }

    @Test
    fun browseRowNotesArePlain() {
        val path = Path.of("/home/me/.cache/uv")
        fun note(kind: CandidateObservation.Kind, vararg reasons: CandidateObservation.Reason) = observationNote(
            CandidateObservation(path, kind, null, 1, Instant.EPOCH, reasons.map { CandidateObservation.Diagnostic(path, it, "detail") }),
        )
        assertEquals("checking…", note(CandidateObservation.Kind.PENDING))
        assertEquals("not created yet", note(CandidateObservation.Kind.MISSING))
        assertEquals("already a link", note(CandidateObservation.Kind.LINK))
        assertEquals("not a directory", note(CandidateObservation.Kind.REGULAR_FILE))
        assertEquals("can't read: permission denied", note(CandidateObservation.Kind.INACCESSIBLE, CandidateObservation.Reason.ACCESS_DENIED))
        assertEquals("can't read", note(CandidateObservation.Kind.INACCESSIBLE))
    }

    @Test
    fun theInSyncCountSaysHowToRevealOrHide() {
        assertEquals("Relocations · c: show 1 in sync", relocationsTitle(1, shown = false))
        assertEquals("Relocations · c: hide 12 in sync", relocationsTitle(12, shown = true))
        assertEquals("Relocations", relocationsTitle(0, shown = false))
    }
}
