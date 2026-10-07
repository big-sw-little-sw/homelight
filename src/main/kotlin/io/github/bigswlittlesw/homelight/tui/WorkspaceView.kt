package io.github.bigswlittlesw.homelight.tui

import dev.tamboui.style.Color
import dev.tamboui.style.Style
import dev.tamboui.text.CharWidth
import dev.tamboui.toolkit.Toolkit
import dev.tamboui.toolkit.element.Element
import dev.tamboui.toolkit.elements.ListElement
import dev.tamboui.widgets.common.ScrollBarPolicy
import io.github.bigswlittlesw.homelight.application.ApplyModel
import io.github.bigswlittlesw.homelight.application.ConfigurationEvaluation
import io.github.bigswlittlesw.homelight.application.DecisionChoice
import io.github.bigswlittlesw.homelight.application.HomeLightSession
import io.github.bigswlittlesw.homelight.application.PlanBadge
import io.github.bigswlittlesw.homelight.application.PlanRelocationItem
import io.github.bigswlittlesw.homelight.config.Relocation
import io.github.bigswlittlesw.homelight.domain.RelocationSourceState
import io.github.bigswlittlesw.homelight.fs.PathObservation
import io.github.bigswlittlesw.homelight.fs.PathState
import io.github.bigswlittlesw.homelight.fs.SymlinkTargetAvailability
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction
import io.github.bigswlittlesw.homelight.reconcile.RelocationOutcome
import io.github.bigswlittlesw.homelight.tui.DetailViewport.Anchored
import io.github.bigswlittlesw.homelight.tui.DetailViewport.Line
import java.nio.file.Path

/** Renders the workspace screen. The object names the screen; it holds no state. */
internal object WorkspaceView {
    fun visibleItems(model: ConfigurationEvaluation.Loaded, showInSync: Boolean): List<PlanRelocationItem> {
        if (showInSync) return model.items
        val active = model.items.filter { item -> item.badge() != PlanBadge.IN_SYNC }
        return if (active.isEmpty()) model.items else active
    }

    /** The in-sync rows `c` hides or shows: none when every row is in sync, because then they always show. */
    private fun toggledInSync(model: ConfigurationEvaluation.Loaded): Int {
        val inSync = model.items.count { item -> item.badge() == PlanBadge.IN_SYNC }
        return if (inSync == model.items.size) 0 else inSync
    }

    /** The relocation list. One instance lives across frames: TamboUI keeps its selection and scroll offset. */
    fun list(): ListElement<Any> = ListElement<Any>().id(WORKSPACE_LIST)
        .scrollbar(ScrollBarPolicy.AS_NEEDED).scrollbarThumbColor(palette.focus).scrollbarTrackColor(palette.dim)
        .highlightSymbol("").highlightStyle(Style.EMPTY).autoScroll()

