package io.github.bigswlittlesw.homelight.tui

import dev.tamboui.style.Color
import dev.tamboui.style.Style
import dev.tamboui.toolkit.Toolkit
import dev.tamboui.toolkit.element.Element
import dev.tamboui.toolkit.element.StyledElement
import dev.tamboui.toolkit.elements.TreeElement
import dev.tamboui.toolkit.event.EventResult
import dev.tamboui.toolkit.event.KeyEventHandler
import dev.tamboui.widgets.common.ScrollBarPolicy
import dev.tamboui.widgets.spinner.SpinnerState
import dev.tamboui.widgets.tree.GuideStyle
import dev.tamboui.widgets.tree.TreeNode
import io.github.bigswlittlesw.homelight.application.ApplyModel
import io.github.bigswlittlesw.homelight.application.pendingSteps
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlan
import io.github.bigswlittlesw.homelight.reconcile.RelocationPlan
import java.nio.file.Path

/** One row of Review's plan tree: a relocation with its steps, or one of those steps. */
internal sealed interface PlanRow {
    data class RelocationRow(val plan: RelocationPlan, val steps: List<ApplyModel.Step>) : PlanRow {
        /** The step rows under it: none for an in-sync relocation, whose single step changes nothing. */
        val children: List<StepRow>
            get() = if (steps.size == 1 && steps.single().action is ReconciliationAction.NoOp) listOf() else steps.map(::StepRow)
    }

    data class StepRow(val step: ApplyModel.Step) : PlanRow
}

/** Renders the review and results screen. The object names the screen; it holds no state. */
internal object ApplyView {
    private val SPINNER_FRAMES = arrayOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")

    /**
     * The plan tree. One instance lives across frames: TamboUI keeps its selection and scroll offset. Every relocation
     * stays expanded, so the selection is an index into [rows].
     *
     * The tree moves its selection on ↑/↓, PageUp/PageDown and Home/End. Every other key goes to `others` first, so
     * TamboUI's expand, collapse and toggle on ←/→, Enter and Space never run: → still opens Details and Enter still
     * leaves Results. The pointer is one cell wide, so at 80 columns "Replace source with a link ⚠" fits beside the
     * scrollbar.
     */
    fun tree(others: KeyEventHandler): TreeElement<PlanRow> = TreeElement<PlanRow>().id(REVIEW_LIST)
        .guideStyle(GuideStyle.UNICODE).indentWidth(2).highlightSymbol("❯").highlightStyle(Style.EMPTY.bold())
        .scrollbar(ScrollBarPolicy.AS_NEEDED).scrollbarThumbColor(palette.focus).scrollbarTrackColor(palette.dim)
        .onKeyEvent { key ->
            if (key.isUp() || key.isDown() || key.isPageUp() || key.isPageDown() || key.isHome() || key.isEnd()) {
                EventResult.UNHANDLED
            } else others.handle(key)
        }

