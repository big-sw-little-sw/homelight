package io.github.bigswlittlesw.homelight.tui

import dev.tamboui.style.Style
import dev.tamboui.text.CharWidth
import dev.tamboui.toolkit.Toolkit
import dev.tamboui.toolkit.element.Element
import dev.tamboui.toolkit.element.StyledElement
import dev.tamboui.toolkit.elements.TreeElement
import dev.tamboui.toolkit.event.KeyEventHandler
import dev.tamboui.tui.event.KeyCode
import dev.tamboui.tui.event.KeyEvent
import dev.tamboui.widgets.common.ScrollBarPolicy
import dev.tamboui.widgets.tree.GuideStyle
import dev.tamboui.widgets.tree.TreeNode
import io.github.bigswlittlesw.homelight.application.BrowseDraft
import io.github.bigswlittlesw.homelight.config.CandidateDefinition
import io.github.bigswlittlesw.homelight.config.CandidateSource
import io.github.bigswlittlesw.homelight.discovery.CandidateDiscovery
import io.github.bigswlittlesw.homelight.discovery.CandidateObservation
import io.github.bigswlittlesw.homelight.tui.DetailViewport.Line
import java.nio.file.Path

/** What a Browse key asks of the Configuration draft. */
internal sealed interface BrowseAction {
    data class Add(val source: Path) : BrowseAction

    /** Remove the relocation at `row` from the draft. */
    data class Remove(val row: Int) : BrowseAction

    /** Edit the relocation at `row` in the draft. */
    data class Edit(val row: Int) : BrowseAction
}

/**
 * Browse: the suggestions grouped by app in TamboUI's tree, with each directory's details and the suggestion lists'
 * state one key away. App expansion, the selected row and draft membership are independent states.
 *
 * The selection follows an item, not a position: Browse keeps the selected item and sets the tree's index from it on
 * every frame, so checking again, `u` and a row added or removed never move it to another item.
 *
 * The focused Browse screen offers each key to the tree inside it before the app sees it. The tree passes every key
 * to `keys`, the app's handler, so its own moves, expand, collapse and toggle never run: Space and Enter keep their
 * meaning here. The app takes every mouse event before any element, so the tree's wheel and clicks never run either.
 */
internal class CandidateBrowser(keys: KeyEventHandler) {
    /** A tree row. A `null` app groups the directories that no list assigns to an app. */
    private sealed interface Item {
        data class Group(val app: String?) : Item

        data class Directory(val path: Path) : Item
    }

    private enum class View { LIST, DETAILS, LISTS }

    private val viewport = DetailViewport()
    private val tree = TreeElement<Item>().guideStyle(GuideStyle.UNICODE).indentWidth(2).highlightSymbol("❯")
        .highlightStyle(Style.EMPTY.bold())
        .scrollbar(ScrollBarPolicy.AS_NEEDED).scrollbarThumbColor(palette.focus).scrollbarTrackColor(palette.dim)
        .onKeyEvent(keys)
    private val collapsed = mutableSetOf<String?>()
    // The item the user selected. While it is not listed (hidden, or not suggested until a check finishes) the row at
    // its last position is selected instead, and the item comes back when it is listed again.
    private var focus: Item? = null
    private var shown = 0
    private var view = View.LIST
    private var reveal = false
    private var message = ""

    /** `interactive` is false while a dialog is open over Browse. */
    fun render(draft: BrowseDraft, interactive: Boolean = true): Element {
        val help = viewport.help(screenHelp(draft), interactive)
        val messageLine = message.takeIf { it.isNotEmpty() }?.let { wrappedText(it, palette.warn) }
        return when (view) {
            View.DETAILS -> Toolkit.column(
                viewport.render(
                    DETAILS_NAME, detailLines(focusedEntry(draft), (focus as? Item.Directory)?.path, draft), focused = true,
                    choiceLine = 0,
                ),
                *listOfNotNull(messageLine, help).toTypedArray(),
            ).fill()
            View.LISTS -> Toolkit.column(
                viewport.render(SUGGESTION_LISTS, listDetails(draft), focused = true, choiceLine = 0), help,
            ).fill()
            View.LIST -> Toolkit.column(*listOfNotNull(
                *listLines(draft).map { wrappedText(it.text, it.color) }.toTypedArray(),
                hiddenCount(draft).takeIf { it > 0 }?.let { wrappedText(hiddenLine(it, reveal), palette.warn) },
                framed(Toolkit.panel(BROWSE_NAME, suggestions(draft)), focused = true),
                messageLine, help,
            ).toTypedArray()).fill()
        }
    }

