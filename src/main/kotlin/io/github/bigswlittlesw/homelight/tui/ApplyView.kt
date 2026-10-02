package io.github.bigswlittlesw.homelight.tui

import dev.tamboui.style.Color
import dev.tamboui.style.Style
import dev.tamboui.toolkit.Toolkit
import dev.tamboui.toolkit.element.Element
import dev.tamboui.toolkit.elements.ListElement
import dev.tamboui.widgets.common.ScrollBarPolicy
import io.github.bigswlittlesw.homelight.application.ApplyModel
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlan
import java.nio.file.Path

/** Renders the review and results screen. The object names the screen; it holds no state. */
internal object ApplyView {
    private val SPINNER_FRAMES = listOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")

    fun render(
        config: Path, model: ApplyModel, selectedIndex: Int, spinnerFrame: Int = 0,
        focus: PaneFocus = PaneFocus.MASTER, viewport: DetailViewport = DetailViewport(),
    ): Element {
        val header = Toolkit.row(
            Toolkit.text("⌂ HOMELIGHT  ").cyan().bold(),
            Toolkit.text(if (model is ApplyModel.Running) "[Workspace unavailable]  " else "[1: Workspace]  ").gray().dim(),
            Toolkit.text(
                when (model) {
                    is ApplyModel.Result -> "[2: Results]"
                    is ApplyModel.Running -> "[Applying]"
                    is ApplyModel.Idle, is ApplyModel.Confirmation -> "[2: Review]"
                },
            ).cyan().bold(),
        )
        val plan = when (model) {
            is ApplyModel.Idle -> return Toolkit.column(
                header, Toolkit.text("Review a resolved plan before applying.").yellow(),
                Toolkit.text("1: Workspace  ·  q: Quit").gray(),
            )
            is ApplyModel.Confirmation -> model.plan
            is ApplyModel.Running -> model.plan
            is ApplyModel.Result -> model.plan
        }
        val steps = steps(model)
        val selected = if (steps.isEmpty()) 0 else selectedIndex.coerceIn(0, steps.size - 1)
        val checklist = ListElement<Any>().title("Reviewed actions")
            .borderColor(if (focus == PaneFocus.MASTER) Color.CYAN else Color.DARK_GRAY)
            .scrollbar(ScrollBarPolicy.AS_NEEDED).scrollbarThumbColor(Color.CYAN)
            .highlightSymbol("").highlightStyle(Style.EMPTY).autoScroll()
        var row = 0
        var selectedRow = 0
        var index = 0
        for (relocation in plan.relocations) {
            checklist.add(Toolkit.text(relocation.relocation.sourcePath).cyan().bold().ellipsisMiddle())
            row++
            for (action in relocation.actions) {
                val step = steps[index]
                if (index == selected) selectedRow = row
                val prefix = if (index == selected) "❯ " else "  "
                checklist.add(
                    Toolkit.text(
                        prefix + glyph(step, spinnerFrame) + " " + actionLabel(action) + (if (action.destructive) " ⚠" else ""),
                    ).fg(color(step)),
                )
                row++
                index++
            }
        }
        checklist.selected(selectedRow)
        val detailLines = if (steps.isEmpty()) mutableListOf(DetailViewport.Line("No actions required."))
        else details(steps[selected]).toMutableList()
        if (model is ApplyModel.Result) model.diagnostics.mapTo(detailLines) { DetailViewport.Line(it, Color.RED, false) }
        val destructive = plan.actions().count { it.destructive }
        val headline = when (model) {
            is ApplyModel.Confirmation -> when {
                !plan.hasChanges() -> "No changes to apply."
                destructive > 0 -> "Confirm reviewed plan: $destructive destructive action(s). Content may be permanently removed."
                else -> "Confirm reviewed plan. No changes have been made."
            }
            is ApplyModel.Running -> "Applying reviewed plan. Wait for execution to finish."
            is ApplyModel.Result -> when {
                model.stale && model.execution == null && model.steps.all { it.status == ApplyModel.StepStatus.PENDING } ->
                    "Plan stale: preflight rejected before any mutation. Inspect details."
                model.stale -> "Plan stale during execution. Inspect failed and not-run actions."
                model.succeeded() -> "Application complete. Observations refreshed. Results retained."
                model.execution == null -> "Worker stopped unexpectedly. Mutation extent may be uncertain; inspect evidence."
                else -> "Application stopped. Inspect failed and not-run actions, then re-plan."
            }
            is ApplyModel.Idle -> error("Idle returned above")
        }
        val footer = when (model) {
            is ApplyModel.Confirmation -> when {
                !plan.hasChanges() -> "1/Enter/n/Esc: Workspace · q: Quit"
                destructive > 0 -> "y: Confirm destructive plan · n/Esc/1: Cancel review · q: Quit"
                else -> "y: Confirm apply · n/Esc/1: Cancel review · q: Quit"
            }
            is ApplyModel.Running -> "q: Quit options"
            is ApplyModel.Result -> "1/Enter: Workspace · r: Re-plan · q: Quit"
            is ApplyModel.Idle -> error("Idle returned above")
        }
        val content = buildList {
            add(header)
            add(wrappedText("Config: $config", Color.GRAY))
            add(wrappedText(headline, if (model is ApplyModel.Result && model.succeeded()) Color.GREEN else Color.YELLOW))
            if (model is ApplyModel.Confirmation) {
                if (plan.hasChanges()) add(
                    wrappedText(
                        "${plan.actions().count { it.mutatesFilesystem }} planned changes · $destructive destructive actions",
                        Color.CYAN,
                    ),
                )
            } else {
                val progress = progress(steps)
                if (progress.isNotEmpty()) add(wrappedText(progress, Color.CYAN))
                add(wrappedText(counts(steps, model is ApplyModel.Result), Color.GRAY))
            }
            add(
                Toolkit.row(
                    checklist.percent(45),
                    viewport.render("Action details", detailLines, focus == PaneFocus.DETAIL, 0),
                ).fill(),
            )
            val navigation = if (focus == PaneFocus.MASTER) "↑/↓: Inspect · Tab/l: Details"
            else "↑/↓: Scroll · Tab/h: List" + (if (model is ApplyModel.Confirmation) "" else " · Esc: Back")
            add(viewport.help(navigation, footer))
        }
        return Toolkit.column(*content.toTypedArray()).fill()
    }

