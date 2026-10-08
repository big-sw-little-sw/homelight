package io.github.bigswlittlesw.lighten.tui

import io.github.bigswlittlesw.lighten.application.DecisionChoice
import io.github.bigswlittlesw.lighten.application.PlanBadge
import io.github.bigswlittlesw.lighten.config.ConfigurationLoader
import io.github.bigswlittlesw.lighten.config.WhenAdoptingTarget
import io.github.bigswlittlesw.lighten.config.WhenOnlyTargetExists
import io.github.bigswlittlesw.lighten.config.WhenSourceAndTargetDirectoriesExist
import io.github.bigswlittlesw.lighten.discovery.CandidateObservation
import io.github.bigswlittlesw.lighten.fs.PathState
import io.github.bigswlittlesw.lighten.reconcile.ActionFailure
import io.github.bigswlittlesw.lighten.reconcile.CopyDifference
import io.github.bigswlittlesw.lighten.reconcile.ReconciliationAction
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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

internal const val QUIT_TITLE = "Quit Lighten?"
internal const val RESULTS_NOT_KEPT = "Results on this screen are not kept after you exit."
internal val QUIT_BODY = listOf("Lighten finishes the changes it is making first, even if one fails.", RESULTS_NOT_KEPT)
internal const val QUIT_KEYS = "y: Exit when it finishes · n/Esc: Keep running"
internal const val QUIT_CHOICES_KEYS = "y: Quit · n/Esc: Go back"
internal fun unappliedChoices(n: Int): List<String> = listOf(
    if (n == 1) "You have 1 choice that is not applied yet. Quitting forgets it."
    else "You have $n choices that are not applied yet. Quitting forgets them.",
    "Press n to go back. You can keep choosing, or press a to review and apply.",
    "To make a choice the rule, select its relocation and press s.",
)
internal const val QUITTING = "Lighten will exit when the changes finish."

// Configuration (tui-design §7).
internal const val DISCARD_SETUP_TITLE = "Discard this configuration?"
internal val DISCARD_SETUP_BODY = listOf("Nothing has been saved yet.", "Your storage locations and relocations will be lost.")
internal const val DISCARD_SETUP_KEYS = "y: Discard · n/Esc: Keep editing"
/** Asked before closing Configuration over an existing file with unsaved changes. */
internal const val DISCARD_CHANGES_TITLE = "Discard your changes?"
internal fun discardChangesBody(n: Int) =
    listOf("The configuration file stays as it is.", "Your " + unsavedChanges(n) + " will be lost.")
internal fun unsavedChanges(n: Int) = when (n) {
    0 -> "no unsaved changes"
    1 -> "1 unsaved change"
    else -> "$n unsaved changes"
}
/** The line under Configuration's header: which file, whether it exists yet, and what is not saved. */
internal fun configurationStatus(path: Path, existing: Boolean, unsaved: Int) =
    displayPath(path) + " · " + (if (existing) "existing file" else "new file") + " · " + unsavedChanges(unsaved)
internal const val CONFIGURATION_LIST_TITLE = "Storage and relocations"
internal const val STORAGE_LOCATIONS = "Storage locations"
internal const val NEW_RELOCATION = "New relocation"
internal const val RESOLVED = "Resolved"
internal const val TARGET_PLACEHOLDER = "blank: under the target root"
internal const val ARCHIVE_PLACEHOLDER = "blank: beside the source"
internal const val OPTIONAL_PLACEHOLDER = "optional"
internal const val SAVE_FROM_TEXT_FIELD = "Esc, then s to save."
internal const val NO_SUGGESTION_LIST = "none; Browse uses the built-in list"
internal const val NEEDS_SOURCE = "waits for a valid Source"
internal const val NEEDS_ROOTS = "waits for valid storage locations"
internal const val OUTSIDE_SOURCE_ROOT = "the source is outside the source root, so type a Target"
internal fun resolvedLine(label: String, value: String) = "$label: $value"

