package io.github.bigswlittlesw.homelight.tui

import dev.tamboui.style.Color
import dev.tamboui.text.CharWidth
import dev.tamboui.toolkit.Toolkit
import dev.tamboui.toolkit.element.Element
import dev.tamboui.tui.event.KeyCode
import dev.tamboui.tui.event.KeyEvent
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

    /** Edit the relocation at `row` in the draft. */
    data class Edit(val row: Int) : BrowseAction
}

/** App expansion, focused identity and draft membership are independent states. */
internal class CandidateBrowser {
    private sealed interface Item {
        /** A `null` app groups the directories that no definition assigns to an app. */
        data class Group(val app: String?, val count: Int) : Item

        data class Directory(val path: Path) : Item
    }

    private val viewport = DetailViewport()
    private val collapsed = mutableSetOf<String?>()
    private var focus: Item? = null
    private var details = false
    private var diagnostics = false
    private var reveal = false
    private var message = ""

    /** `interactive` is false while a dialog is open over the browser. */
    fun render(draft: BrowseDraft, interactive: Boolean = true): Element {
        val lines = mutableListOf<Line>()
        var anchor = -1
        val title: String
        if (diagnostics) {
            title = "Discovery sources"
            sourceDetails(lines, draft)
        } else if (details) {
            title = "Candidate details"
            val entry = focusedEntry(draft)
            if (entry == null) {
                lines.add(Line("No longer in current discovery."))
                val focused = focus
                if (focused is Item.Directory) lines.add(Line("Source: " + literal(focused.path.toString())))
                lines.add(Line("Existing draft rows are retained. Esc returns to the list."))
            } else detailLines(lines, entry, draft)
        } else {
            title = "Browse candidates"
            val unique = entriesByPath(draft)
            val items = items(draft, unique)
            if (focus == null && items.isNotEmpty()) focus = items.first()
            val inDraft = unique.values.count { it.row != null }
            lines.add(Line("${unique.size} candidates · $inDraft in draft", palette.dim, false))
            if (draft.discovery?.sources.orEmpty().any { it.status != CandidateDiscovery.SourceStatus.CURRENT }) {
                lines.add(Line(sourceSummary(draft), palette.warn, false))
            }
            val hidden = hiddenCount(draft)
            if (hidden > 0) lines.add(
                Line(
                    "$hidden usually not needed, " + (if (reveal) "shown" else "hidden"),
                    palette.warn, true,
                ),
            )
            if (items.isEmpty()) lines.add(Line("No candidates available yet. Esc returns to Configuration."))
            for (item in items) {
                val selected = same(item, focus)
                if (selected) anchor = lines.size
                val label: String
                val color: Color
                when (item) {
                    is Item.Group -> {
                        label = (if (item.app in collapsed) "▸ " else "▾ ") + (item.app ?: "Other directories") + " (" + item.count + ")"
                        color = palette.text
                    }
                    is Item.Directory -> {
                        val entry = unique.getValue(item.path) // directory items come from these keys
                        val path = compact(relative(draft, item.path))
                        val marker = when {
                            entry.row != null -> "[x]"
                            draft.canAdd(entry) -> "[ ]"
                            else -> " − "
                        }
                        label = "  " + marker + " " + path + " ".repeat(maxOf(1, 32 - CharWidth.of(path))) + listNotes(entry)
                        color = if (entry.row != null) palette.ok else palette.text
                    }
                }
                lines.add(
                    Line((if (selected) "❯ " else "  ") + literal(label), if (selected) palette.focus else color, selected || item is Item.Group),
                )
            }
            val listedFocus = focus != null && items.any { same(it, focus) }
            if (focus != null && !listedFocus) {
                anchor = lines.size
                lines.add(
                    Line(
                        if (focus is Item.Directory) "❯ Focused path is hidden or no longer listed."
                        else "❯ Focused app is no longer listed.",
                        palette.focus, true,
                    ),
                )
            }
        }
        if (message.isNotEmpty() && details) lines.add(0, Line(literal(message), palette.warn, false))
        val reader = viewport.render(title, lines, interactive, anchor)
        val help = viewport.help(screenHelp(draft), interactive)
        return if (message.isNotEmpty() && !details && !diagnostics)
            Toolkit.column(
                reader,
                wrappedText("Not added. Inspect the row for details; prior choices are unchanged.", palette.warn),
                help,
            ).fill()
        else Toolkit.column(reader, help).fill()
    }

