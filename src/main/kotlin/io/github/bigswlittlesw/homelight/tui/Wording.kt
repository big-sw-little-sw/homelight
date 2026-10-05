package io.github.bigswlittlesw.homelight.tui

import io.github.bigswlittlesw.homelight.application.DecisionChoice
import io.github.bigswlittlesw.homelight.application.PlanBadge
import io.github.bigswlittlesw.homelight.config.WhenAdoptingTarget
import io.github.bigswlittlesw.homelight.config.WhenOnlyTargetExists
import io.github.bigswlittlesw.homelight.config.WhenSourceAndTargetDirectoriesExist
import io.github.bigswlittlesw.homelight.discovery.CandidateObservation
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction
import java.nio.file.Path

// The TUI's words for application values and the messages its screens show (tui-design §9). Application enums keep
// meaning only. Key hints stay in each screen's `keys` function, beside the handlers that bind them, so a hint and its
// key cannot drift apart; only keys several screens share are here.

internal fun badgeLabel(badge: PlanBadge): String = when (badge) {
    PlanBadge.CONFLICT -> "Choose"
    PlanBadge.BLOCKED -> "Blocked"
    PlanBadge.INACCESSIBLE -> "Can't read"
    PlanBadge.WARNING -> "Check"
    PlanBadge.MIGRATE -> "Move"
    PlanBadge.ADOPT -> "Keep target"
    PlanBadge.LINK -> "Link"
    PlanBadge.BACKUP -> "Archive"
    PlanBadge.DISCARD -> "Delete"
    PlanBadge.SKIPPED -> "Left as is"
    PlanBadge.IN_SYNC -> "In sync"
}

/** A one-time choice reads as the **Both exist** or **Only target** rule value it stands for. */
internal fun choiceLabel(choice: DecisionChoice): String = when (choice) {
    DecisionChoice.ADOPT_TARGET -> onlyTargetLabel(WhenOnlyTargetExists.ADOPT_TARGET)
    DecisionChoice.ADOPT_AND_DISCARD_SOURCE ->
        bothExistLabel(WhenSourceAndTargetDirectoriesExist.ADOPT, WhenAdoptingTarget.DISCARD_SOURCE)
    DecisionChoice.ADOPT_AND_ARCHIVE_SOURCE ->
        bothExistLabel(WhenSourceAndTargetDirectoriesExist.ADOPT, WhenAdoptingTarget.ARCHIVE_SOURCE)
    DecisionChoice.LEAVE_UNCHANGED -> bothLabel(WhenSourceAndTargetDirectoriesExist.LEAVE_UNCHANGED)
    DecisionChoice.DISCARD_BOTH -> bothLabel(WhenSourceAndTargetDirectoriesExist.DISCARD)
}

/** `archive` names where archiving would move the source, when the choice only offers it (#107). */
internal fun choiceDescription(choice: DecisionChoice, archive: Path? = null): String = when (choice) {
    DecisionChoice.ADOPT_TARGET -> "Use the existing target and put a link to it at the source."
    DecisionChoice.ADOPT_AND_DISCARD_SOURCE ->
        "Keep the target's contents. Delete the source and replace it with a link to the target."
    DecisionChoice.ADOPT_AND_ARCHIVE_SOURCE ->
        "Keep the target's contents. Move the source to " + (archive?.let(::displayPath) ?: "the archive") +
            " and replace it with a link to the target."
    DecisionChoice.LEAVE_UNCHANGED -> "Change nothing. Source and target stay as they are."
    DecisionChoice.DISCARD_BOTH -> "Delete the contents of both, then create an empty target and link the source to it."
}

// A missing rule is "prompt", so it reads Ask each time too (decision "A missing rule means Ask each time").

/** The **Both exist** rule as one outcome: the file keeps it in two fields. */
internal fun bothExistLabel(both: WhenSourceAndTargetDirectoriesExist, adopting: WhenAdoptingTarget): String =
    if (both != WhenSourceAndTargetDirectoriesExist.ADOPT) bothLabel(both)
    else when (adopting) {
        WhenAdoptingTarget.PROMPT -> "Keep target, ask about source"
        WhenAdoptingTarget.DISCARD_SOURCE -> "Keep target, delete source"
        WhenAdoptingTarget.ARCHIVE_SOURCE -> "Keep target, archive source"
    }