    /** The tree of suggestions, its selection set from [focus]; or a line saying there are none yet. */
    private fun suggestions(draft: BrowseDraft): Element {
        val entries = entriesByPath(draft)
        val groups = groups(draft, entries)
        val rows = rows(groups)
        if (rows.isEmpty()) return wrappedText(NO_SUGGESTIONS, palette.dim)
        shown = selectedIndex(rows)
        val nodes = groups.map { (app, members) ->
            TreeNode.of<Item>("", Item.Group(app)).expanded(app !in collapsed).apply {
                members.forEach { entry -> add(TreeNode.of<Item>("", Item.Directory(path(entry))).leaf()) }
            }
        }
        // Rebuilt every frame, so rows follow the draft and discovery; the tree keeps only its scroll offset.
        return tree.roots(*nodes.toTypedArray()).selected(shown).nodeRenderer { node ->
            when (val item = node.data()) {
                is Item.Group -> Toolkit.text(literal(groupLabel(item.app, node.children().size))).fg(palette.text).bold()
                is Item.Directory -> directoryRow(entries.getValue(item.path), draft)
                null -> null
            }
        }.fill()
    }

    /** Browse's purpose and keys in its current state, for its help lines and the Help screen. */
    fun screenHelp(draft: BrowseDraft): ScreenHelp {
        val checkAgain = CHECK_AGAIN_KEY.copy(description = CHECK_LISTS_AGAIN)
        val close = CLOSE_CONFIGURATION_KEY.copy(keys = "q")
        val scroll = KeyHint("[/]", "Scroll", inHelpArea = false)
        fun help(navigation: List<KeyHint?>, commands: List<KeyHint?>) = ScreenHelp(
            place(CONFIGURATION_NAME, BROWSE_NAME), PURPOSE_BROWSE, Step.CONFIGURE,
            navigation.filterNotNull(), commands.filterNotNull(),
        )
        return when (view) {
            View.LISTS -> help(
                listOf(SCROLL_KEY, SCROLL_ENDS_KEYS, scroll, KeyHint("Esc", "Back", description = BACK_TO_SUGGESTIONS)),
                listOf(checkAgain, HELP_KEY, close),
            )
            View.DETAILS -> {
                val entry = focusedEntry(draft)
                help(
                    listOf(SCROLL_KEY, SCROLL_ENDS_KEYS, scroll, KeyHint("Esc", "Back", description = BACK_TO_SUGGESTIONS)),
                    listOf(entry?.let { toggleKey(it, draft) }, entry?.let(::editKey), checkAgain, HELP_KEY, close),
                )
            }
            View.LIST -> {
                val item = selected(draft)
                val entry = (item as? Item.Directory)?.let { entry(draft, it.path) }
                val hidden = hiddenCount(draft)
                help(
                    listOf(
                        KeyHint("↑/↓", "Move", description = SELECT_SUGGESTION).takeIf { item != null },
                        entry?.let { toggleKey(it, draft) }, entry?.let(::editKey),
                        when (item) {
                            is Item.Directory -> KeyHint("Enter", "Inspect", description = INSPECT_SUGGESTION)
                            is Item.Group -> KeyHint("Enter", "Expand/collapse", description = EXPAND_GROUP)
                            null -> null
                        },
                        KeyHint("Esc", "Back", description = BACK_TO_CONFIGURATION_LIST),
                        HOME_END_KEYS.takeIf { item != null },
                    ),
                    listOf(
                        checkAgain, KeyHint("i", "Lists", description = SEE_LISTS),
                        KeyHint("u", (if (reveal) "Hide " else "Show ") + hidden, description = showHidden(reveal))
                            .takeIf { hidden > 0 },
                        HELP_KEY, close,
                    ),
                )
            }
        }
    }

    /**
     * The mouse wheel at `x`, `y`: over the suggestions it moves the selection a row, as ↑/↓ do; over details it
     * scrolls them.
     */
    fun wheel(x: Int, y: Int, delta: Int, draft: BrowseDraft) {
        if (view != View.LIST) { if (viewport.contains(x, y)) viewport.scroll(delta); return }
        if (tree.renderedArea()?.contains(x, y) == true) move(draft) { index, _ -> index + delta }
    }

