package io.github.bigswlittlesw.homelight.tui

import dev.tamboui.layout.Rect
import dev.tamboui.style.Style
import dev.tamboui.terminal.Frame
import dev.tamboui.toolkit.Toolkit
import dev.tamboui.toolkit.element.Element
import dev.tamboui.toolkit.element.RenderContext
import dev.tamboui.toolkit.element.Size
import dev.tamboui.toolkit.element.StyledElement
import dev.tamboui.toolkit.elements.ListElement
import dev.tamboui.toolkit.focus.FocusManager
import dev.tamboui.tui.event.KeyCode
import dev.tamboui.tui.event.KeyEvent
import dev.tamboui.widgets.common.ScrollBarPolicy
import dev.tamboui.widgets.input.TextInputState
import dev.tamboui.widgets.select.Select
import dev.tamboui.widgets.select.SelectState
import io.github.bigswlittlesw.homelight.application.BrowseDraft
import io.github.bigswlittlesw.homelight.application.HomeLightSession
import io.github.bigswlittlesw.homelight.application.Suggestions
import io.github.bigswlittlesw.homelight.config.ConfigurationChangedException
import io.github.bigswlittlesw.homelight.config.ConfigurationException
import io.github.bigswlittlesw.homelight.config.ConfigurationLoader
import io.github.bigswlittlesw.homelight.config.ConfigurationPublisher
import io.github.bigswlittlesw.homelight.config.DiscoveryFile
import io.github.bigswlittlesw.homelight.config.HomeLightFile
import io.github.bigswlittlesw.homelight.config.Relocation
import io.github.bigswlittlesw.homelight.config.RelocationFile
import io.github.bigswlittlesw.homelight.config.WhenAdoptingTarget
import io.github.bigswlittlesw.homelight.config.WhenOnlyTargetExists
import io.github.bigswlittlesw.homelight.config.WhenSourceAndTargetDirectoriesExist
import io.github.bigswlittlesw.homelight.config.defaultArchiveRoot
import io.github.bigswlittlesw.homelight.config.derivedTarget
import io.github.bigswlittlesw.homelight.config.parseSharedList
import io.github.bigswlittlesw.homelight.config.relocationProblem
import io.github.bigswlittlesw.homelight.config.resolvePath
import io.github.bigswlittlesw.homelight.discovery.CandidateDiscovery
import io.github.bigswlittlesw.homelight.tui.DetailViewport.Line
import java.nio.file.Files
import java.nio.file.Path

/**
 * The Configuration screen: one editor that creates the configuration file or changes the one that exists
 * (tui-design §7). The draft is the file's own shape, so `~` and `${USER}` stay as written and the loader stays the
 * one owner of path rules: the screen resolves its fields with the loader's functions, and saving checks the draft
 * with the loader itself.
 *
 * Focus is TamboUI's: the list and each field have their own id, Tab moves through them in order, and a focused
 * text input takes its own keys first. [key] gets the keys they leave. Everything here runs on the UI thread;
 * discovery workers only publish snapshots.
 */