internal fun bothLabel(value: WhenSourceAndTargetDirectoriesExist): String = when (value) {
    WhenSourceAndTargetDirectoriesExist.PROMPT -> ASK_EACH_TIME
    WhenSourceAndTargetDirectoriesExist.ADOPT -> "Keep target"
    WhenSourceAndTargetDirectoriesExist.LEAVE_UNCHANGED -> "Leave both as they are"
    WhenSourceAndTargetDirectoriesExist.DISCARD -> "Delete both, start empty"
}

internal fun onlyTargetLabel(value: WhenOnlyTargetExists): String = when (value) {
    WhenOnlyTargetExists.PROMPT -> ASK_EACH_TIME
    WhenOnlyTargetExists.ADOPT_TARGET -> "Keep target, link source"
}

internal fun adoptingLabel(value: WhenAdoptingTarget): String = when (value) {
    WhenAdoptingTarget.PROMPT -> ASK_EACH_TIME
    WhenAdoptingTarget.DISCARD_SOURCE -> "Delete source"
    WhenAdoptingTarget.ARCHIVE_SOURCE -> "Archive source"
}

internal const val ASK_EACH_TIME = "Ask each time"

internal fun actionLabel(action: ReconciliationAction): String = when (action) {
    is ReconciliationAction.EnsureDirectory -> "Create parent folder"
    is ReconciliationAction.CreateDirectory -> "Create target folder"
    is ReconciliationAction.MigrateDirectoryForPublication -> "Copy to target and check"
    is ReconciliationAction.ReplaceDirectoryWithSymlink -> "Replace source with a link"
    is ReconciliationAction.CreateSymlink -> "Link source to target"
    is ReconciliationAction.ReplaceSymlink -> "Fix source link"
    is ReconciliationAction.ArchiveDirectory -> "Archive source"
    is ReconciliationAction.DeleteDirectory -> "Delete folder"
    is ReconciliationAction.NoOp -> "Already in sync"
    is ReconciliationAction.LeaveUnchanged -> "Leave as is"
    is ReconciliationAction.Blocked -> "Blocked"
}

/** A Browse row note (tui-design §8), also the state line in its details. */
internal fun observationNote(observation: CandidateObservation): String = when (observation.kind) {
    CandidateObservation.Kind.PENDING -> "checking…"
    CandidateObservation.Kind.DIRECTORY -> "directory"
    CandidateObservation.Kind.LINK -> "already a link"
    CandidateObservation.Kind.MISSING -> "not created yet"
    CandidateObservation.Kind.REGULAR_FILE, CandidateObservation.Kind.OTHER -> "not a directory"
    CandidateObservation.Kind.INACCESSIBLE ->
        "can't read" + (observation.diagnostics.firstOrNull()?.let { ": " + reasonWords(it.reason) } ?: "")
    CandidateObservation.Kind.BLOCKED_BY_LINK -> "a parent is a link"
    CandidateObservation.Kind.BLOCKED_BY_NON_DIRECTORY -> "a parent is not a directory"
    CandidateObservation.Kind.UNKNOWN -> "couldn't check"
}

private fun reasonWords(reason: CandidateObservation.Reason): String = when (reason) {
    CandidateObservation.Reason.ACCESS_DENIED -> "permission denied"
    CandidateObservation.Reason.IO_ERROR -> "read error"
    CandidateObservation.Reason.CHANGED -> "it changed while checking"
    CandidateObservation.Reason.DEADLINE -> "took too long"
    CandidateObservation.Reason.SYMLINK_EXCLUDED -> "it is a link"
    CandidateObservation.Reason.ALIAS_UNCERTAINTY -> "its real location is unclear"
    CandidateObservation.Reason.NOT_DIRECTORY -> "not a directory"
    CandidateObservation.Reason.MISSING -> "missing"
}

