package io.github.bigswlittlesw.lighten.tui

import io.github.bigswlittlesw.lighten.application.DecisionChoice
import io.github.bigswlittlesw.lighten.application.PlanBadge
import io.github.bigswlittlesw.lighten.config.WhenAdoptingTarget
import io.github.bigswlittlesw.lighten.config.WhenOnlyTargetExists
import io.github.bigswlittlesw.lighten.config.WhenSourceAndTargetDirectoriesExist
import io.github.bigswlittlesw.lighten.discovery.CandidateObservation
import io.github.bigswlittlesw.lighten.fs.PathState
import io.github.bigswlittlesw.lighten.reconcile.ActionFailure
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
        assertEquals("Replace ~/.lighten.json?",
            replaceConfigurationTitle(Path.of(System.getProperty("user.home"), ".lighten.json")))
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

    /** One sentence per failure kind: what is there, what Lighten expected, and what to do (#172). */
    @Test
    fun failedStepsInPlainWords() {
        val archive = Path.of("/scratch/archive/tool-b")
        val source = Path.of("/home/me/.cache/tool-a")
        val target = Path.of("/scratch/local/tool-a")
        for ((failure, words) in listOf(
            ActionFailure.Drift(archive, PathState.ABSENT, PathState.FILE) to "/scratch/archive/tool-b already exists " +
                "as a file. Lighten expected nothing there. Move or remove it, then press r to check again.",
            ActionFailure.Drift(target, PathState.ABSENT, PathState.DIRECTORY) to "/scratch/local/tool-a already " +
                "exists as a folder. Lighten expected nothing there. Move or remove it, then press r to check again.",
            ActionFailure.Drift(source, PathState.DIRECTORY, PathState.ABSENT) to
                "/home/me/.cache/tool-a no longer exists. Lighten expected a folder there. Press r to check again.",
            ActionFailure.Drift(Path.of("/scratch/archive"), PathState.DIRECTORY, PathState.SYMLINK) to
                "/scratch/archive is a link. Lighten expected a folder there. Press r to check again.",
            ActionFailure.Drift(source, PathState.SYMLINK, PathState.OTHER) to
                "/home/me/.cache/tool-a is a special file. Lighten expected a link there. Press r to check again.",
            ActionFailure.Drift(source, PathState.DIRECTORY, PathState.INACCESSIBLE) to "/home/me/.cache/tool-a " +
                "can't be read. Lighten expected a folder there. Check its permissions, then press r to check again.",
            ActionFailure.LinkChanged(source, target, Path.of("/elsewhere")) to "/home/me/.cache/tool-a now links " +
                "to /elsewhere. Lighten expected it to link to /scratch/local/tool-a. Press r to check again.",
            ActionFailure.StagingElsewhere(Path.of("/scratch/local/.staging"), target) to "The staging folder " +
                "/scratch/local/.staging is not on the same filesystem as /scratch/local/tool-a, so Lighten can't " +
                "move the copy there in one step. Set staging-root in the configuration file to a folder on the " +
                "target's filesystem, then press r to check again.",
            ActionFailure.NoPosixPermissions(Path.of("/mnt/usb")) to "/mnt/usb is on a filesystem without Unix " +
                "permissions, so Lighten can't keep the folder's permissions when it copies it. Use a location on a " +
                "filesystem that has them, then press r to check again.",
            ActionFailure.Busy(target, here = false) to "Another Lighten is moving a folder to /scratch/local/tool-a. " +
                "Wait for it to finish, then press r to check again.",
            ActionFailure.Busy(target, here = true) to
                "Lighten is already moving another folder to /scratch/local/tool-a. Press r to check again.",
            ActionFailure.CopyChanged(source.resolve("index.db")) to "/home/me/.cache/tool-a/index.db changed while " +
                "Lighten was copying it, so Lighten threw the copy away and moved nothing. Close any app that uses " +
                "it, then press r to check again.",
            ActionFailure.PermissionsNotKept(source) to "The copy of /home/me/.cache/tool-a didn't keep its " +
                "permissions, so Lighten threw the copy away and moved nothing. Check that the target's filesystem " +
                "keeps Unix permissions, then press r to check again.",
            ActionFailure.PermissionsNotRestored(target) to "Lighten copied the folder to /scratch/local/tool-a but " +
                "couldn't set the copy's permissions back. The source is still in place. Give /scratch/local/tool-a " +
                "the source's permissions, then press r to check again.",
            ActionFailure.DifferentFilesystems(source, archive) to "/home/me/.cache/tool-a and " +
                "/scratch/archive/tool-b are on different filesystems, so Lighten can't move one to the other in one " +
                "step. Change the configuration so both are on one filesystem, then press r to check again.",
            ActionFailure.AccessDenied(source) to "Lighten isn't allowed to change /home/me/.cache/tool-a. Check its " +
                "owner and permissions, then press r to check again.",
            ActionFailure.Gone(source) to
                "/home/me/.cache/tool-a no longer exists. Lighten expected it there. Press r to check again.",
            ActionFailure.AlreadyExists(target) to "/scratch/local/tool-a already exists. Lighten expected nothing " +
                "there. Move or remove it, then press r to check again.",
            ActionFailure.Io(target, "No space left on device") to "Lighten couldn't change /scratch/local/tool-a: " +
                "no space left on device. Fix that, then press r to check again.",
            ActionFailure.Io(target, null) to "Lighten couldn't change /scratch/local/tool-a. Press r to check again.",
            ActionFailure.Io(null, "Interrupted") to
                "Lighten couldn't read or change a file. See the detail below, then press r to check again.",
        )) {
            assertEquals(words, failureWords(failure), failure.toString())
        }
        assertEquals("Detail: expected absent at $archive but found file",
            failureDetail("expected absent at $archive but found file"))
    }
}