    fun steps(model: ApplyModel): List<ApplyModel.Step> = when (model) {
        is ApplyModel.Idle -> listOf()
        is ApplyModel.Confirmation -> pendingSteps(model.plan)
        is ApplyModel.Running -> model.steps
        is ApplyModel.Result -> model.steps
    }

    private fun pendingSteps(plan: ReconciliationPlan): List<ApplyModel.Step> =
        plan.relocations.flatMap { relocation ->
            relocation.actions.map { action -> ApplyModel.Step(relocation, action, ApplyModel.StepStatus.PENDING, "Not started") }
        }

    fun details(step: ApplyModel.Step): List<DetailViewport.Line> = buildList {
        val action = step.action
        add(DetailViewport.Line(actionLabel(action), color(step), true))
        if (step.status != ApplyModel.StepStatus.PENDING) add(DetailViewport.Line(step.message, color(step), false))
        if (action.destructive) add(DetailViewport.Line("⚠ Destructive: existing content or link will be removed.", Color.YELLOW, true))
        add(DetailViewport.Line(affectedPath(action)))
        if (destination(action).isNotEmpty()) add(DetailViewport.Line(destination(action)))
        val relocation = step.relocation.relocation
        if (action.path != relocation.sourcePath) add(DetailViewport.Line("Source: " + relocation.sourcePath))
        if (action.path != relocation.targetPath && destinationPath(action) != relocation.targetPath) {
            add(DetailViewport.Line("Target: " + relocation.targetPath))
        }
    }

    fun affectedPath(action: ReconciliationAction): String = when (action) {
        is ReconciliationAction.EnsureDirectory -> "Parent directory: "
        is ReconciliationAction.CreateDirectory -> "Create at: "
        is ReconciliationAction.CopyDirectory, is ReconciliationAction.MigrateDirectoryForPublication -> "Copy from: "
        is ReconciliationAction.ArchiveDirectory -> "Archive from: "
        is ReconciliationAction.DeleteDirectory -> "Delete at: "
        is ReconciliationAction.CreateSymlink, is ReconciliationAction.ReplaceDirectoryWithSymlink,
        is ReconciliationAction.ReplaceSymlink -> "Link at: "
        is ReconciliationAction.NoOp, is ReconciliationAction.LeaveUnchanged -> "Unchanged path: "
        is ReconciliationAction.Blocked -> "Blocked path: "
    } + action.path

