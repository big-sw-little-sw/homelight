package io.github.bigswlittlesw.homelight.tui

import dev.tamboui.toolkit.Toolkit
import dev.tamboui.toolkit.element.Element
import dev.tamboui.toolkit.elements.Column
import dev.tamboui.toolkit.event.EventResult
import dev.tamboui.tui.TuiConfig
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
import java.util.function.Supplier

/** Owns navigation and inspection; the session owns decisions and guarded execution. */
class HomeLightApp(private val session: HomeLightSession, private val customTuiConfig: TuiConfig?) {
    private var activeScreen = Screen.WORKSPACE
    private var selectedIndex = 0
    private var actionIndex = 0
    private var reviewedSource: Path? = null
    private var paneFocus = PaneFocus.MASTER
    private var actionFocus = PaneFocus.MASTER
    private var detailSelectedIndex = 0
    private var showInSync = false
    private var userShowInSync: Boolean? = null
    private var spinnerFrame = 0
    private var followedAction: ReconciliationAction? = null
    private var displayedResult: ApplyModel.Result? = null
    private val workspaceDetails = DetailViewport()
    private val actionDetails = DetailViewport()
    private var exitIntent = ExitIntent.STAY
    private var setup: SetupView? = null
    private var discoveryFactory: Supplier<CandidateDiscovery> = Supplier { CandidateDiscovery() }

    private enum class ExitIntent { STAY, CONFIRM_KEEP, CONFIRM_EXIT, AFTER_EXECUTION, EXIT }

    init {
        syncInSyncSetting()
    }

    constructor(configPath: Path) : this(HomeLightSession(configPath))
    constructor(session: HomeLightSession) : this(session, null as TuiConfig?)
    constructor(session: HomeLightSession, startSetup: Boolean) : this(session, null as TuiConfig?) {
        if (startSetup) setup = SetupView(session, discoveryFactory)
    }

    // A factory gives each reopened setup its own discovery lifetime.
    internal constructor(session: HomeLightSession, discoveryFactory: Supplier<CandidateDiscovery>) :
        this(session, null as TuiConfig?) {
        this.discoveryFactory = discoveryFactory
    }

    internal fun configure(): TuiConfig = customTuiConfig ?: TuiConfig.defaults()

    @Throws(Exception::class)
    fun run() { TuiLauncher.run(this) }

    // Package-private (render: protected) in Java and called by the Java tests. Internal functions get
    // mangled JVM names, so @JvmName keeps the names the tests call.
    @JvmName("exitRequested")
    internal fun exitRequested(): Boolean = exitIntent == ExitIntent.EXIT

    @JvmName("activeScreen")
    internal fun activeScreen(): Screen = activeScreen

