package io.github.bigswlittlesw.homelight.tui

import dev.tamboui.toolkit.Toolkit
import dev.tamboui.toolkit.element.Element
import dev.tamboui.toolkit.elements.Column
import dev.tamboui.toolkit.event.EventResult
import dev.tamboui.tui.TuiConfig
import dev.tamboui.tui.bindings.BindingSets
import dev.tamboui.tui.bindings.Bindings
import dev.tamboui.tui.event.KeyCode
import dev.tamboui.tui.event.KeyEvent
import io.github.bigswlittlesw.homelight.application.ApplyModel
import io.github.bigswlittlesw.homelight.application.ConfigurationEvaluation
import io.github.bigswlittlesw.homelight.application.DecisionChoice
import io.github.bigswlittlesw.homelight.application.HomeLightSession
import io.github.bigswlittlesw.homelight.application.PlanModel
import io.github.bigswlittlesw.homelight.application.PlanRelocationItem
import io.github.bigswlittlesw.homelight.discovery.CandidateDiscovery
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction
import java.nio.file.Path

/**
 * Key handlers read navigation from this set: arrows plus `h`/`j`/`k`/`l` and `g`/`G`, matched without Ctrl or Alt.
 *
 * Vim also binds `x` (delete forward) and Ctrl+U/Ctrl+D (page up/down). HomeLight has no such actions; setup
 * ignores the paging chords and its text fields take typed characters before any binding.
 */
internal val KEY_BINDINGS: Bindings = BindingSets.vim()

/**
 * Owns navigation and inspection; the session owns decisions and guarded execution.
 *
 * `discoveryFactory` gives each reopened setup its own discovery lifetime.
 */
