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
import java.util.Optional

internal object ApplyView {
    private val SPINNER_FRAMES = arrayOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")

    @JvmStatic
    fun render(config: Path, model: ApplyModel, selectedIndex: Int): Element = render(config, model, selectedIndex, 0)

    @JvmStatic
    fun render(config: Path, model: ApplyModel, selectedIndex: Int, spinnerFrame: Int): Element =
        render(config, model, selectedIndex, spinnerFrame, PaneFocus.MASTER, DetailViewport())

    @JvmStatic
    fun render(
        config: Path, model: ApplyModel, selectedIndex: Int, spinnerFrame: Int,
        focus: PaneFocus, viewport: DetailViewport,
    ): Element {
        val header = Toolkit.row(
            Toolkit.text("⌂ HOMELIGHT  ").cyan().bold(),
            Toolkit.text(if (model is ApplyModel.Running) "[Workspace unavailable]  " else "[1: Workspace]  ").gray().dim(),
            Toolkit.text(
                if (model is ApplyModel.Result) "[2: Results]" else if (model is ApplyModel.Running)
                    "[Applying]" else "[2: Review]",
            ).cyan().bold(),
        )
        if (model is ApplyModel.Idle) {
            return Toolkit.column(
                header, Toolkit.text("Review a resolved plan before applying.").yellow(),
                Toolkit.text("1: Workspace  ·  q: Quit").gray(),
            )
        }
        val plan = when (model) {
            is ApplyModel.Confirmation -> model.plan
            is ApplyModel.Running -> model.plan
            is ApplyModel.Result -> model.plan
            is ApplyModel.Idle -> throw IllegalStateException()
        }
        val steps = steps(model)
        val selected = if (steps.isEmpty()) 0 else Math.clamp(selectedIndex.toLong(), 0, steps.size - 1)
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
                if (index == selected) {
                    selectedRow = row
                }
                val prefix = if (index == selected) "❯ " else "  "
                checklist.add(
                    Toolkit.text(
                        prefix + glyph(step, spinnerFrame) + " " + actionLabel(action) +
                            (if (action.destructive) " ⚠" else ""),
                    ).fg(color(step)),
                )
                row++
                index++
            }
        }
        checklist.selected(selectedRow)
        val detailLines = ArrayList<DetailViewport.Line>()
        if (steps.isEmpty()) detailLines.add(DetailViewport.Line("No actions required."))
        else detailLines.addAll(details(steps[selected]))
        if (model is ApplyModel.Result) for (diagnostic in model.diagnostics)
            detailLines.add(DetailViewport.Line(diagnostic, Color.RED, false))
        val destructive = plan.actions().stream().filter(ReconciliationAction::destructive).count()
        val headline = when (model) {
            is ApplyModel.Confirmation -> if (!plan.hasChanges()) "No changes to apply."
            else if (destructive > 0)
                "Confirm reviewed plan: " + destructive + " destructive action(s). Content may be permanently removed."
            else "Confirm reviewed plan. No changes have been made."
            is ApplyModel.Running -> "Applying reviewed plan. Wait for execution to finish."
            is ApplyModel.Result -> if (model.stale && model.execution == null
                && model.steps.stream().allMatch { step -> step.status == ApplyModel.StepStatus.PENDING })
                "Plan stale: preflight rejected before any mutation. Inspect details."
            else if (model.stale) "Plan stale during execution. Inspect failed and not-run actions."
            else if (model.succeeded()) "Application complete. Observations refreshed. Results retained."
            else if (model.execution == null) "Worker stopped unexpectedly. Mutation extent may be uncertain; inspect evidence."
            else "Application stopped. Inspect failed and not-run actions, then re-plan."
            is ApplyModel.Idle -> throw IllegalStateException()
        }
        val footer = when (model) {
            is ApplyModel.Confirmation -> if (!plan.hasChanges()) "1/Enter/n/Esc: Workspace · q: Quit"
            else if (destructive > 0) "y: Confirm destructive plan · n/Esc/1: Cancel review · q: Quit"
            else "y: Confirm apply · n/Esc/1: Cancel review · q: Quit"
            is ApplyModel.Running -> "q: Quit options"
            is ApplyModel.Result -> "1/Enter: Workspace · r: Re-plan · q: Quit"
            is ApplyModel.Idle -> throw IllegalStateException()
        }
        val content = ArrayList<Element>()
        content.add(header)
        content.add(DetailViewport.text("Config: " + config, Color.GRAY))
        content.add(
            DetailViewport.text(
                headline,
                if (model is ApplyModel.Result && model.succeeded()) Color.GREEN else Color.YELLOW,
            ),
        )
        if (model is ApplyModel.Confirmation) {
            if (plan.hasChanges()) content.add(
                DetailViewport.text(
                    plan.actions().stream().filter(ReconciliationAction::mutatesFilesystem).count()
                        .toString() + " planned changes · " + destructive + " destructive actions",
                    Color.CYAN,
                ),
            )
        } else {
            if (!progress(steps).isEmpty()) content.add(DetailViewport.text(progress(steps), Color.CYAN))
            content.add(DetailViewport.text(counts(steps, model is ApplyModel.Result), Color.GRAY))
        }
        content.add(
            Toolkit.row(
                checklist.percent(45),
                viewport.render("Action details", detailLines, focus == PaneFocus.DETAIL, 0),
            ).fill(),
        )
        val navigation = if (focus == PaneFocus.MASTER) "↑/↓: Inspect · Tab/l: Details"
        else "↑/↓: Scroll · Tab/h: List" + (if (model is ApplyModel.Confirmation) "" else " · Esc: Back")
        content.add(viewport.help(navigation, footer))
        return Toolkit.column(*content.toTypedArray()).fill()
    }

    @JvmStatic
    fun steps(model: ApplyModel): List<ApplyModel.Step> = when (model) {
        is ApplyModel.Idle -> listOf()
        is ApplyModel.Confirmation -> pendingSteps(model.plan)
        is ApplyModel.Running -> model.steps
        is ApplyModel.Result -> model.steps
    }

    private fun pendingSteps(plan: ReconciliationPlan): List<ApplyModel.Step> =
        plan.relocations.stream().flatMap { relocation ->
            relocation.actions.stream()
                .map { action -> ApplyModel.Step(relocation, action, ApplyModel.StepStatus.PENDING, "Not started") }
        }.toList()

    @JvmStatic
    fun details(step: ApplyModel.Step): List<DetailViewport.Line> {
        val action = step.action
        val lines = ArrayList<DetailViewport.Line>()
        lines.add(DetailViewport.Line(actionLabel(action), color(step), true))
        if (step.status != ApplyModel.StepStatus.PENDING)
            lines.add(DetailViewport.Line(step.message, color(step), false))
        if (action.destructive) lines.add(
            DetailViewport.Line("⚠ Destructive: existing content or link will be removed.", Color.YELLOW, true),
        )
        lines.add(DetailViewport.Line(affectedPath(action)))
        if (!destination(action).isEmpty()) lines.add(DetailViewport.Line(destination(action)))
        val relocation = step.relocation.relocation
        if (action.path != relocation.sourcePath) lines.add(DetailViewport.Line("Source: " + relocation.sourcePath))
        if (action.path != relocation.targetPath
            && destinationPath(action).filter(relocation.targetPath::equals).isEmpty
        )
            lines.add(DetailViewport.Line("Target: " + relocation.targetPath))
        return lines
    }

    @JvmStatic
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

    @JvmStatic
    fun destination(action: ReconciliationAction): String = when (action) {
        is ReconciliationAction.ArchiveDirectory -> "Archive: " + action.target
        is ReconciliationAction.CopyDirectory -> "Copy to: " + action.target
        is ReconciliationAction.MigrateDirectoryForPublication -> "Publish to: " + action.target
        is ReconciliationAction.CreateSymlink -> "Link to: " + action.target
        is ReconciliationAction.ReplaceDirectoryWithSymlink -> "Link to: " + action.target
        is ReconciliationAction.ReplaceSymlink -> "Link to: " + action.target
        else -> ""
    }

    private fun destinationPath(action: ReconciliationAction): Optional<Path> = when (action) {
        is ReconciliationAction.ArchiveDirectory -> Optional.of(action.target)
        is ReconciliationAction.CopyDirectory -> Optional.of(action.target)
        is ReconciliationAction.MigrateDirectoryForPublication -> Optional.of(action.target)
        is ReconciliationAction.CreateSymlink -> Optional.of(action.target)
        is ReconciliationAction.ReplaceDirectoryWithSymlink -> Optional.of(action.target)
        is ReconciliationAction.ReplaceSymlink -> Optional.of(action.target)
        else -> Optional.empty()
    }

    @JvmStatic
    fun actionLabel(action: ReconciliationAction): String = when (action) {
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
        if (!step.action.mutatesFilesystem && step.status != ApplyModel.StepStatus.FAILED) {
            return "─"
        }
        return when (step.status) {
            ApplyModel.StepStatus.PENDING -> "○"
            ApplyModel.StepStatus.RUNNING -> SPINNER_FRAMES[Math.floorMod(spinnerFrame, SPINNER_FRAMES.size)]
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
        val changes = steps.stream().filter { step -> step.action.mutatesFilesystem }.toList()
        if (changes.isEmpty()) {
            return ""
        }
        val completed = changes.stream().filter { step -> step.status == ApplyModel.StepStatus.COMPLETED }.count()
        val filled = (20 * completed / changes.size).toInt()
        return "[" + "█".repeat(filled) + "░".repeat(20 - filled) + "] " + completed + "/" + changes.size + " actions completed"
    }

    private fun counts(steps: List<ApplyModel.Step>, result: Boolean): String {
        val changes = steps.stream().filter { step -> step.action.mutatesFilesystem }.toList()
        val completed = changes.stream().filter { step -> step.status == ApplyModel.StepStatus.COMPLETED }.count()
        val failed = changes.stream().filter { step -> step.status == ApplyModel.StepStatus.FAILED }.count()
        val pending = changes.stream().filter { step -> step.status == ApplyModel.StepStatus.PENDING }.count()
        val running = changes.stream().filter { step -> step.status == ApplyModel.StepStatus.RUNNING }.count()
        val inSync = steps.stream().filter { step -> step.action is ReconciliationAction.NoOp }.count()
        val unchanged = steps.stream().filter { step -> step.action is ReconciliationAction.LeaveUnchanged }.count()
        return "Mutations: " + completed + " completed · " + failed + " failed · " + pending + (if (result) " not run" else " pending") +
            " · " + running + " running\nNo change: " + inSync + " in sync · " + unchanged + " intentionally unchanged"
    }
}
