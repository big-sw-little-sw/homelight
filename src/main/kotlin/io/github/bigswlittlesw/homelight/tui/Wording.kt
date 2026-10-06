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
    PlanBadge.WARNING -> "Warning"
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

// The Help screen (tui-design §3 Help screen). Its Guide tab is docs/user-guide.md, never copied here.
internal const val HELP_TITLE = "Help"
internal const val THIS_SCREEN_TAB = "This screen"
internal const val GUIDE_TAB = "Guide"
internal const val STEP = "Step"
internal fun keysOn(place: String) = "Keys on $place"
internal const val KEYS_LEAD_IN = "They work after you go back (Esc or q). In Help they do nothing."
internal const val MOVE_AROUND = "Move around"
internal const val DO_KEYS = "Do"
internal const val HELP_HINT = "Press ? for help."
internal const val FIRST_RUN_HINT = "New to HomeLight? Press ? to read the guide."

/** The steps of using HomeLight, as "You are here" names them. */
internal fun stepLabel(step: Step): String = when (step) {
    Step.CONFIGURE -> "Configure"
    Step.WORKSPACE -> "Workspace"
    Step.REVIEW -> "Review"
    Step.APPLY -> "Apply"
    Step.RESULTS -> "Results"
}

// Screen names as the header shows them, and what each screen is for in its current state.
internal const val WORKSPACE_NAME = "Workspace"
internal const val REVIEW_NAME = "Review"
internal const val RESULTS_NAME = "Results"
internal const val APPLYING_NAME = "Applying"
internal const val CONFIGURATION_NAME = "Configuration"
internal const val BROWSE_NAME = "Browse"
internal const val PURPOSE_NO_CONFIGURATION =
    "There is no configuration file yet. Press i to create one: say where storage is and which directories to move."
internal const val PURPOSE_INVALID =
    "HomeLight cannot read the configuration file; the message says why. Fix the file, then press r to check again."
internal const val PURPOSE_NO_RELOCATIONS =
    "The configuration lists no directories to move yet. Add them to the configuration file, then press r."
internal const val PURPOSE_WORKSPACE =
    "Each relocation and what HomeLight plans for it. Pick a choice where one is needed, then press a to review."
internal const val PURPOSE_REVIEW =
    "Every step apply will take. Nothing has changed yet: y applies the plan, n goes back without changing anything."
internal const val PURPOSE_NO_CHANGES = "Nothing needs to change. Press 1 or Enter to go back to the Workspace."
internal const val PURPOSE_APPLYING =
    "HomeLight is making the changes. Leave it running until it finishes; Help does not stop it."
internal const val PURPOSE_RESULTS =
    "What apply did, step by step. Press r to check the disk again, or 1 to go back to the Workspace."
internal const val PURPOSE_CONFIGURATION =
    "Create the configuration file: where storage is and which directories to move. Saving changes nothing on disk."
internal const val PURPOSE_BROWSE =
    "Suggestions from the built-in list and your list. Add the ones you want to move."
internal const val DETAILS_NAME = "Details"
internal const val ACTION_DETAILS_NAME = "Action details"
internal const val RELOCATIONS_NAME = "Relocations"
/** Configuration's fields, as its pane labels them; Help names the focused one. */
internal val LOCATION_FIELDS = listOf("Source root", "Target root", "Suggestion list (optional)")
internal val RELOCATION_FIELDS =
    listOf("Source path", "Target path", "Both exist", "Only target", "Source when keeping target", "Archive root")
internal fun place(vararg parts: String) = parts.joinToString(" › ")
/** Where Help goes back to: the screen in the `place` its This screen pane is titled with. */
internal fun backTo(place: String) = "Back to " + place.substringBefore(" › ")

internal val HELP_KEY = KeyHint("?", "Help")
/** Help's key in a text field, where `?` types. */
internal val TEXT_FIELD_HELP_KEY = KeyHint("F1", "Help")
internal val QUIT_KEY =
    KeyHint("q", "Quit", description = "Quit HomeLight; asks first if choices are not applied or changes are running")
internal val CHECK_AGAIN_KEY = KeyHint(
    "r", "Check again", description = "Read the configuration and the disk again and make a new plan; forgets choices",
)
internal val SCROLL_KEY = KeyHint("↑/↓", "Scroll", scrolls = true)
internal val PAGE_KEYS = KeyHint("PageUp/PageDown", "Move a page", inHelpArea = false, description = "Move a page in the list")
internal val HOME_END_KEYS = KeyHint("Home/End", "First/last", inHelpArea = false)
internal val SCROLL_ENDS_KEYS = KeyHint("Home/End", "Top/bottom", inHelpArea = false)
internal val SCROLL_DETAILS_KEYS = KeyHint("[/]", "Scroll details", inHelpArea = false)
