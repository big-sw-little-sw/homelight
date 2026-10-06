package io.github.bigswlittlesw.homelight.tui

import dev.tamboui.style.Color
import dev.tamboui.text.CharWidth
import dev.tamboui.toolkit.Toolkit
import dev.tamboui.toolkit.element.Element
import dev.tamboui.tui.event.KeyCode
import dev.tamboui.tui.event.KeyEvent
import io.github.bigswlittlesw.homelight.application.SetupDraft
import io.github.bigswlittlesw.homelight.config.CandidateDefinition
import io.github.bigswlittlesw.homelight.config.CandidateSource
import io.github.bigswlittlesw.homelight.discovery.CandidateDiscovery
import io.github.bigswlittlesw.homelight.discovery.CandidateObservation
import io.github.bigswlittlesw.homelight.tui.DetailViewport.Line
import java.nio.file.Path

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
    fun render(draft: SetupDraft, interactive: Boolean = true): Element {
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
            val configured = unique.values.count { it.configured != null }
            val inDraft = unique.values.count { it.configured == null && it.draft != null }
            lines.add(
                Line(
                    "${unique.size} candidates · $inDraft in draft" + (if (configured == 0) "" else " · $configured configured"),
                    palette.dim, false,
                ),
            )
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
            if (items.isEmpty()) lines.add(Line("No candidates available yet. Esc returns to manual setup."))
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
                            entry.configured != null -> "[=]"
                            entry.draft != null -> "[x]"
                            draft.canAdd(entry) -> "[ ]"
                            else -> " − "
                        }
                        label = "  " + marker + " " + path + " ".repeat(maxOf(1, 32 - CharWidth.of(path))) + listNotes(entry)
                        color = if (entry.draft != null) palette.ok else palette.text
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
    fun screenHelp(draft: SetupDraft): ScreenHelp {
        val scroll = KeyHint("[/]", "Scroll", inHelpArea = false)
        val refresh = KeyHint("r", "Refresh", description = "Read the suggestion lists again")
        val discard = KeyHint("q", "Discard", description = "Discard the configuration; asks first")
        if (diagnostics || details) {
            val entryKey = if (diagnostics) null else focusedEntry(draft)?.let { entry -> action(entry, draft) }
            return ScreenHelp(
                place(CONFIGURATION_NAME, BROWSE_NAME), PURPOSE_BROWSE, Step.CONFIGURE,
                listOf(SCROLL_KEY, SCROLL_ENDS_KEYS, scroll, KeyHint("Esc", "Back", description = "Back to the suggestions")),
                listOfNotNull(entryKey, refresh, HELP_KEY, discard.copy(action = "Discard draft")),
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
                KeyHint("Esc", "Back", description = "Back to the relocation list"), HOME_END_KEYS.takeIf { items.isNotEmpty() },
                scroll,
            ),
            listOfNotNull(
                refresh, KeyHint("i", "Sources", description = "See whether each suggestion list was read"),
                KeyHint(
                    "u", (if (reveal) "Hide " else "Show ") + hidden,
                    description = (if (reveal) "Hide" else "Show") + " the suggestions marked usually not needed",
                ).takeIf { hidden > 0 },
                HELP_KEY, discard,
            ),
        )
    }

    /** Handles a key and returns the index of a draft row to edit, or -1 to stay in the browser. */
    fun key(key: KeyEvent, draft: SetupDraft): Int {
        if (key.isChar('[') || key.isChar(']')) { viewport.scroll(if (key.isChar(']')) 1 else -1); return -1 }
        if (!diagnostics) {
            val entry = focusedEntry(draft)
            if (key.isCharIgnoreCase('e') && entry != null && entry.configured == null && entry.draft != null) {
                val row = draft.rows.indexOfFirst { it === entry.draft }
                if (row >= 0) return row
            }
            if ((key.isCharIgnoreCase('a') || key.isChar(' ')) && entry != null && draft.canAdd(entry)) {
                // focusedEntry matches entries by source path, so the path is set.
                try { draft.add(checkNotNull(entry.sourcePath)); message = "" }
                catch (error: IllegalArgumentException) { message = "Not added. " + error.message + ". Prior choices are unchanged." }
                if (details) viewport.reset() else viewport.keepChoiceVisible()
                return -1
            }
        }
        if (details || diagnostics) {
            if (key.isUp()) viewport.scroll(-1)
            else if (key.isDown()) viewport.scroll(1)
            else if (key.isHome()) viewport.scroll(-Int.MAX_VALUE)
            else if (key.isEnd()) viewport.scroll(Int.MAX_VALUE)
            return -1
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
        return -1
    }

    fun back(): Boolean {
        if (!details && !diagnostics) return false
        details = false; diagnostics = false; message = ""; viewport.reset(); viewport.keepChoiceVisible(); return true
    }

    private fun items(draft: SetupDraft, entriesByPath: Map<Path, SetupDraft.Entry>): List<Item> =
        entriesByPath.values
            .filter { entry -> reveal || !hidden(entry, draft) }
            .groupBy { entry -> definitions(entry).firstNotNullOfOrNull { it.app } }
            // entriesByPath keeps only entries with a source path.
            .flatMap { (app, entries) ->
                listOf(Item.Group(app, entries.size)) +
                    entries.filter { app !in collapsed || member(it) }.map { Item.Directory(checkNotNull(it.sourcePath)) }
            }

    private fun focusedEntry(draft: SetupDraft): SetupDraft.Entry? {
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

internal fun attribution(lines: MutableList<Line>, entry: SetupDraft.Entry, draft: SetupDraft) {
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

private fun entriesByPath(draft: SetupDraft): Map<Path, SetupDraft.Entry> {
    val entries = linkedMapOf<Path, SetupDraft.Entry>()
    draft.entries().forEach { e -> e.sourcePath?.let { path -> entries.putIfAbsent(path, e) } }
    // Membership changes must not reorder the list while marking adjacent rows.
    val ordered = linkedMapOf<Path, SetupDraft.Entry>()
    draft.discovery?.candidates?.forEach { c ->
        val path = c.catalog.sourcePath
        entries[path]?.let { ordered[path] = it }
    }
    entries.forEach { (path, entry) -> ordered.putIfAbsent(path, entry) }
    return ordered
}

private fun definitions(entry: SetupDraft.Entry): List<CandidateDefinition> =
    entry.discovery?.catalog?.definitions ?: listOf()

private fun member(entry: SetupDraft.Entry): Boolean = entry.configured != null || entry.draft != null

private fun membership(entry: SetupDraft.Entry): String = when {
    entry.configured != null -> "Configured"
    entry.draft != null -> "In draft"
    else -> "Not added"
}

private fun hidden(entry: SetupDraft.Entry, draft: SetupDraft): Boolean {
    val definitions = definitions(entry)
    return !member(entry) && definitions.isNotEmpty() && definitions.all { d ->
        d.advice == CandidateDefinition.Advice.USUALLY_UNNECESSARY &&
            draft.discovery?.sources.orEmpty().any { s -> s.source == d.source && s.status == CandidateDiscovery.SourceStatus.CURRENT }
    }
}

private fun hiddenCount(draft: SetupDraft): Int =
    draft.entries().filter { hidden(it, draft) }.map { it.sourcePath }.distinct().size

private fun relative(draft: SetupDraft, path: Path): String =
    if (path.startsWith(draft.sourceRoot)) draft.sourceRoot.relativize(path).toString() else path.toString()

private fun compact(path: String): String {
    val value = literal(path)
    return if (value.length <= 30) value else value.substring(0, 14) + "…" + value.substring(value.length - 15)
}


private fun action(entry: SetupDraft.Entry, draft: SetupDraft): KeyHint? = when {
    entry.configured != null -> null
    entry.draft != null -> KeyHint("e", "Edit draft row", description = "Edit its relocation")
    draft.canAdd(entry) -> KeyHint("a", "Add to draft", description = "Add it to the configuration")
    else -> null
}

private fun listAction(entry: SetupDraft.Entry, draft: SetupDraft): KeyHint? = when {
    entry.configured != null -> null
    entry.draft != null -> KeyHint("e", "Edit", description = "Edit its relocation")
    draft.canAdd(entry) -> KeyHint("Space/a", "Add", description = "Add the suggestion to the configuration")
    else -> null
}

private fun listNotes(entry: SetupDraft.Entry): String {
    val notes = mutableListOf<String>()
    if (entry.configured != null) notes.add("configured")
    if (entry.discovery?.observation?.kind != CandidateObservation.Kind.DIRECTORY) notes.add(state(entry))
    val advice = adviceSummary(entry)
    if (advice == " · Mixed advice" || advice == " · Usually not needed") notes.add(advice.substring(3).lowercase())
    return notes.joinToString(" · ")
}

private fun adviceSummary(entry: SetupDraft.Entry): String {
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

private fun state(entry: SetupDraft.Entry): String = entry.discovery?.let { observationNote(it.observation) } ?: "not checked"

private fun detailLines(lines: MutableList<Line>, entry: SetupDraft.Entry, draft: SetupDraft) {
    lines.add(Line(membership(entry) + (if (entry.outsideRoot) " · Outside this source root" else ""), palette.text, true))
    // The browser finds entries by source path (focusedEntry, entriesByPath), so the path is set.
    lines.add(Line("Source: " + literal(checkNotNull(entry.sourcePath).toString())))
    entry.configured?.let { r ->
        lines.add(Line("Saved target: " + literal(r.targetPath.toString())))
        lines.add(
            Line(
                "Saved rules: Both exist: " + bothExistLabel(r.whenSourceAndTargetDirectoriesExist, r.whenAdoptingTarget) +
                    " · Only target: " + onlyTargetLabel(r.whenOnlyTargetExists),
            ),
        )
        lines.add(Line("Saved archive root: " + literal(r.archiveRoot.toString())))
    }
    entry.draft?.let { r ->
        lines.add(Line("Draft target: " + literal(draft.targetRoot.resolve(r.targetRelative).normalize().toString())))
    }
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
                entry.configured != null -> "Saved target and rules stay as they are."
                entry.draft != null -> "Target and rules can be changed in the row's details."
                draft.canAdd(entry) -> "Adding uses a matching target path; its rules ask each time."
                else -> "Add needs a directory or missing path observed in this request. Manual entry is available from Relocations."
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

private fun sourceSummary(draft: SetupDraft): String =
    draft.discovery?.sources?.joinToString(" · ") { s -> sourceName(s.source) + ": " + sourceState(s.status) }
        ?: "Discovery has not started"

private fun sourceDetails(lines: MutableList<Line>, draft: SetupDraft) {
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
    CandidateDiscovery.SourceProblem.Kind.DEADLINE -> "No response within five seconds. Manual setup remains available."
    CandidateDiscovery.SourceProblem.Kind.PREVIOUS_PENDING -> "Previous read still pending; manual setup remains available."
}