    /** `focused` is the focused element's id; `interactive` is false while a dialog is open over the screen. */
    fun render(
        session: HomeLightSession, list: ListElement<Any>, showInSync: Boolean, focused: String?, interactive: Boolean,
        choice: Int, viewport: DetailViewport,
    ): Element {
        val retained = session.applyModel() is ApplyModel.Result
        val header = Toolkit.row(
            Toolkit.text("⌂ HOMELIGHT  ").fg(palette.brand).bold(),
            Toolkit.text("[1: Workspace]").fg(palette.focus).bold(),
            Toolkit.text(
                if (retained) "  [2: Results]" else if (session.isPlanReady()) "  [2: Review]" else "  [Review unavailable]",
            ).fg(palette.dim),
        )
        val model = session.evaluation()
        if (model !is ConfigurationEvaluation.Loaded) {
            val missing = model is ConfigurationEvaluation.Missing || model is ConfigurationEvaluation.Unconfigured
            val message = when (model) {
                is ConfigurationEvaluation.Missing -> model.message
                is ConfigurationEvaluation.Invalid -> model.message
                else -> NO_CONFIGURATION
            }
            val lines = listOf(Line("Config: " + displayPath(session.configPath)), Line(message, palette.warn, false)) +
                (if (missing) listOf(Line(FIRST_RUN_HINT)) else listOf())
            return Toolkit.column(
                header,
                // The only pane, so it has focus unless a dialog is open.
                viewport.render("Configuration", lines, interactive, 0, WORKSPACE_DETAILS, interactive),
                viewport.help(screenHelp(session, list, showInSync, focused), interactive),
            )
        }
        val configured = model
        val items = visibleItems(configured, showInSync)
        val selected = selection(list, items)
        val item = items.getOrNull(selected)
        val rows = items.mapIndexed { i, listed ->
            val label = badgeLabel(listed.badge())
            Toolkit.row(
                Toolkit.text(if (i == selected) "❯ " else "  ").fg(palette.focus).length(2),
                Toolkit.text("[$label] ").fg(color(listed.badge())).length(label.length + 3),
                Toolkit.text(displayPath(listed.relocation.sourcePath)).ellipsisMiddle().fill(),
            )
        }
        val inSync = toggledInSync(configured)
        // In the title, not a row, so the list's own selection never lands on it.
        list.elements(*rows.toTypedArray()).focusable(interactive).fill()
        // The list is framed by a panel, which can show focus with a thick border; ListElement offers only rounded.
        val listPane = framed(Toolkit.panel(relocationsTitle(inSync, showInSync), list), focused == WORKSPACE_LIST)
        val detailsFocused = focused == WORKSPACE_DETAILS
        val details = if (item == null) Anchored(listOf(Line(NO_RELOCATIONS), Line(HELP_HINT)), 0)
        else details(configured, item, choice, detailsFocused, retained)
        val lines = details.lines + configured.plan.diagnostics.map { Line(it.message, palette.warn, false) }
        val summary = summary(configured.items)
        val content = buildList {
            add(header)
            add(wrappedText("Config: " + displayPath(session.configPath), palette.dim))
            add(summaryElement(summary.counts))
            if (summary.risks.isNotEmpty()) add(wrappedText(summary.risks, palette.warn))
            add(
                Toolkit.row(
                    listPane.percent(45), viewport.render("Details", lines, detailsFocused, details.anchor, WORKSPACE_DETAILS, interactive),
                ).fill(),
            )
            if (!session.isPlanReady() && !retained) add(
                wrappedText(
                    if (configured.items.any { it.isBlocked() }) FIX_TO_REVIEW else CHOOSE_TO_REVIEW,
                    palette.warn,
                ),
            )
            add(viewport.help(screenHelp(session, list, showInSync, focused), interactive))
        }
        return Toolkit.column(*content.toTypedArray()).fill()
    }