    /**
     * `focused` is the focused element's id; `interactive` is false while a dialog is open over the screen.
     * `quitting` is true once HomeLight will exit when the apply finishes, so `q` no longer does anything.
     */
    fun render(
        config: Path, model: ApplyModel, tree: TreeElement<PlanRow>, spinnerFrame: Int = 0,
        focused: String? = REVIEW_LIST, interactive: Boolean = true, viewport: DetailViewport = DetailViewport(),
        quitting: Boolean = false,
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
                Toolkit.text(screenHelp(model, focused, quitting).let { helpLine(it.navigation + it.commands) }).fg(palette.dim),
            )
            is ApplyModel.Reviewed -> model
        }
        val plan = reviewed.plan
        val steps = steps(model)
        val rows = rows(steps)
        val selected = tree.selected().coerceIn(0, maxOf(0, rows.size - 1))
        // Rebuilt every frame, so the marks follow the model; the tree keeps only its selection and scroll offset.
        tree.roots(*nodes(rows).toTypedArray()).nodeRenderer { node -> node.data()?.let { rowElement(it, spinnerFrame) } }
            .focusable(interactive).fill()
        // Framed by a panel, which can show focus with a thick border; TreeElement offers only rounded.
        val treePane = framed(Toolkit.panel(REVIEW_LIST_TITLE, tree), focused == REVIEW_LIST)
        val detailLines = if (rows.isEmpty()) listOf(DetailViewport.Line(NO_STEPS))
        else details(rows[selected], plan) +
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
                    treePane.percent(45),
                    viewport.render(DETAILS_NAME, detailLines, focused == REVIEW_DETAILS, 0, REVIEW_DETAILS, interactive),
                ).fill(),
            )
            add(viewport.help(screenHelp(model, focused, quitting), interactive))
        }
        return Toolkit.column(*content.toTypedArray()).fill()
    }

    /** Review's purpose and keys in its current state, for its help lines and the Help screen. */
    fun screenHelp(model: ApplyModel, focused: String?, quitting: Boolean): ScreenHelp {
        val navigation = when {
            model is ApplyModel.Idle -> listOf()
            focused != REVIEW_DETAILS -> listOf(
                KeyHint("↑/↓", "Inspect", description = "Select a relocation or a step to see its details"),
                KeyHint("Tab/→", "Details", description = "Move to the selected row's details"),
                PAGE_KEYS, HOME_END_KEYS, SCROLL_DETAILS_KEYS,
            )
            else -> listOf(SCROLL_KEY, SCROLL_DETAILS_KEYS, KeyHint("Tab/←", "List", description = "Back to the plan")) +
                (if (model is ApplyModel.Confirmation) listOf()
                else listOf(KeyHint("Esc", "Back", description = "Back to the plan")))
        }
        val commands = when (model) {
            is ApplyModel.Idle -> listOf(KeyHint("1", "Workspace", description = "Go to the Workspace"), HELP_KEY, QUIT_KEY)
            is ApplyModel.Confirmation ->
                if (!model.plan.hasChanges()) listOf(
                    KeyHint("1/Enter/n/Esc", "Workspace", description = "Back to the Workspace"), HELP_KEY, QUIT_KEY,
                )
                else listOf(
                    KeyHint("y", "Apply", description = "Apply the plan; this changes files on disk"),
                    KeyHint("n/Esc/1", "Cancel", description = "Back to the Workspace; nothing changes"), HELP_KEY, QUIT_KEY,
                )
            // Once HomeLight will exit when the apply finishes, `q` does nothing.
            is ApplyModel.Running -> listOfNotNull(HELP_KEY, QUIT_KEY.takeUnless { quitting })
            is ApplyModel.Result -> listOf(
                KeyHint("1/Enter", "Workspace", description = "Back to the Workspace; the results stay until you check again"),
                CHECK_AGAIN_KEY, HELP_KEY, QUIT_KEY,
            )
        }
        fun help(name: String, purpose: String, step: Step) = ScreenHelp(
            if (focused == REVIEW_DETAILS) place(name, DETAILS_NAME) else name, purpose, step, navigation, commands,
        )
        return when (model) {
            is ApplyModel.Idle -> help(REVIEW_NAME, NOTHING_TO_REVIEW, Step.REVIEW)
            is ApplyModel.Confirmation ->
                help(REVIEW_NAME, if (model.plan.hasChanges()) PURPOSE_REVIEW else PURPOSE_NO_CHANGES, Step.REVIEW)
            is ApplyModel.Running -> help(APPLYING_NAME, PURPOSE_APPLYING, Step.APPLY)
            is ApplyModel.Result -> help(RESULTS_NAME, PURPOSE_RESULTS, Step.RESULTS)
        }
    }

    fun steps(model: ApplyModel): List<ApplyModel.Step> = when (model) {
        is ApplyModel.Idle -> listOf()
        is ApplyModel.Confirmation -> pendingSteps(model.plan)
        is ApplyModel.Running -> model.steps
        is ApplyModel.Result -> model.steps
    }

    /** What Details shows for the selected row of `plan`. */
    fun details(row: PlanRow, plan: ReconciliationPlan): List<DetailViewport.Line> = when (row) {
        is PlanRow.RelocationRow -> relocationDetails(row, plan)
        is PlanRow.StepRow -> details(row.step)
    }

    /**
     * The relocation's path, the decision that shaped its steps and its paths. The decision is the rule for what
     * the reviewed plan observed, so Results keep it after the disk changes.
     */
    private fun relocationDetails(row: PlanRow.RelocationRow, plan: ReconciliationPlan): List<DetailViewport.Line> {
        val relocation = row.plan.relocation
        val reviewed = plan.expectedStates.firstOrNull { it.relocation.sourcePath == relocation.sourcePath }
        val rule = reviewed?.let { WorkspaceView.rule(relocation, it.source.state, it.target.state) }
        val archive = row.steps.firstNotNullOfOrNull { (it.action as? ReconciliationAction.ArchiveDirectory)?.destination }
        return listOfNotNull(
            DetailViewport.Line(displayPath(relocation.sourcePath), palette.text, true),
            if (row.children.isEmpty()) DetailViewport.Line(actionLabel(row.steps.single().action), palette.dim, false) else null,
            rule?.let { DetailViewport.Line(reviewDecision(it)) },
            DetailViewport.Line(""),
            DetailViewport.Line(PATHS, palette.text, true),
            DetailViewport.Line(sourceLine(relocation.sourcePath)),
            DetailViewport.Line(targetLine(relocation.targetPath)),
            archive?.let { DetailViewport.Line(archiveLine(it)) },
        )
    }

    fun details(step: ApplyModel.Step): List<DetailViewport.Line> = buildList {
        val action = step.action
        add(DetailViewport.Line(actionLabel(action), color(step), true))
        if (step.status != ApplyModel.StepStatus.PENDING) add(DetailViewport.Line(step.message, color(step), false))
        if (action.destructive) add(DetailViewport.Line(DELETES_OR_REPLACES, palette.warn, true))
        add(DetailViewport.Line(affectedPath(action)))
        if (destination(action).isNotEmpty()) add(DetailViewport.Line(destination(action)))
        val relocation = step.relocation.relocation
        if (action.path != relocation.sourcePath) add(DetailViewport.Line(sourceLine(relocation.sourcePath)))
        if (action.path != relocation.targetPath && action.destination != relocation.targetPath) {
            add(DetailViewport.Line(targetLine(relocation.targetPath)))
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

    /** The tree's rows in display order: each relocation, then its step rows. */
    fun rows(steps: List<ApplyModel.Step>): List<PlanRow> = steps.groupBy { it.relocation }.flatMap { (plan, own) ->
        val relocation = PlanRow.RelocationRow(plan, own)
        listOf(relocation) + relocation.children
    }

    /** The tree's nodes for `rows`; flattened, they are `rows` again. Labels are unused: rows draw themselves. */
    private fun nodes(rows: List<PlanRow>): List<TreeNode<PlanRow>> =
        rows.filterIsInstance<PlanRow.RelocationRow>().map { relocation ->
            TreeNode.of<PlanRow>("", relocation).expanded().apply {
                relocation.children.forEach { child -> add(TreeNode.of<PlanRow>("", child).leaf()) }
            }
        }

    /**
     * A relocation row: its mark and path. The tree draws `▼` before a relocation with step rows, so an in-sync
     * relocation, which has none, starts two cells in to keep the marks in one column. A step row: its mark and label;
     * the tree's guide (`├─`, `└─`) sits before it.
     */
    private fun rowElement(row: PlanRow, spinnerFrame: Int): StyledElement<*> = when (row) {
        is PlanRow.RelocationRow -> {
            val path = displayPath(row.plan.relocation.sourcePath)
            if (row.children.isEmpty()) markedRow(mark(row.steps.single().status, false, spinnerFrame), inSyncRow(path),
                palette.dim, indent = 2)
            else markedRow(mark(relocationStatus(row.steps), row.steps.any { it.action.mutatesFilesystem }, spinnerFrame),
                path, palette.text, bold = true)
        }
        is PlanRow.StepRow -> markedRow(
            mark(row.step.status, row.step.action.mutatesFilesystem, spinnerFrame),
            actionLabel(row.step.action) + (if (row.step.action.destructive) " ⚠" else ""), color(row.step),
        )
    }

    /** A status mark, then the label, shortened in the middle. */
    private fun markedRow(
        mark: StyledElement<*>, label: String, color: Color, bold: Boolean = false, indent: Int = 0,
    ): StyledElement<*> {
        val text = Toolkit.text(label).fg(color).ellipsisMiddle().fill()
        val cells = listOf(mark.length(2), if (bold) text.bold() else text)
        return Toolkit.row(*(if (indent == 0) cells else listOf(Toolkit.text("").length(indent)) + cells).toTypedArray())
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