/**
 * `path` with the home directory shown as `~` (tui-design §4). Paths sections in details show the absolute path
 * instead, so they do not call this.
 */
internal fun displayPath(path: Path, home: Path = Path.of(System.getProperty("user.home"))): String =
    if (path.startsWith(home) && home.nameCount > 0) "~" + path.toString().substring(home.toString().length)
    else path.toString()

internal const val QUIT_TITLE = "Quit HomeLight?"
internal const val RESULTS_NOT_KEPT = "Results on this screen are not kept after you exit."
internal val QUIT_BODY = listOf("HomeLight finishes the changes it is making first, even if one fails.", RESULTS_NOT_KEPT)
internal const val QUIT_KEYS = "y: Exit when it finishes · n/Esc: Keep running"
internal const val QUIT_CHOICES_KEYS = "y: Quit · n/Esc: Go back"
internal fun unappliedChoices(n: Int): List<String> = listOf(
    if (n == 1) "You have 1 choice that is not applied yet. Quitting forgets it."
    else "You have $n choices that are not applied yet. Quitting forgets them.",
    "Press n to go back. You can keep choosing, or press a to review and apply.",
)
internal const val QUITTING = "HomeLight will exit when the changes finish."

internal const val DISCARD_SETUP_TITLE = "Discard this configuration?"
internal val DISCARD_SETUP_BODY = listOf("Nothing has been saved yet.", "Your storage locations and relocations will be lost.")
internal const val DISCARD_SETUP_KEYS = "y: Discard · n/Esc: Keep editing"

/** Asked before saving over an existing configuration (tui-design §7); a new file is created without asking. */
internal fun replaceConfigurationTitle(path: Path) = "Replace ${displayPath(path)}?"
internal val REPLACE_CONFIGURATION_BODY = listOf("HomeLight rewrites the whole file.", "Comments in it are not kept.")
internal const val REPLACE_CONFIGURATION_KEYS = "y: Replace · n/Esc: Keep editing"

internal const val NO_CONFIGURATION = "No configuration file yet. Press i to create one; nothing is written until you save."
internal const val NO_RELOCATIONS = "No relocations in the configuration."
internal const val CHOOSE_TO_REVIEW = "Choose what to do for each relocation marked Choose."
internal const val FIX_TO_REVIEW = "Fix the blocked paths; see Details."
internal const val DELETES_OR_REPLACES = "⚠ This deletes or replaces existing data."
internal const val RESULTS_KEPT = "The last apply's results are on 2: Results. Check again (r) before choosing."
internal const val DELETES_DATA = "⚠ This deletes data for good."
internal const val LEFT_BEHIND_DELETED =
    "delete the original source an interrupted replacement left behind (under Paths); the link stays."

/** A blocked row's problem, in the planner's words (they are shared with JSON output). */
internal fun problem(reason: String) = "Problem: $reason."

internal fun relocationCount(n: Int) = "$n " + if (n == 1) "relocation" else "relocations"
internal fun toChange(n: Int) = "⚡ $n to change"
internal fun needChoice(n: Int) = "⚠ $n " + if (n == 1) "needs a choice" else "need a choice"
internal fun blockedCount(n: Int) = "✖ $n blocked"
internal fun inSyncCount(n: Int) = "✔ $n in sync"
internal fun leftAsIsCount(n: Int) = "─ $n left as is"
internal fun deletesOrReplaces(n: Int) = "$n " + if (n == 1) "deletes or replaces data" else "delete or replace data"
internal fun risks(warnings: Int, deleting: Int) =
    "Of these: $warnings with warnings · $deleting " + if (deleting == 1) "deletes data" else "delete data"

/** The one Details line that says what decides a row and where that comes from (tui-design §5). */
internal fun ruleDecision(rule: String) = "Decision: " + rule.lowercase() + " (your configuration)"
internal fun choiceDecision(choice: DecisionChoice) =
    "Decision: " + choiceLabel(choice).lowercase() + " (your choice, this run only)"

