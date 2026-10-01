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
import io.github.bigswlittlesw.homelight.application.PlanModel
import io.github.bigswlittlesw.homelight.application.PlanRelocationItem
import io.github.bigswlittlesw.homelight.config.Relocation
import io.github.bigswlittlesw.homelight.config.WhenAdoptingTarget
import io.github.bigswlittlesw.homelight.config.WhenOnlyTargetExists
import io.github.bigswlittlesw.homelight.config.WhenSourceAndTargetDirectoriesExist
import io.github.bigswlittlesw.homelight.config.javaSplit
import io.github.bigswlittlesw.homelight.domain.RelocationSourceState
import io.github.bigswlittlesw.homelight.fs.PathObservation
import io.github.bigswlittlesw.homelight.fs.PathState
import io.github.bigswlittlesw.homelight.fs.SymlinkTargetAvailability
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction
import io.github.bigswlittlesw.homelight.reconcile.RelocationOutcome
import io.github.bigswlittlesw.homelight.tui.DetailViewport.Line
import java.util.Optional

internal object WorkspaceView {
    @JvmStatic
    fun visibleItems(model: PlanModel.Configured, showInSync: Boolean): List<PlanRelocationItem> {
        if (showInSync) return model.items
        val active = model.items.stream().filter { item -> item.badge() != PlanBadge.IN_SYNC }.toList()
        return if (active.isEmpty()) model.items else active
    }

    @JvmStatic
    fun render(
        session: HomeLightSession, selected: Int, showInSync: Boolean, focus: PaneFocus,
        choice: Int, viewport: DetailViewport,
    ): Element {
        val retained = session.applyModel() is ApplyModel.Result
        val header = Toolkit.row(
            Toolkit.text("⌂ HOMELIGHT  [1: Workspace]").cyan().bold(),
            Toolkit.text(
                if (retained) "  [2: Results]" else if (session.isPlanReady()) "  [2: Review]" else "  [Review unavailable]",
            ).gray(),
        )
        val model = session.planModel()
        if (model !is PlanModel.Configured) {
            val missing = session.evaluation() is ConfigurationEvaluation.Missing ||
                session.evaluation() is ConfigurationEvaluation.Unconfigured
            val message = if (model is PlanModel.Invalid) model.message
            else "No configuration file found. Press i to create one manually; nothing is saved until you choose Save."
            return Toolkit.column(
                header,
                viewport.render(
                    "Configuration",
                    listOf(Line("Config: " + session.configPath), Line(message, Color.YELLOW, false)),
                    focus == PaneFocus.DETAIL, 0,
                ),
                viewport.help(
                    if (focus == PaneFocus.DETAIL) "↑/↓: Scroll · Tab/Esc: Back" else "Tab/l: Details",
                    (if (missing) "i: Manual setup · " else "") + "r: Reload · q: Quit",
                ),
            )
        }
        val configured = model
        val items = visibleItems(configured, showInSync)
        val master = ListElement<Any>().title("Relocations")
            .borderColor(if (focus == PaneFocus.MASTER) Color.CYAN else Color.DARK_GRAY)
            .scrollbar(ScrollBarPolicy.AS_NEEDED).scrollbarThumbColor(Color.CYAN)
            .highlightSymbol("").highlightStyle(Style.EMPTY).autoScroll()
        for (i in items.indices) {
            val item = items[i]
            val label = if (item.badge() == PlanBadge.SKIPPED) "Unchanged" else item.badge().label
            master.add(
                Toolkit.row(
                    Toolkit.text(if (i == selected) "❯ " else "  ").cyan().length(2),
                    Toolkit.text("[$label] ").fg(color(item.badge())).length(label.length + 3),
                    Toolkit.text(item.relocation.sourcePath).ellipsisMiddle().fill(),
                ),
            )
        }
        master.selected(selected)
        val hidden = configured.items.size - items.size
        if (hidden > 0) master.add(Toolkit.text("$hidden in sync hidden").gray())
        val lines = ArrayList<Line>()
        var anchor = 0
        if (items.isEmpty()) lines.add(Line("No configured relocations."))
        else anchor = details(
            session, items[Math.clamp(selected.toLong(), 0, items.size - 1)], choice, focus, lines, retained,
        )
        for (diagnostic in configured.plan.diagnostics) lines.add(Line(diagnostic.message, Color.YELLOW, false))
        val summary = summary(configured.items)
        val choices = !retained && !items.isEmpty() &&
            !items[Math.clamp(selected.toLong(), 0, items.size - 1)].availableResolutions.isEmpty()
        val content = ArrayList<Element>()
        content.add(header)
        content.add(DetailViewport.text("Config: " + session.configPath, Color.GRAY))
        content.add(summaryElement(summary.first()))
        if (!summary.last().isEmpty()) content.add(DetailViewport.text(summary.last(), Color.YELLOW))
        if (!session.discardedChoices().isEmpty()) content.add(
            DetailViewport.text(
                session.discardedChoices().size.toString() +
                    " draft choices discarded after re-plan; inspect current decisions.",
                Color.YELLOW,
            ),
        )
        content.add(
            Toolkit.row(master.percent(45), viewport.render("Details", lines, focus == PaneFocus.DETAIL, anchor)).fill(),
        )
        if (!session.isPlanReady() && !retained) content.add(
            DetailViewport.text(
                if (configured.items.stream().anyMatch(PlanRelocationItem::isBlocked))
                    "Review unavailable: repair blocked paths/configuration; inspect Details."
                else "Review unavailable: choose a decision for each conflict.",
                Color.YELLOW,
            ),
        )
        val navigation = if (focus == PaneFocus.DETAIL)
            (if (choices) "↑/↓: Choose · Space/Enter: Select" else "↑/↓: Scroll") + " · Tab/Esc: Back"
        else "↑/↓: Select · Tab/l: Details · c: In sync"
        val review = if (retained) "2: Results" else if (session.isPlanReady())
            (if (configured.plan.hasChanges()) "a: Review & apply · 2: Review" else "2: Review") else ""
        content.add(viewport.help(navigation, (if (review.isEmpty()) "" else "$review · ") + "r: Re-plan · q: Quit"))
        return Toolkit.column(*content.toTypedArray()).fill()
    }