// What the focused field means, shown under the fields.
internal const val SOURCE_ROOT_HELP =
    "The folder your sources are usually in, normally your home folder. A relocation with no Target keeps its " +
        "place under this folder, inside the target root."
internal const val TARGET_ROOT_HELP = "Where storage is, for example a larger disk. Use a full path, or one starting with ~/."
internal const val SUGGESTION_LIST_HELP =
    "A file of directories to suggest in Browse, for example one shared on a team drive. Built-in suggestions are " +
        "always included."
internal const val SOURCE_HELP = "The directory to move, for example ~/.cache/uv."
internal const val TARGET_HELP =
    "Where its contents go. Leave it blank for the same place under the target root; a source outside the source " +
        "root needs one."
internal const val ONLY_TARGET_HELP = "What to do when the target exists and the source does not."
internal const val ARCHIVE_ROOT_HELP =
    "Where Keep target, archive source moves the source. Leave it blank for a folder beside the source; it must " +
        "be on the same disk as the source."
internal const val BOTH_EXIST_HELP = "What to do when the source and the target both exist."
internal const val DISCARD_BOTH_WARNING =
    "⚠ Deletes both directories for good, then creates an empty target and links the source to it."

// Saving Configuration.
internal fun notSaved(reason: String) = "Not saved: $reason"
internal fun notSavedIn(place: String, reason: String) = "Not saved. $place: $reason"
internal const val CHANGED_SINCE_LOADED =
    "Not saved: the configuration file changed after Configuration opened it. Your changes are still here. " +
        "To start again from the file, press q, then y, then e."
internal fun cannotOpen(reason: String) = "Cannot open Configuration: $reason"

// A configuration file Lighten can't read (#164). The Workspace, its Help purpose and the CLI say the same; only the
// way to check again differs. `positioned`: the problem names a line, not a setting such as a missing key.
internal fun unreadable(path: Path, problem: String, positioned: Boolean) = listOf(
    cannotRead(displayPath(path)), problem,
    "To fix it: open the file in a text editor, ${correct(positioned)}, then press r to check again.",
    "To start over: rename or delete the file, then press r. Lighten then offers i to create a new one.",
)
/** For the CLI's stderr; `init` creates a new file, as `i` does. */
internal fun unreadableCli(path: Path, problem: String, positioned: Boolean) = listOf(
    cannotRead(path.toString()), problem,
    "To fix it: open the file in a text editor, ${correct(positioned)}, then run the command again.",
    "To start over: rename or delete the file, then run lighten init" +
        (if (path == ConfigurationLoader.DEFAULT_PATH) "" else " --config $path") + " to create a new one.",
)
private fun cannotRead(path: String) = "Lighten can't read $path"
private fun correct(positioned: Boolean) = if (positioned) "correct that line" else "correct that setting"
internal fun cannotBrowse(reason: String) = "Fix the storage locations to browse: $reason"
/** What the Workspace says after a save, once it has checked again (tui-design §1, Say the next step). */
internal fun savedNextStep(toChange: Int, needChoice: Int, blocked: Int): String = when {
    blocked > 0 -> "Saved. $FIX_TO_REVIEW"
    needChoice > 0 -> "Saved. " + relocationCount(needChoice) + (if (needChoice == 1) " needs" else " need") +
        " a choice: select it and press Tab."
    toChange > 0 -> "Saved. " + relocationCount(toChange) + " will change: press a to review and apply."
    else -> "Saved. Nothing needs to change."
}
internal const val SAVED = "Saved."

/** Asked before saving over an existing configuration (tui-design §7); a new file is created without asking. */
internal fun replaceConfigurationTitle(path: Path) = "Replace ${displayPath(path)}?"
internal val REPLACE_CONFIGURATION_BODY = listOf("Lighten rewrites the whole file.", "Comments in it are not kept.")
internal const val REPLACE_CONFIGURATION_KEYS = "y: Replace · n/Esc: Keep editing"

