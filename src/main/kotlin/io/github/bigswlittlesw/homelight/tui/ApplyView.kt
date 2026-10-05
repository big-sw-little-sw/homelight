package io.github.bigswlittlesw.homelight.tui

import dev.tamboui.style.Color
import dev.tamboui.style.Style
import dev.tamboui.text.CharWidth
import dev.tamboui.toolkit.Toolkit
import dev.tamboui.toolkit.element.Element
import dev.tamboui.toolkit.element.StyledElement
import dev.tamboui.toolkit.elements.ListElement
import dev.tamboui.widgets.common.ScrollBarPolicy
import dev.tamboui.widgets.spinner.SpinnerState
import io.github.bigswlittlesw.homelight.application.ApplyModel
import io.github.bigswlittlesw.homelight.application.pendingSteps
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction
import java.nio.file.Path

/** Renders the review and results screen. The object names the screen; it holds no state. */
internal object ApplyView {
    private val SPINNER_FRAMES = arrayOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")

    /**
     * The action list, one row per step. One instance lives across frames: TamboUI keeps its selection and scroll
     * offset. A relocation's first step carries the relocation's own line, so the selection is always an action.
     */
    fun list(): ListElement<Any> = ListElement<Any>().title(REVIEW_LIST_TITLE).id(REVIEW_LIST)
        .scrollbar(ScrollBarPolicy.AS_NEEDED).scrollbarThumbColor(palette.focus).scrollbarTrackColor(palette.dim)
        .highlightSymbol("").highlightStyle(Style.EMPTY).autoScroll()

    /**
     * `focused` is the focused element's id; `interactive` is false while a dialog is open over the screen.
     * `quitting` is true once HomeLight will exit when the apply finishes, so `q` no longer does anything.
     */
    fun render(
        config: Path, model: ApplyModel, list: ListElement<Any>, spinnerFrame: Int = 0, focused: String? = REVIEW_LIST,
        interactive: Boolean = true, viewport: DetailViewport = DetailViewport(), quitting: Boolean = false,
    ): Element {
        val brand = Toolkit.text("⌂ HOMELIGHT  ").fg(palette.brand).bold()
        // While applying, neither destination is reachable, so the header names only what is happening.
        val header = when (model) {
            is ApplyModel.Running -> Toolkit.row(brand, Toolkit.text("[Applying]").fg(palette.focus).bold())
            is ApplyModel.Idle, is ApplyModel.Confirmation, is ApplyModel.Result -> Toolkit.row(
                brand, Toolkit.text("[1: Workspace]  ").fg(palette.dim),
                Toolkit.text(if (model is ApplyModel.Result) "[2: Results]" else "[2: Review]").fg(palette.focus).bold(),
            )
        }
        val reviewed = when (model) {
            is ApplyModel.Idle -> return Toolkit.column(
                header, Toolkit.text(NOTHING_TO_REVIEW).fg(palette.warn),
                Toolkit.text(helpLine(keys(model, focused, quitting).all)).fg(palette.dim),
            )
            is ApplyModel.Reviewed -> model
        }
        val plan = reviewed.plan
        val steps = steps(model)
        val selected = list.selected().coerceIn(0, maxOf(0, steps.size - 1))
        val byRelocation = steps.groupBy { it.relocation }
        val rows = steps.mapIndexed { i, step ->
            val pointer = if (i == selected) "❯ " else "  "
            val own = byRelocation.getValue(step.relocation)
            val path = displayPath(step.relocation.relocation.sourcePath)
            when {
                i > 0 && steps[i - 1].relocation == step.relocation -> actionRow(pointer, step, spinnerFrame)
                // An in-sync relocation is its own single row, at the relocation column: there is no step to show
                // under it. Selected, the pointer takes its mark's cell, so the row does not shift.
                own.size == 1 && step.action is ReconciliationAction.NoOp -> markedRow(
                    "", if (i == selected) Toolkit.text("❯").fg(palette.focus) else mark(step.status, false, spinnerFrame),
                    inSyncRow(path), palette.dim,
                )
                else -> Toolkit.column(
                    markedRow("", mark(relocationStatus(own), own.any { it.action.mutatesFilesystem }, spinnerFrame),
                        path, palette.text, bold = true),
                    actionRow(pointer, step, spinnerFrame),
                ).length(2)
            }
        }
        list.elements(*rows.toTypedArray()).borderColor(if (focused == REVIEW_LIST) palette.focus else palette.dim)
            .focusable(interactive)
        val detailLines = if (steps.isEmpty()) listOf(DetailViewport.Line(NO_STEPS))
        else details(steps[selected]) +
            (if (model is ApplyModel.Result) model.diagnostics.map { DetailViewport.Line(it, palette.error, false) } else listOf())
        val destructive = plan.actions().count { it.destructive }
        val headline = when (reviewed) {
            is ApplyModel.Confirmation -> when {
                !plan.hasChanges() -> NO_CHANGES
                destructive > 0 -> CONFIRM_DESTRUCTIVE
                else -> CONFIRM
            }
            is ApplyModel.Running -> APPLYING
            is ApplyModel.Result -> when {
                reviewed.stale && reviewed.execution == null && reviewed.steps.all { it.status == ApplyModel.StepStatus.PENDING } ->
                    REFUSED
                reviewed.stale -> STALE
                reviewed.succeeded() -> DONE
                reviewed.execution == null -> WORKER_STOPPED
                else -> STOPPED
            }
        }
        val changes = steps.filter { it.action.mutatesFilesystem }
        val content = buildList {
            add(header)
            add(wrappedText("Config: " + displayPath(config), palette.dim))
            add(wrappedText(headline, if (model is ApplyModel.Result && model.succeeded()) palette.ok else palette.warn))
            if (model is ApplyModel.Confirmation) {
                if (plan.hasChanges()) add(
                    wrappedText(plannedChanges(plan.actions().count { it.mutatesFilesystem }, destructive), palette.change),
                )
            } else if (changes.isNotEmpty()) {
                fun count(status: ApplyModel.StepStatus) = changes.count { it.status == status }
                val done = count(ApplyModel.StepStatus.COMPLETED)
                add(
                    // LineGauge sets each cell's whole style, so the background must be in it.
                    Toolkit.lineGauge(done.toDouble() / changes.size).thick()
                        .filledStyle(Style.EMPTY.fg(palette.ok).bg(palette.background))
                        .unfilledStyle(Style.EMPTY.fg(palette.dim).bg(palette.background)).length(1),
                )
                val failed = count(ApplyModel.StepStatus.FAILED)
                add(
                    wrappedText(
                        if (model is ApplyModel.Result) finishedCount(done, changes.size, failed, count(ApplyModel.StepStatus.PENDING))
                        else runningCount(done, changes.size, count(ApplyModel.StepStatus.RUNNING), failed),
                        palette.text,
                    ),
                )
            }
            add(
                Toolkit.row(
                    list.percent(45),
                    viewport.render("Action details", detailLines, focused == REVIEW_DETAILS, 0, REVIEW_DETAILS, interactive),
                ).fill(),
            )
            add(viewport.help(keys(model, focused, quitting), interactive))
        }
        return Toolkit.column(*content.toTypedArray()).fill()
    }

