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
import io.github.bigswlittlesw.homelight.tui.DetailViewport.Line

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
            return Toolkit.column(
                header,
                // The only pane, so it has focus unless a dialog is open.
                viewport.render(
                    "Configuration",
                    listOf(Line("Config: " + displayPath(session.configPath)), Line(message, palette.warn, false)),
                    interactive, 0, WORKSPACE_DETAILS, interactive,
                ),
                viewport.help("↑/↓: Scroll", (if (missing) "i: Create configuration · " else "") + "r: Check again · q: Quit", interactive),
            )
        }
        val configured = model
        val items = visibleItems(configured, showInSync)
        val selected = list.selected().coerceIn(0, maxOf(0, items.size - 1))
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
        list.elements(*rows.toTypedArray()).title(relocationsTitle(inSync, showInSync))
            .borderColor(if (focused == WORKSPACE_LIST) palette.focus else palette.dim).focusable(interactive)
        val detailsFocused = focused == WORKSPACE_DETAILS
        val lines = mutableListOf<Line>()
        var anchor = 0
        if (item == null) lines.add(Line(NO_RELOCATIONS))
        else anchor = details(session, item, choice, detailsFocused, lines, retained)
        configured.plan.diagnostics.mapTo(lines) { Line(it.message, palette.warn, false) }
        val summary = summary(configured.items)
        val choices = !retained && item != null && item.availableResolutions.isNotEmpty()
        val content = buildList {
            add(header)
            add(wrappedText("Config: " + displayPath(session.configPath), palette.dim))
            add(summaryElement(summary.counts))
            if (summary.risks.isNotEmpty()) add(wrappedText(summary.risks, palette.warn))
            add(
                Toolkit.row(
                    list.percent(45), viewport.render("Details", lines, detailsFocused, anchor, WORKSPACE_DETAILS, interactive),
                ).fill(),
            )
            if (!session.isPlanReady() && !retained) add(
                wrappedText(
                    if (configured.items.any { it.isBlocked() }) FIX_TO_REVIEW else CHOOSE_TO_REVIEW,
                    palette.warn,
                ),
            )
            val navigation = if (detailsFocused)
                (if (choices) "↑/↓: Choose · Space/Enter: Select" else "↑/↓: Scroll") + " · Tab/Esc: Back"
            else "↑/↓: Select · Tab/→: Details"
            val review = when {
                retained -> "2: Results"
                !session.isPlanReady() -> ""
                configured.plan.hasChanges() -> "a: Review & apply · 2: Review"
                else -> "2: Review"
            }
            add(viewport.help(navigation, (if (review.isEmpty()) "" else "$review · ") + "r: Check again · q: Quit", interactive))
        }
        return Toolkit.column(*content.toTypedArray()).fill()
    }

    fun summary(items: List<PlanRelocationItem>): Summary {
        var actionable = 0
        var conflict = 0
        var blocked = 0
        var unchanged = 0
        var synced = 0
        var warnings = 0
        var destructive = 0
        for (item in items) {
            if (item.isBlocked()) blocked++
            else if (item.hasConflict()) conflict++
            else if (item.plan.actions.any { it.mutatesFilesystem }) actionable++
            else if (item.plan.outcome == RelocationOutcome.UNCHANGED) unchanged++
            else synced++
            if (item.hasWarnings()) warnings++
            if (item.hasDestructiveActions()) destructive++
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
            if (warnings == 0 && destructive == 0) "" else risks(warnings, destructive),
        )
    }

    /** Count rows render as cells joined by ` · `. `risks` is empty when no item has warnings or destructive changes. */
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
        session: HomeLightSession, item: PlanRelocationItem, choice: Int, focused: Boolean,
        lines: MutableList<Line>, retained: Boolean,
    ): Int {
        val shown = displayPath(item.relocation.sourcePath)
        lines.add(
            Line(
                if (item.sourceObservation.state == PathState.DIRECTORY && item.targetObservation.state == PathState.DIRECTORY)
                    "Now: both $shown and its target are directories."
                else "Now: $shown is " + observation(item.sourceObservation) + "; its target is " +
                    observation(item.targetObservation) + ".",
                palette.text, true,
            ),
        )
        when (item.sourceState) {
            RelocationSourceState.WRONG_SYMLINK -> lines.add(Line("The source link points somewhere else.", palette.warn, false))
            RelocationSourceState.BROKEN_SYMLINK ->
                lines.add(Line("The source link is broken: what it points to is missing.", palette.warn, false))
            RelocationSourceState.CORRECT_SYMLINK -> lines.add(Line("The source link already points to the target.", palette.ok, false))
            RelocationSourceState.ABSENT, RelocationSourceState.FILE, RelocationSourceState.DIRECTORY,
            RelocationSourceState.INACCESSIBLE, RelocationSourceState.OTHER -> {}
        }
        val evaluation = session.evaluation()
        if (evaluation is ConfigurationEvaluation.Loaded) {
            val source = item.relocation.sourcePath
            evaluation.savedConfiguration.relocations.firstOrNull { it.sourcePath == source }
                ?.let { saved -> lines.add(Line("Your rule: " + policy(saved, item))) }
            lines.add(
                Line(
                    (if (retained) "Your choice (reviewed): " else "Your choice (not saved): ") +
                        (evaluation.draft[source]?.let(::choiceLabel) ?: "none; your rule applies"),
                ),
            )
        }
        lines.add(Line("Will do: " + consequence(item), palette.text, true))
        if (retained) lines.add(Line(RESULTS_KEPT, palette.warn, false))
        item.plan.diagnostics.mapTo(lines) { Line(it.message, palette.warn, false) }
        if (item.hasDestructiveActions()) lines.add(Line(DELETES_OR_REPLACES, palette.warn, true))
        var anchor = 0
        if (!retained) item.availableResolutions.forEachIndexed { i, option ->
            lines.add(Line(""))
            if (i == choice) anchor = lines.size
            val chosen = item.selectedResolution() == option
            lines.add(
                Line(
                    (if (i == choice && focused) "❯ " else "  ") + (if (chosen) "(●) " else "(○) ") + choiceLabel(option),
                    if (chosen) palette.ok else palette.text, i == choice,
                ),
            )
            lines.add(Line(choiceDescription(option)))
        }
        lines.add(Line(""))
        lines.add(Line("Paths", palette.text, true))
        lines.add(Line("Source: " + item.relocation.sourcePath))
        lines.add(Line("Target: " + item.relocation.targetPath))
        item.sourceObservation.symlinkTarget?.takeIf { path -> path != item.relocation.targetPath }
            ?.let { path -> lines.add(Line("Current link destination: $path")) }
        val source = item.relocation.sourcePath
        lines.add(Line("Archive: " + item.relocation.archiveRoot.resolve(source.root.relativize(source))))
        return anchor
    }

    private fun consequence(item: PlanRelocationItem): String {
        if (item.isBlocked()) return "nothing until you fix the problem below, then check again."
        if (item.hasConflict()) return "nothing until you choose."
        if (item.plan.outcome == RelocationOutcome.UNCHANGED) return "nothing; source and target are left as they are."
        if (item.plan.actions.none { it.mutatesFilesystem }) return "nothing; already in sync."
        return when (item.badge()) {
            PlanBadge.DISCARD -> "delete the contents of source and target, then create an empty target and link the source to it."
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

    /** The saved rule for the case observed now, as the clause after "Your rule: ". */
    fun policy(relocation: Relocation, item: PlanRelocationItem): String {
        if (item.sourceObservation.state == PathState.ABSENT && item.targetObservation.state == PathState.DIRECTORY)
            return onlyTargetLabel(relocation.whenOnlyTargetExists).lowercase() + "."
        if (item.sourceObservation.state != PathState.DIRECTORY || item.targetObservation.state != PathState.DIRECTORY)
            return "none needed here."
        return bothExistLabel(relocation.whenSourceAndTargetDirectoriesExist, relocation.whenAdoptingTarget).lowercase() + "."
    }

    private fun color(badge: PlanBadge): Color = when (badge) {
        PlanBadge.IN_SYNC -> palette.ok
        PlanBadge.MIGRATE, PlanBadge.ADOPT, PlanBadge.LINK, PlanBadge.BACKUP, PlanBadge.DISCARD -> palette.change
        PlanBadge.CONFLICT, PlanBadge.WARNING -> palette.warn
        PlanBadge.BLOCKED, PlanBadge.INACCESSIBLE -> palette.error
        PlanBadge.SKIPPED -> palette.dim
    }
}