// Saving a Workspace choice as the rule (tui-design §5).
internal const val ALWAYS_DO_THIS_TITLE = "Always do this?"
/** What `s` saves, for which relocation and where, in the case the choice is for. */
internal fun alwaysDoThis(source: Path, choice: DecisionChoice, config: Path): List<String> = listOf(
    "From now on, for " + displayPath(source) + ",",
    ruleCase(choice) + ": " + choiceLabel(choice).lowercase() + ".",
    "",
    "y saves this rule in " + displayPath(config) + ".",
) + REPLACE_CONFIGURATION_BODY + "Nothing on disk changes until you apply."
private fun ruleCase(choice: DecisionChoice): String = when (choice) {
    DecisionChoice.ADOPT_TARGET -> "when only the target exists"
    DecisionChoice.ADOPT_AND_DISCARD_SOURCE, DecisionChoice.ADOPT_AND_ARCHIVE_SOURCE, DecisionChoice.LEAVE_UNCHANGED,
    DecisionChoice.DISCARD_BOTH -> "when the source and the target both exist"
}
/**
 * The warning for a rule that deletes data not kept elsewhere (tui-design §5), or none. Once saved, it applies
 * without asking, `apply --yes` included.
 */
internal fun alwaysDoThisWarning(choice: DecisionChoice): List<String> = when (choice) {
    DecisionChoice.ADOPT_AND_DISCARD_SOURCE -> listOf(
        "⚠ This rule deletes the source's contents for good whenever it applies,",
        "  including with lighten apply --yes.",
    )
    DecisionChoice.DISCARD_BOTH -> listOf(
        "⚠ This rule deletes the contents of both source and target for good",
        "  whenever it applies, including with lighten apply --yes.",
    )
    DecisionChoice.ADOPT_TARGET, DecisionChoice.ADOPT_AND_ARCHIVE_SOURCE, DecisionChoice.LEAVE_UNCHANGED -> listOf()
}
internal const val ALWAYS_DO_THIS_KEYS = "y: Save rule · n/Esc: Cancel"
/** [CHANGED_SINCE_LOADED] for the Workspace, where q, y, e would quit rather than reopen the file. */
internal const val CHOICE_NOT_SAVED =
    "Not saved: the configuration file changed after Lighten read it. Your choice is still here. Press r to " +
        "read the file again; that forgets the choice."

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
internal const val CHOOSE_AROUND_FOLDER = "Or choose an option below that doesn't need this folder."

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

// The Paths section of Workspace and Review Details.
internal const val PATHS = "Paths"
internal fun sourceLine(path: Path) = "Source: $path"
internal fun targetLine(path: Path) = "Target: $path"
internal fun archiveLine(path: Path) = "Archive: $path"

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
internal const val APPLYING = "Applying. Leave Lighten running until it finishes."
internal const val REFUSED = "Nothing changed: the disk no longer matches the reviewed plan. Check again."
internal const val STALE = "Stopped: a step found something different from the plan. The steps after it did not run. " +
    "See the failed step's details, then press r to check again."
internal const val DONE = "Done. Checked again; results are kept until you check again."

/**
 * A failed step in plain words: what is at the path, what Lighten expected, and what to do about it. Results' headline
 * already says to press r, so the sentence does not. Paths show home as `~`; `config` is the configuration file in
 * use.
 *
 * It is the only text Results show for the failure (user decision), so it keeps every specific of the executor's own
 * text: each path it named, what was expected and found, and the system's reason.
 */
