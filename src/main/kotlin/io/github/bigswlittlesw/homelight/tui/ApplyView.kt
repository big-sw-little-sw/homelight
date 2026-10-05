package io.github.bigswlittlesw.homelight.tui

import dev.tamboui.style.Color
import dev.tamboui.style.Style
import dev.tamboui.toolkit.Toolkit
import dev.tamboui.toolkit.element.Element
import dev.tamboui.toolkit.elements.ListElement
import dev.tamboui.widgets.common.ScrollBarPolicy
import io.github.bigswlittlesw.homelight.application.ApplyModel
import io.github.bigswlittlesw.homelight.application.pendingSteps
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction
import java.nio.file.Path

/** Renders the review and results screen. The object names the screen; it holds no state. */
internal object ApplyView {
    private val SPINNER_FRAMES = listOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")

    /**
     * The action list, one row per step. One instance lives across frames: TamboUI keeps its selection and scroll
     * offset. A relocation's first step carries the relocation's own line, so the selection is always an action.
     */
    fun list(): ListElement<Any> = ListElement<Any>().title("Reviewed actions").id(REVIEW_LIST)
        .scrollbar(ScrollBarPolicy.AS_NEEDED).scrollbarThumbColor(palette.focus).scrollbarTrackColor(palette.dim)
        .highlightSymbol("").highlightStyle(Style.EMPTY).autoScroll()

    /** `focused` is the focused element's id; `interactive` is false while a dialog is open over the screen. */
    fun render(
        config: Path, model: ApplyModel, list: ListElement<Any>, spinnerFrame: Int = 0, focused: String? = REVIEW_LIST,
        interactive: Boolean = true, viewport: DetailViewport = DetailViewport(),
    ): Element {
        val header = Toolkit.row(
            Toolkit.text("⌂ HOMELIGHT  ").fg(palette.brand).bold(),
            Toolkit.text(if (model is ApplyModel.Running) "[Workspace unavailable]  " else "[1: Workspace]  ").fg(palette.dim),
            Toolkit.text(
                when (model) {
                    is ApplyModel.Result -> "[2: Results]"
                    is ApplyModel.Running -> "[Applying]"
                    is ApplyModel.Idle, is ApplyModel.Confirmation -> "[2: Review]"
                },
            ).fg(palette.focus).bold(),
        )
        val reviewed = when (model) {
            is ApplyModel.Idle -> return Toolkit.column(
                header, Toolkit.text("Review a resolved plan before applying.").fg(palette.warn),
                Toolkit.text("1: Workspace  ·  q: Quit").fg(palette.dim),
            )
            is ApplyModel.Reviewed -> model
        }
        val plan = reviewed.plan
        val steps = steps(model)
        val selected = list.selected().coerceIn(0, maxOf(0, steps.size - 1))
        val rows = steps.mapIndexed { i, step ->
            val action = step.action
            val row = Toolkit.text(
                (if (i == selected) "❯ " else "  ") + glyph(step, spinnerFrame) + " " + actionLabel(action) +
                    (if (action.destructive) " ⚠" else ""),
            ).fg(color(step))
            if (i > 0 && steps[i - 1].relocation == step.relocation) row
            // TamboUI reserves the scrollbar's column by counting items, not lines, so with two-line items the
            // scrollbar can cover a row's last cell: the trailing space is what it covers.
            else Toolkit.column(Toolkit.text(step.relocation.relocation.sourcePath.toString() + " ").bold().ellipsisMiddle(), row)
                .length(2)
        }
        list.elements(*rows.toTypedArray()).borderColor(if (focused == REVIEW_LIST) palette.focus else palette.dim)
            .focusable(interactive)
        val detailLines = if (steps.isEmpty()) mutableListOf(DetailViewport.Line("No actions required."))
        else details(steps[selected]).toMutableList()
        if (model is ApplyModel.Result) model.diagnostics.mapTo(detailLines) { DetailViewport.Line(it, palette.error, false) }
        val destructive = plan.actions().count { it.destructive }
        val headline = when (reviewed) {
            is ApplyModel.Confirmation -> when {
                !plan.hasChanges() -> "No changes to apply."
                destructive > 0 -> "Confirm reviewed plan: $destructive destructive action(s). Content may be permanently removed."
                else -> "Confirm reviewed plan. No changes have been made."
            }
            is ApplyModel.Running -> "Applying reviewed plan. Wait for execution to finish."
            is ApplyModel.Result -> when {
                reviewed.stale && reviewed.execution == null && reviewed.steps.all { it.status == ApplyModel.StepStatus.PENDING } ->
                    "Plan stale: preflight rejected before any mutation. Inspect details."
                reviewed.stale -> "Plan stale during execution. Inspect failed and not-run actions."
                reviewed.succeeded() -> "Application complete. Observations refreshed. Results retained."
                reviewed.execution == null -> "Worker stopped unexpectedly. Mutation extent may be uncertain; inspect evidence."
                else -> "Application stopped. Inspect failed and not-run actions, then re-plan."
            }
        }
        val footer = when (reviewed) {
            is ApplyModel.Confirmation -> when {
                !plan.hasChanges() -> "1/Enter/n/Esc: Workspace · q: Quit"
                destructive > 0 -> "y: Confirm destructive plan · n/Esc/1: Cancel review · q: Quit"
                else -> "y: Confirm apply · n/Esc/1: Cancel review · q: Quit"
            }
            is ApplyModel.Running -> "q: Quit options"
            is ApplyModel.Result -> "1/Enter: Workspace · r: Re-plan · q: Quit"
        }
        val content = buildList {
            add(header)
            add(wrappedText("Config: $config", palette.dim))
            add(wrappedText(headline, if (model is ApplyModel.Result && model.succeeded()) palette.ok else palette.warn))
            if (model is ApplyModel.Confirmation) {
                if (plan.hasChanges()) add(
                    wrappedText(
                        "${plan.actions().count { it.mutatesFilesystem }} planned changes · $destructive destructive actions",
                        palette.change,
                    ),
                )
            } else {
                val progress = progress(steps)
                if (progress.isNotEmpty()) add(wrappedText(progress, palette.ok))
                add(wrappedText(counts(steps, model is ApplyModel.Result), palette.text))
            }
            add(
                Toolkit.row(
                    list.percent(45),
                    viewport.render("Action details", detailLines, focused == REVIEW_DETAILS, 0, REVIEW_DETAILS, interactive),
                ).fill(),
            )
            val navigation = if (focused != REVIEW_DETAILS) "↑/↓: Inspect · Tab/→: Details"
            else "↑/↓: Scroll · Tab/←: List" + (if (model is ApplyModel.Confirmation) "" else " · Esc: Back")
            add(viewport.help(navigation, footer, interactive))
        }
        return Toolkit.column(*content.toTypedArray()).fill()
    }