    @JvmStatic
    fun summary(items: List<PlanRelocationItem>): List<String> {
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
            else if (item.plan.actions.stream().anyMatch(ReconciliationAction::mutatesFilesystem)) actionable++
            else if (item.plan.outcome == RelocationOutcome.UNCHANGED) unchanged++
            else synced++
            if (item.hasWarnings()) warnings++
            if (item.hasDestructiveActions()) destructive++
        }
        return listOf(
            items.size.toString() + " relocations · ⚡ " + actionable + " actionable · ⚠ " + conflict + " conflict · ✖ " +
                blocked + " blocked\n✔ " + synced + " in sync · ─ " + unchanged + " unchanged",
            if (warnings == 0 && destructive == 0) ""
            else "Of these: " + warnings + " with warnings · " + destructive + " with destructive changes",
        )
    }

    private fun summaryElement(summary: String): Element {
        val rows = ArrayList<Element>()
        for (line in summary.javaSplit("\n")) {
            val parts = line.javaSplit(" · ")
            val cells = ArrayList<Element>()
            for (i in parts.indices) {
                val part = parts[i]
                val color = if (part.startsWith("⚠")) Color.YELLOW else if (part.startsWith("✖")) Color.RED
                else if (part.startsWith("✔")) Color.GREEN else if (part.startsWith("─")) Color.GRAY else Color.CYAN
                val label = (if (i == 0) "" else " · ") + part
                cells.add(Toolkit.text(label).fg(color).length(CharWidth.of(label)))
            }
            rows.add(Toolkit.row(*cells.toTypedArray()))
        }
        return Toolkit.column(*rows.toTypedArray())
    }

    private fun details(
        session: HomeLightSession, item: PlanRelocationItem, choice: Int, focus: PaneFocus,
        lines: ArrayList<Line>, retained: Boolean,
    ): Int {
        lines.add(
            Line(
                if (item.sourceObservation.state == PathState.DIRECTORY && item.targetObservation.state == PathState.DIRECTORY)
                    "Current: Source and target are directories."
                else "Current: Source " + observation(item.sourceObservation) + "; target " +
                    observation(item.targetObservation) + ".",
                Color.CYAN, true,
            ),
        )
        when (item.sourceState) {
            RelocationSourceState.WRONG_SYMLINK -> lines.add(Line("Source link points to a different target.", Color.YELLOW, false))
            RelocationSourceState.BROKEN_SYMLINK -> lines.add(Line("Source link is broken: its destination is absent.", Color.YELLOW, false))
            RelocationSourceState.CORRECT_SYMLINK -> lines.add(Line("Source link points to the configured target.", Color.GREEN, false))
            else -> {}
        }
        val evaluation = session.evaluation()
        if (evaluation is ConfigurationEvaluation.Loaded) {
            val source = item.relocation.sourcePath
            evaluation.savedConfiguration.relocations.stream().filter { r -> r.sourcePath == source }.findFirst()
                .ifPresent { saved -> lines.add(Line("Saved policy: " + policy(saved, item), Color.GRAY, false)) }
            lines.add(
                Line(
                    (if (retained) "Reviewed draft: " else "Draft (not saved): ") +
                        Optional.ofNullable(evaluation.draft[source]).map(DecisionChoice::label).orElse("None; using saved policy"),
                ),
            )
        }
        lines.add(Line("Expected outcome: " + consequence(item), Color.CYAN, true))
        if (retained) lines.add(Line("Execution history: Results retained in 2: Results. Re-plan before editing.", Color.YELLOW, false))
        for (diagnostic in item.plan.diagnostics) lines.add(Line(diagnostic.message, Color.YELLOW, false))
        if (item.hasDestructiveActions()) lines.add(Line("⚠ Destructive: existing content or links will be removed.", Color.YELLOW, true))
        var anchor = 0
        if (!retained) for (i in item.availableResolutions.indices) {
            val option = item.availableResolutions[i]
            lines.add(Line(""))
            if (i == choice) anchor = lines.size
            val chosen = item.selectedResolution() == option
            lines.add(
                Line(
                    (if (i == choice && focus == PaneFocus.DETAIL) "❯ " else "  ") + (if (chosen) "(●) " else "(○) ") +
                        option.label,
                    if (chosen) Color.GREEN else Color.CYAN, i == choice,
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
        lines.add(Line("Paths", Color.CYAN, true))
        lines.add(Line("Source: " + item.relocation.sourcePath))
        lines.add(Line("Target: " + item.relocation.targetPath))
        item.sourceObservation.symlinkTarget?.takeIf { path -> path != item.relocation.targetPath }
            ?.let { path -> lines.add(Line("Current link destination: $path")) }
        item.relocation.sourceArchiveRoot?.let { root ->
            lines.add(
                Line("Archive: " + root.resolve(item.relocation.sourcePath.root.relativize(item.relocation.sourcePath))),
            )
        }
        return anchor
    }

    private fun consequence(item: PlanRelocationItem): String {
        if (item.isBlocked()) return "Blocked; repair the problem described below, then re-plan."
        if (item.hasConflict()) return "Choose a decision to see planned changes."
        if (item.plan.outcome == RelocationOutcome.UNCHANGED)
            return "No changes; left unmanaged by choice."
        if (item.plan.actions.stream().noneMatch(ReconciliationAction::mutatesFilesystem))
            return "No changes needed; already in sync."
        return when (item.badge()) {
            PlanBadge.DISCARD -> "Delete source and target contents; recreate an empty target and source link."
            PlanBadge.BACKUP -> "Archive the source directory; keep target contents and create a source link."
            PlanBadge.ADOPT -> "Keep target contents; delete the source directory and replace it with a link."
            PlanBadge.MIGRATE -> "Copy, verify and publish source contents to target; replace source with a link."
            PlanBadge.LINK -> if (item.plan.actions.stream().anyMatch { it is ReconciliationAction.CreateDirectory })
                "Create an empty target directory and link source to it."
            else if (item.plan.actions.stream().anyMatch { it is ReconciliationAction.ReplaceSymlink })
                "Remove the existing source link and replace it with a link to the configured target."
            else "Keep target contents and create a source link to it."
            else -> "Reconcile source and target; inspect exact changes in Review."
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

    @JvmStatic
    fun policy(relocation: Relocation, item: PlanRelocationItem): String {
        if (item.sourceObservation.state == PathState.ABSENT && item.targetObservation.state == PathState.DIRECTORY)
            return when (relocation.whenOnlyTargetExists) {
                WhenOnlyTargetExists.PROMPT, null -> "Ask before adopting the existing target."
                WhenOnlyTargetExists.ADOPT_TARGET -> "Adopt the existing target and create a source link."
            }
        if (item.sourceObservation.state != PathState.DIRECTORY || item.targetObservation.state != PathState.DIRECTORY)
            return "No conflict policy needed for this observed case."
        val bothPolicy = relocation.whenSourceAndTargetDirectoriesExist ?: WhenSourceAndTargetDirectoriesExist.PROMPT
        val both = when (bothPolicy) {
            WhenSourceAndTargetDirectoriesExist.PROMPT -> "Ask"
            WhenSourceAndTargetDirectoriesExist.ADOPT -> "Adopt target"
            WhenSourceAndTargetDirectoriesExist.LEAVE_UNCHANGED -> "Leave unmanaged"
            WhenSourceAndTargetDirectoriesExist.DISCARD -> "Discard both"
        }
        val adopting = when (relocation.whenAdoptingTarget) {
            WhenAdoptingTarget.PROMPT, null -> "ask about source"
            WhenAdoptingTarget.DISCARD_SOURCE -> "discard source"
            WhenAdoptingTarget.ARCHIVE_SOURCE -> "archive source"
        }
        return both + (if (bothPolicy == WhenSourceAndTargetDirectoriesExist.ADOPT) "; $adopting" else "") + "."
    }

    @JvmStatic
    fun color(badge: PlanBadge): Color = when (badge) {
        PlanBadge.IN_SYNC -> Color.GREEN
        PlanBadge.MIGRATE, PlanBadge.ADOPT, PlanBadge.LINK, PlanBadge.BACKUP, PlanBadge.DISCARD -> Color.CYAN
        PlanBadge.CONFLICT, PlanBadge.WARNING -> Color.YELLOW
        PlanBadge.BLOCKED, PlanBadge.INACCESSIBLE -> Color.RED
        PlanBadge.SKIPPED -> Color.GRAY
    }
}