    /** Browse's purpose and keys in its current state, for its help lines and the Help screen. */
    fun screenHelp(draft: BrowseDraft): ScreenHelp {
        val scroll = KeyHint("[/]", "Scroll", inHelpArea = false)
        val refresh = KeyHint("r", "Refresh", description = "Read the suggestion lists again")
        val close = CLOSE_CONFIGURATION_KEY.copy(keys = "q")
        if (diagnostics || details) {
            val entryKey = if (diagnostics) null else focusedEntry(draft)?.let { entry -> action(entry, draft) }
            return ScreenHelp(
                place(CONFIGURATION_NAME, BROWSE_NAME), PURPOSE_BROWSE, Step.CONFIGURE,
                listOf(SCROLL_KEY, SCROLL_ENDS_KEYS, scroll, KeyHint("Esc", "Back", description = "Back to the suggestions")),
                listOfNotNull(entryKey, refresh, HELP_KEY, close),
            )
        }
        val items = items(draft, entriesByPath(draft))
        val listedFocus = focus != null && items.any { same(it, focus) }
        val hidden = hiddenCount(draft)
        val enter = when {
            focus is Item.Directory -> KeyHint("Enter", "Inspect", description = "See why it is suggested and by which list")
            listedFocus -> KeyHint("Enter", "Expand/collapse", description = "Show or hide the group's directories")
            else -> null
        }
        return ScreenHelp(
            place(CONFIGURATION_NAME, BROWSE_NAME), PURPOSE_BROWSE, Step.CONFIGURE,
            listOfNotNull(
                KeyHint("↑/↓", "Move", description = "Select a suggestion").takeIf { items.isNotEmpty() },
                focusedEntry(draft)?.let { listAction(it, draft) }, enter,
                KeyHint("Esc", "Back", description = "Back to the configuration list"), HOME_END_KEYS.takeIf { items.isNotEmpty() },
                scroll,
            ),
            listOfNotNull(
                refresh, KeyHint("i", "Sources", description = "See whether each suggestion list was read"),
                KeyHint(
                    "u", (if (reveal) "Hide " else "Show ") + hidden,
                    description = (if (reveal) "Hide" else "Show") + " the suggestions marked usually not needed",
                ).takeIf { hidden > 0 },
                HELP_KEY, close,
            ),
        )
    }

    /** The mouse wheel at `x`, `y` scrolls the pane under it; it never moves the focused suggestion. */
    fun wheel(x: Int, y: Int, delta: Int) {
        if (viewport.contains(x, y)) viewport.scroll(delta)
    }

    /** Handles a key, and returns what the draft should do about it: null when only Browse changes. */
    fun key(key: KeyEvent, draft: BrowseDraft): BrowseAction? {
        if (key.isChar('[') || key.isChar(']')) { viewport.scroll(if (key.isChar(']')) 1 else -1); return null }
        if (!diagnostics) {
            val entry = focusedEntry(draft)
            val row = entry?.row
            if (key.isCharIgnoreCase('e') && row != null) return BrowseAction.Edit(row)
            if ((key.isCharIgnoreCase('a') || key.isChar(' ')) && entry != null && draft.canAdd(entry)) {
                // focusedEntry matches entries by source path, so the path is set.
                return BrowseAction.Add(checkNotNull(entry.sourcePath))
            }
        }
        if (details || diagnostics) {
            if (key.isUp()) viewport.scroll(-1)
            else if (key.isDown()) viewport.scroll(1)
            else if (key.isHome()) viewport.scroll(-Int.MAX_VALUE)
            else if (key.isEnd()) viewport.scroll(Int.MAX_VALUE)
            return null
        }
        if (key.isCharIgnoreCase('i')) { diagnostics = true; viewport.reset(); message = "" }
        else if (key.isCharIgnoreCase('u') && hiddenCount(draft) > 0) { reveal = !reveal; viewport.reset() }
        else if (key.isKey(KeyCode.ENTER)) {
            val focused = focus
            if (focused is Item.Group) {
                if (!collapsed.remove(focused.app)) collapsed.add(focused.app)
            } else if (focused is Item.Directory) { details = true; viewport.reset() }
        } else if (key.isUp() || key.isDown() || key.isHome() || key.isEnd()) {
            val items = items(draft, entriesByPath(draft))
            if (items.isNotEmpty()) {
                val index = items.indexOfLast { same(it, focus) }
                val next = when {
                    key.isHome() -> 0
                    key.isEnd() -> items.size - 1
                    else -> (index + (if (key.isUp()) -1 else 1)).coerceIn(0, items.size - 1)
                }
                focus = items[next]; message = ""; viewport.keepChoiceVisible()
            }
        }
        return null
    }

