package io.github.bigswlittlesw.homelight.tui

import dev.tamboui.toolkit.Toolkit
import dev.tamboui.toolkit.element.Element
import dev.tamboui.toolkit.elements.Column
import dev.tamboui.toolkit.event.EventResult
import dev.tamboui.toolkit.event.GlobalEventHandler
import dev.tamboui.toolkit.focus.FocusManager
import dev.tamboui.tui.bindings.BindingSets
import dev.tamboui.tui.bindings.Bindings
import dev.tamboui.tui.event.KeyCode
import dev.tamboui.tui.event.KeyEvent
import io.github.bigswlittlesw.homelight.application.ApplyModel
import io.github.bigswlittlesw.homelight.application.ConfigurationEvaluation
import io.github.bigswlittlesw.homelight.application.DecisionChoice
import io.github.bigswlittlesw.homelight.application.HomeLightSession
import io.github.bigswlittlesw.homelight.application.PlanBadge
import io.github.bigswlittlesw.homelight.application.PlanRelocationItem
import io.github.bigswlittlesw.homelight.discovery.CandidateDiscovery
import java.nio.file.Path

/**
 * Key handlers read navigation from this set: arrows, Home/End and PageUp/PageDown, with no letter aliases, so
 * navigation never takes a key a text field could type. `q`, `Q` and Ctrl+C are still quit and Space is still
 * select, so setup's text fields take typed characters before any binding.
 */
internal val KEY_BINDINGS: Bindings = BindingSets.standard()

// Fixed focus ids: TamboUI's FocusManager tracks focus by id, and an element without one gets a new id every frame.
internal const val WORKSPACE_LIST = "workspace-list"
internal const val WORKSPACE_DETAILS = "workspace-details"
internal const val REVIEW_LIST = "review-list"
internal const val REVIEW_DETAILS = "review-details"
internal const val SETUP_SCREEN = "setup"
internal const val DIALOG = "dialog"

/**
 * Owns navigation and inspection; the session owns decisions and guarded execution.
 *
 * Focus is TamboUI's: the focused element takes its own keys first (a list moves its selection, a dialog answers),
 * and [keyHandler] gets every key it leaves. `discoveryFactory` gives each reopened setup its own discovery lifetime.
 */