    fun steps(model: ApplyModel): List<ApplyModel.Step> = when (model) {
        is ApplyModel.Idle -> listOf()
        is ApplyModel.Confirmation -> pendingSteps(model.plan)
        is ApplyModel.Running -> model.steps
        is ApplyModel.Result -> model.steps
    }

    fun details(step: ApplyModel.Step): List<DetailViewport.Line> = buildList {
        val action = step.action
        add(DetailViewport.Line(actionLabel(action), color(step), true))
        if (step.status != ApplyModel.StepStatus.PENDING) add(DetailViewport.Line(step.message, color(step), false))
        if (action.destructive) add(DetailViewport.Line("⚠ Destructive: existing content or link will be removed.", palette.warn, true))
        add(DetailViewport.Line(affectedPath(action)))
        if (destination(action).isNotEmpty()) add(DetailViewport.Line(destination(action)))
        val relocation = step.relocation.relocation
        if (action.path != relocation.sourcePath) add(DetailViewport.Line("Source: " + relocation.sourcePath))
        if (action.path != relocation.targetPath && action.destination != relocation.targetPath) {
            add(DetailViewport.Line("Target: " + relocation.targetPath))
        }
    }

    fun affectedPath(action: ReconciliationAction): String = when (action) {
        is ReconciliationAction.EnsureDirectory -> "Parent directory: "
        is ReconciliationAction.CreateDirectory -> "Create at: "
        is ReconciliationAction.MigrateDirectoryForPublication -> "Copy from: "
        is ReconciliationAction.ArchiveDirectory -> "Archive from: "
        is ReconciliationAction.DeleteDirectory -> "Delete at: "
        is ReconciliationAction.CreateSymlink, is ReconciliationAction.ReplaceDirectoryWithSymlink,
        is ReconciliationAction.ReplaceSymlink -> "Link at: "
        is ReconciliationAction.NoOp, is ReconciliationAction.LeaveUnchanged -> "Unchanged path: "
        is ReconciliationAction.Blocked -> "Blocked path: "
    } + action.path

    fun destination(action: ReconciliationAction): String {
        val destination = action.destination ?: return ""
        return when (action) {
            is ReconciliationAction.ArchiveDirectory -> "Archive: "
            is ReconciliationAction.MigrateDirectoryForPublication -> "Publish to: "
            is ReconciliationAction.CreateSymlink, is ReconciliationAction.ReplaceDirectoryWithSymlink,
            is ReconciliationAction.ReplaceSymlink -> "Link to: "
            // Unreachable: these actions have no destination.
            is ReconciliationAction.EnsureDirectory, is ReconciliationAction.CreateDirectory,
            is ReconciliationAction.DeleteDirectory, is ReconciliationAction.NoOp,
            is ReconciliationAction.LeaveUnchanged, is ReconciliationAction.Blocked -> ""
        } + destination
    }

    private fun actionLabel(action: ReconciliationAction): String = when (action) {
        is ReconciliationAction.EnsureDirectory -> "Ensure parent directory"
        is ReconciliationAction.CreateDirectory -> "Create directory"
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
        ApplyModel.StepStatus.PENDING -> palette.dim
        ApplyModel.StepStatus.RUNNING -> palette.focus
        ApplyModel.StepStatus.COMPLETED -> if (step.action.mutatesFilesystem) palette.ok else palette.dim
        ApplyModel.StepStatus.FAILED -> palette.error
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