    /** After [BrowseAction.Add]: `refusal` says why the draft did not take the suggestion, or is null when it did. */
    fun added(refusal: String?) {
        message = refusal?.let { "Not added. $it. Prior choices are unchanged." } ?: ""
        if (details) viewport.reset() else viewport.keepChoiceVisible()
    }

    fun back(): Boolean {
        if (!details && !diagnostics) return false
        details = false; diagnostics = false; message = ""; viewport.reset(); viewport.keepChoiceVisible(); return true
    }

    private fun items(draft: BrowseDraft, entriesByPath: Map<Path, BrowseDraft.Entry>): List<Item> =
        entriesByPath.values
            .filter { entry -> reveal || !hidden(entry, draft) }
            .groupBy { entry -> definitions(entry).firstNotNullOfOrNull { it.app } }
            // entriesByPath keeps only entries with a source path.
            .flatMap { (app, entries) ->
                listOf(Item.Group(app, entries.size)) +
                    entries.filter { app !in collapsed || member(it) }.map { Item.Directory(checkNotNull(it.sourcePath)) }
            }

    private fun focusedEntry(draft: BrowseDraft): BrowseDraft.Entry? {
        val focused = focus as? Item.Directory ?: return null
        return draft.entries().firstOrNull { it.sourcePath == focused.path }
    }

    /** Identity ignores a group's count, which changes as membership does. */
    private fun same(left: Item, right: Item?): Boolean = when (left) {
        is Item.Group -> right is Item.Group && left.app == right.app
        is Item.Directory -> right is Item.Directory && left.path == right.path
    }
}

/** Escapes control and format characters as `\uXXXX`, so untrusted text cannot control the terminal. */
internal fun literal(text: String): String = buildString {
    text.codePoints().forEach { point ->
        if (Character.isISOControl(point) || Character.getType(point) == Character.FORMAT.toInt()) {
            append("\\u%04x".format(point))
        } else appendCodePoint(point)
    }
}

private fun attribution(lines: MutableList<Line>, entry: BrowseDraft.Entry, draft: BrowseDraft) {
    val current = definitions(entry)
    lines.add(Line("Advice is optional, not a safety assessment or a requirement.", palette.dim, false))
    if (current.isEmpty()) lines.add(Line("No current catalog attribution."))
    else {
        lines.add(Line("Discovery attribution · source status below", palette.text, true))
        for (definition in current) {
            val status = draft.discovery?.sources.orEmpty().firstOrNull { it.source == definition.source }
                ?.let { sourceState(it.status) } ?: "unavailable"
            definition(lines, definition, status)
        }
    }
}

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

private fun definitions(entry: BrowseDraft.Entry): List<CandidateDefinition> =
    entry.discovery?.catalog?.definitions ?: listOf()

private fun member(entry: BrowseDraft.Entry): Boolean = entry.row != null

private fun membership(entry: BrowseDraft.Entry): String = if (entry.row != null) "In draft" else "Not added"