    /** Handles a key, and returns what the draft should do about it: null when only Browse changes. */
    fun key(key: KeyEvent, draft: BrowseDraft): BrowseAction? {
        if (key.isChar('[') || key.isChar(']')) { viewport.scroll(if (key.isChar(']')) 1 else -1); return null }
        if (view == View.LISTS) { scroll(key); return null }
        val item = if (view == View.DETAILS) focus else selected(draft)
        val entry = (item as? Item.Directory)?.let { entry(draft, it.path) }
        val row = entry?.row
        when {
            key.isChar(' ') && entry != null -> {
                focus = item
                message = ""
                // entry() matches entries by source path, so the path is set.
                return if (row != null) BrowseAction.Remove(row)
                else if (draft.canAdd(entry)) BrowseAction.Add(path(entry)) else null
            }
            key.isCharIgnoreCase('e') && row != null -> return BrowseAction.Edit(row)
            view == View.DETAILS -> scroll(key)
            key.isCharIgnoreCase('i') -> { view = View.LISTS; viewport.reset(); message = "" }
            key.isCharIgnoreCase('u') && hiddenCount(draft) > 0 -> reveal = !reveal
            key.isKey(KeyCode.ENTER) -> {
                focus = item
                when (item) {
                    is Item.Group -> if (!collapsed.remove(item.app)) collapsed.add(item.app)
                    is Item.Directory -> { view = View.DETAILS; viewport.reset() }
                    null -> {}
                }
            }
            key.isUp() || key.isDown() -> move(draft) { index, _ -> index + if (key.isUp()) -1 else 1 }
            key.isHome() || key.isEnd() -> move(draft) { _, last -> if (key.isEnd()) last else 0 }
        }
        return null
    }

    /** After [BrowseAction.Add]: `refusal` says why the draft did not take the suggestion, or is null when it did. */
    fun added(refusal: String?) {
        message = refusal?.let(::notAdded).orEmpty()
    }

    fun back(): Boolean {
        if (view == View.LIST) return false
        view = View.LIST; message = ""; viewport.reset(); return true
    }

    private fun scroll(key: KeyEvent) {
        when {
            key.isUp() -> viewport.scroll(-1)
            key.isDown() -> viewport.scroll(1)
            key.isHome() -> viewport.scroll(-Int.MAX_VALUE)
            key.isEnd() -> viewport.scroll(Int.MAX_VALUE)
        }
    }

    private fun move(draft: BrowseDraft, to: (index: Int, last: Int) -> Int) {
        val rows = rows(groups(draft, entriesByPath(draft)))
        if (rows.isEmpty()) return
        focus = rows[to(selectedIndex(rows), rows.size - 1).coerceIn(0, rows.size - 1)]
        message = ""
    }

    /** The selected row's index in `rows`: [focus] where it is listed, else the row at the position last shown. */
    private fun selectedIndex(rows: List<Item>): Int =
        rows.indexOf(focus).takeIf { it >= 0 } ?: shown.coerceIn(0, maxOf(0, rows.size - 1))

    /** The selected row, which keys act on; null when there are no suggestions. */
    private fun selected(draft: BrowseDraft): Item? = rows(groups(draft, entriesByPath(draft))).let { it.getOrNull(selectedIndex(it)) }

    /** The listed entries, grouped by the app of their first definition, which is your list's when it names one. */
    private fun groups(draft: BrowseDraft, entries: Map<Path, BrowseDraft.Entry>): List<Pair<String?, List<BrowseDraft.Entry>>> =
        entries.values.filter { entry -> reveal || !hidden(entry, draft) }
            .groupBy { entry -> definitions(entry).firstOrNull()?.app }.toList()

    /** The rows the tree shows, in its order: each group, then its directories unless it is collapsed. */
    private fun rows(groups: List<Pair<String?, List<BrowseDraft.Entry>>>): List<Item> = groups.flatMap { (app, entries) ->
        listOf(Item.Group(app)) + if (app in collapsed) listOf() else entries.map { Item.Directory(path(it)) }
    }

    private fun focusedEntry(draft: BrowseDraft): BrowseDraft.Entry? = (focus as? Item.Directory)?.let { entry(draft, it.path) }
}