    /** Workspace's purpose and keys in its current state, for its help lines and the Help screen. */
    fun screenHelp(session: HomeLightSession, list: ListElement<Any>, showInSync: Boolean, focused: String?): ScreenHelp {
        val model = session.evaluation()
        if (model !is ConfigurationEvaluation.Loaded) {
            val missing = model is ConfigurationEvaluation.Missing || model is ConfigurationEvaluation.Unconfigured
            return ScreenHelp(
                // Without a configuration that loads, the next step is to configure.
                WORKSPACE_NAME, if (missing) PURPOSE_NO_CONFIGURATION else PURPOSE_INVALID, Step.CONFIGURE,
                listOf(SCROLL_KEY, SCROLL_ENDS_KEYS, SCROLL_DETAILS_KEYS),
                listOfNotNull(KeyHint("i", "Create configuration", description = "Create a configuration file; nothing is written until you save")
                    .takeIf { missing }, CHECK_AGAIN_KEY, HELP_KEY, QUIT_KEY),
            )
        }
        val retained = session.applyModel() is ApplyModel.Result
        val navigation = if (focused == WORKSPACE_DETAILS) {
            val items = visibleItems(model, showInSync)
            val item = items.getOrNull(selection(list, items))
            val choices = !retained && item != null && item.availableResolutions.isNotEmpty()
            (if (choices) listOf(
                KeyHint("↑/↓", "Choose", description = "Move between the choices"),
                KeyHint("Space/Enter", "Select", description = "Pick the highlighted choice, for the next apply only"),
                HOME_END_KEYS,
            )
            else listOf(SCROLL_KEY, SCROLL_ENDS_KEYS)) +
                listOf(
                    SCROLL_DETAILS_KEYS, KeyHint("Tab/Esc", "Back", description = "Back to the relocation list"),
                    KeyHint("←", "Back", inHelpArea = false, description = "Back to the relocation list"),
                )
        } else listOf(
            KeyHint("↑/↓", "Select", description = "Select a relocation"),
            KeyHint("Tab/→", "Details", description = "Move to Details for the selected relocation"),
            KeyHint("Enter", "Details", inHelpArea = false, description = "Move to Details for the selected relocation"),
            PAGE_KEYS, HOME_END_KEYS, SCROLL_DETAILS_KEYS,
        )
        val review = when {
            retained -> listOf(KeyHint("2", "Results", description = "Show what the last apply did"))
            !session.isPlanReady() -> listOf()
            model.plan.hasChanges() -> listOf(
                KeyHint("a", "Review & apply", description = "Review the plan; nothing changes until you press y there"),
                KeyHint("2", "Review", description = "Open Review, as a does"),
            )
            else -> listOf(KeyHint("2", "Review", description = "Open Review; there is nothing to apply"))
        }
        val inSync = toggledInSync(model)
        // The list title shows `c`, so the help lines leave it out.
        val toggle = KeyHint(
            "c", (if (showInSync) "Hide " else "Show ") + "$inSync in sync", inHelpArea = false,
            description = (if (showInSync) "Hide" else "Show") + " the relocations already in sync",
        )
        return ScreenHelp(
            if (focused == WORKSPACE_DETAILS) place(WORKSPACE_NAME, DETAILS_NAME) else WORKSPACE_NAME,
            if (model.items.isEmpty()) PURPOSE_NO_RELOCATIONS else PURPOSE_WORKSPACE, Step.WORKSPACE,
            navigation, review + listOfNotNull(toggle.takeIf { inSync > 0 }, CHECK_AGAIN_KEY, HELP_KEY, QUIT_KEY),
        )
    }

    private fun selection(list: ListElement<Any>, items: List<PlanRelocationItem>): Int =
        list.selected().coerceIn(0, maxOf(0, items.size - 1))

    fun summary(items: List<PlanRelocationItem>): Summary {
        var actionable = 0
        var conflict = 0
        var blocked = 0
        var unchanged = 0
        var synced = 0
        var warnings = 0
        var deleting = 0
        for (item in items) {
            if (item.isBlocked()) blocked++
            else if (item.hasConflict()) conflict++
            else if (item.plan.actions.any { it.mutatesFilesystem }) actionable++
            else if (item.plan.outcome == RelocationOutcome.UNCHANGED) unchanged++
            else synced++
            if (item.hasWarnings()) warnings++
            if (item.deletesData()) deleting++
        }
        return Summary(
            listOf(
                listOf(
                    SummaryCell(relocationCount(items.size), palette.text),
                    SummaryCell(toChange(actionable), palette.change),
                    SummaryCell(needChoice(conflict), palette.warn),
                    SummaryCell(blockedCount(blocked), palette.error),
                ),
                listOf(SummaryCell(inSyncCount(synced), palette.ok), SummaryCell(leftAsIsCount(unchanged), palette.dim)),
            ),
            if (warnings == 0 && deleting == 0) "" else risks(warnings, deleting),
        )
    }

    /** Count rows render as cells joined by ` · `. `risks` is empty when no item has warnings or deletes data. */
    data class Summary(val counts: List<List<SummaryCell>>, val risks: String)

    data class SummaryCell(val text: String, val color: Color)