private fun hidden(entry: BrowseDraft.Entry, draft: BrowseDraft): Boolean {
    val definitions = definitions(entry)
    return !member(entry) && definitions.isNotEmpty() && definitions.all { d ->
        d.advice == CandidateDefinition.Advice.USUALLY_UNNECESSARY &&
            draft.discovery?.sources.orEmpty().any { s -> s.source == d.source && s.status == CandidateDiscovery.SourceStatus.CURRENT }
    }
}

private fun hiddenCount(draft: BrowseDraft): Int =
    draft.entries().filter { hidden(it, draft) }.map { it.sourcePath }.distinct().size

private fun relative(draft: BrowseDraft, path: Path): String =
    if (path.startsWith(draft.sourceRoot)) draft.sourceRoot.relativize(path).toString() else path.toString()

private fun compact(path: String): String {
    val value = literal(path)
    return if (value.length <= 30) value else value.substring(0, 14) + "…" + value.substring(value.length - 15)
}


private fun action(entry: BrowseDraft.Entry, draft: BrowseDraft): KeyHint? = when {
    entry.row != null -> KeyHint("e", "Edit draft row", description = "Edit its relocation")
    draft.canAdd(entry) -> KeyHint("a", "Add to draft", description = "Add it to the configuration")
    else -> null
}

private fun listAction(entry: BrowseDraft.Entry, draft: BrowseDraft): KeyHint? = when {
    entry.row != null -> KeyHint("e", "Edit", description = "Edit its relocation")
    draft.canAdd(entry) -> KeyHint("Space/a", "Add", description = "Add the suggestion to the configuration")
    else -> null
}

private fun listNotes(entry: BrowseDraft.Entry): String {
    val notes = mutableListOf<String>()
    if (entry.discovery?.observation?.kind != CandidateObservation.Kind.DIRECTORY) notes.add(state(entry))
    val advice = adviceSummary(entry)
    if (advice == " · Mixed advice" || advice == " · Usually not needed") notes.add(advice.substring(3).lowercase())
    return notes.joinToString(" · ")
}

private fun adviceSummary(entry: BrowseDraft.Entry): String {
    val values = definitions(entry).map { it.advice }.distinct()
    if (values.filterNotNull().size > 1) return " · Mixed advice"
    // One supplied value and one omitted value.
    if (values.size > 1) return " · " + advice(values.firstNotNullOf { it }) + "; some advice omitted"
    val only = values.firstOrNull() ?: return ""
    return " · " + advice(only)
}

private fun advice(advice: CandidateDefinition.Advice?): String = when (advice) {
    CandidateDefinition.Advice.CONSIDER -> "Consider"
    CandidateDefinition.Advice.USUALLY_UNNECESSARY -> "Usually not needed"
    null -> "Not supplied"
}

private fun state(entry: BrowseDraft.Entry): String = entry.discovery?.let { observationNote(it.observation) } ?: "not checked"

private fun detailLines(lines: MutableList<Line>, entry: BrowseDraft.Entry, draft: BrowseDraft) {
    lines.add(Line(membership(entry), palette.text, true))
    // The browser finds entries by source path (focusedEntry, entriesByPath), so the path is set.
    lines.add(Line("Source: " + literal(checkNotNull(entry.sourcePath).toString())))
    lines.add(Line("State: " + state(entry)))
    if (entry.discovery?.observation?.kind == CandidateObservation.Kind.MISSING) {
        lines.add(Line("Not found under the source root. You can configure it before the app creates it."))
        lines.add(Line("On Apply, if source and target are both missing: create the target directory and source link."))
        lines.add(Line("If only the target exists: follow the row's Only target rule (Ask each time unless you change it)."))
        lines.add(Line("Save writes configuration only. Apply checks the paths again."))
    }
    lines.add(Line("Size: not estimated · Ownership: not evaluated"))
    entry.discovery?.let { candidate ->
        val observation = candidate.observation
        if (observation.kind != CandidateObservation.Kind.PENDING) lines.add(Line("Observed: " + observation.observedAt))
        observation.rawLinkTarget?.let { path -> lines.add(Line("Link text: " + literal(path.toString()) + " · Target not checked")) }
        observation.diagnostics.forEach { d ->
            lines.add(Line("Note: " + literal(d.detail) + " · " + literal(d.path.toString())))
        }
        candidate.ancestors.forEach { path -> lines.add(Line("Overlaps catalog parent: " + literal(path.toString()))) }
        draft.discovery?.candidates.orEmpty().filter { c -> candidate.catalog.sourcePath in c.ancestors }.forEach { c ->
            lines.add(Line("Overlaps catalog child: " + literal(c.catalog.sourcePath.toString())))
        }
    }
    lines.add(
        Line(
            when {
                entry.row != null -> "Target and rules can be changed in the row's details."
                draft.canAdd(entry) -> "Adding uses a matching target path; its rules ask each time."
                else -> "Add needs a directory or missing path observed in this request. To type a path instead, go back and press a."
            },
        ),
    )
    attribution(lines, entry, draft)
}