/** The Workspace list title: the in-sync rows `c` hides or shows, or none when `c` would change nothing. */
internal fun relocationsTitle(inSync: Int, shown: Boolean): String = when {
    inSync == 0 -> "Relocations"
    shown -> "Relocations · c: hide $inSync in sync"
    else -> "Relocations · c: show $inSync in sync"
}

internal const val REVIEW_LIST_TITLE = "Plan"
internal const val NOTHING_TO_REVIEW = "Nothing to review yet. Choose what to do on Workspace first."
internal const val NO_CHANGES = "No changes to apply."
internal const val CONFIRM = "Nothing has changed yet. Press y to apply this plan."
internal const val CONFIRM_DESTRUCTIVE = "Nothing has changed yet. Some steps delete or replace data for good."
internal const val APPLYING = "Applying. Leave HomeLight running until it finishes."
internal const val REFUSED = "Nothing changed: the disk no longer matches the reviewed plan. Check again."
internal const val STALE = "Stopped: the disk changed while applying. Check the failed and not-run steps, then check again."
internal const val DONE = "Done. Checked again; results are kept until you check again."
internal const val WORKER_STOPPED =
    "Stopped unexpectedly; some changes may have been made. Check the steps, then check again."
internal const val STOPPED = "Stopped after some changes. Check the failed and not-run steps, then check again."
internal const val NO_STEPS = "Nothing to do."
internal fun inSyncRow(path: String) = "$path (in sync)"
internal fun runningCount(done: Int, changes: Int, running: Int, failed: Int) =
    "$done of $changes changes done · $running running · $failed failed"
internal fun finishedCount(done: Int, changes: Int, failed: Int, notRun: Int) =
    "$done of $changes changes done · $failed failed · $notRun not run"
internal fun plannedChanges(changes: Int, destructive: Int) = "$changes planned changes · " + deletesOrReplaces(destructive)

// The `?` overlay (tui-design §3 Help). docs/user-guide.md copies HOW_IT_WORKS word for word; UserGuideTest checks it.
internal const val HELP_TITLE = "Help"
internal const val HOW_IT_WORKS_TITLE = "How it works"
internal val HOW_IT_WORKS = listOf(
    "Setup: say where storage is.",
    "Workspace: see what HomeLight found and what it plans for each directory.",
    "Pick a choice for anything marked as needing one, or leave the plan as is.",
    "Press a to review every change. Nothing changes until you press y.",
    "Apply runs the changes and shows the results. Press r to check again.",
)
internal const val SCREEN_KEYS_TITLE = "Keys on this screen"
internal const val KEY_IDEAS_TITLE = "Key ideas"
internal val KEY_IDEAS = listOf(
    "Rule or one-time choice" to
        "A rule is saved in your configuration and decides every run. A one-time choice decides one relocation for " +
        "the next apply only. Checking again, saving or applying forgets it.",
    "Archive or delete" to
        "Archive moves the source into an archive folder, shown under Paths, so you can move it back. " +
        "Delete removes it for good.",
    "Check again" to
        "HomeLight looks at the disk again and makes a new plan. Do it after you change files outside HomeLight.",
)
internal const val HELP_HINT = "Press ? for help."

internal val HELP_KEY = KeyHint("?", "Help")
internal val QUIT_KEY = KeyHint("q", "Quit")
internal val CHECK_AGAIN_KEY = KeyHint("r", "Check again")
internal val SCROLL_KEY = KeyHint("↑/↓", "Scroll")
internal val PAGE_KEYS = KeyHint("PageUp/PageDown", "Move a page", inHelpArea = false)
internal val HOME_END_KEYS = KeyHint("Home/End", "First/last", inHelpArea = false)
internal val SCROLL_ENDS_KEYS = KeyHint("Home/End", "Top/bottom", inHelpArea = false)
internal val SCROLL_DETAILS_KEYS = KeyHint("[/]", "Scroll details", inHelpArea = false)
internal val HELP_DIALOG_KEYS = ScreenKeys(listOf(SCROLL_KEY, KeyHint("?/Esc", "Close")), listOf())