internal class HomeLightApp(
    val session: HomeLightSession,
    private val customTuiConfig: TuiConfig? = null,
    startSetup: Boolean = false,
    private val discoveryFactory: () -> CandidateDiscovery = { CandidateDiscovery() },
) {
    internal var activeScreen = Screen.WORKSPACE
        private set
    private var selectedIndex = 0
    private var actionIndex = 0
    private var reviewedSource: Path? = null
    private var paneFocus = PaneFocus.MASTER
    private var actionFocus = PaneFocus.MASTER
    var detailSelectedIndex = 0
        private set
    var showInSync = false
        private set
    private var userShowInSync: Boolean? = null
    private var spinnerFrame = 0
    private var followedAction: ReconciliationAction? = null
    private var displayedResult: ApplyModel.Result? = null
    private val workspaceDetails = DetailViewport()
    private val actionDetails = DetailViewport()
    private var exitIntent = ExitIntent.STAY
    private var setup: SetupView? = if (startSetup) SetupView(session, discoveryFactory) else null

    private enum class ExitIntent { STAY, CONFIRM_KEEP, CONFIRM_EXIT, AFTER_EXECUTION, EXIT }

    init {
        syncInSyncSetting()
    }

    // The key handlers depend on KEY_BINDINGS, so a custom configuration gets them too.
    internal fun configure(): TuiConfig =
        (customTuiConfig ?: TuiConfig.defaults()).toBuilder().bindings(KEY_BINDINGS).build()

    fun run() { runTui(this) }

    internal fun exitRequested(): Boolean = exitIntent == ExitIntent.EXIT

    internal fun render(): Element {
        settleDeferredExit()
        val view = setup?.render() ?: if (activeScreen == Screen.APPLY) renderApply()
        else WorkspaceView.render(session, selectedIndex, showInSync, paneFocus, detailSelectedIndex, workspaceDetails)
        var content: Column = if (view is Column) view.fill() else Toolkit.column(view).fill()
        if (exitIntent == ExitIntent.CONFIRM_KEEP || exitIntent == ExitIntent.CONFIRM_EXIT) {
            content = Toolkit.column(
                content,
                Toolkit.panel(
                    "Quit HomeLight?",
                    Toolkit.column(
                        Toolkit.text("Filesystem operations will finish, including on failure."),
                        Toolkit.text("Results are session-local and won't remain available after exit."),
                        Toolkit.text((if (exitIntent == ExitIntent.CONFIRM_KEEP) "❯ " else "  ") + "Keep running"),
                        Toolkit.text((if (exitIntent == ExitIntent.CONFIRM_EXIT) "❯ " else "  ") + "Exit when execution finishes"),
                        Toolkit.text("↑/↓ or Tab: Choose  ·  Enter: Confirm  ·  Esc: Cancel").gray(),
                    ),
                ).length(7),
            )
        } else if (exitIntent == ExitIntent.AFTER_EXECUTION) {
            content = Toolkit.column(
                content, Toolkit.text("Will exit after execution settles, including failure.").yellow(),
                Toolkit.text("Session-local results won't remain available after exit.").gray(),
            )
        }
        return content.id("homelight-screen").onKeyEvent(this::handleKeyEvent).focusable()
    }

    private fun renderApply(): Element {
        val model = session.applyModel()
        when (model) {
            is ApplyModel.Running -> {
                val running = model.steps.indexOfFirst { step ->
                    step.status == ApplyModel.StepStatus.RUNNING && step.action !== followedAction
                }
                if (running >= 0) {
                    // Manual inspection lasts until the next action transition.
                    actionIndex = running
                    actionDetails.reset()
                    followedAction = model.steps[running].action
                }
            }
            is ApplyModel.Result -> {
                if (model !== displayedResult) {
                    for ((i, step) in model.steps.withIndex()) {
                        if (step.status == ApplyModel.StepStatus.COMPLETED || step.status == ApplyModel.StepStatus.FAILED) actionIndex = i
                        if (step.status == ApplyModel.StepStatus.FAILED) break
                    }
                    actionDetails.reset()
                    displayedResult = model
                }
            }
            is ApplyModel.Idle, is ApplyModel.Confirmation -> {
                followedAction = null
                displayedResult = null
            }
        }
        return ApplyView.render(session.configPath, model, actionIndex, spinnerFrame++, actionFocus, actionDetails)
    }

    fun handleKeyEvent(key: KeyEvent): EventResult {
        if (exitIntent == ExitIntent.CONFIRM_KEEP || exitIntent == ExitIntent.CONFIRM_EXIT) return handleExitDialog(key)
        val currentSetup = setup
        if (currentSetup != null) {
            currentSetup.key(key)
            if (currentSetup.closed) { setup = null; workspaceDetails.reset(); syncInSyncSetting() }
            return EventResult.HANDLED
        }
        if (key.isKey(KeyCode.ESCAPE)) {
            if (activeScreen == Screen.APPLY && session.applyModel() is ApplyModel.Confirmation) {
                session.cancelApply()
                activeScreen = Screen.WORKSPACE
            } else if (activeScreen == Screen.APPLY) actionFocus = PaneFocus.MASTER
            else paneFocus = PaneFocus.MASTER
            return EventResult.HANDLED
        }
        if (key.isQuit()) {
            if (exitIntent == ExitIntent.STAY) exitIntent = if (session.isApplying() || !session.executionSettled())
                ExitIntent.CONFIRM_KEEP else ExitIntent.EXIT
            return EventResult.HANDLED
        }
        if (exitIntent == ExitIntent.EXIT) return EventResult.HANDLED
        if (key.isCharIgnoreCase('i') && (session.evaluation() is ConfigurationEvaluation.Missing ||
                session.evaluation() is ConfigurationEvaluation.Unconfigured)
        ) {
            setup = SetupView(session, discoveryFactory)
            return EventResult.HANDLED
        }
        if (key.isChar('1')) { switchScreen(Screen.WORKSPACE); return EventResult.HANDLED }
        if (key.isChar('2')) { switchScreen(Screen.APPLY); return EventResult.HANDLED }
        if (activeScreen == Screen.APPLY) return handleApplyKeyEvent(key)
        if (key.isCharIgnoreCase('r')) { refresh(); return EventResult.HANDLED }
        if (key.isCharIgnoreCase('a')) { switchScreen(Screen.APPLY); return EventResult.HANDLED }
        if (key.isCharIgnoreCase('c')) { toggleInSync(); return EventResult.HANDLED }
        if (key.isChar('[') || key.isChar(']')) {
            workspaceDetails.scroll(if (key.isChar(']')) 1 else -1)
            return EventResult.HANDLED
        }
        if (isTab(key) || key.isRight()) {
            paneFocus = if (isTab(key) && paneFocus == PaneFocus.DETAIL) PaneFocus.MASTER else PaneFocus.DETAIL
            if (paneFocus == PaneFocus.DETAIL) workspaceDetails.followChoice()
            return EventResult.HANDLED
        }
        if (key.isLeft()) { paneFocus = PaneFocus.MASTER; return EventResult.HANDLED }
        val item = selectedPlanItem()
        val choices = paneFocus == PaneFocus.DETAIL && item != null && item.availableResolutions.isNotEmpty() &&
            session.applyModel() !is ApplyModel.Result
        if (key.isUp() || key.isDown()) {
            val delta = if (key.isUp()) -1 else 1
            if (choices) {
                detailSelectedIndex = (detailSelectedIndex + delta).coerceIn(0, item.availableResolutions.size - 1)
                workspaceDetails.followChoice()
            } else if (paneFocus == PaneFocus.DETAIL) workspaceDetails.scroll(delta)
            else if (delta < 0) selectPrevious() else selectNext()
        } else if (key.isHome() || key.isEnd()) {
            val end = key.isEnd()
            if (choices) {
                detailSelectedIndex = if (end) item.availableResolutions.size - 1 else 0
                workspaceDetails.followChoice()
            } else if (paneFocus == PaneFocus.DETAIL) workspaceDetails.scroll(if (end) Int.MAX_VALUE else -Int.MAX_VALUE)
            else if (end) selectLast() else selectFirst()
        } else if (key.isSelect()) {
            if (choices) resolveSelected(item.availableResolutions[detailSelectedIndex])
            else { paneFocus = PaneFocus.DETAIL; workspaceDetails.followChoice() }
        }
        return EventResult.HANDLED
    }

    internal fun closeSetup() {
        setup?.close()
        setup = null
    }

    private fun handleApplyKeyEvent(key: KeyEvent): EventResult {
        val model = session.applyModel()
        if (isTab(key) || key.isRight()) {
            actionFocus = if (isTab(key) && actionFocus == PaneFocus.DETAIL) PaneFocus.MASTER else PaneFocus.DETAIL
        } else if (key.isLeft()) actionFocus = PaneFocus.MASTER
        else if (key.isChar('[') || key.isChar(']')) actionDetails.scroll(if (key.isChar(']')) 1 else -1)
        else if (key.isUp()) {
            if (actionFocus == PaneFocus.DETAIL) actionDetails.scroll(-1) else selectPrevious()
        } else if (key.isDown()) {
            if (actionFocus == PaneFocus.DETAIL) actionDetails.scroll(1) else selectNext()
        } else if (model is ApplyModel.Running || exitIntent == ExitIntent.AFTER_EXECUTION) return EventResult.HANDLED
        else if (model is ApplyModel.Confirmation) {
            if (key.isChar('y') && model.plan.hasChanges()) session.confirmApply()
            else if (key.isCharIgnoreCase('n') || !model.plan.hasChanges() && key.isKey(KeyCode.ENTER)) {
                session.cancelApply()
                activeScreen = Screen.WORKSPACE
            }
        } else if (model is ApplyModel.Result) {
            if (key.isKey(KeyCode.ENTER) || key.isChar('1')) switchScreen(Screen.WORKSPACE)
            else if (key.isCharIgnoreCase('r')) refresh()
        }
        return EventResult.HANDLED
    }

    private fun handleExitDialog(key: KeyEvent): EventResult {
        if (key.isKey(KeyCode.ESCAPE)) exitIntent = ExitIntent.STAY
        else if (key.isUp()) exitIntent = ExitIntent.CONFIRM_KEEP
        else if (key.isDown()) exitIntent = ExitIntent.CONFIRM_EXIT
        else if (isTab(key)) exitIntent = if (exitIntent == ExitIntent.CONFIRM_KEEP) ExitIntent.CONFIRM_EXIT else ExitIntent.CONFIRM_KEEP
        else if (key.isKey(KeyCode.ENTER)) {
            exitIntent = if (exitIntent == ExitIntent.CONFIRM_KEEP) ExitIntent.STAY else ExitIntent.AFTER_EXECUTION
            settleDeferredExit()
        }
        return EventResult.HANDLED
    }

    private fun isTab(key: KeyEvent): Boolean =
        key.isKey(KeyCode.TAB) || key.isChar('\t') || key.isFocusNext() || key.isFocusPrevious()

    private fun settleDeferredExit() {
        // Result publication alone does not imply that post-execution refresh has settled.
        if (exitIntent == ExitIntent.AFTER_EXECUTION && session.executionSettled()) exitIntent = ExitIntent.EXIT
    }

    internal fun switchScreen(screen: Screen) {
        if (session.isApplying() || !session.executionSettled()) return
        if (screen == activeScreen) return
        if (screen == Screen.APPLY) {
            if (session.applyModel() is ApplyModel.Idle && !session.requestApply()) return
            if (activeScreen == Screen.WORKSPACE) selectedPlanItem()?.let { reviewedSource = it.relocation.sourcePath }
            if (activeScreen != Screen.APPLY && session.applyModel() is ApplyModel.Confirmation) {
                actionIndex = 0
                actionFocus = PaneFocus.MASTER
                actionDetails.reset()
            }
        } else { session.cancelApply(); restoreSelection(reviewedSource) }
        activeScreen = screen
    }

    private fun refresh() {
        if (session.isApplying() || !session.executionSettled()) return
        val source = selectedPlanItem()?.relocation?.sourcePath
        session.refresh()
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
        val model = session.planModel()
        return if (model is PlanModel.Configured) WorkspaceView.visibleItems(model, showInSync) else listOf()
    }

    private fun selectedPlanItem(): PlanRelocationItem? {
        val items = visibleItems()
        return if (items.isEmpty()) null else items[selectedIndex.coerceIn(0, items.size - 1)]
    }

    private fun toggleInSync() {
        val source = selectedPlanItem()?.relocation?.sourcePath
        showInSync = !showInSync
        userShowInSync = showInSync
        restoreSelection(source)
    }

    private fun selectPrevious() { select(-1, false) }
    private fun selectNext() { select(1, false) }
    private fun selectFirst() { select(0, true) }
    private fun selectLast() { select(Int.MAX_VALUE, true) }

    private fun select(value: Int, absolute: Boolean) {
        if (activeScreen == Screen.APPLY) {
            val last = maxOf(0, ApplyView.steps(session.applyModel()).size - 1)
            actionIndex = (if (absolute) value else actionIndex + value).coerceIn(0, last)
            actionDetails.reset()
        } else {
            selectedIndex = (if (absolute) value else selectedIndex + value).coerceIn(0, maxOf(0, visibleItems().size - 1))
            resetDetailSelection()
        }
    }

    private fun resetDetailSelection() {
        val item = selectedPlanItem()
        detailSelectedIndex = item?.selectedResolution()?.let { choice -> maxOf(0, item.availableResolutions.indexOf(choice)) } ?: 0
        workspaceDetails.reset()
    }

    private fun restoreSelection(source: Path?) {
        val index = visibleItems().indexOfFirst { it.relocation.sourcePath == source }
        if (index >= 0) selectedIndex = index else clampSelection()
    }

    private fun clampSelection() {
        selectedIndex = selectedIndex.coerceIn(0, maxOf(0, visibleItems().size - 1))
    }

    private fun syncInSyncSetting() {
        showInSync = userShowInSync ?: session.planModel().let { model ->
            model is PlanModel.Configured && model.summary.inSync == model.summary.total
        }
        clampSelection()
    }

    // Read only by tests.
    internal fun planModel(): PlanModel = session.planModel()
    internal fun selectedIndex(): Int = if (activeScreen == Screen.APPLY) actionIndex else selectedIndex
    internal fun paneFocus(): PaneFocus = if (activeScreen == Screen.APPLY) actionFocus else paneFocus
}