internal class ConfigurationView private constructor(
    private val session: HomeLightSession,
    private val focus: FocusManager,
    private val discoveryFactory: () -> CandidateDiscovery,
    /** The file as opened. A new file starts from an empty draft, so typing into it counts as a change. */
    private val loaded: HomeLightFile,
    /** The bytes the file was read from, which a replace compares; null for a new file. */
    private val loadedBytes: ByteArray?,
) : AutoCloseable {
    private enum class Field(val id: String, val label: String, val text: Boolean) {
        SOURCE_ROOT("config-source-root", SOURCE_ROOT_LABEL, true),
        TARGET_ROOT("config-target-root", TARGET_ROOT_LABEL, true),
        SUGGESTION_LIST("config-suggestion-list", SUGGESTION_LIST_LABEL, true),
        SOURCE("config-source", SOURCE_LABEL, true),
        TARGET("config-target", TARGET_LABEL, true),
        BOTH_EXIST("config-both-exist", BOTH_EXIST_LABEL, false),
        ONLY_TARGET("config-only-target", ONLY_TARGET_LABEL, false),
        ARCHIVE_ROOT("config-archive-root", ARCHIVE_ROOT_LABEL, true),
    }

    private enum class Question { DISCARD, REPLACE }

    private var draft: HomeLightFile = loaded
    // Each draft relocation's index in the loaded file, or null when added here. Only adding and removing a row
    // change it, so an edited row still counts as one change.
    private var origins: List<Int?> = loaded.relocations.indices.toList()
    private val list: ListElement<Any> = ListElement<Any>().id(CONFIG_LIST)
        .scrollbar(ScrollBarPolicy.AS_NEEDED).scrollbarThumbColor(palette.focus).scrollbarTrackColor(palette.dim)
        .highlightSymbol("").highlightStyle(Style.EMPTY).autoScroll()
    // The text inputs hold the fields of one list row. The draft takes their text before anything reads it (pull).
    private val inputs: Map<Field, TextInputState> = Field.entries.filter { it.text }.associateWith { TextInputState() }
    private var shownRow = -1
    private var question: Question? = null
    private var focusBeforeQuestion: String? = null
    private var message = ""
    // Only for its help lines, which therefore never offer a scroll key: in a text field `[` and `]` type.
    private val helpArea = DetailViewport()
    private val detailsArea = DetailViewport()
    private val browser = CandidateBrowser()
    private var browsing = false
    private var suggestions: Suggestions? = null

    var closed = false
        private set

    /** Whether it closed by saving, so the Workspace checks again and says the next step. */
    var saved = false
        private set

    /** `interactive` is false while a dialog, this view's or the app's, is open over Configuration. */
    fun render(interactive: Boolean): Element {
        val header = Toolkit.row(
            Toolkit.text("⌂ HOMELIGHT  ").fg(palette.brand).bold(), Toolkit.text("[$CONFIGURATION_NAME]").fg(palette.focus).bold(),
        )
        // Browse keeps its own selection, so the screen is one focusable while it is open.
        if (browsing) {
            return Toolkit.column(header, browser.render(browseDraft(), interactive)).fill().id(CONFIG_BROWSE).focusable(interactive)
        }
        pull()
        val row = selectedRow()
        if (row != shownRow) show(row)
        val focused = focus.focusedId()
        val rows = (listOf(STORAGE_LOCATIONS) + draft.relocations.map { name(it) }).mapIndexed { i, name ->
            Toolkit.row(
                Toolkit.text(if (i == row) "❯ " else "  ").fg(palette.focus).length(2),
                Toolkit.text(name).ellipsisMiddle().fill(),
            )
        }
        list.elements(*rows.toTypedArray()).focusable(interactive).fill()
        val listPane = framed(Toolkit.panel(CONFIGURATION_LIST_TITLE, list), focused == CONFIG_LIST)
        val content = buildList {
            add(header)
            add(wrappedText(configurationStatus(session.configPath, loadedBytes != null, unsaved()), palette.dim))
            add(Toolkit.row(listPane.percent(40), fieldsPane(row, focused, interactive)).fill())
            if (message.isNotEmpty()) add(wrappedText(message, palette.warn))
            add(helpArea.help(screenHelp(focused), interactive))
        }
        return Toolkit.column(*content.toTypedArray()).fill()
    }

    /**
     * The selected item's fields, two rows each, and under them Details: the focused field's help, then the
     * Resolved section. Details takes the rest of the height and scrolls, so long paths never push a field away.
     */
    private fun fieldsPane(row: Int, focused: String?, interactive: Boolean): Element {
        val title = if (row == 0) STORAGE_LOCATIONS else name(relocation(row))
        val fields = fields(row)
        val elements = fields.flatMap { field ->
            val isFocused = focused == field.id
            val label = Toolkit.text((if (isFocused) "❯ " else "  ") + field.label).fg(if (isFocused) palette.focus else palette.text)
            listOf(if (isFocused) label.bold() else label, Toolkit.row(Toolkit.text("  ").length(2), value(field, row, interactive)))
        }
        val help = fields.firstOrNull { it.id == focused }?.let { field ->
            listOfNotNull(
                Line(fieldHelp(field), palette.dim),
                Line(DISCARD_BOTH_WARNING, palette.warn).takeIf {
                    field == Field.BOTH_EXIST &&
                        relocation(row).whenSourceAndTargetDirectoriesExist == WhenSourceAndTargetDirectoriesExist.DISCARD
                },
                Line(""),
            )
        }.orEmpty()
        val resolved = resolvedLines(row).map { (label, resolved) ->
            when (resolved) {
                is Resolved.Found -> Line(resolvedLine(label, resolved.path.toString()))
                is Resolved.Problem -> Line(resolvedLine(label, resolved.text), palette.warn)
                is Resolved.Empty -> Line(resolvedLine(label, resolved.text), palette.dim)
            }
        }
        return Toolkit.column(
            framed(Toolkit.panel(title, Toolkit.column(*elements.toTypedArray())), fields.any { it.id == focused })
                .length(elements.size + 2),
            detailsArea.render(DETAILS_NAME, help + Line(RESOLVED, palette.text, true) + resolved, focused = false, choiceLine = 0),
        ).fill()
    }

    private fun value(field: Field, row: Int, interactive: Boolean): Element = when (field) {
        Field.BOTH_EXIST -> {
            val relocation = relocation(row)
            choice(BOTH_EXIST_CHOICES.map { (both, adopting) -> bothExistLabel(both, adopting) }, bothExistIndex(relocation), field.id, interactive)
        }
        Field.ONLY_TARGET ->
            choice(WhenOnlyTargetExists.entries.map(::onlyTargetLabel), relocation(row).whenOnlyTargetExists.ordinal, field.id, interactive)
        Field.SOURCE_ROOT, Field.TARGET_ROOT, Field.SUGGESTION_LIST, Field.SOURCE, Field.TARGET, Field.ARCHIVE_ROOT ->
            Toolkit.textInput(inputs.getValue(field)).id(field.id).focusable(interactive)
                .placeholder(
                    when (field) {
                        Field.TARGET -> TARGET_PLACEHOLDER
                        Field.ARCHIVE_ROOT -> ARCHIVE_PLACEHOLDER
                        else -> ""
                    },
                ).placeholderColor(palette.dim).fill()
    }

    private fun fieldHelp(field: Field): String = when (field) {
        Field.SOURCE_ROOT -> SOURCE_ROOT_HELP
        Field.TARGET_ROOT -> TARGET_ROOT_HELP
        Field.SUGGESTION_LIST -> SUGGESTION_LIST_HELP
        Field.SOURCE -> SOURCE_HELP
        Field.TARGET -> TARGET_HELP
        Field.BOTH_EXIST -> BOTH_EXIST_HELP
        Field.ONLY_TARGET -> ONLY_TARGET_HELP
        Field.ARCHIVE_ROOT -> ARCHIVE_ROOT_HELP
    }

    /** The discard or replace question, while one is open. */
    fun dialog(): Element? = when (question) {
        null -> null
        Question.DISCARD ->
            if (loadedBytes == null) confirmDialog(DISCARD_SETUP_TITLE, DISCARD_SETUP_BODY, DISCARD_SETUP_KEYS, ::close, ::answered)
            else confirmDialog(DISCARD_CHANGES_TITLE, discardChangesBody(unsaved()), DISCARD_SETUP_KEYS, ::close, ::answered)
        Question.REPLACE -> confirmDialog(
            replaceConfigurationTitle(session.configPath), REPLACE_CONFIGURATION_BODY, REPLACE_CONFIGURATION_KEYS,
            onYes = {
                answered()
                // A replace is only asked for over a file that was read, so its bytes are there.
                publish { ConfigurationPublisher().replace(session.configPath, draft, checkNotNull(loadedBytes)) }
            },
            onNo = ::answered,
        )
    }

    /**
     * Configuration's place, purpose and keys for the element with id `focused`, for its help lines and the Help
     * screen. In a text field `?` types, so help offers F1 there, and `s` and `q` type too.
     */
    fun screenHelp(focused: String?): ScreenHelp {
        if (browsing) return browser.screenHelp(browseDraft())
        val field = Field.entries.firstOrNull { it.id == focused }
        val nextField = KeyHint("↑/↓", "Field", description = "Move to the next or previous field")
        val tab = KeyHint("Tab", "Next", description = "Move to the next field, then back to the list")
        val toList = KeyHint("Esc", "List", description = "Back to the list")
        val save = KeyHint(
            "s", "Save",
            description = if (loadedBytes == null) "Create the configuration file, then check again"
            else "Replace the configuration file after asking, then check again",
        )
        fun help(navigation: List<KeyHint>, commands: List<KeyHint>) = ScreenHelp(
            field?.let { place(CONFIGURATION_NAME, it.label) } ?: CONFIGURATION_NAME, PURPOSE_CONFIGURATION,
            Step.CONFIGURE, navigation, commands,
        )
        return when {
            field == null -> help(
                listOf(
                    KeyHint("↑/↓", "Select", description = "Select the storage locations or a relocation"),
                    KeyHint("Tab/Enter", "Edit", description = "Edit the selected item's fields"),
                    PAGE_KEYS, HOME_END_KEYS,
                ),
                listOfNotNull(
                    KeyHint("a", "Add", description = "Add a relocation, with the source root filled in"),
                    KeyHint("d", "Remove", description = "Remove the selected relocation; the file changes only when you save")
                        .takeIf { selectedRow() > 0 },
                    KeyHint("b", "Browse", description = "Browse suggestions to add"),
                    save, HELP_KEY, CLOSE_CONFIGURATION_KEY,
                ),
            )
            field.text -> help(
                listOf(
                    nextField, tab, toList,
                    KeyHint("←/→", "Cursor", inHelpArea = false, description = "Move the cursor in the field"),
                    KeyHint("Home/End", "Start/end", inHelpArea = false, description = "Move to the start or end of the field"),
                ),
                listOf(
                    KeyHint("Type", "Edit", description = "Type into the field; letters and ? type too"),
                    KeyHint("Ctrl-U", "Clear", description = "Clear the field"),
                    TEXT_FIELD_HELP_KEY,
                ),
            )
            else -> help(
                listOf(nextField, tab, toList),
                listOf(
                    KeyHint("←/→", "Change", description = "Switch to the previous or next value"),
                    save, HELP_KEY, CLOSE_CONFIGURATION_KEY.copy(keys = "q"),
                ),
            )
        }
    }

    /** The mouse wheel at `x`, `y` scrolls the pane under it; the list and fields ignore it. */
    fun wheel(x: Int, y: Int, delta: Int) {
        if (browsing) browser.wheel(x, y, delta)
        else if (detailsArea.contains(x, y)) detailsArea.scroll(delta)
    }

    /** A key the focused element left. Esc goes back one level: from a field to the list, from the list to close. */
    fun key(key: KeyEvent) {
        if (closed) return
        if (browsing) { browseKey(key); return }
        // A text input changed since the last frame takes its own keys, so the draft catches up first.
        pull()
        val field = Field.entries.firstOrNull { it.id == focus.focusedId() }
        when {
            key.isKey(KeyCode.ESCAPE) -> if (field == null) requestClose() else focus.setFocus(CONFIG_LIST)
            key.isQuit() -> requestClose()
            clears(key) -> field?.takeIf { it.text }?.let { inputs.getValue(it).clear() }
            // Letter commands match with or without Ctrl or Alt, so without this Ctrl+D would remove a row.
            key.hasCtrl() || key.hasAlt() -> {}
            field == null -> listKey(key)
            key.isUp() || key.isDown() -> moveField(field, if (key.isUp()) -1 else 1)
            field.text -> {}
            key.isLeft() || key.isRight() -> choose(field, if (key.isLeft()) -1 else 1)
            key.isCharIgnoreCase('s') -> save()
        }
    }

    private fun listKey(key: KeyEvent) {
        when {
            key.isSelect() || key.isRight() -> focus.setFocus(fields(selectedRow()).first().id)
            key.isCharIgnoreCase('a') -> add()
            key.isCharIgnoreCase('d') && selectedRow() > 0 -> remove(selectedRow())
            key.isCharIgnoreCase('b') -> openBrowse()
            key.isCharIgnoreCase('s') -> save()
        }
    }

    private fun moveField(field: Field, delta: Int) {
        val fields = fields(selectedRow())
        focus.setFocus(fields[(fields.indexOf(field) + delta).coerceIn(0, fields.size - 1)].id)
    }

    /** A new relocation, with the source root filled in, and its Source focused. */
    private fun add() {
        edit(draft.relocations + RelocationFile(draft.sourceRoot.trimEnd('/') + "/"), origins + null)
        list.selected(draft.relocations.size)
        focus.setFocus(Field.SOURCE.id)
    }

    private fun remove(row: Int) {
        edit(draft.relocations.filterIndexed { i, _ -> i != row - 1 }, origins.filterIndexed { i, _ -> i != row - 1 })
        list.selected(minOf(row, draft.relocations.size))
    }

    /** Replaces the relocations; the inputs then reload, since a row may have moved. */
    private fun edit(relocations: List<RelocationFile>, origins: List<Int?>) {
        draft = draft.copy(relocations = relocations)
        this.origins = origins
        shownRow = -1
        message = ""
    }

    private fun choose(field: Field, delta: Int) {
        val row = selectedRow()
        val relocation = relocation(row)
        val next = when (field) {
            Field.BOTH_EXIST -> {
                val (both, adopting) = BOTH_EXIST_CHOICES[(bothExistIndex(relocation) + delta).mod(BOTH_EXIST_CHOICES.size)]
                // Leaving the target's rule for one that does not keep the target keeps the source's rule as it was.
                relocation.copy(
                    whenSourceAndTargetDirectoriesExist = both,
                    whenAdoptingTarget = if (both == WhenSourceAndTargetDirectoriesExist.ADOPT) adopting else relocation.whenAdoptingTarget,
                )
            }
            Field.ONLY_TARGET -> {
                val values = WhenOnlyTargetExists.entries
                relocation.copy(whenOnlyTargetExists = values[(relocation.whenOnlyTargetExists.ordinal + delta).mod(values.size)])
            }
            Field.SOURCE_ROOT, Field.TARGET_ROOT, Field.SUGGESTION_LIST, Field.SOURCE, Field.TARGET, Field.ARCHIVE_ROOT -> relocation
        }
        draft = draft.copy(relocations = draft.relocations.mapIndexed { i, it -> if (i == row - 1) next else it })
        message = ""
    }

    private fun requestClose() {
        if (unsaved() > 0) ask(Question.DISCARD) else close()
    }

    private fun ask(next: Question) {
        question = next
        focusBeforeQuestion = focus.focusedId()
    }

    private fun answered() {
        question = null
        focus.setFocus(focusBeforeQuestion)
    }

    /** Saves a new file at once; replacing an existing one asks first. Either way the draft is checked first. */
    private fun save() {
        pull()
        problem()?.let { (row, text) ->
            list.selected(row)
            message = text
            return
        }
        if (loadedBytes == null) publish { ConfigurationPublisher().saveNew(session.configPath, draft) }
        else ask(Question.REPLACE)
    }

    private fun publish(write: () -> Unit) {
        try {
            write()
        } catch (_: ConfigurationChangedException) {
            message = CHANGED_SINCE_LOADED
            return
        } catch (error: ConfigurationException) {
            message = notSaved(error.message.orEmpty())
            return
        } catch (error: IllegalArgumentException) {
            message = notSaved(error.message.orEmpty())
            return
        }
        saved = true
        close()
    }

    /** The first field that cannot be saved, as the list row it is on and the message that says so. */
    private fun problem(): Pair<Int, String>? {
        resolvedLines(0).firstNotNullOfOrNull { (label, resolved) -> (resolved as? Resolved.Problem)?.let { label to it } }
            ?.let { (label, problem) -> return 0 to notSavedIn(place(STORAGE_LOCATIONS, label), problem.text) }
        for (row in 1..draft.relocations.size) {
            resolvedLines(row).firstNotNullOfOrNull { (label, resolved) -> (resolved as? Resolved.Problem)?.let { label to it } }
                ?.let { (label, problem) -> return row to notSavedIn(place(name(relocation(row)), label), problem.text) }
        }
        return null
    }

    override fun close() {
        suggestions?.close()
        suggestions = null
        closed = true
    }

    private fun openBrowse() {
        val root = resolved(draft.sourceRoot, SOURCE_ROOT_LABEL)
        if (root !is Resolved.Found) { message = cannotBrowse((root as? Resolved.Problem)?.text.orEmpty()); return }
        val listed = suggestionList()
        if (listed is Resolved.Problem) { message = cannotBrowse(listed.text); return }
        val current = suggestions ?: Suggestions(discoveryFactory()).also { suggestions = it }
        val path = (listed as? Resolved.Found)?.path
        if (current.request != CandidateDiscovery.Request.of(root.path, path)) current.check(root.path, path)
        browsing = true
        message = ""
        focus.setFocus(CONFIG_BROWSE)
    }

    private fun leaveBrowse() {
        browsing = false
        shownRow = -1
        focus.setFocus(CONFIG_LIST)
    }

    private fun browseKey(key: KeyEvent) {
        if (key.isKey(KeyCode.ESCAPE)) { if (!browser.back()) leaveBrowse(); return }
        if (key.isQuit()) { requestClose(); return }
        // As in the list: Ctrl+U must not reveal hidden suggestions.
        if (key.hasCtrl() || key.hasAlt()) return
        if (key.isCharIgnoreCase('r')) {
            // Browse opens only with a check under way, so there is a request to repeat.
            val request = checkNotNull(suggestions?.request)
            suggestions?.check(request.root, request.sharedLocation)
            return
        }
        when (val action = browser.key(key, browseDraft())) {
            null -> {}
            is BrowseAction.Add -> browser.added(addSuggestion(action.source))
            is BrowseAction.Edit -> {
                leaveBrowse()
                list.selected(action.row + 1)
                focus.setFocus(Field.SOURCE.id)
            }
        }
    }

    /** Adds `source` as written in Browse, and returns why not when it would overlap a relocation. */
    private fun addSuggestion(source: Path): String? {
        val row = RelocationFile(displayPath(source))
        val relocations = (draft.relocations + row).mapNotNull { relocation ->
            val resolved = resolve(relocation)
            val path = (resolved.source as? Resolved.Found)?.path
            val target = (resolved.target as? Resolved.Found)?.path
            if (path == null || target == null) null else Relocation(path, target)
        }
        relocationProblem(relocations)?.let { return it.message }
        edit(draft.relocations + row, origins + null)
        return null
    }

    private fun browseDraft(): BrowseDraft {
        // Browse opens only after a check starts, which sets the request.
        val request = checkNotNull(suggestions?.request)
        val sources = draft.relocations.map { (resolve(it).source as? Resolved.Found)?.path }
        return BrowseDraft(request.root, sources, suggestions?.result())
    }

    /** Takes the shown row's typed text into the draft, field by field, when it differs. */
    private fun pull() {
        if (shownRow < 0 || shownRow > draft.relocations.size) return
        for (field in fields(shownRow).filter { it.text }) {
            val typed = inputs.getValue(field).text()
            if (typed != text(field, shownRow)) {
                draft = withText(field, shownRow, typed)
                message = ""
            }
        }
    }

    private fun show(row: Int) {
        for (field in fields(row).filter { it.text }) {
            inputs.getValue(field).setText(text(field, row))
            inputs.getValue(field).moveCursorToEnd()
        }
        shownRow = row
        detailsArea.reset()
    }

    private fun text(field: Field, row: Int): String = when (field) {
        Field.SOURCE_ROOT -> draft.sourceRoot
        Field.TARGET_ROOT -> draft.targetRoot
        Field.SUGGESTION_LIST -> draft.discovery?.suggestionList.orEmpty()
        Field.SOURCE -> relocation(row).sourcePath
        Field.TARGET -> relocation(row).targetPath.orEmpty()
        Field.ARCHIVE_ROOT -> relocation(row).archiveRoot.orEmpty()
        Field.BOTH_EXIST, Field.ONLY_TARGET -> ""
    }

    /** A blank optional field is left out of the file, as the loader reads a missing one. */
    private fun withText(field: Field, row: Int, value: String): HomeLightFile {
        fun edited(change: (RelocationFile) -> RelocationFile) =
            draft.copy(relocations = draft.relocations.mapIndexed { i, it -> if (i == row - 1) change(it) else it })
        return when (field) {
            Field.SOURCE_ROOT -> draft.copy(sourceRoot = value)
            Field.TARGET_ROOT -> draft.copy(targetRoot = value)
            Field.SUGGESTION_LIST -> draft.copy(discovery = value.ifEmpty { null }?.let(::DiscoveryFile))
            Field.SOURCE -> edited { it.copy(sourcePath = value) }
            Field.TARGET -> edited { it.copy(targetPath = value.ifEmpty { null }) }
            Field.ARCHIVE_ROOT -> edited { it.copy(archiveRoot = value.ifEmpty { null }) }
            Field.BOTH_EXIST, Field.ONLY_TARGET -> draft
        }
    }

    private fun selectedRow(): Int = list.selected().coerceIn(0, draft.relocations.size)

    private fun relocation(row: Int): RelocationFile = draft.relocations[row - 1]

    private fun fields(row: Int): List<Field> =
        if (row == 0) listOf(Field.SOURCE_ROOT, Field.TARGET_ROOT, Field.SUGGESTION_LIST)
        else listOf(Field.SOURCE, Field.TARGET, Field.BOTH_EXIST, Field.ONLY_TARGET, Field.ARCHIVE_ROOT)

    /**
     * Draft changes against the file as opened: each storage location that differs, and each relocation added,
     * removed or edited.
     */
    private fun unsaved(): Int {
        val locations = listOf(
            draft.sourceRoot != loaded.sourceRoot, draft.targetRoot != loaded.targetRoot, draft.discovery != loaded.discovery,
        ).count { it }
        val kept = origins.withIndex().filter { it.value != null }
        val edited = kept.count { (i, origin) -> draft.relocations[i] != loaded.relocations[checkNotNull(origin)] }
        return locations + origins.count { it == null } + edited + (loaded.relocations.size - kept.size)
    }

    /** What the Resolved section shows for `row`: each field's label and its path as the loader reads it. */
    private fun resolvedLines(row: Int): List<Pair<String, Resolved>> {
        if (row == 0) return listOf(
            SOURCE_ROOT_LABEL to resolved(draft.sourceRoot, SOURCE_ROOT_LABEL),
            TARGET_ROOT_LABEL to resolved(draft.targetRoot, TARGET_ROOT_LABEL),
            SUGGESTION_LIST_NAME to suggestionList(),
        )
        val resolved = resolve(relocation(row))
        return listOf(SOURCE_LABEL to resolved.source, TARGET_LABEL to resolved.target, ARCHIVE_ROOT_LABEL to resolved.archive)
    }

    private fun suggestionList(): Resolved {
        val value = draft.discovery?.suggestionList?.takeUnless { it.isBlank() } ?: return Resolved.Empty(NO_SUGGESTION_LIST)
        return try {
            // Not blank, so there is a path.
            Resolved.Found(checkNotNull(parseSharedList(value)))
        } catch (error: IllegalArgumentException) {
            Resolved.Problem(error.message.orEmpty())
        }
    }

    /** A relocation's paths as the loader reads them: a blank Target derives from the roots, a blank Archive root defaults. */
    private fun resolve(relocation: RelocationFile): ResolvedRelocation {
        val source = resolved(relocation.sourcePath, SOURCE_LABEL)
        val path = (source as? Resolved.Found)?.path
        val target = relocation.targetPath?.let { resolved(it, TARGET_LABEL) } ?: derived(path)
        val archive = relocation.archiveRoot?.let { resolved(it, ARCHIVE_ROOT_LABEL) }
            ?: path?.let { Resolved.Found(defaultArchiveRoot(it)) } ?: Resolved.Problem(NEEDS_SOURCE)
        return ResolvedRelocation(source, target, archive)
    }

    private fun derived(source: Path?): Resolved {
        if (source == null) return Resolved.Problem(NEEDS_SOURCE)
        val sourceRoot = (resolved(draft.sourceRoot, SOURCE_ROOT_LABEL) as? Resolved.Found)?.path
        val targetRoot = (resolved(draft.targetRoot, TARGET_ROOT_LABEL) as? Resolved.Found)?.path
        if (sourceRoot == null || targetRoot == null) return Resolved.Problem(NEEDS_ROOTS)
        return derivedTarget(sourceRoot, targetRoot, source)?.let { Resolved.Found(it) } ?: Resolved.Problem(OUTSIDE_SOURCE_ROOT)
    }

    companion object {
        /**
         * Opens the configuration file for editing, or an empty draft when there is none yet.
         *
         * @throws ConfigurationException when the file cannot be read as JSON in the configuration's shape
         */
        fun open(session: HomeLightSession, focus: FocusManager, discoveryFactory: () -> CandidateDiscovery): ConfigurationView {
            val view = if (Files.isRegularFile(session.configPath)) {
                val file = ConfigurationLoader().read(session.configPath)
                ConfigurationView(session, focus, discoveryFactory, file.file, file.bytes)
            } else ConfigurationView(session, focus, discoveryFactory, HomeLightFile(targetRoot = ""), null)
            // A new file needs its target root first; an existing one opens on its list.
            focus.setFocus(if (view.loadedBytes == null) Field.TARGET_ROOT.id else CONFIG_LIST)
            return view
        }
    }
}