/** Escapes control and format characters as `\uXXXX`, so untrusted text cannot control the terminal. */
internal fun literal(text: String): String = buildString {
    text.codePoints().forEach { point ->
        if (Character.isISOControl(point) || Character.getType(point) == Character.FORMAT.toInt()) {
            append("\\u%04x".format(point))
        } else appendCodePoint(point)
    }
}

private fun entry(draft: BrowseDraft, path: Path): BrowseDraft.Entry? = draft.entries().firstOrNull { it.sourcePath == path }

// Browse lists only entries with a source path (entriesByPath).
private fun path(entry: BrowseDraft.Entry): Path = checkNotNull(entry.sourcePath)

private fun entriesByPath(draft: BrowseDraft): Map<Path, BrowseDraft.Entry> {
    val entries = linkedMapOf<Path, BrowseDraft.Entry>()
    draft.entries().forEach { e -> e.sourcePath?.let { path -> entries.putIfAbsent(path, e) } }
    // Membership changes must not reorder the list while marking adjacent rows.
    val ordered = linkedMapOf<Path, BrowseDraft.Entry>()
    draft.discovery?.candidates?.forEach { c ->
        val path = c.catalog.sourcePath
        entries[path]?.let { ordered[path] = it }
    }
    entries.forEach { (path, entry) -> ordered.putIfAbsent(path, entry) }
    return ordered
}

/** Your list's definitions first (see `CandidateCatalog.merge`), so the first one's app and advice win. */
private fun definitions(entry: BrowseDraft.Entry): List<CandidateDefinition> =
    entry.discovery?.catalog?.definitions ?: listOf()

/**
 * Hidden when every list that names it marks it usually not needed, and only while each of those lists is read in
 * this check; never when it is in the configuration.
 */
private fun hidden(entry: BrowseDraft.Entry, draft: BrowseDraft): Boolean {
    val definitions = definitions(entry)
    return entry.row == null && definitions.isNotEmpty() && definitions.all { d ->
        d.advice == CandidateDefinition.Advice.USUALLY_UNNECESSARY &&
            draft.discovery?.sources.orEmpty().any { s -> s.source == d.source && s.status == CandidateDiscovery.SourceStatus.CURRENT }
    }
}

private fun hiddenCount(draft: BrowseDraft): Int = entriesByPath(draft).values.count { hidden(it, draft) }

private fun toggleKey(entry: BrowseDraft.Entry, draft: BrowseDraft): KeyHint? = when {
    entry.row != null -> KeyHint("Space", "Remove", description = REMOVE_SUGGESTION)
    draft.canAdd(entry) -> KeyHint("Space", "Add", description = ADD_SUGGESTION)
    else -> null
}

private fun editKey(entry: BrowseDraft.Entry): KeyHint? =
    KeyHint("e", "Edit", description = EDIT_SUGGESTION).takeIf { entry.row != null }

/** A directory row: its marker, its path under the source root and its notes, in the configuration's color when in it. */
private fun directoryRow(entry: BrowseDraft.Entry, draft: BrowseDraft): StyledElement<*> {
    val marker = when {
        entry.row != null -> "[x]"
        draft.canAdd(entry) -> "[ ]"
        else -> " − "
    }
    val path = compact(relative(draft, path(entry)))
    val notes = listOfNotNull(
        state(entry).takeIf { entry.discovery?.observation?.kind != CandidateObservation.Kind.DIRECTORY },
        USUALLY_NOT_NEEDED_NOTE.takeIf { definitions(entry).firstOrNull()?.advice == CandidateDefinition.Advice.USUALLY_UNNECESSARY },
    ).joinToString(" · ")
    return Toolkit.text(" $marker $path" + " ".repeat(maxOf(1, PATH_COLUMN - CharWidth.of(path))) + notes)
        .fg(if (entry.row != null) palette.ok else palette.text)
}

private fun relative(draft: BrowseDraft, path: Path): String =
    if (path.startsWith(draft.sourceRoot)) draft.sourceRoot.relativize(path).toString() else path.toString()

private fun compact(path: String): String {
    val value = literal(path)
    return if (value.length <= 30) value else value.substring(0, 14) + "…" + value.substring(value.length - 15)
}