    private fun summaryElement(counts: List<List<SummaryCell>>): Element {
        val rows = counts.map { row ->
            val cells = row.mapIndexed { i, cell ->
                val label = (if (i == 0) "" else " · ") + cell.text
                Toolkit.text(label).fg(cell.color).length(CharWidth.of(label))
            }
            Toolkit.row(*cells.toTypedArray())
        }
        return Toolkit.column(*rows.toTypedArray())
    }

    private fun details(
        configured: ConfigurationEvaluation.Loaded, item: PlanRelocationItem, choice: Int, focused: Boolean,
        retained: Boolean,
    ): Anchored {
        var anchor = 0
        val source = item.relocation.sourcePath
        // Evaluation inspects the archive destination for every relocation, so it is known even when nothing archives.
        val archive = configured.observations.firstOrNull { it.relocation.sourcePath == source }?.archiveDestination?.path
        val archiving = archives(item)
        val lines = buildList {
            val shown = displayPath(source)
            add(
                Line(
                    if (item.sourceObservation.state == PathState.DIRECTORY && item.targetObservation.state == PathState.DIRECTORY)
                        "Now: both $shown and its target are directories."
                    else "Now: $shown is " + observation(item.sourceObservation) + "; its target is " +
                        observation(item.targetObservation) + ".",
                    palette.text, true,
                ),
            )
            when (item.sourceState) {
                RelocationSourceState.WRONG_SYMLINK -> add(Line("The source link points somewhere else.", palette.warn, false))
                RelocationSourceState.BROKEN_SYMLINK ->
                    add(Line("The source link is broken: what it points to is missing.", palette.warn, false))
                RelocationSourceState.CORRECT_SYMLINK -> add(Line("The source link already points to the target.", palette.ok, false))
                RelocationSourceState.ABSENT, RelocationSourceState.FILE, RelocationSourceState.DIRECTORY,
                RelocationSourceState.INACCESSIBLE, RelocationSourceState.OTHER -> {}
            }
            decision(item, configured.draft[source])?.let { add(Line(it)) }
            add(Line("Will do: " + consequence(item), palette.text, true))
            item.plan.actions.filterIsInstance<ReconciliationAction.Blocked>()
                .mapTo(this) { blocked -> Line(problem(blocked.reason), palette.error, false) }
            if (retained) add(Line(RESULTS_KEPT, palette.warn, false))
            item.plan.diagnostics.mapTo(this) { Line(it.message, palette.warn, false) }
            if (item.deletesData()) add(Line(DELETES_DATA, palette.warn, true))
            if (!retained) item.availableResolutions.forEachIndexed { i, option ->
                add(Line(""))
                if (i == choice) anchor = size
                val chosen = item.selectedResolution() == option
                add(
                    Line(
                        (if (i == choice && focused) "❯ " else "  ") + (if (chosen) "(●) " else "(○) ") + choiceLabel(option),
                        if (chosen) palette.ok else palette.text, i == choice,
                    ),
                )
                // An offered archive names its destination here; a planned one shows it once, under Paths.
                add(Line(choiceDescription(option, archive.takeUnless { archiving })))
            }
            add(Line(""))
            add(Line("Paths", palette.text, true))
            add(Line("Source: $source"))
            add(Line("Target: " + item.relocation.targetPath))
            item.sourceObservation.symlinkTarget?.takeIf { path -> path != item.relocation.targetPath }
                ?.let { path -> add(Line("Current link destination: $path")) }
            if (archiving && archive != null) add(Line("Archive: $archive"))
            leftBehind(item)?.let { path -> add(Line("Left behind: $path")) }
        }
        return Anchored(lines, anchor)
    }

    /** The original source an interrupted replacement left beside the link, when the plan deletes it. */
    private fun leftBehind(item: PlanRelocationItem): Path? =
        item.plan.actions.filterIsInstance<ReconciliationAction.DeleteDirectory>().map { it.path }
            .firstOrNull { path -> path != item.relocation.sourcePath && path != item.relocation.targetPath }