private fun definition(lines: MutableList<Line>, definition: CandidateDefinition, freshness: String) {
    lines.add(Line(literal(definition.app ?: "Ungrouped") + " · " + sourceName(definition.source) + " · " + freshness))
    lines.add(Line("Advice: " + advice(definition.advice)))
    definition.reason?.let { reason -> lines.add(Line("Reason: " + literal(reason))) }
    lines.add(
        Line(
            "From: " + literal(definition.source.location) + " · " + literal(definition.location) +
                " · original path: " + literal(definition.originalPath),
        ),
    )
}

private fun sourceName(source: CandidateSource): String =
    if (source.kind == CandidateSource.Kind.BUNDLED) "Bundled" else "Shared"

private fun sourceState(status: CandidateDiscovery.SourceStatus): String = when (status) {
    CandidateDiscovery.SourceStatus.CURRENT -> "current"
    CandidateDiscovery.SourceStatus.PENDING -> "checking…"
    CandidateDiscovery.SourceStatus.FAILED -> "unavailable"
}

private fun sourceSummary(draft: BrowseDraft): String =
    draft.discovery?.sources?.joinToString(" · ") { s -> sourceName(s.source) + ": " + sourceState(s.status) }
        ?: "Discovery has not started"

private fun sourceDetails(lines: MutableList<Line>, draft: BrowseDraft) {
    lines.add(Line("Manual editing, saving and exit do not wait for discovery."))
    val result = draft.discovery ?: return
    for (source in result.sources) {
        lines.add(Line(sourceName(source.source) + ": " + sourceState(source.status), palette.text, true))
        lines.add(Line("Location: " + literal(source.source.location)))
        for (problem in source.problems) {
            lines.add(Line(problemAdvice(problem.kind), palette.warn, false))
            lines.add(Line("Diagnostic: " + literal(problem.detail)))
        }
        if (source.diagnostics.isNotEmpty()) lines.add(Line("List rejected. Fix the JSON and refresh.", palette.warn, false))
        for (d in source.diagnostics) {
            lines.add(
                Line(
                    literal(d.message) + (if (d.location.isEmpty()) "" else " · " + literal(d.location)) +
                        (if (d.line == 0) "" else " · input line " + d.line + ", column " + d.column),
                    palette.warn, false,
                ),
            )
        }
    }
    result.rootFailure?.let { d -> lines.add(Line("Root: " + literal(d.detail) + " · " + literal(d.path.toString()))) }
}

private fun problemAdvice(kind: CandidateDiscovery.SourceProblem.Kind): String = when (kind) {
    CandidateDiscovery.SourceProblem.Kind.MISSING -> "The list was not found. Check its location or clear the optional field."
    CandidateDiscovery.SourceProblem.Kind.UNREADABLE -> "The list could not be read. Check access permissions."
    CandidateDiscovery.SourceProblem.Kind.NOT_REGULAR -> "Choose a regular JSON file, not a directory or special file."
    CandidateDiscovery.SourceProblem.Kind.IO_ERROR -> "Reading the list failed. Retry when storage is available."
    CandidateDiscovery.SourceProblem.Kind.DEADLINE -> "No response within five seconds. You can still type paths in Configuration."
    CandidateDiscovery.SourceProblem.Kind.PREVIOUS_PENDING -> "Previous read still pending. You can still type paths in Configuration."
}
