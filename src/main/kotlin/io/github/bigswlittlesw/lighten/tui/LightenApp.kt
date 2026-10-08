package io.github.bigswlittlesw.lighten.tui

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
import io.github.bigswlittlesw.lighten.application.ApplyModel
import io.github.bigswlittlesw.lighten.application.ConfigurationEvaluation
import io.github.bigswlittlesw.lighten.application.DecisionChoice
import io.github.bigswlittlesw.lighten.application.LightenSession
import io.github.bigswlittlesw.lighten.application.PlanBadge
import io.github.bigswlittlesw.lighten.application.PlanRelocationItem
import io.github.bigswlittlesw.lighten.application.userGuide
import io.github.bigswlittlesw.lighten.config.ConfigurationException
import io.github.bigswlittlesw.lighten.discovery.CandidateDiscovery
import io.github.bigswlittlesw.lighten.domain.RelocationSourceState
import java.nio.file.Path

/**
 * Key handlers read navigation from this set: arrows, Home/End and PageUp/PageDown, with no letter aliases, so
 * navigation never takes a key a text field could type. `q`, `Q` and Ctrl+C are still quit and Space is still
 * select, so Configuration's text fields take typed characters before any binding.
 */
internal val KEY_BINDINGS: Bindings = BindingSets.standard()

// Fixed focus ids: TamboUI's FocusManager tracks focus by id, and an element without one gets a new id every frame.
internal const val WORKSPACE_LIST = "workspace-list"
internal const val WORKSPACE_DETAILS = "workspace-details"
internal const val REVIEW_LIST = "review-list"
internal const val REVIEW_DETAILS = "review-details"
internal const val CONFIG_LIST = "config-list"
internal const val CONFIG_BROWSE = "config-browse"
internal const val DIALOG = "dialog"
internal const val HELP_THIS_SCREEN = "help-this-screen"
internal const val HELP_GUIDE = "help-guide"

/**
 * Owns navigation and inspection; the session owns decisions and guarded execution.
 *
 * Focus is TamboUI's: the focused element takes its own keys first (a list moves its selection, a dialog answers),
 * and [keyHandler] gets every key it leaves. `discoveryFactory` gives each opened Configuration its own discovery
 * lifetime. With `openConfiguration`, Lighten starts on Configuration, as `lighten init` and `config` do.
 */