internal class HomeLightApp(
    val session: HomeLightSession,
    private val focus: FocusManager,
    startSetup: Boolean = false,
    private val discoveryFactory: () -> CandidateDiscovery = { CandidateDiscovery() },
) {
    internal var activeScreen = Screen.WORKSPACE
        private set
    private val workspaceList = WorkspaceView.list()
    private val reviewList = ApplyView.list()
    private var reviewedSource: Path? = null
    var detailSelectedIndex = 0
        private set
    var showInSync = false
        private set
    private var userShowInSync: Boolean? = null
    private var spinnerFrame = 0
    // The result the selection has already jumped for: on finish it moves once, then the user's selection stands.
    private var shownResult: ApplyModel.Result? = null
    // What the details panes show. The lists move their selection themselves, so a frame compares against these.
    private var detailsSource: Path? = null
    private var detailsStep = 0
    private var detailsWereFocused = false
    // Where the user was on Workspace, restored when Review or Results returns there.
    private var workspaceFocus: String? = null
    private val workspaceDetails = DetailViewport()
    private val actionDetails = DetailViewport()
    private var exitIntent = ExitIntent.STAY
    private var focusBeforeDialog: String? = null
    private var setup: SetupView? = if (startSetup) SetupView(session, discoveryFactory) else null

    /** `CONFIRM_*` while a quit dialog is open: during an apply, or with one-time choices not applied yet. */
    private enum class ExitIntent { STAY, CONFIRM_APPLYING, CONFIRM_CHOICES, AFTER_EXECUTION, EXIT }

    init {
        syncInSyncSetting()
        // Set on every screen change rather than left to the runner, which focuses the first focusable only after a
        // frame has rendered without focus.
        focus.setFocus(if (startSetup) SETUP_SCREEN else WORKSPACE_LIST)
    }

    /**
     * Handles every key the focused element leaves, keyed by the focused id, and always reports it handled.
     * Otherwise TamboUI would offer it to unfocused lists, which move their selection, clear focus on Escape and
     * quit on `q`.
     */
    val keyHandler = GlobalEventHandler { event ->
        if (event !is KeyEvent) return@GlobalEventHandler EventResult.UNHANDLED
        handleKey(event)
        EventResult.HANDLED
    }

    internal fun exitRequested(): Boolean = exitIntent == ExitIntent.EXIT

    internal fun render(): Element {
        settleDeferredExit()
        // The discard dialog closes setup without a key reaching handleKey.
        dropClosedSetup()
        val dialog = setup?.dialog() ?: quitDialog()
        val interactive = dialog == null
        val view = setup?.render() ?: if (activeScreen == Screen.APPLY) renderApply(interactive) else renderWorkspace(interactive)
        var content: Column = if (view is Column) view.fill() else Toolkit.column(view).fill()
        if (exitIntent == ExitIntent.AFTER_EXECUTION) {
            content = Toolkit.column(
                content, Toolkit.text(QUITTING).fg(palette.warn), Toolkit.text(RESULTS_NOT_KEPT).fg(palette.dim),
            )
        }
        // Every element inherits the root's colors, and the root fills the whole screen with the background.
        return Toolkit.stack(*listOfNotNull(content, dialog).toTypedArray()).bg(palette.background).fg(palette.text)
    }

    private fun quitDialog(): Element? = when (exitIntent) {
        ExitIntent.CONFIRM_APPLYING -> confirmDialog(
            QUIT_TITLE, QUIT_BODY, QUIT_KEYS,
            onYes = { closeQuitDialog(ExitIntent.AFTER_EXECUTION) },
            onNo = { closeQuitDialog(ExitIntent.STAY) },
        )
        ExitIntent.CONFIRM_CHOICES -> confirmDialog(
            QUIT_TITLE, unappliedChoices(unappliedChoiceCount()), QUIT_CHOICES_KEYS,
            onYes = { closeQuitDialog(ExitIntent.EXIT) },
            onNo = { closeQuitDialog(ExitIntent.STAY) },
        )
        ExitIntent.STAY, ExitIntent.AFTER_EXECUTION, ExitIntent.EXIT -> null
    }

    /** One-time choices are kept only in the session, so quitting forgets them. A plan alone is rebuilt next run. */
    private fun unappliedChoiceCount(): Int = (session.evaluation() as? ConfigurationEvaluation.Loaded)?.draft?.size ?: 0

    private fun renderWorkspace(interactive: Boolean): Element {
        val source = selectedPlanItem()?.relocation?.sourcePath
        if (source != detailsSource) {
            detailsSource = source
            resetDetailSelection()
        }
        // However focus arrives (Tab, → or Enter), the details show the choice.
        val detailsFocused = focus.focusedId() == WORKSPACE_DETAILS
        if (detailsFocused && !detailsWereFocused) workspaceDetails.followChoice()
        detailsWereFocused = detailsFocused
        return WorkspaceView.render(
            session, workspaceList, showInSync, focus.focusedId(), interactive, detailSelectedIndex, workspaceDetails,
        )
    }

    private fun renderApply(interactive: Boolean): Element {
        val model = session.applyModel()
        if (model is ApplyModel.Result && model !== shownResult) {
            shownResult = model
            finishedSelection(model.steps)?.let(reviewList::selected)
        }
        if (reviewList.selected() != detailsStep) {
            detailsStep = reviewList.selected()
            actionDetails.reset()
        }
        return ApplyView.render(
            session.configPath, model, reviewList, spinnerFrame++, focus.focusedId(), interactive, actionDetails,
            quitting = exitIntent == ExitIntent.AFTER_EXECUTION,
        )
    }

    private fun handleKey(key: KeyEvent) {
        setup?.let { current ->
            current.key(key)
            dropClosedSetup()
            return
        }
        if (key.isKey(KeyCode.ESCAPE)) { back(); return }
        if (key.isQuit()) { requestQuit(); return }
        if (exitIntent == ExitIntent.EXIT) return
        if (key.isCharIgnoreCase('i') && (session.evaluation() is ConfigurationEvaluation.Missing ||
                session.evaluation() is ConfigurationEvaluation.Unconfigured)
        ) {
            setup = SetupView(session, discoveryFactory)
            focus.setFocus(SETUP_SCREEN)
            return
        }
        if (key.isChar('1')) { switchScreen(Screen.WORKSPACE); return }
        if (key.isChar('2')) { switchScreen(Screen.APPLY); return }
        if (activeScreen == Screen.APPLY) applyKey(key) else workspaceKey(key)
    }

    /** Esc goes back one level and never exits. */
    private fun back() {
        when {
            // Returning to Workspace cancels the review.
            activeScreen == Screen.APPLY && session.applyModel() is ApplyModel.Confirmation -> switchScreen(Screen.WORKSPACE)
            focus.focusedId() == WORKSPACE_DETAILS -> focus.setFocus(WORKSPACE_LIST)
            focus.focusedId() == REVIEW_DETAILS -> focus.setFocus(REVIEW_LIST)
        }
    }

    private fun workspaceKey(key: KeyEvent) {
        when {
            key.isCharIgnoreCase('r') -> refresh()
            key.isCharIgnoreCase('a') -> switchScreen(Screen.APPLY)
            key.isCharIgnoreCase('c') -> toggleInSync()
            key.isChar('[') || key.isChar(']') -> workspaceDetails.scroll(if (key.isChar(']')) 1 else -1)
            focus.focusedId() == WORKSPACE_LIST && (key.isRight() || key.isSelect()) -> focus.setFocus(WORKSPACE_DETAILS)
            focus.focusedId() == WORKSPACE_DETAILS -> detailsKey(key)
        }
    }

    private fun detailsKey(key: KeyEvent) {
        val item = selectedPlanItem()
        val choices = item != null && item.availableResolutions.isNotEmpty() && session.applyModel() !is ApplyModel.Result
        val last = if (item == null) 0 else item.availableResolutions.size - 1
        when {
            key.isLeft() -> focus.setFocus(WORKSPACE_LIST)
            key.isUp() || key.isDown() -> {
                val delta = if (key.isUp()) -1 else 1
                if (choices) chooseIndex(detailSelectedIndex + delta, last)
                else workspaceDetails.scroll(delta)
            }
            key.isHome() || key.isEnd() -> {
                if (choices) chooseIndex(if (key.isEnd()) last else 0, last)
                else workspaceDetails.scroll(if (key.isEnd()) Int.MAX_VALUE else -Int.MAX_VALUE)
            }
            key.isSelect() && choices -> resolveSelected(item.availableResolutions[detailSelectedIndex])
        }
    }

    private fun chooseIndex(index: Int, last: Int) {
        detailSelectedIndex = index.coerceIn(0, last)
        workspaceDetails.followChoice()
    }

    private fun applyKey(key: KeyEvent) {
        val model = session.applyModel()
        val details = focus.focusedId() == REVIEW_DETAILS
        if (!details && key.isRight()) focus.setFocus(REVIEW_DETAILS)
        else if (details && key.isLeft()) focus.setFocus(REVIEW_LIST)
        else if (key.isChar('[') || key.isChar(']')) actionDetails.scroll(if (key.isChar(']')) 1 else -1)
        else if (details && (key.isUp() || key.isDown())) actionDetails.scroll(if (key.isUp()) -1 else 1)
        else if (model is ApplyModel.Running || exitIntent == ExitIntent.AFTER_EXECUTION) return
        else if (model is ApplyModel.Confirmation) {
            if (key.isChar('y') && model.plan.hasChanges()) session.confirmApply()
            else if (key.isCharIgnoreCase('n') || !model.plan.hasChanges() && key.isKey(KeyCode.ENTER)) {
                switchScreen(Screen.WORKSPACE)
            }
        } else if (model is ApplyModel.Result) {
            if (key.isKey(KeyCode.ENTER)) switchScreen(Screen.WORKSPACE)
            else if (key.isCharIgnoreCase('r')) refresh()
        }
    }

    private fun requestQuit() {
        if (exitIntent != ExitIntent.STAY) return
        exitIntent = when {
            session.isApplying() || !session.executionSettled() -> ExitIntent.CONFIRM_APPLYING
            unappliedChoiceCount() > 0 -> ExitIntent.CONFIRM_CHOICES
            else -> ExitIntent.EXIT
        }
        // A dialog is the only focusable while it is open, so the next frame focuses it.
        if (exitIntent != ExitIntent.EXIT) focusBeforeDialog = focus.focusedId()
    }

    private fun closeQuitDialog(intent: ExitIntent) {
        exitIntent = intent
        focus.setFocus(focusBeforeDialog)
        settleDeferredExit()
    }

    private fun dropClosedSetup() {
        if (setup?.closed != true) return
        setup = null
        workspaceDetails.reset()
        syncInSyncSetting()
        focus.setFocus(WORKSPACE_LIST)
    }

    internal fun closeSetup() {
        setup?.close()
        setup = null
    }

    private fun settleDeferredExit() {
        // Result publication alone does not imply that post-execution refresh has settled.
        if (exitIntent == ExitIntent.AFTER_EXECUTION && session.executionSettled()) exitIntent = ExitIntent.EXIT
    }

    internal fun switchScreen(screen: Screen) {
        if (session.isApplying() || !session.executionSettled()) return
        if (screen == activeScreen) return
        if (screen == Screen.APPLY) {
            if (session.applyModel() is ApplyModel.Idle && !session.requestApply()) return
            selectedPlanItem()?.let { reviewedSource = it.relocation.sourcePath }
            workspaceFocus = focus.focusedId()
            if (session.applyModel() is ApplyModel.Confirmation) {
                reviewList.selected(0)
                actionDetails.reset()
            }
            focus.setFocus(REVIEW_LIST)
        } else {
            session.cancelApply()
            restoreSelection(reviewedSource)
            focus.setFocus(workspaceFocus)
        }
        activeScreen = screen
    }

    private fun refresh() {
        if (session.isApplying() || !session.executionSettled()) return
        val source = selectedPlanItem()?.relocation?.sourcePath
        session.refresh()
        if (activeScreen != Screen.WORKSPACE) focus.setFocus(workspaceFocus)
        activeScreen = Screen.WORKSPACE
        syncInSyncSetting()
        restoreSelection(source)
        resetDetailSelection()
    }

    private fun resolveSelected(choice: DecisionChoice) {
        val item = selectedPlanItem()
        if (item == null || session.applyModel() is ApplyModel.Result) return
        session.choose(item.relocation.sourcePath, choice)
        restoreSelection(item.relocation.sourcePath)
        // Choosing re-plans the same loaded relocations, so the list still has a selected item.
        detailSelectedIndex = selectedPlanItem()!!.availableResolutions.indexOf(choice)
        workspaceDetails.followChoice()
    }

    private fun visibleItems(): List<PlanRelocationItem> {
        val model = session.evaluation()
        return if (model is ConfigurationEvaluation.Loaded) WorkspaceView.visibleItems(model, showInSync) else listOf()
    }

    private fun selectedPlanItem(): PlanRelocationItem? = visibleItems().let { items -> items.getOrNull(workspaceSelection(items)) }

    private fun workspaceSelection(items: List<PlanRelocationItem>): Int =
        workspaceList.selected().coerceIn(0, maxOf(0, items.size - 1))

    private fun toggleInSync() {
        val source = selectedPlanItem()?.relocation?.sourcePath
        showInSync = !showInSync
        userShowInSync = showInSync
        restoreSelection(source)
    }

    private fun resetDetailSelection() {
        val item = selectedPlanItem()
        detailSelectedIndex = item?.selectedResolution()?.let { choice -> maxOf(0, item.availableResolutions.indexOf(choice)) } ?: 0
        workspaceDetails.reset()
    }

    private fun restoreSelection(source: Path?) {
        val items = visibleItems()
        val index = items.indexOfFirst { it.relocation.sourcePath == source }
        workspaceList.selected(if (index >= 0) index else workspaceSelection(items))
    }

    private fun syncInSyncSetting() {
        showInSync = userShowInSync ?: session.evaluation().let { model ->
            model is ConfigurationEvaluation.Loaded && model.items.all { it.badge() == PlanBadge.IN_SYNC }
        }
        workspaceList.selected(workspaceSelection(visibleItems()))
    }

    // Read only by tests.
    internal fun selectedIndex(): Int =
        if (activeScreen == Screen.APPLY) reviewList.selected() else workspaceSelection(visibleItems())
}

/** Where Review's selection moves once when an apply finishes: the first failure, or else the last completed step. */
internal fun finishedSelection(steps: List<ApplyModel.Step>): Int? {
    val failed = steps.indexOfFirst { it.status == ApplyModel.StepStatus.FAILED }
    if (failed >= 0) return failed
    return steps.indexOfLast { it.status == ApplyModel.StepStatus.COMPLETED }.takeIf { it >= 0 }
}