internal fun failureWords(failure: ActionFailure, config: Path): String = when (failure) {
    is ActionFailure.Drift -> driftWords(failure)
    is ActionFailure.LinkChanged ->
        (failure.found?.let { "${shown(failure.path)} now links to ${shown(it)}." } ?: "${shown(failure.path)} has changed.") +
            " Lighten expected it to link to ${shown(failure.expected)}."
    is ActionFailure.StagingElsewhere ->
        "The staging folder ${shown(failure.stagingRoot)} is not on the same filesystem as ${shown(failure.target)}, " +
            "so Lighten can't move the copy there in one step. Set the staging-root setting in ${shown(config)} to a " +
            "folder on the target's filesystem."
    is ActionFailure.NoPosixPermissions ->
        "${shown(failure.path)} is on a filesystem without Unix permissions, so Lighten can't keep the folder's " +
            "permissions when it copies it. Use a location on a filesystem that has them."
    is ActionFailure.Busy ->
        if (failure.here) "Lighten is already moving another folder to ${shown(failure.target)}."
        else "Another Lighten is moving a folder to ${shown(failure.target)}. Wait for it to finish."
    is ActionFailure.CopyChanged ->
        "${shown(failure.entry)} changed while Lighten was copying it (${copyDifference(failure.difference)}), so " +
            "Lighten threw the copy away and moved nothing. Close any app that uses it."
    is ActionFailure.PermissionsNotKept ->
        "The copy of ${shown(failure.entry)} didn't keep its permissions, so Lighten threw the copy away and moved " +
            "nothing. Check that the target's filesystem keeps Unix permissions."
    is ActionFailure.PermissionsNotRestored ->
        "Lighten copied the folder to ${shown(failure.target)} but couldn't set the copy's permissions back: " +
            "${lowerFirst(failure.reason)}. The source is still in place. Give ${shown(failure.target)} the source's " +
            "permissions."
    is ActionFailure.DifferentFilesystems ->
        "${shown(failure.from)} and ${shown(failure.to)} are on different filesystems, so Lighten can't move one to " +
            "the other in one step. Change the configuration so both are on one filesystem."
    is ActionFailure.AccessDenied -> "Lighten isn't allowed to change ${shown(failure.path)}. Check its owner and permissions."
    is ActionFailure.Gone -> "${shown(failure.path)} no longer exists. Lighten expected it there."
    is ActionFailure.AlreadyExists -> "${shown(failure.path)} already exists. Lighten expected nothing there. Move or remove it."
    is ActionFailure.Io -> {
        val path = failure.path
        val other = failure.other
        when {
            path == null -> "Lighten couldn't read or change a file"
            other == null -> "Lighten couldn't change ${shown(path)}"
            else -> "Lighten couldn't move or copy ${shown(path)} to ${shown(other)}"
        } + ": ${lowerFirst(failure.reason)}."
    }
}

private fun shown(path: Path) = displayPath(path)

/** The system's reason mid-sentence: "No space left on device" reads "no space left on device". */
private fun lowerFirst(reason: String) = reason.replaceFirstChar { it.lowercase() }

private fun copyDifference(difference: CopyDifference): String = when (difference) {
    CopyDifference.MISSING_FOLDER -> "the folder is missing from the copy"
    CopyDifference.FILE_DIFFERS -> "the copied file doesn't match"
    CopyDifference.LINK_DIFFERS -> "the copied link points elsewhere"
    CopyDifference.EXTRA_ENTRY -> "the copy has it, but the source no longer does"
}

/**
 * Says what is there before what Lighten expected; only something in the way of a step is the user's to move, so only
 * that asks them to move or remove it.
 */
private fun driftWords(drift: ActionFailure.Drift): String {
    val path = shown(drift.path)
    val found = when (drift.found) {
        PathState.ABSENT -> "$path no longer exists."
        PathState.INACCESSIBLE -> "$path can't be read."
        PathState.FILE, PathState.DIRECTORY, PathState.SYMLINK, PathState.OTHER ->
            if (drift.expected == PathState.ABSENT) "$path already exists as ${thing(drift.found)}."
            else "$path is ${thing(drift.found)}."
    }
    val fix = when {
        drift.found == PathState.INACCESSIBLE -> " Check its permissions."
        drift.expected == PathState.ABSENT -> " Move or remove it."
        else -> ""
    }
    return "$found Lighten expected ${thing(drift.expected)} there.$fix"
}

