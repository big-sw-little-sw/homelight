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
import io.github.bigswlittlesw.homelight.config.WhenAdoptingTarget
import io.github.bigswlittlesw.homelight.config.WhenOnlyTargetExists
import io.github.bigswlittlesw.homelight.config.WhenSourceAndTargetDirectoriesExist
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
                else -> "No configuration file found. Press i to create one manually; nothing is saved until you choose Save."
            }
            return Toolkit.column(
                header,
                // The only pane, so it has focus unless a dialog is open.
                viewport.render(
                    "Configuration",
                    listOf(Line("Config: " + session.configPath), Line(message, palette.warn, false)),
                    interactive, 0, WORKSPACE_DETAILS, interactive,
                ),
                viewport.help("↑/↓: Scroll", (if (missing) "i: Manual setup · " else "") + "r: Reload · q: Quit", interactive),
            )
        }
        val configured = model
        val items = visibleItems(configured, showInSync)
        val selected = list.selected().coerceIn(0, maxOf(0, items.size - 1))
        val item = items.getOrNull(selected)
        val rows = items.mapIndexed { i, listed ->
            val label = listed.badge().label
            Toolkit.row(
                Toolkit.text(if (i == selected) "❯ " else "  ").fg(palette.focus).length(2),
                Toolkit.text("[$label] ").fg(color(listed.badge())).length(label.length + 3),
                Toolkit.text(listed.relocation.sourcePath).ellipsisMiddle().fill(),
            )
        }
        val hidden = configured.items.size - items.size
        // In the title, not a row, so the list's own selection never lands on it.
        list.elements(*rows.toTypedArray()).title(if (hidden > 0) "Relocations · $hidden in sync hidden" else "Relocations")
            .borderColor(if (focused == WORKSPACE_LIST) palette.focus else palette.dim).focusable(interactive)
        val detailsFocused = focused == WORKSPACE_DETAILS
        val lines = mutableListOf<Line>()
        var anchor = 0
        if (item == null) lines.add(Line("No configured relocations."))
        else anchor = details(session, item, choice, detailsFocused, lines, retained)
        configured.plan.diagnostics.mapTo(lines) { Line(it.message, palette.warn, false) }
        val summary = summary(configured.items)
        val choices = !retained && item != null && item.availableResolutions.isNotEmpty()
        val content = buildList {
            add(header)
            add(wrappedText("Config: " + session.configPath, palette.dim))
            add(summaryElement(summary.counts))
            if (summary.risks.isNotEmpty()) add(wrappedText(summary.risks, palette.warn))
            add(
                Toolkit.row(
                    list.percent(45), viewport.render("Details", lines, detailsFocused, anchor, WORKSPACE_DETAILS, interactive),
                ).fill(),
            )
            if (!session.isPlanReady() && !retained) add(
                wrappedText(
                    if (configured.items.any { it.isBlocked() }) "Review unavailable: repair blocked paths/configuration; inspect Details."
                    else "Review unavailable: choose a decision for each conflict.",
                    palette.warn,
                ),
            )
            val navigation = if (detailsFocused)
                (if (choices) "↑/↓: Choose · Space/Enter: Select" else "↑/↓: Scroll") + " · Tab/Esc: Back"
            else "↑/↓: Select · Tab/→: Details · c: In sync"
            val review = when {
                retained -> "2: Results"
                !session.isPlanReady() -> ""
                configured.plan.hasChanges() -> "a: Review & apply · 2: Review"
                else -> "2: Review"
            }
            add(viewport.help(navigation, (if (review.isEmpty()) "" else "$review · ") + "r: Re-plan · q: Quit", interactive))
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
                    SummaryCell("${items.size} relocations", palette.text),
                    SummaryCell("⚡ $actionable actionable", palette.change),
                    SummaryCell("⚠ $conflict conflict", palette.warn),
                    SummaryCell("✖ $blocked blocked", palette.error),
                ),
                listOf(SummaryCell("✔ $synced in sync", palette.ok), SummaryCell("─ $unchanged unchanged", palette.dim)),
            ),
            if (warnings == 0 && destructive == 0) ""
            else "Of these: $warnings with warnings · $destructive with destructive changes",
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
        lines.add(
            Line(
                if (item.sourceObservation.state == PathState.DIRECTORY && item.targetObservation.state == PathState.DIRECTORY)
                    "Current: Source and target are directories."
                else "Current: Source " + observation(item.sourceObservation) + "; target " +
                    observation(item.targetObservation) + ".",
                palette.text, true,
            ),
        )
        when (item.sourceState) {
            RelocationSourceState.WRONG_SYMLINK -> lines.add(Line("Source link points to a different target.", palette.warn, false))
            RelocationSourceState.BROKEN_SYMLINK -> lines.add(Line("Source link is broken: its destination is absent.", palette.warn, false))
            RelocationSourceState.CORRECT_SYMLINK -> lines.add(Line("Source link points to the configured target.", palette.ok, false))
            RelocationSourceState.ABSENT, RelocationSourceState.FILE, RelocationSourceState.DIRECTORY,
            RelocationSourceState.INACCESSIBLE, RelocationSourceState.OTHER -> {}
        }
        val evaluation = session.evaluation()
        if (evaluation is ConfigurationEvaluation.Loaded) {
            val source = item.relocation.sourcePath
            evaluation.savedConfiguration.relocations.firstOrNull { it.sourcePath == source }
                ?.let { saved -> lines.add(Line("Saved policy: " + policy(saved, item))) }
            lines.add(
                Line(
                    (if (retained) "Reviewed draft: " else "Draft (not saved): ") +
                        (evaluation.draft[source]?.label ?: "None; using saved policy"),
                ),
            )
        }
        lines.add(Line("Expected outcome: " + consequence(item), palette.text, true))
        if (retained) lines.add(Line("Execution history: Results retained in 2: Results. Re-plan before editing.", palette.warn, false))
        item.plan.diagnostics.mapTo(lines) { Line(it.message, palette.warn, false) }
        if (item.hasDestructiveActions()) lines.add(Line("⚠ Destructive: existing content or links will be removed.", palette.warn, true))
        var anchor = 0
        if (!retained) item.availableResolutions.forEachIndexed { i, option ->
            lines.add(Line(""))
            if (i == choice) anchor = lines.size
            val chosen = item.selectedResolution() == option
            lines.add(
                Line(
                    (if (i == choice && focused) "❯ " else "  ") + (if (chosen) "(●) " else "(○) ") +
                        option.label,
                    if (chosen) palette.ok else palette.text, i == choice,
                ),
            )
            lines.add(
                Line(
                    option.description +
                        if (option == DecisionChoice.ADOPT_AND_ARCHIVE_SOURCE || option == DecisionChoice.ADOPT_AND_DISCARD_SOURCE)
                            " Replace source with a link to target." else "",
                ),
            )
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
        if (item.isBlocked()) return "Blocked; repair the problem described below, then re-plan."
        if (item.hasConflict()) return "Choose a decision to see planned changes."
        if (item.plan.outcome == RelocationOutcome.UNCHANGED)
            return "No changes; source and target left unchanged by choice."
        if (item.plan.actions.none { it.mutatesFilesystem }) return "No changes needed; already in sync."
        return when (item.badge()) {
            PlanBadge.DISCARD -> "Delete source and target contents; recreate an empty target and source link."
            PlanBadge.BACKUP -> "Archive the source directory; keep target contents and create a source link."
            PlanBadge.ADOPT -> "Keep target contents; delete the source directory and replace it with a link."
            PlanBadge.MIGRATE -> "Copy, verify and publish source contents to target; replace source with a link."
            PlanBadge.LINK -> when {
                item.plan.actions.any { it is ReconciliationAction.CreateDirectory } ->
                    "Create an empty target directory and link source to it."
                item.plan.actions.any { it is ReconciliationAction.ReplaceSymlink } ->
                    "Remove the existing source link and replace it with a link to the configured target."
                else -> "Keep target contents and create a source link to it."
            }
            PlanBadge.CONFLICT, PlanBadge.BLOCKED, PlanBadge.INACCESSIBLE, PlanBadge.WARNING, PlanBadge.SKIPPED,
            PlanBadge.IN_SYNC -> "Reconcile source and target; inspect exact changes in Review."
        }
    }

    private fun observation(observation: PathObservation): String = when (observation.state) {
        PathState.ABSENT -> "Absent"
        PathState.DIRECTORY -> if (observation.emptyDirectory) "Empty directory" else "Directory"
        PathState.FILE -> "File"
        PathState.OTHER -> "Other filesystem entry"
        PathState.INACCESSIBLE -> "Inaccessible"
        PathState.SYMLINK -> when (observation.symlinkTargetAvailability) {
            SymlinkTargetAvailability.EXISTS -> "link destination exists"
            SymlinkTargetAvailability.ABSENT -> "link destination is missing"
            SymlinkTargetAvailability.INACCESSIBLE -> "link destination is inaccessible"
            SymlinkTargetAvailability.NOT_A_SYMLINK -> "symlink"
        }
    }

    fun policy(relocation: Relocation, item: PlanRelocationItem): String {
        if (item.sourceObservation.state == PathState.ABSENT && item.targetObservation.state == PathState.DIRECTORY)
            return when (val only = relocation.whenOnlyTargetExists) {
                WhenOnlyTargetExists.PROMPT -> onlyTargetLabel(only) + " before adopting the existing target."
                WhenOnlyTargetExists.ADOPT_TARGET -> onlyTargetLabel(only) + " and create a source link."
            }
        if (item.sourceObservation.state != PathState.DIRECTORY || item.targetObservation.state != PathState.DIRECTORY)
            return "No conflict policy needed for this observed case."
        val both = relocation.whenSourceAndTargetDirectoriesExist
        if (both != WhenSourceAndTargetDirectoriesExist.ADOPT) return bothLabel(both) + "."
        // A clause after the label, so lowercase, but in the adopting labels' words.
        val adopting = when (relocation.whenAdoptingTarget) {
            WhenAdoptingTarget.PROMPT -> "prompt for source"
            WhenAdoptingTarget.DISCARD_SOURCE -> "discard source"
            WhenAdoptingTarget.ARCHIVE_SOURCE -> "archive source"
        }
        return bothLabel(both) + "; " + adopting + "."
    }

    private fun color(badge: PlanBadge): Color = when (badge) {
        PlanBadge.IN_SYNC -> palette.ok
        PlanBadge.MIGRATE, PlanBadge.ADOPT, PlanBadge.LINK, PlanBadge.BACKUP, PlanBadge.DISCARD -> palette.change
        PlanBadge.CONFLICT, PlanBadge.WARNING -> palette.warn
        PlanBadge.BLOCKED, PlanBadge.INACCESSIBLE -> palette.error
        PlanBadge.SKIPPED -> palette.dim
    }
}