/** A field's path as the loader reads it, why it cannot, or that it is empty and needs nothing. */
private sealed interface Resolved {
    data class Found(val path: Path) : Resolved
    data class Problem(val text: String) : Resolved
    data class Empty(val text: String) : Resolved
}

private data class ResolvedRelocation(val source: Resolved, val target: Resolved, val archive: Resolved)

private fun resolved(value: String, name: String): Resolved = try {
    Resolved.Found(resolvePath(value, name))
} catch (error: ConfigurationException) {
    Resolved.Problem(error.message.orEmpty())
}

/** A relocation as the list names it: its source as written. */
private fun name(relocation: RelocationFile): String = literal(relocation.sourcePath).ifBlank { NEW_RELOCATION }

/**
 * The **Both exist** values in screen order. The file keeps them in two fields; a value that does not keep the
 * target leaves the source's field as it is, so its second half is ignored.
 */
private val BOTH_EXIST_CHOICES = listOf(
    WhenSourceAndTargetDirectoriesExist.PROMPT to WhenAdoptingTarget.PROMPT,
    WhenSourceAndTargetDirectoriesExist.ADOPT to WhenAdoptingTarget.DISCARD_SOURCE,
    WhenSourceAndTargetDirectoriesExist.ADOPT to WhenAdoptingTarget.ARCHIVE_SOURCE,
    WhenSourceAndTargetDirectoriesExist.ADOPT to WhenAdoptingTarget.PROMPT,
    WhenSourceAndTargetDirectoriesExist.LEAVE_UNCHANGED to WhenAdoptingTarget.PROMPT,
    WhenSourceAndTargetDirectoriesExist.DISCARD to WhenAdoptingTarget.PROMPT,
)