    /** Review's keys in its current state, for its help lines and the `?` overlay. */
    fun keys(model: ApplyModel, focused: String?, quitting: Boolean): ScreenKeys {
        val navigation = when {
            model is ApplyModel.Idle -> listOf()
            focused != REVIEW_DETAILS -> listOf(
                KeyHint("↑/↓", "Inspect"), KeyHint("Tab/→", "Details"), PAGE_KEYS, HOME_END_KEYS, SCROLL_DETAILS_KEYS,
            )
            else -> listOf(SCROLL_KEY, SCROLL_DETAILS_KEYS, KeyHint("Tab/←", "List")) +
                (if (model is ApplyModel.Confirmation) listOf() else listOf(KeyHint("Esc", "Back")))
        }
        val commands = when (model) {
            is ApplyModel.Idle -> listOf(KeyHint("1", "Workspace"), HELP_KEY, QUIT_KEY)
            is ApplyModel.Confirmation ->
                if (!model.plan.hasChanges()) listOf(KeyHint("1/Enter/n/Esc", "Workspace"), HELP_KEY, QUIT_KEY)
                else listOf(KeyHint("y", "Apply"), KeyHint("n/Esc/1", "Cancel"), HELP_KEY, QUIT_KEY)
            // Once HomeLight will exit when the apply finishes, `q` does nothing.
            is ApplyModel.Running -> listOfNotNull(HELP_KEY, QUIT_KEY.takeUnless { quitting })
            is ApplyModel.Result -> listOf(KeyHint("1/Enter", "Workspace"), CHECK_AGAIN_KEY, HELP_KEY, QUIT_KEY)
        }
        return ScreenKeys(navigation, commands)
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
        if (action.destructive) add(DetailViewport.Line(DELETES_OR_REPLACES, palette.warn, true))
        add(DetailViewport.Line(affectedPath(action)))
        if (destination(action).isNotEmpty()) add(DetailViewport.Line(destination(action)))
        val relocation = step.relocation.relocation
        if (action.path != relocation.sourcePath) add(DetailViewport.Line("Source: " + relocation.sourcePath))
        if (action.path != relocation.targetPath && action.destination != relocation.targetPath) {
            add(DetailViewport.Line("Target: " + relocation.targetPath))
        }
    }