    fun destination(action: ReconciliationAction): String = when (action) {
        is ReconciliationAction.ArchiveDirectory -> "Archive: " + action.target
        is ReconciliationAction.CopyDirectory -> "Copy to: " + action.target
        is ReconciliationAction.MigrateDirectoryForPublication -> "Publish to: " + action.target
        is ReconciliationAction.CreateSymlink -> "Link to: " + action.target
        is ReconciliationAction.ReplaceDirectoryWithSymlink -> "Link to: " + action.target
        is ReconciliationAction.ReplaceSymlink -> "Link to: " + action.target
        is ReconciliationAction.EnsureDirectory, is ReconciliationAction.CreateDirectory,
        is ReconciliationAction.DeleteDirectory, is ReconciliationAction.NoOp,
        is ReconciliationAction.LeaveUnchanged, is ReconciliationAction.Blocked -> ""
    }

    private fun destinationPath(action: ReconciliationAction): Path? = when (action) {
        is ReconciliationAction.ArchiveDirectory -> action.target
        is ReconciliationAction.CopyDirectory -> action.target
        is ReconciliationAction.MigrateDirectoryForPublication -> action.target
        is ReconciliationAction.CreateSymlink -> action.target
        is ReconciliationAction.ReplaceDirectoryWithSymlink -> action.target
        is ReconciliationAction.ReplaceSymlink -> action.target
        is ReconciliationAction.EnsureDirectory, is ReconciliationAction.CreateDirectory,
        is ReconciliationAction.DeleteDirectory, is ReconciliationAction.NoOp,
        is ReconciliationAction.LeaveUnchanged, is ReconciliationAction.Blocked -> null
    }

    private fun actionLabel(action: ReconciliationAction): String = when (action) {
        is ReconciliationAction.EnsureDirectory -> "Ensure parent directory"
        is ReconciliationAction.CreateDirectory -> "Create directory"
        is ReconciliationAction.CopyDirectory -> "Copy directory"
        is ReconciliationAction.MigrateDirectoryForPublication -> "Copy, verify and publish"
        is ReconciliationAction.ArchiveDirectory -> "Archive source"
        is ReconciliationAction.DeleteDirectory -> "Delete directory"
        is ReconciliationAction.CreateSymlink -> "Create source link"
        is ReconciliationAction.ReplaceDirectoryWithSymlink -> "Replace directory with link"
        is ReconciliationAction.ReplaceSymlink -> "Replace source link"
        is ReconciliationAction.NoOp -> "Already in sync"
        is ReconciliationAction.LeaveUnchanged -> "Leave unchanged"
        is ReconciliationAction.Blocked -> "Blocked"
    }

    private fun glyph(step: ApplyModel.Step, spinnerFrame: Int): String {
        if (!step.action.mutatesFilesystem && step.status != ApplyModel.StepStatus.FAILED) return "─"
        return when (step.status) {
            ApplyModel.StepStatus.PENDING -> "○"
            ApplyModel.StepStatus.RUNNING -> SPINNER_FRAMES[spinnerFrame.mod(SPINNER_FRAMES.size)]
            ApplyModel.StepStatus.COMPLETED -> if (step.action.mutatesFilesystem) "✔" else "─"
            ApplyModel.StepStatus.FAILED -> "✖"
        }
    }

    private fun color(step: ApplyModel.Step): Color = when (step.status) {
        ApplyModel.StepStatus.PENDING -> Color.GRAY
        ApplyModel.StepStatus.RUNNING -> Color.CYAN
        ApplyModel.StepStatus.COMPLETED -> if (step.action.mutatesFilesystem) Color.GREEN else Color.GRAY
        ApplyModel.StepStatus.FAILED -> Color.RED
    }

    private fun progress(steps: List<ApplyModel.Step>): String {
        val changes = steps.filter { it.action.mutatesFilesystem }
        if (changes.isEmpty()) return ""
        val completed = changes.count { it.status == ApplyModel.StepStatus.COMPLETED }
        val filled = 20 * completed / changes.size
        return "[" + "█".repeat(filled) + "░".repeat(20 - filled) + "] " + completed + "/" + changes.size + " actions completed"
    }

    private fun counts(steps: List<ApplyModel.Step>, result: Boolean): String {
        val changes = steps.filter { it.action.mutatesFilesystem }
        fun changes(status: ApplyModel.StepStatus) = changes.count { it.status == status }
        val inSync = steps.count { it.action is ReconciliationAction.NoOp }
        val unchanged = steps.count { it.action is ReconciliationAction.LeaveUnchanged }
        return "Mutations: " + changes(ApplyModel.StepStatus.COMPLETED) + " completed · " +
            changes(ApplyModel.StepStatus.FAILED) + " failed · " + changes(ApplyModel.StepStatus.PENDING) +
            (if (result) " not run" else " pending") + " · " + changes(ApplyModel.StepStatus.RUNNING) + " running\n" +
            "No change: " + inSync + " in sync · " + unchanged + " intentionally unchanged"
    }
}