private fun bothExistIndex(relocation: RelocationFile): Int = BOTH_EXIST_CHOICES.indexOfFirst { (both, adopting) ->
    both == relocation.whenSourceAndTargetDirectoriesExist &&
        (both != WhenSourceAndTargetDirectoriesExist.ADOPT || adopting == relocation.whenAdoptingTarget)
}

/** TamboUI's `Select` for one value among `options`, focusable as `id`: ←/→ change it (see [ConfigurationView.key]). */
private fun choice(options: List<String>, index: Int, id: String, focusable: Boolean): Element {
    // TamboUI has a Select widget but no element for it, so this renders the widget with focus from the context.
    class Choice : StyledElement<Choice>() {
        override fun preferredSize(width: Int, height: Int, context: RenderContext): Size = Size.heightOnly(1)
        override fun renderContent(frame: Frame, area: Rect, context: RenderContext) {
            Select.builder().leftIndicator("‹ ").rightIndicator(" ›").style(context.currentStyle())
                .selectedColor(if (context.isFocused(id)) palette.focus else palette.text).indicatorColor(palette.dim)
                .build().render(area, frame.buffer(), SelectState(options, index))
        }
    }
    return Choice().id(id).focusable(focusable).fill()
}

private fun clears(key: KeyEvent): Boolean = key.isChar('\u0015') || key.hasCtrl() && key.isCharIgnoreCase('u')