    /** Whether the effective rule or one-time choice archives the source in the case observed now. */
    private fun archives(item: PlanRelocationItem): Boolean =
        item.selectedResolution() == DecisionChoice.ADOPT_AND_ARCHIVE_SOURCE &&
            DecisionChoice.ADOPT_AND_ARCHIVE_SOURCE in item.availableResolutions

    /**
     * The one line saying what decides this row and where that comes from, or null when no rule governs the case
     * observed now. A one-time choice wins over the saved rule it replaces for the next apply.
     */
    private fun decision(item: PlanRelocationItem, chosen: DecisionChoice?): String? {
        if (chosen != null) return choiceDecision(chosen)
        return rule(item.relocation, item)?.let(::ruleDecision)
    }

    private fun consequence(item: PlanRelocationItem): String {
        if (item.isBlocked()) return "nothing until you fix the problem below, then check again."
        if (item.hasConflict()) return "nothing until you choose."
        if (item.plan.outcome == RelocationOutcome.UNCHANGED) return "nothing; source and target are left as they are."
        if (item.plan.actions.none { it.mutatesFilesystem }) return "nothing; already in sync."
        return when (item.badge()) {
            PlanBadge.DISCARD -> if (leftBehind(item) != null) LEFT_BEHIND_DELETED
                else "delete the contents of source and target, then create an empty target and link the source to it."
            PlanBadge.BACKUP -> "move the source to the archive, keep the target's contents and link the source to it."
            PlanBadge.ADOPT -> "keep the target's contents, delete the source and replace it with a link."
            PlanBadge.MIGRATE -> "copy the source to the target, check the copy, then replace the source with a link."
            PlanBadge.LINK -> when {
                item.plan.actions.any { it is ReconciliationAction.CreateDirectory } ->
                    "create an empty target directory and link the source to it."
                item.plan.actions.any { it is ReconciliationAction.ReplaceSymlink } ->
                    "fix the source link so it points to the target."
                else -> "keep the target's contents and link the source to it."
            }
            PlanBadge.CONFLICT, PlanBadge.BLOCKED, PlanBadge.INACCESSIBLE, PlanBadge.WARNING, PlanBadge.SKIPPED,
            PlanBadge.IN_SYNC -> "change source and target; see the exact steps in Review."
        }
    }

    private fun observation(observation: PathObservation): String = when (observation.state) {
        PathState.ABSENT -> "missing"
        PathState.DIRECTORY -> if (observation.emptyDirectory) "an empty directory" else "a directory"
        PathState.FILE -> "a file"
        PathState.OTHER -> "a special file"
        PathState.INACCESSIBLE -> "unreadable"
        PathState.SYMLINK -> when (observation.symlinkTargetAvailability) {
            SymlinkTargetAvailability.EXISTS, SymlinkTargetAvailability.NOT_A_SYMLINK -> "a link"
            SymlinkTargetAvailability.ABSENT -> "a broken link"
            SymlinkTargetAvailability.INACCESSIBLE -> "a link to something unreadable"
        }
    }

    /** The rule that governs the case observed now, in the configuration's words, or null when none does. */
    fun rule(relocation: Relocation, item: PlanRelocationItem): String? {
        val source = item.sourceObservation.state
        val target = item.targetObservation.state
        return when {
            target != PathState.DIRECTORY -> null
            source == PathState.ABSENT -> onlyTargetLabel(relocation.whenOnlyTargetExists)
            source == PathState.DIRECTORY ->
                bothExistLabel(relocation.whenSourceAndTargetDirectoriesExist, relocation.whenAdoptingTarget)
            else -> null
        }
    }

    private fun color(badge: PlanBadge): Color = when (badge) {
        PlanBadge.IN_SYNC -> palette.ok
        PlanBadge.MIGRATE, PlanBadge.ADOPT, PlanBadge.LINK, PlanBadge.BACKUP, PlanBadge.DISCARD -> palette.change
        PlanBadge.CONFLICT, PlanBadge.WARNING -> palette.warn
        PlanBadge.BLOCKED, PlanBadge.INACCESSIBLE -> palette.error
        PlanBadge.SKIPPED -> palette.dim
    }
}