    fun affectedPath(action: ReconciliationAction): String = when (action) {
        is ReconciliationAction.EnsureDirectory -> "Parent folder: "
        is ReconciliationAction.CreateDirectory -> "Create at: "
        is ReconciliationAction.MigrateDirectoryForPublication -> "Copy from: "
        is ReconciliationAction.ArchiveDirectory -> "Archive from: "
        is ReconciliationAction.DeleteDirectory -> "Delete at: "
        is ReconciliationAction.CreateSymlink, is ReconciliationAction.ReplaceDirectoryWithSymlink,
        is ReconciliationAction.ReplaceSymlink -> "Link at: "
        is ReconciliationAction.NoOp, is ReconciliationAction.LeaveUnchanged -> "Path: "
        is ReconciliationAction.Blocked -> "Blocked path: "
    } + action.path

    fun destination(action: ReconciliationAction): String {
        val destination = action.destination ?: return ""
        return when (action) {
            is ReconciliationAction.ArchiveDirectory -> "Archive: "
            is ReconciliationAction.MigrateDirectoryForPublication -> "Copy to: "
            is ReconciliationAction.CreateSymlink, is ReconciliationAction.ReplaceDirectoryWithSymlink,
            is ReconciliationAction.ReplaceSymlink -> "Link to: "
            // Unreachable: these actions have no destination.
            is ReconciliationAction.EnsureDirectory, is ReconciliationAction.CreateDirectory,
            is ReconciliationAction.DeleteDirectory, is ReconciliationAction.NoOp,
            is ReconciliationAction.LeaveUnchanged, is ReconciliationAction.Blocked -> ""
        } + destination
    }

    // Indented under its relocation line by the pointer's two cells only: at 80 columns a deeper indent would cut the
    // longest label, "Replace source with a link ⚠", once the scrollbar's cell is kept free.
    private fun actionRow(pointer: String, step: ApplyModel.Step, spinnerFrame: Int): StyledElement<*> = markedRow(
        pointer, mark(step.status, step.action.mutatesFilesystem, spinnerFrame),
        actionLabel(step.action) + (if (step.action.destructive) " ⚠" else ""), color(step),
    )

    /**
     * A list row: the pointer (none on a relocation line), a status mark, then the label, shortened in the middle.
     *
     * TamboUI reserves the scrollbar's column by counting items, not lines, so with two-line items the scrollbar can
     * cover a row's last cell: the trailing space is what it covers.
     */
    private fun markedRow(
        prefix: String, mark: StyledElement<*>, label: String, color: Color, bold: Boolean = false,
    ): StyledElement<*> {
        val text = Toolkit.text("$label ").fg(color).ellipsisMiddle().fill()
        val cells = listOf(mark.length(2), if (bold) text.bold() else text)
        val row = if (prefix.isEmpty()) cells else listOf(Toolkit.text(prefix).fg(palette.focus).length(CharWidth.of(prefix))) + cells
        return Toolkit.row(*row.toTypedArray())
    }

    /** TamboUI's spinner while running; otherwise the step's glyph. `changes` is false for steps that change nothing. */
    private fun mark(status: ApplyModel.StepStatus, changes: Boolean, spinnerFrame: Int): StyledElement<*> {
        if (!changes && status != ApplyModel.StepStatus.FAILED) return Toolkit.text("─").fg(palette.dim)
        return when (status) {
            ApplyModel.StepStatus.PENDING -> Toolkit.text("○").fg(palette.dim)
            // The spinner advances its state once before drawing, and a fresh state per frame keeps rows in step.
            ApplyModel.StepStatus.RUNNING ->
                Toolkit.spinner(*SPINNER_FRAMES).state(SpinnerState(spinnerFrame.toLong())).fg(palette.focus)
            ApplyModel.StepStatus.COMPLETED -> Toolkit.text("✔").fg(palette.ok)
            ApplyModel.StepStatus.FAILED -> Toolkit.text("✖").fg(palette.error)
        }
    }

    /** A relocation's own status: failed if any step failed, done when all are, running while any runs. */
    private fun relocationStatus(steps: List<ApplyModel.Step>): ApplyModel.StepStatus = when {
        steps.any { it.status == ApplyModel.StepStatus.FAILED } -> ApplyModel.StepStatus.FAILED
        steps.all { it.status == ApplyModel.StepStatus.COMPLETED } -> ApplyModel.StepStatus.COMPLETED
        steps.any { it.status == ApplyModel.StepStatus.RUNNING } -> ApplyModel.StepStatus.RUNNING
        else -> ApplyModel.StepStatus.PENDING
    }

    private fun color(step: ApplyModel.Step): Color = when (step.status) {
        ApplyModel.StepStatus.PENDING -> palette.dim
        ApplyModel.StepStatus.RUNNING -> palette.focus
        ApplyModel.StepStatus.COMPLETED -> if (step.action.mutatesFilesystem) palette.ok else palette.dim
        ApplyModel.StepStatus.FAILED -> palette.error
    }
}