/** The longest path a row shows, [compact]'s 30 cells, and two spaces before the notes. */
private const val PATH_COLUMN = 32

private fun state(entry: BrowseDraft.Entry): String = entry.discovery?.let { observationNote(it.observation) } ?: NOT_CHECKED

/** The two Lists lines over the suggestions: the built-in list, then yours, each with its state. */
private fun listLines(draft: BrowseDraft): List<Line> {
    val sources = draft.discovery?.sources.orEmpty()
    val yours = sources.firstOrNull { it.source.kind == CandidateSource.Kind.SHARED }
    val yourLine = when {
        yours != null -> listLine(yours)
        draft.discovery?.request?.sharedLocation == null && draft.discovery != null -> Line(NO_LIST_OF_YOUR_OWN, palette.dim)
        else -> Line(listSummary(YOUR_LIST, null, LIST_CHECKING), palette.dim)
    }
    val builtIn = sources.firstOrNull { it.source.kind == CandidateSource.Kind.BUNDLED }
    return listOf(builtIn?.let(::listLine) ?: Line(listSummary(BUILT_IN_LIST, null, LIST_CHECKING), palette.dim), yourLine)
}

private fun listLine(outcome: CandidateDiscovery.SourceOutcome): Line {
    val name = listName(outcome.source)
    val location = outcome.source.takeIf { it.kind == CandidateSource.Kind.SHARED }?.let { displayPath(Path.of(it.location)) }
    return when (outcome.status) {
        CandidateDiscovery.SourceStatus.PENDING -> Line(listSummary(name, location, LIST_CHECKING), palette.dim)
        CandidateDiscovery.SourceStatus.CURRENT -> Line(
            listSummary(
                name, location,
                suggestionCount(outcome.catalog?.definitions.orEmpty().map { it.sourcePath }.distinct().size),
                outcome.modified?.let(::fileUpdated),
            ),
            palette.dim,
        )
        CandidateDiscovery.SourceStatus.FAILED -> Line(
            listSummary(name, location, listNotUsed(outcome.problems.firstOrNull()?.let { listProblem(it.kind) } ?: listErrors(outcome.diagnostics.size))),
            palette.warn,
        )
    }
}

private fun listName(source: CandidateSource): String =
    if (source.kind == CandidateSource.Kind.BUNDLED) BUILT_IN_LIST else YOUR_LIST

/** The `i` view: each list's line, then where it is and everything said about why it was not used. */
private fun listDetails(draft: BrowseDraft): List<Line> {
    val result = draft.discovery ?: return listOf(Line(LISTS_DO_NOT_BLOCK))
    return listOf(Line(LISTS_DO_NOT_BLOCK)) + result.sources.flatMap { source ->
        listOf(listLine(source).copy(bold = true)) +
            listOfNotNull(source.source.takeIf { it.kind == CandidateSource.Kind.SHARED }?.let { Line(location(literal(it.location))) }) +
            source.problems.flatMap { problem ->
                listOf(Line(listProblemAdvice(problem.kind), palette.warn), Line(diagnostic(literal(problem.detail))))
            } +
            listOfNotNull(Line(LIST_REJECTED, palette.warn).takeIf { source.diagnostics.isNotEmpty() }) +
            source.diagnostics.map { d ->
                Line(
                    literal(d.message) + (if (d.location.isEmpty()) "" else " · " + literal(d.location)) +
                        (if (d.line == 0) "" else " · " + inputPosition(d.line, d.column)),
                    palette.warn,
                )
            }
    } + listOfNotNull(result.rootFailure?.let { d -> Line(rootProblem(literal(d.detail), literal(d.path.toString()))) })
}