private fun thing(state: PathState): String = when (state) {
    PathState.ABSENT -> "nothing"
    PathState.FILE -> "a file"
    PathState.DIRECTORY -> "a folder"
    PathState.SYMLINK -> "a link"
    PathState.OTHER -> "a special file"
    PathState.INACCESSIBLE -> "something it can't read"
}

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
internal const val FIRST_RUN_HINT = "New to Lighten? Press ? to read the guide."

/** The steps of using Lighten, as "You are here" names them. */
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
internal const val PURPOSE_NO_RELOCATIONS =
    "The configuration lists no directories to move yet. Press e to open Configuration and add them."
internal const val PURPOSE_WORKSPACE =
    "Each relocation and what Lighten plans for it. Pick a choice where one is needed, then press a to review."
internal const val PURPOSE_REVIEW =
    "Every step apply will take. Nothing has changed yet: y applies the plan, n goes back without changing anything."
internal const val PURPOSE_NO_CHANGES = "Nothing needs to change. Press 1 or Enter to go back to the Workspace."
internal const val PURPOSE_APPLYING =
    "Lighten is making the changes. Leave it running until it finishes; Help does not stop it."
internal const val PURPOSE_RESULTS =
    "What apply did, step by step. Press r to check the disk again, or 1 to go back to the Workspace."
internal const val PURPOSE_CONFIGURATION =
    "Create or change the configuration file: where storage is and which directories to move. Saving changes " +
        "nothing on disk."
internal const val PURPOSE_BROWSE =
    "Suggestions from the built-in list and your list. Space adds a directory to the configuration or takes it out; " +
        "the file changes only when you save."
internal const val DETAILS_NAME = "Details"
internal const val SUGGESTION_LISTS = "Suggestion lists"
// Configuration's fields, as its pane labels them; Help names the focused one.
internal const val SOURCE_ROOT_LABEL = "Source root"
internal const val TARGET_ROOT_LABEL = "Target root"
internal const val SUGGESTION_LIST_NAME = "Suggestion list"
internal const val SOURCE_LABEL = "Source"
internal const val TARGET_LABEL = "Target"
internal const val BOTH_EXIST_LABEL = "Both exist"
internal const val ONLY_TARGET_LABEL = "Only target"
internal const val ARCHIVE_ROOT_LABEL = "Archive root"
internal fun place(vararg parts: String) = parts.joinToString(" › ")
/** Where Help goes back to: the screen in the `place` its This screen pane is titled with. */
internal fun backTo(place: String) = "Back to " + place.substringBefore(" › ")

internal val HELP_KEY = KeyHint("?", "Help")
/** Help's key in a text field, where `?` types. */
internal val TEXT_FIELD_HELP_KEY = KeyHint("F1", "Help")
internal val QUIT_KEY =
    KeyHint("q", "Quit", description = "Quit Lighten; asks first if choices are not applied or changes are running")
internal val CHECK_AGAIN_KEY = KeyHint(
    "r", "Check again", description = "Read the configuration and the disk again and make a new plan; forgets choices",
)
internal val CLOSE_CONFIGURATION_KEY =
    KeyHint("Esc/q", "Close", description = "Close Configuration; asks first if there are unsaved changes")
internal val SCROLL_KEY = KeyHint("↑/↓", "Scroll", scrolls = true)
internal val PAGE_KEYS = KeyHint("PageUp/PageDown", "Move a page", inHelpArea = false, description = "Move a page in the list")
internal val HOME_END_KEYS = KeyHint("Home/End", "First/last", inHelpArea = false)
internal val SCROLL_ENDS_KEYS = KeyHint("Home/End", "Top/bottom", inHelpArea = false)
internal val SCROLL_DETAILS_KEYS = KeyHint("[/]", "Scroll details", inHelpArea = false)