internal class LightenApp(
    val session: LightenSession,
    private val focus: FocusManager,
    openConfiguration: Boolean = false,
    private val discoveryFactory: () -> CandidateDiscovery = { CandidateDiscovery() },
) {
    internal var activeScreen = Screen.WORKSPACE
        private set
    private val workspaceList = WorkspaceView.list()
    // Keys the list does not move its selection with come straight here.
    private val reviewList = ApplyView.list { key -> keyHandler.handle(key) }
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
    private var detailsRow = 0
    private var detailsWereFocused = false
    // Where the user was on Workspace, restored when Review or Results returns there.
    private var workspaceFocus: String? = null
    private val workspaceDetails = DetailViewport()
    private val reviewDetails = DetailViewport()
    private var exitIntent = ExitIntent.STAY
    private var focusBeforeDialog: String? = null
    // The source whose one-time choice the Always do this dialog asks to save, while it is open.
    private var savingChoice: Path? = null
    // The row the Ignore or Stop ignoring dialog asks about, while it is open.
    private var ignoring: WorkspaceRow? = null
    // Whether the ignored group is open; it starts closed every run.
    var showIgnored = false
        private set
    private var editor: ConfigurationView? = null
    // Said on the Workspace after Configuration closes, until the next key the app handles (moving in a list keeps it).
    private var notice: DetailViewport.Line? = null
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
        focus.setFocus(WORKSPACE_LIST)
        if (openConfiguration) openEditor()
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
        // The discard and replace dialogs close Configuration without a key reaching handleKey.
        dropClosedEditor()
        val dialog = editor?.dialog() ?: quitDialog() ?: saveChoiceDialog() ?: ignoreDialog()
        val interactive = dialog == null
        val view = when {
            helpOpen -> renderHelp(interactive)
            else -> editor?.render(interactive) ?: if (activeScreen == Screen.APPLY) renderApply(interactive) else renderWorkspace(interactive)
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

    private fun saveChoiceDialog(): Element? {
        val source = savingChoice ?: return null
        val choice = (session.evaluation() as? ConfigurationEvaluation.Loaded)?.draft?.get(source) ?: return null
        return confirmDialog(
            ALWAYS_DO_THIS_TITLE, alwaysDoThis(source, choice, session.configPath), ALWAYS_DO_THIS_KEYS,
            onYes = { closeSaveChoiceDialog(); save(CHOICE_NOT_SAVED) { session.saveChoice(source) } },
            onNo = ::closeSaveChoiceDialog, warning = alwaysDoThisWarning(choice),
        )
    }

    /**
     * Saving checks again, which forgets one-time choices; the dialog says so when a choice for another relocation
     * would go. The row's own choice goes with the relocation, so it is not counted.
     */
    private fun forgetsChoices(row: WorkspaceRow): List<String> {
        val draft = (session.evaluation() as? ConfigurationEvaluation.Loaded)?.draft.orEmpty()
        return if (draft.keys.any { it != row.source }) listOf(FORGETS_OTHER_CHOICES) else listOf()
    }

    private fun ignoreDialog(): Element? = when (val row = ignoring) {
        null, is WorkspaceRow.IgnoredGroup -> null
        is WorkspaceRow.Planned -> confirmDialog(
            ignoreTitle(row.source), ignoreBody(session.configPath) + forgetsChoices(row), IGNORE_KEYS,
            onYes = { closeSaveChoiceDialog(); save(IGNORE_NOT_SAVED) { session.ignore(row.source) } },
            onNo = ::closeSaveChoiceDialog,
            warning = if (row.item.sourceState != RelocationSourceState.CORRECT_SYMLINK) listOf()
            else ignoreLinkedWarning(row.source, row.item.relocation.targetPath),
        )
        is WorkspaceRow.Ignored -> confirmDialog(
            stopIgnoringTitle(row.source), stopIgnoringBody(session.configPath) + forgetsChoices(row), STOP_IGNORING_KEYS,
            onYes = { closeSaveChoiceDialog(); save(IGNORE_NOT_SAVED) { session.stopIgnoring(row.source) } },
            onNo = ::closeSaveChoiceDialog,
        )
    }

    private fun askToIgnore() {
        ignoring = WorkspaceView.ignoreTarget(session, selectedRow()) ?: return
        // A dialog is the only focusable while it is open, so the next frame focuses it.
        focusBeforeDialog = focus.focusedId()
    }

    /** The selected relocation's source when `s` has a one-time choice to save as its rule. */
    private fun choiceToSave(): Path? = selectedPlanItem()?.relocation?.sourcePath?.takeIf { source ->
        (session.evaluation() as? ConfigurationEvaluation.Loaded)?.ruleFile(source) != null
    }

    private fun askToSaveChoice() {
        savingChoice = choiceToSave() ?: return
        // A dialog is the only focusable while it is open, so the next frame focuses it.
        focusBeforeDialog = focus.focusedId()
    }

    /** Closes the Always do this, Ignore or Stop ignoring dialog. */
    private fun closeSaveChoiceDialog() {
        savingChoice = null
        ignoring = null
        focus.setFocus(focusBeforeDialog)
    }

    /**
     * Runs `write`, a save the Workspace asked about, as a save in Configuration does: check again, so the file
     * decides (a saved rule, an ignored source) and any choice is gone, then say the next step. Focus returns to the
     * list with Details at the top, where the Decision line names the rule. A file that changed since it was read is
     * left as it is, and `changed` says so.
     */
    private fun save(changed: String, write: () -> Unit) {
        saveProblem(changed, write)?.let { problem ->
            notice = DetailViewport.Line(problem, palette.warn)
            return
        }
        refresh()
        focus.setFocus(WORKSPACE_LIST)
        notice = DetailViewport.Line(WorkspaceView.savedNotice(session.evaluation()), palette.ok)
    }

    /** The screen behind Help, with the focus it had when Help opened. */
    private fun screenHelp(): ScreenHelp = editor?.screenHelp(focusBeforeHelp) ?: when (activeScreen) {
        Screen.WORKSPACE -> WorkspaceView.screenHelp(session, workspaceList, showInSync, showIgnored, focusBeforeHelp)
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
        val firstRun = editor == null && activeScreen == Screen.WORKSPACE &&
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
            key.isCtrlC() -> editor?.let { current -> current.key(key); dropClosedEditor() } ?: requestQuit()
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
            editor != null -> editor?.wheel(x, y, delta)
            activeScreen == Screen.APPLY -> when {
                reviewDetails.contains(x, y) -> reviewDetails.scroll(delta)
                reviewDetails.besideOnTheLeft(x, y) ->
                    moveSelection(reviewList, ApplyView.rows(ApplyView.steps(session.applyModel())).size, delta)
            }
            workspaceDetails.contains(x, y) -> workspaceDetails.scroll(delta)
            workspaceDetails.besideOnTheLeft(x, y) -> moveSelection(workspaceList, workspaceRows().size, delta)
        }
    }

    private fun moveSelection(list: ListElement<Any>, size: Int, delta: Int) {
        if (size > 0) list.selected((list.selected() + delta).coerceIn(0, size - 1))
    }

    /** One-time choices are kept only in the session, so quitting forgets them. A plan alone is rebuilt next run. */
    private fun unappliedChoiceCount(): Int = (session.evaluation() as? ConfigurationEvaluation.Loaded)?.draft?.size ?: 0

    private fun renderWorkspace(interactive: Boolean): Element {
        val source = selectedRow()?.source
        if (source != detailsSource) {
            detailsSource = source
            resetDetailSelection()
        }
        // However focus arrives (Tab, → or Enter), the details show the choice.
        val detailsFocused = focus.focusedId() == WORKSPACE_DETAILS
        if (detailsFocused && !detailsWereFocused) workspaceDetails.followChoice()
        detailsWereFocused = detailsFocused
        return WorkspaceView.render(
            session, workspaceList, showInSync, showIgnored, focus.focusedId(), interactive, detailSelectedIndex,
            workspaceDetails, notice,
        )
    }

    private fun renderApply(interactive: Boolean): Element {
        val model = session.applyModel()
        if (model is ApplyModel.Result && model !== shownResult) {
            shownResult = model
            finishedSelection(ApplyView.rows(model.steps))?.let(reviewList::selected)
        }
        if (reviewList.selected() != detailsRow) {
            detailsRow = reviewList.selected()
            reviewDetails.reset()
        }
        return ApplyView.render(
            session.configPath, model, reviewList, spinnerFrame++, focus.focusedId(), interactive, reviewDetails,
            quitting = exitIntent == ExitIntent.AFTER_EXECUTION,
        )
    }

    private fun handleKey(key: KeyEvent) {
        if (helpOpen) { helpKey(key); return }
        // F1 opens Help everywhere. A focused text field types `?` itself, so `?` arrives here only from elsewhere.
        if (key.isKey(KeyCode.F1) || key.isChar('?')) { openHelp(); return }
        editor?.let { current ->
            current.key(key)
            dropClosedEditor()
            return
        }
        notice = null
        if (key.isKey(KeyCode.ESCAPE)) { back(); return }
        if (key.isQuit()) { requestQuit(); return }
        if (exitIntent == ExitIntent.EXIT) return
        val evaluation = session.evaluation()
        val missing = evaluation is ConfigurationEvaluation.Missing || evaluation is ConfigurationEvaluation.Unconfigured
        if (key.isCharIgnoreCase('i') && missing ||
            key.isCharIgnoreCase('e') && evaluation is ConfigurationEvaluation.Loaded && activeScreen == Screen.WORKSPACE
        ) {
            openEditor()
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
            key.isCharIgnoreCase('s') -> askToSaveChoice()
            key.isCharIgnoreCase('x') -> askToIgnore()
            key.isCharIgnoreCase('i') -> toggleIgnored()
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
        else if (key.isChar('[') || key.isChar(']')) reviewDetails.scroll(if (key.isChar(']')) 1 else -1)
        else if (details && (key.isUp() || key.isDown())) reviewDetails.scroll(if (key.isUp()) -1 else 1)
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

    /** Opens Configuration on the file as it is on disk now, or says why it cannot. */
    private fun openEditor() {
        if (session.isApplying() || !session.executionSettled()) return
        editor = try {
            ConfigurationView.open(session, focus, discoveryFactory) { key -> keyHandler.handle(key) }
        } catch (error: ConfigurationException) {
            notice = DetailViewport.Line(cannotOpen(error.message.orEmpty()), palette.warn)
            null
        }
    }

    /** After Configuration closes: a save checks again and says the next step; a discard changes nothing. */
    private fun dropClosedEditor() {
        val closed = editor?.takeIf { it.closed } ?: return
        editor = null
        if (closed.saved) {
            refresh()
            notice = DetailViewport.Line(WorkspaceView.savedNotice(session.evaluation()), palette.ok)
        }
        // Discarding from Help over Configuration returns to the Workspace, not to Help about it.
        helpOpen = false
        activeScreen = Screen.WORKSPACE
        workspaceDetails.reset()
        syncInSyncSetting()
        focus.setFocus(WORKSPACE_LIST)
    }

    internal fun closeEditor() {
        editor?.close()
        editor = null
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
                reviewDetails.reset()
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
        val source = selectedRow()?.source
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

    private fun workspaceRows(): List<WorkspaceRow> {
        val model = session.evaluation()
        return if (model is ConfigurationEvaluation.Loaded) WorkspaceView.rows(model, showInSync, showIgnored) else listOf()
    }

    private fun selectedRow(): WorkspaceRow? = workspaceRows().let { rows -> rows.getOrNull(workspaceSelection(rows)) }

    private fun selectedPlanItem(): PlanRelocationItem? = (selectedRow() as? WorkspaceRow.Planned)?.item

    private fun workspaceSelection(rows: List<WorkspaceRow>): Int =
        workspaceList.selected().coerceIn(0, maxOf(0, rows.size - 1))

    private fun toggleInSync() {
        val source = selectedRow()?.source
        showInSync = !showInSync
        userShowInSync = showInSync
        restoreSelection(source)
    }

    /** Opens or closes the ignored group; the selection stays on its row, or goes to the heading when it closes. */
    private fun toggleIgnored() {
        val model = session.evaluation() as? ConfigurationEvaluation.Loaded ?: return
        if (model.ignored.isEmpty()) return
        val source = selectedRow()?.source
        showIgnored = !showIgnored
        restoreSelection(source)
    }

    private fun resetDetailSelection() {
        val item = selectedPlanItem()
        detailSelectedIndex = item?.selectedResolution()?.let { choice -> maxOf(0, item.availableResolutions.indexOf(choice)) } ?: 0
        workspaceDetails.reset()
    }

    /** Selects the row for `source`; without one listed, the row at the same place. The heading has no source. */
    private fun restoreSelection(source: Path?) {
        val rows = workspaceRows()
        val index = if (source == null) -1 else rows.indexOfFirst { it.source == source }
        workspaceList.selected(if (index >= 0) index else workspaceSelection(rows))
    }

    private fun syncInSyncSetting() {
        showInSync = userShowInSync ?: session.evaluation().let { model ->
            model is ConfigurationEvaluation.Loaded && model.items.all { it.badge() == PlanBadge.IN_SYNC }
        }
        workspaceList.selected(workspaceSelection(workspaceRows()))
    }

    // Read only by tests: Configuration's help for the focused element, while it is open.
    internal fun configurationHelp(): ScreenHelp? = editor?.screenHelp(focus.focusedId())

    // Read only by tests.
    internal fun selectedIndex(): Int =
        if (activeScreen == Screen.APPLY) reviewList.selected() else workspaceSelection(workspaceRows())
}

/**
 * The row Review's selection moves to once when an apply finishes: the first failed step, or else the last completed
 * step. Relocation rows are never chosen: a step says what happened.
 */
internal fun finishedSelection(rows: List<PlanRow>): Int? {
    fun status(row: PlanRow): ApplyModel.StepStatus? = (row as? PlanRow.StepRow)?.step?.status
    val failed = rows.indexOfFirst { status(it) == ApplyModel.StepStatus.FAILED }
    if (failed >= 0) return failed
    return rows.indexOfLast { status(it) == ApplyModel.StepStatus.COMPLETED }.takeIf { it >= 0 }
}