/** The details of `entry`, or that the directory at `path` is no longer listed. */
private fun detailLines(entry: BrowseDraft.Entry?, path: Path?, draft: BrowseDraft): List<Line> {
    if (entry == null) {
        return listOfNotNull(Line(NO_LONGER_LISTED), path?.let { Line(sourceText(literal(it.toString()))) }, Line(BACK_FOR_SUGGESTIONS))
    }
    val candidate = entry.discovery
    val observation = candidate?.observation
    val missing = observation?.kind == CandidateObservation.Kind.MISSING
    return listOfNotNull(
        Line(if (entry.row != null) IN_CONFIGURATION else NOT_IN_CONFIGURATION, palette.text, true),
        Line(sourceText(literal(path(entry).toString()))),
        Line(stateLine(state(entry))),
    ) + (if (missing) MISSING_SUGGESTION.map(::Line) else listOf()) +
        listOfNotNull(
            Line(SIZE_AND_OWNERSHIP),
            observation?.takeIf { it.kind != CandidateObservation.Kind.PENDING }?.let { Line(observedLine(it.observedAt.toString())) },
            observation?.rawLinkTarget?.let { Line(linkText(literal(it.toString()))) },
        ) +
        observation?.diagnostics.orEmpty().map { d -> Line(observationDetail(literal(d.detail), literal(d.path.toString()))) } +
        candidate?.ancestors.orEmpty().map { Line(suggestedAround(literal(it.toString()))) } +
        draft.discovery?.candidates.orEmpty().filter { c -> candidate != null && candidate.catalog.sourcePath in c.ancestors }
            .map { c -> Line(suggestedInside(literal(c.catalog.sourcePath.toString()))) } +
        Line(
            when {
                entry.row != null -> CHANGE_IN_CONFIGURATION
                draft.canAdd(entry) -> ADDING_ASKS
                else -> CANNOT_ADD
            },
        ) +
        attribution(entry)
}

/** Which lists suggest it and what each says, your list's first, so the built-in advice shows too. */
private fun attribution(entry: BrowseDraft.Entry): List<Line> {
    val definitions = definitions(entry)
    val lead = Line(ADVICE_IS_OPTIONAL, palette.dim)
    if (definitions.isEmpty()) return listOf(lead, Line(NO_LIST_SUGGESTS))
    return listOf(lead, Line(SUGGESTED_BY, palette.text, true)) + definitions.flatMap { d ->
        listOfNotNull(
            Line(listName(d.source) + " · " + literal(d.app ?: OTHER_DIRECTORIES)),
            Line(adviceLine(adviceLabel(d.advice))),
            d.reason?.let { Line(reasonLine(literal(it))) },
            // The built-in list is inside HomeLight, so only your list has a location worth showing.
            Line(
                fromLine(
                    d.source.location.takeIf { d.source.kind == CandidateSource.Kind.SHARED }?.let(::literal),
                    literal(d.location), literal(d.originalPath),
                ),
            ),
        )
    }
}

private fun adviceLabel(advice: CandidateDefinition.Advice?): String = when (advice) {
    CandidateDefinition.Advice.CONSIDER -> ADVICE_CONSIDER
    CandidateDefinition.Advice.USUALLY_UNNECESSARY -> ADVICE_USUALLY_NOT_NEEDED
    null -> ADVICE_NOT_GIVEN
}

private fun listProblem(kind: CandidateDiscovery.SourceProblem.Kind): String = when (kind) {
    CandidateDiscovery.SourceProblem.Kind.MISSING -> LIST_MISSING
    CandidateDiscovery.SourceProblem.Kind.UNREADABLE -> LIST_UNREADABLE
    CandidateDiscovery.SourceProblem.Kind.NOT_REGULAR -> LIST_NOT_REGULAR
    CandidateDiscovery.SourceProblem.Kind.IO_ERROR -> LIST_IO_ERROR
    CandidateDiscovery.SourceProblem.Kind.DEADLINE -> LIST_DEADLINE
    CandidateDiscovery.SourceProblem.Kind.PREVIOUS_PENDING -> LIST_PREVIOUS_PENDING
}

private fun listProblemAdvice(kind: CandidateDiscovery.SourceProblem.Kind): String = when (kind) {
    CandidateDiscovery.SourceProblem.Kind.MISSING -> LIST_MISSING_ADVICE
    CandidateDiscovery.SourceProblem.Kind.UNREADABLE -> LIST_UNREADABLE_ADVICE
    CandidateDiscovery.SourceProblem.Kind.NOT_REGULAR -> LIST_NOT_REGULAR_ADVICE
    CandidateDiscovery.SourceProblem.Kind.IO_ERROR -> LIST_IO_ERROR_ADVICE
    CandidateDiscovery.SourceProblem.Kind.DEADLINE -> LIST_DEADLINE_ADVICE
    CandidateDiscovery.SourceProblem.Kind.PREVIOUS_PENDING -> LIST_PREVIOUS_PENDING_ADVICE
}