// Browse (tui-design §8). The feature is the suggestion list: the built-in list and your list.
internal const val BUILT_IN_LIST = "Built-in list"
internal const val YOUR_LIST = "Your list"
internal const val NO_LIST_OF_YOUR_OWN = "Your list · none; set one in Storage locations"
internal const val LIST_CHECKING = "checking…"
/** A Lists line: the list's name, then its location and state, as far as they are known. */
internal fun listSummary(vararg parts: String?) = parts.filterNotNull().joinToString(" · ")
internal fun suggestionCount(n: Int) = if (n == 1) "1 suggestion" else "$n suggestions"
private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH).withZone(ZoneId.systemDefault())
internal fun fileUpdated(time: Instant) = "file updated " + DAY_MONTH.format(time)
internal fun listNotUsed(reason: String) = "not used: $reason"
internal fun listErrors(n: Int) = if (n == 1) "1 error in the file" else "$n errors in the file"
internal const val LIST_MISSING = "file not found"
internal const val LIST_UNREADABLE = "permission denied"
internal const val LIST_NOT_REGULAR = "not a regular file"
internal const val LIST_IO_ERROR = "read error"
internal const val LIST_DEADLINE = "no answer within 5 seconds"
internal const val LIST_PREVIOUS_PENDING = "an earlier read is still waiting"
internal const val LIST_MISSING_ADVICE = "The list was not found. Check its location or clear the optional field."
internal const val LIST_UNREADABLE_ADVICE = "The list could not be read. Check access permissions."
internal const val LIST_NOT_REGULAR_ADVICE = "Choose a regular JSON file, not a directory or special file."
internal const val LIST_IO_ERROR_ADVICE = "Reading the list failed. Check again when storage is available."
internal const val LIST_DEADLINE_ADVICE = "No response within five seconds. You can still type paths in Configuration."
internal const val LIST_PREVIOUS_PENDING_ADVICE =
    "Previous read still pending. You can still type paths in Configuration."
internal const val LIST_REJECTED = "List not used. Fix the file, then press r to check again."
internal const val LISTS_DO_NOT_BLOCK = "Editing, saving and closing never wait for the lists."
internal fun location(text: String) = "Location: $text"
internal fun diagnostic(text: String) = "Diagnostic: $text"
internal fun inputPosition(line: Int, column: Int) = "input line $line, column $column"
internal fun rootProblem(detail: String, path: String) = "Source root: $detail · $path"
internal fun hiddenLine(n: Int, shown: Boolean) = "$n usually not needed, " + if (shown) "shown" else "hidden"
internal fun showHidden(shown: Boolean) = (if (shown) "Hide" else "Show") + " the suggestions marked usually not needed"
internal const val NO_SUGGESTIONS = "No suggestions yet. Esc returns to Configuration."
internal const val OTHER_DIRECTORIES = "Other directories"
internal const val OTHER_TOOLS = "Other tools"
internal fun addedCount(added: Int, of: Int) = "$added of $of added"
internal const val CANNOT_ADD_ANY = "can't add"
// Browse's marks (tui-design §4 Glyphs): included or not.
internal const val ADDED_MARK = "●"
internal const val NOT_ADDED_MARK = "○"
/** A heading only: some of its directories are added. */
internal const val SOME_ADDED_MARK = "◐"
internal const val CANNOT_ADD_MARK = "−"
internal const val USUALLY_NOT_NEEDED_NOTE = "usually not needed"
internal const val NOT_CHECKED = "not checked"
internal fun notAdded(reason: String) = "Not added. $reason. Prior choices are unchanged."
internal const val SELECT_SUGGESTION = "Select a suggestion or a group"
internal const val ADD_SUGGESTION = "Add the directory to the configuration"
internal const val ADD_GROUP = "Add every directory shown in the group that can be added"
internal const val REMOVE_GROUP = "Take every directory in the group out of the configuration"
internal const val ADD_CATEGORY = "Add every directory shown under the category's apps that can be added"
internal const val REMOVE_CATEGORY = "Take every directory under the category's apps out of the configuration"
/**
 * After Space on a group: null when nothing was skipped; else how many were added and why the rest were not.
 * `overlapped` names, for each skipped row, the relocation it overlaps when known.
 */