    @JvmName("render")
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
                for (i in model.steps.indices) {
                    val step = model.steps[i]
                    if (step.status == ApplyModel.StepStatus.RUNNING && step.action !== followedAction) {
                        // Manual inspection lasts until the next action transition.
                        actionIndex = i
                        actionDetails.reset()
                        followedAction = step.action
                        break
                    }
                }
            }
            is ApplyModel.Result -> {
                if (model !== displayedResult) {
                    for (i in model.steps.indices) {
                        val status = model.steps[i].status
                        if (status == ApplyModel.StepStatus.COMPLETED || status == ApplyModel.StepStatus.FAILED) actionIndex = i
                        if (status == ApplyModel.StepStatus.FAILED) break
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
        return ApplyView.render(session.configPath(), model, actionIndex, spinnerFrame++, actionFocus, actionDetails)
    }

    fun handleKeyEvent(key: KeyEvent): EventResult {
        if (exitIntent == ExitIntent.CONFIRM_KEEP || exitIntent == ExitIntent.CONFIRM_EXIT) return handleExitDialog(key)
        val currentSetup = setup
        if (currentSetup != null) {
            currentSetup.key(key)
            if (currentSetup.closed()) { setup = null; workspaceDetails.reset(); syncInSyncSetting() }
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
        if (key.isQuit() || key.isCharIgnoreCase('q')) {
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
        if (isTab(key) || key.isRight() || key.isCharIgnoreCase('l')) {
            paneFocus = if (isTab(key) && paneFocus == PaneFocus.DETAIL) PaneFocus.MASTER else PaneFocus.DETAIL
            if (paneFocus == PaneFocus.DETAIL) workspaceDetails.followChoice()
            return EventResult.HANDLED
        }
        if (key.isLeft() || key.isCharIgnoreCase('h')) { paneFocus = PaneFocus.MASTER; return EventResult.HANDLED }
        val item = selectedPlanItem()
        val choices = paneFocus == PaneFocus.DETAIL && item != null && !item.availableResolutions.isEmpty() &&
            session.applyModel() !is ApplyModel.Result
        if (key.isUp() || key.isCharIgnoreCase('k') || key.isDown() || key.isCharIgnoreCase('j')) {
            val delta = if (key.isUp() || key.isCharIgnoreCase('k')) -1 else 1
            if (choices) {
                detailSelectedIndex = Math.clamp((detailSelectedIndex + delta).toLong(), 0, item.availableResolutions.size - 1)
                workspaceDetails.followChoice()
            } else if (paneFocus == PaneFocus.DETAIL) workspaceDetails.scroll(delta)
            else if (delta < 0) selectPrevious() else selectNext()
        } else if (key.isHome() || key.isChar('g') || key.isEnd() || key.isChar('G')) {
            val end = key.isEnd() || key.isChar('G')
            if (choices) {
                detailSelectedIndex = if (end) item.availableResolutions.size - 1 else 0
                workspaceDetails.followChoice()
            } else if (paneFocus == PaneFocus.DETAIL) workspaceDetails.scroll(if (end) Int.MAX_VALUE else -Int.MAX_VALUE)
            else if (end) selectLast() else selectFirst()
        } else if (key.isChar(' ') || key.isKey(KeyCode.ENTER)) {
            if (choices) resolveSelected(item.availableResolutions[detailSelectedIndex])
            else { paneFocus = PaneFocus.DETAIL; workspaceDetails.followChoice() }
        }
        return EventResult.HANDLED
    }

    @JvmName("closeSetup")
    internal fun closeSetup() {
        val currentSetup = setup
        if (currentSetup != null) { currentSetup.close(); setup = null }
    }

    private fun handleApplyKeyEvent(key: KeyEvent): EventResult {
        val model = session.applyModel()
        if (isTab(key) || key.isRight() || key.isCharIgnoreCase('l')) {
            actionFocus = if (isTab(key) && actionFocus == PaneFocus.DETAIL) PaneFocus.MASTER else PaneFocus.DETAIL
        } else if (key.isLeft() || key.isCharIgnoreCase('h')) actionFocus = PaneFocus.MASTER
        else if (key.isChar('[') || key.isChar(']')) actionDetails.scroll(if (key.isChar(']')) 1 else -1)
        else if (key.isUp() || key.isCharIgnoreCase('k')) {
            if (actionFocus == PaneFocus.DETAIL) actionDetails.scroll(-1) else selectPrevious()
        } else if (key.isDown() || key.isCharIgnoreCase('j')) {
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
        else if (key.isUp() || key.isCharIgnoreCase('k')) exitIntent = ExitIntent.CONFIRM_KEEP
        else if (key.isDown() || key.isCharIgnoreCase('j')) exitIntent = ExitIntent.CONFIRM_EXIT
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

    @JvmName("switchScreen")
    internal fun switchScreen(screen: Screen) {
        if (session.isApplying() || !session.executionSettled()) return
        if (screen == activeScreen) return
        if (screen == Screen.APPLY) {
            if (session.applyModel() is ApplyModel.Idle && !session.requestApply()) return
            if (activeScreen == Screen.WORKSPACE && selectedPlanItem() != null) reviewedSource = selectedPlanItem()!!.relocation.sourcePath
            if (activeScreen != Screen.APPLY && session.applyModel() is ApplyModel.Confirmation) {
                actionIndex = 0
                actionFocus = PaneFocus.MASTER
                actionDetails.reset()
            }
        } else { session.cancelApply(); restoreSelection(reviewedSource) }
        activeScreen = screen
    }

    fun refresh() {
        if (session.isApplying() || !session.executionSettled()) return
        val source = if (selectedPlanItem() == null) null else selectedPlanItem()!!.relocation.sourcePath
        session.refresh()
        activeScreen = Screen.WORKSPACE
        syncInSyncSetting()
        restoreSelection(source)
        resetDetailSelection()
    }

    fun resolveSelected(choice: DecisionChoice) {
        val item = selectedPlanItem()
        if (item == null || session.applyModel() is ApplyModel.Result) return
        session.choose(item.relocation.sourcePath, choice)
        restoreSelection(item.relocation.sourcePath)
        detailSelectedIndex = selectedPlanItem()!!.availableResolutions.indexOf(choice)
        workspaceDetails.followChoice()
    }

    private fun visibleItems(): List<PlanRelocationItem> {
        val model = session.planModel()
        return if (model is PlanModel.Configured) WorkspaceView.visibleItems(model, showInSync) else listOf()
    }

    private fun selectedPlanItem(): PlanRelocationItem? {
        val items = visibleItems()
        return if (items.isEmpty()) null else items[Math.clamp(selectedIndex.toLong(), 0, items.size - 1)]
    }

    fun toggleInSync() {
        val source = if (selectedPlanItem() == null) null else selectedPlanItem()!!.relocation.sourcePath
        userShowInSync = !showInSync
        showInSync = userShowInSync!!
        restoreSelection(source)
    }

    fun selectPrevious() { select(-1, false) }
    fun selectNext() { select(1, false) }
    fun selectFirst() { select(0, true) }
    fun selectLast() { select(Int.MAX_VALUE, true) }

    private fun select(value: Int, absolute: Boolean) {
        if (activeScreen == Screen.APPLY) {
            actionIndex = Math.clamp(
                (if (absolute) value else actionIndex + value).toLong(), 0,
                Math.max(0, ApplyView.steps(session.applyModel()).size - 1),
            )
            actionDetails.reset()
        } else {
            selectedIndex = Math.clamp(
                (if (absolute) value else selectedIndex + value).toLong(), 0, Math.max(0, visibleItems().size - 1),
            )
            resetDetailSelection()
        }
    }

    private fun resetDetailSelection() {
        val item = selectedPlanItem()
        detailSelectedIndex = if (item == null) 0 else item.selectedResolution()
            .map { choice -> Math.max(0, item.availableResolutions.indexOf(choice)) }.orElse(0)
        workspaceDetails.reset()
    }

    private fun restoreSelection(source: Path?) {
        val items = visibleItems()
        for (i in items.indices) if (items[i].relocation.sourcePath == source) { selectedIndex = i; return }
        clampSelection()
    }

    private fun clampSelection() {
        selectedIndex = Math.clamp(selectedIndex.toLong(), 0, Math.max(0, visibleItems().size - 1))
    }

    private fun syncInSyncSetting() {
        showInSync = userShowInSync ?: session.planModel().let { model ->
            model is PlanModel.Configured && model.summary.inSync == model.summary.total
        }
        clampSelection()
    }

    fun session(): HomeLightSession = session
    fun planModel(): PlanModel = session.planModel()
    fun selectedIndex(): Int = if (activeScreen == Screen.APPLY) actionIndex else selectedIndex
    fun paneFocus(): PaneFocus = if (activeScreen == Screen.APPLY) actionFocus else paneFocus
    fun detailSelectedIndex(): Int = detailSelectedIndex
    fun showInSync(): Boolean = showInSync
}
