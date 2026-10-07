package io.github.bigswlittlesw.homelight.tui

import dev.tamboui.toolkit.Toolkit
import dev.tamboui.toolkit.element.Element
import dev.tamboui.toolkit.elements.Column
import dev.tamboui.toolkit.elements.ListElement
import dev.tamboui.toolkit.event.EventResult
import dev.tamboui.toolkit.event.GlobalEventHandler
import dev.tamboui.toolkit.focus.FocusManager
import dev.tamboui.tui.bindings.BindingSets
import dev.tamboui.tui.bindings.Bindings
import dev.tamboui.tui.event.KeyCode
import dev.tamboui.tui.event.KeyEvent
import dev.tamboui.tui.event.MouseEvent
import dev.tamboui.tui.event.MouseEventKind
import io.github.bigswlittlesw.homelight.application.ApplyModel
import io.github.bigswlittlesw.homelight.application.ConfigurationEvaluation
import io.github.bigswlittlesw.homelight.application.DecisionChoice
import io.github.bigswlittlesw.homelight.application.HomeLightSession
import io.github.bigswlittlesw.homelight.application.PlanBadge
import io.github.bigswlittlesw.homelight.application.PlanRelocationItem
import io.github.bigswlittlesw.homelight.application.userGuide
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
internal const val HELP_THIS_SCREEN = "help-this-screen"
internal const val HELP_GUIDE = "help-guide"

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
    private var helpOpen = false
    private var focusBeforeHelp: String? = null
    private var helpTab = HelpTab.THIS_SCREEN
    // One per tab, so each keeps its scroll position.
    private val helpViewports = HelpTab.entries.associateWith { DetailViewport() }
    private val guide: String by lazy { userGuide() }

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
     * quit on `q`. TamboUI offers mouse events here before any element, so handling all of them keeps a click or a
     * trackpad tap from focusing what is under the pointer.
     */
    val keyHandler = GlobalEventHandler { event ->
        when (event) {
            is KeyEvent -> handleKey(event)
            is MouseEvent -> handleMouse(event)
            else -> return@GlobalEventHandler EventResult.UNHANDLED
        }
        EventResult.HANDLED
    }

    internal fun exitRequested(): Boolean = exitIntent == ExitIntent.EXIT

    internal fun render(): Element {
        settleDeferredExit()
        // The discard dialog closes setup without a key reaching handleKey.
        dropClosedSetup()
        val dialog = setup?.dialog() ?: quitDialog()
        val interactive = dialog == null
        val view = when {
            helpOpen -> renderHelp(interactive)
            else -> setup?.render(interactive) ?: if (activeScreen == Screen.APPLY) renderApply(interactive) else renderWorkspace(interactive)
        }
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

    /** The screen behind Help, with the focus it had when Help opened. */
    private fun screenHelp(): ScreenHelp = setup?.screenHelp() ?: when (activeScreen) {
        Screen.WORKSPACE -> WorkspaceView.screenHelp(session, workspaceList, showInSync, focusBeforeHelp)
        Screen.APPLY ->
            ApplyView.screenHelp(session.applyModel(), focusBeforeHelp, quitting = exitIntent == ExitIntent.AFTER_EXECUTION)
    }

    /** The open tab follows focus, which is how Tab switches it (see [helpScreen]). */
    private fun renderHelp(interactive: Boolean): Element {
        when (focus.focusedId()) {
            HELP_THIS_SCREEN -> helpTab = HelpTab.THIS_SCREEN
            HELP_GUIDE -> helpTab = HelpTab.GUIDE
        }
        return helpScreen(screenHelp(), guide, helpTab, helpViewports, interactive)
    }

    /**
     * From the empty Workspace before there is a configuration file, Help opens on the guide, so a first run starts by
     * reading it. Elsewhere, Configuration included, it opens on This screen.
     */
    private fun openHelp() {
        helpOpen = true
        val firstRun = setup == null && activeScreen == Screen.WORKSPACE &&
            session.evaluation().let { it is ConfigurationEvaluation.Missing || it is ConfigurationEvaluation.Unconfigured }
        helpTab = if (firstRun) HelpTab.GUIDE else HelpTab.THIS_SCREEN
        // This screen changes with the screen behind; the guide keeps where the reader left it.
        helpViewports.getValue(HelpTab.THIS_SCREEN).reset()
        focusBeforeHelp = focus.focusedId()
        focus.setFocus(helpTabId(helpTab))
    }

    private fun closeHelp() {
        helpOpen = false
        focus.setFocus(focusBeforeHelp)
    }

    /**
     * Help switches tabs, scrolls and goes back; every other key does nothing, so a key typed while reading changes
     * nothing. TamboUI switches the tab on Tab (see [helpScreen]); ←/→ switch it here. `q` goes back too, as in less,
     * man and other help screens. Ctrl+C quits as it does everywhere: through the screen behind, so a draft or an
     * apply still gets its question.
     */
    private fun helpKey(key: KeyEvent) {
        val viewport = helpViewports.getValue(helpTab)
        when {
            key.isCtrlC() -> setup?.let { current -> current.key(key); dropClosedSetup() } ?: requestQuit()
            key.isChar('?') || key.isKey(KeyCode.F1) || key.isKey(KeyCode.ESCAPE) || key.isQuit() -> closeHelp()
            key.isLeft() || key.isRight() ->
                focus.setFocus(helpTabId(if (helpTab == HelpTab.GUIDE) HelpTab.THIS_SCREEN else HelpTab.GUIDE))
            key.isUp() || key.isChar('[') -> viewport.scroll(-1)
            key.isDown() || key.isChar(']') -> viewport.scroll(1)
            key.isPageUp() || key.isPageDown() -> viewport.scrollPage(if (key.isPageUp()) -1 else 1)
            key.isHome() || key.isEnd() -> viewport.scroll(if (key.isEnd()) Int.MAX_VALUE else -Int.MAX_VALUE)
        }
    }

    /**
     * The mouse is captured only for its wheel. Wheel up and down scroll the pane under the pointer, or move a list's
     * selection, and never change focus or tab; sideways scrolling, clicks, drags and taps do nothing.
     */
    private fun handleMouse(event: MouseEvent) {
        val delta = when (event.kind()) {
            MouseEventKind.SCROLL_UP -> -1
            MouseEventKind.SCROLL_DOWN -> 1
            else -> return
        }
        val x = event.x()
        val y = event.y()
        when {
            exitIntent == ExitIntent.EXIT -> {}
            helpOpen -> helpViewports.getValue(helpTab).takeIf { it.contains(x, y) }?.scroll(delta)
            setup != null -> setup?.wheel(x, y, delta)
            activeScreen == Screen.APPLY -> when {
                actionDetails.contains(x, y) -> actionDetails.scroll(delta)
                actionDetails.besideOnTheLeft(x, y) -> moveSelection(reviewList, ApplyView.steps(session.applyModel()).size, delta)
            }
            workspaceDetails.contains(x, y) -> workspaceDetails.scroll(delta)
            workspaceDetails.besideOnTheLeft(x, y) -> moveSelection(workspaceList, visibleItems().size, delta)
        }
    }

    private fun moveSelection(list: ListElement<Any>, size: Int, delta: Int) {
        if (size > 0) list.selected((list.selected() + delta).coerceIn(0, size - 1))
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
        if (helpOpen) { helpKey(key); return }
        // F1 opens Help everywhere; in a setup text field `?` is typed like any other character.
        if (key.isKey(KeyCode.F1) || key.isChar('?') && setup?.editsText(key) != true) { openHelp(); return }
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
        // Discarding from Help over Configuration returns to the Workspace, not to Help about it.
        helpOpen = false
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