internal fun groupAdded(added: Int, overlapped: List<String?>, unaddable: Int): String? {
    if (overlapped.isEmpty() && unaddable == 0) return null
    val overlap = when {
        overlapped.isEmpty() -> null
        overlapped.size == 1 -> "Skipped 1 that overlaps " + (overlapped.single() ?: "a directory in the configuration") + "."
        else -> "Skipped ${overlapped.size} that overlap directories in the configuration."
    }
    val cannot = if (unaddable == 0) null else "Skipped $unaddable that can't be added."
    return listOfNotNull("Added $added.", overlap, cannot).joinToString(" ")
}
internal const val REMOVE_SUGGESTION = "Take the directory out of the configuration; saving writes the change"
internal const val EDIT_SUGGESTION = "Edit its relocation in Configuration"
internal const val INSPECT_SUGGESTION = "See why it is suggested and by which list"
internal const val BACK_TO_CONFIGURATION_LIST = "Back to the configuration list"
internal const val BACK_TO_SUGGESTIONS = "Back to the suggestions"
internal const val SEE_LISTS = "See each suggestion list and whether it was read"
internal const val CHECK_LISTS_AGAIN = "Read the lists again and check each directory; the configuration does not change"
// A directory's details.
internal const val IN_CONFIGURATION = "In the configuration"
internal const val NOT_IN_CONFIGURATION = "Not in the configuration"
internal const val NO_LONGER_LISTED = "No longer listed: hidden, or no list suggests it now."
internal const val BACK_FOR_SUGGESTIONS = "Esc returns to the suggestions."
internal fun sourceText(path: String) = "Source: $path"
internal fun stateLine(state: String) = "State: $state"
internal val MISSING_SUGGESTION = listOf(
    "Not found under the source root. You can configure it before the app creates it.",
    "On Apply, if source and target are both missing: create the target directory and source link.",
    "If only the target exists: follow the row's Only target rule (Ask each time unless you change it).",
    "Save writes configuration only. Apply checks the paths again.",
)
internal const val SIZE_AND_OWNERSHIP = "Size: not estimated · Ownership: not evaluated"
internal fun observedLine(time: String) = "Observed: $time"
internal fun linkText(path: String) = "Link text: $path · Target not checked"
internal fun observationDetail(detail: String, path: String) = "Note: $detail · $path"
internal fun suggestedAround(path: String) = "Also suggested, around it: $path"
internal fun suggestedInside(path: String) = "Also suggested, inside it: $path"
internal const val CHANGE_IN_CONFIGURATION = "Target and rules can be changed in Configuration: press e."
internal const val ADDING_ASKS = "Adding uses a matching target path; its rules ask each time."
internal const val CANNOT_ADD =
    "Only a directory, or one not created yet, can be added. To type a path instead, go back and press a."
internal const val SUGGESTED_BY = "Suggested by"
internal const val NO_LIST_SUGGESTS = "No list suggests it."
internal fun adviceLine(advice: String) = "Advice: $advice"
internal fun reasonLine(reason: String) = "Reason: $reason"
// The sign keeps a caution visible on a terminal without color.
internal fun cautionLine(caution: String) = "⚠ Caution: $caution"
internal fun fromLine(location: String?, record: String, written: String) =
    "From: " + listSummary(location, record, "as written: $written")
internal const val ADVICE_CONSIDER = "Consider"
internal const val ADVICE_USUALLY_NOT_NEEDED = "Usually not needed"
internal const val ADVICE_NOT_GIVEN = "Not given"
