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
import io.github.bigswlittlesw.homelight.config.WhenSourceAndTargetDirectoriesExist
import io.github.bigswlittlesw.homelight.discovery.CandidateDiscovery
import io.github.bigswlittlesw.homelight.discovery.CandidateObservation
import java.nio.file.Path
import java.util.Optional

/** App expansion, focused identity and draft membership are independent states. */
internal class CandidateBrowser {
    private sealed interface Item {
        @JvmRecord
        data class Group(val app: Optional<String>, val count: Int) : Item

        @JvmRecord
        data class Directory(val path: Path) : Item
    }

    private val viewport = DetailViewport()
    private val collapsed = HashSet<Optional<String>>()
    private var focus: Item? = null
    private var details = false
    private var diagnostics = false
    private var reveal = false
    private var message = ""

    fun render(draft: SetupDraft): Element {
        val lines = ArrayList<DetailViewport.Line>()
        var anchor = -1
        val title: String
        val navigation: String
        val commands: String
        if (diagnostics) {
            title = "Discovery sources"
            sourceDetails(lines, draft)
            navigation = "↑/↓: Scroll · Esc: Back"
            commands = "r: Refresh · q: Discard draft"
        } else if (details) {
            title = "Candidate details"
            val entry = focusedEntry(draft)
            if (entry.isEmpty) {
                lines.add(DetailViewport.Line("No longer in current discovery."))
                val focused = focus
                if (focused is Item.Directory) lines.add(DetailViewport.Line("Source: " + literal(focused.path.toString())))
                lines.add(DetailViewport.Line("Existing draft rows are retained. Esc returns to the list."))
            } else detailLines(lines, entry.orElseThrow(), draft)
            navigation = "↑/↓: Scroll · Esc: Back"
            commands = entry.map { e -> action(e, draft) }.orElse("") + "r: Refresh · q: Discard draft"
        } else {
            title = "Browse candidates"
            val unique = entriesByPath(draft)
            val items = items(draft, unique)
            if (focus == null && !items.isEmpty()) focus = items.first()
            val configured = unique.values.stream().filter { e -> e.configured != null }.count()
            val inDraft = unique.values.stream().filter { e -> e.configured == null && e.draft != null }.count()
            lines.add(
                DetailViewport.Line(
                    unique.size.toString() + " candidates · " + inDraft + " in draft" +
                        (if (configured == 0L) "" else " · $configured configured"),
                    Color.GRAY, false,
                ),
            )
            if (draft.discovery?.sources.orEmpty().stream()
                    .anyMatch { s -> s.status != CandidateDiscovery.SourceStatus.CURRENT }
            ) {
                lines.add(DetailViewport.Line(sourceSummary(draft), Color.YELLOW, false))
            }
            val hidden = hiddenCount(draft)
            if (hidden > 0) lines.add(
                DetailViewport.Line(
                    hidden.toString() + " usually-unnecessary " + (if (hidden == 1L) "directory " else "directories ") +
                        (if (reveal) "revealed" else "hidden"),
                    Color.YELLOW, true,
                ),
            )
            if (items.isEmpty()) lines.add(DetailViewport.Line("No candidates available yet. Esc returns to manual setup."))
            for (item in items) {
                val selected = same(item, focus)
                if (selected) anchor = lines.size
                val label = when (item) {
                    is Item.Group -> (if (collapsed.contains(item.app)) "▸ " else "▾ ") +
                        item.app.orElse("Other directories") + " (" + item.count + ")"
                    is Item.Directory -> {
                        val entry = unique[item.path]!!
                        val path = compact(relative(draft, item.path))
                        val marker = if (entry.configured != null) "[=]" else if (entry.draft != null) "[x]"
                        else if (canAdd(entry, draft)) "[ ]" else " − "
                        "  " + marker + " " + path + " ".repeat(Math.max(1, 32 - CharWidth.of(path))) + listNotes(entry, draft)
                    }
                }
                val color = if (selected) Color.CYAN else if (item is Item.Group) Color.BLUE
                else if (unique[(item as Item.Directory).path]!!.draft != null) Color.GREEN else Color.GRAY
                lines.add(
                    DetailViewport.Line((if (selected) "❯ " else "  ") + literal(label), color, selected || item is Item.Group),
                )
            }
            val listedFocus = focus != null && items.stream().anyMatch { i -> same(i, focus) }
            if (focus != null && !listedFocus) {
                anchor = lines.size
                lines.add(
                    DetailViewport.Line(
                        if (focus is Item.Directory) "❯ Focused path is hidden or no longer listed."
                        else "❯ Focused app is no longer listed.",
                        Color.CYAN, true,
                    ),
                )
            }
            navigation = (if (items.isEmpty()) "" else "↑/↓: Move · ") +
                focusedEntry(draft).map { e -> listAction(e, draft) }.orElse("") +
                (if (focus is Item.Directory) "Enter: Inspect · " else if (listedFocus) "Enter: Expand/collapse · " else "") +
                "Esc: Back"
            commands = "r: Refresh · i: Sources" +
                (if (hidden > 0) " · u: " + (if (reveal) "Hide " else "Show ") + hidden else "") + " · q: Discard"
        }
        if (!message.isEmpty() && details) lines.add(0, DetailViewport.Line(literal(message), Color.YELLOW, false))
        val reader = viewport.render(title, lines, true, anchor)
        val help = viewport.help(navigation, commands)
        return if (!message.isEmpty() && !details && !diagnostics)
            Toolkit.column(
                reader,
                DetailViewport.text("Not added. Inspect the row for details; prior choices are unchanged.", Color.YELLOW),
                help,
            ).fill()
        else Toolkit.column(reader, help).fill()
    }

    fun key(key: KeyEvent, draft: SetupDraft): Int {
        if (key.isChar('[') || key.isChar(']')) { viewport.scroll(if (key.isChar(']')) 1 else -1); return -1 }
        if (!diagnostics) {
            val entry = focusedEntry(draft)
            if (key.isCharIgnoreCase('e') && entry.filter { e -> e.configured == null && e.draft != null }.isPresent) {
                val value = checkNotNull(entry.orElseThrow().draft)
                for (i in draft.rows.indices) if (draft.rows[i] === value) return i
            }
            if ((key.isCharIgnoreCase('a') || key.isChar(' ')) && entry.filter { e -> canAdd(e, draft) }.isPresent) {
                try { draft.add(checkNotNull(entry.orElseThrow().sourcePath)); message = "" }
                catch (error: IllegalArgumentException) { message = "Not added. " + error.message + ". Prior choices are unchanged." }
                if (details) viewport.reset() else viewport.keepChoiceVisible()
                return -1
            }
        }
        if (details || diagnostics) {
            if (key.isUp() || key.isCharIgnoreCase('k')) viewport.scroll(-1)
            else if (key.isDown() || key.isCharIgnoreCase('j')) viewport.scroll(1)
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
        } else if (key.isUp() || key.isCharIgnoreCase('k') || key.isDown() || key.isCharIgnoreCase('j') || key.isHome() || key.isEnd()) {
            val items = items(draft)
            if (!items.isEmpty()) {
                var index = -1
                for (i in items.indices) if (same(items[i], focus)) index = i
                val next = if (key.isHome()) 0 else if (key.isEnd()) items.size - 1
                else Math.clamp((index + (if (key.isUp() || key.isCharIgnoreCase('k')) -1 else 1)).toLong(), 0, items.size - 1)
                focus = items[next]; message = ""; viewport.keepChoiceVisible()
            }
        }
        return -1
    }

    fun back(): Boolean {
        if (!details && !diagnostics) return false
        details = false; diagnostics = false; message = ""; viewport.reset(); viewport.keepChoiceVisible(); return true
    }

    private fun items(draft: SetupDraft): List<Item> = items(draft, entriesByPath(draft))

    private fun items(draft: SetupDraft, entriesByPath: Map<Path, SetupDraft.Entry>): List<Item> {
        val groups = LinkedHashMap<Optional<String>, MutableList<SetupDraft.Entry>>()
        for (entry in entriesByPath.values) {
            if (!reveal && hidden(entry, draft)) continue
            val app = Optional.ofNullable(definitions(entry).firstNotNullOfOrNull { d -> d.app })
            groups.computeIfAbsent(app) { ArrayList() }.add(entry)
        }
        val result = ArrayList<Item>()
        for ((app, entries) in groups) {
            result.add(Item.Group(app, entries.size))
            for (entry in entries) if (!collapsed.contains(app) || member(entry)) result.add(Item.Directory(checkNotNull(entry.sourcePath)))
        }
        return result
    }

    private fun focusedEntry(draft: SetupDraft): Optional<SetupDraft.Entry> {
        val focused = focus
        return if (focused is Item.Directory) entry(draft, focused.path) else Optional.empty()
    }

    companion object {
        private fun entriesByPath(draft: SetupDraft): Map<Path, SetupDraft.Entry> {
            val entries = LinkedHashMap<Path, SetupDraft.Entry>()
            draft.entries().forEach { e -> e.sourcePath?.let { path -> entries.putIfAbsent(path, e) } }
            // Membership changes must not reorder the list while marking adjacent rows.
            val ordered = LinkedHashMap<Path, SetupDraft.Entry>()
            draft.discovery?.let { r ->
                r.candidates.forEach { c ->
                    val path = c.catalog.sourcePath
                    if (entries.containsKey(path)) ordered[path] = entries[path]!!
                }
            }
            entries.forEach { (path, entry) -> ordered.putIfAbsent(path, entry) }
            return ordered
        }

        private fun same(left: Item, right: Item?): Boolean = when (left) {
            is Item.Group -> right is Item.Group && left.app == right.app
            is Item.Directory -> right is Item.Directory && left.path == right.path
        }

        private fun entry(draft: SetupDraft, path: Path): Optional<SetupDraft.Entry> =
            draft.entries().stream().filter { e -> e.sourcePath == path }.findFirst()

        private fun definitions(entry: SetupDraft.Entry): List<CandidateDefinition> =
            entry.discovery?.catalog?.definitions ?: listOf()

        private fun member(entry: SetupDraft.Entry): Boolean = entry.configured != null || entry.draft != null

        private fun membership(entry: SetupDraft.Entry): String =
            if (entry.configured != null) "Configured" else if (entry.draft != null) "In draft" else "Not added"

        private fun hidden(entry: SetupDraft.Entry, draft: SetupDraft): Boolean {
            val definitions = definitions(entry)
            return !member(entry) && !definitions.isEmpty() && definitions.stream().allMatch { d ->
                d.advice == CandidateDefinition.Advice.USUALLY_UNNECESSARY &&
                    draft.discovery?.sources.orEmpty().stream().anyMatch { s ->
                        s.source == d.source && s.status == CandidateDiscovery.SourceStatus.CURRENT
                    }
            }
        }

        private fun hiddenCount(draft: SetupDraft): Long =
            draft.entries().stream().filter { e -> hidden(e, draft) }.map { e -> e.sourcePath }.distinct().count()

        private fun relative(draft: SetupDraft, path: Path): String =
            if (path.startsWith(draft.sourceRoot)) draft.sourceRoot.relativize(path).toString() else path.toString()

        private fun compact(path: String): String {
            val value = literal(path)
            return if (value.length <= 30) value else value.substring(0, 14) + "…" + value.substring(value.length - 15)
        }

        private fun canAdd(entry: SetupDraft.Entry, draft: SetupDraft): Boolean = draft.canAdd(entry)

        private fun action(entry: SetupDraft.Entry, draft: SetupDraft): String {
            if (entry.configured != null) return ""
            if (entry.draft != null) return "e: Edit draft row · "
            return if (canAdd(entry, draft)) "a: Add to draft · " else ""
        }

        private fun listAction(entry: SetupDraft.Entry, draft: SetupDraft): String {
            if (entry.configured != null) return ""
            if (entry.draft != null) return "e: Edit · "
            return if (canAdd(entry, draft)) "Space/a: Add · " else ""
        }

        private fun listNotes(entry: SetupDraft.Entry, draft: SetupDraft): String {
            val notes = ArrayList<String>()
            if (entry.configured != null) notes.add("Configured")
            val ordinary = entry.discovery?.takeIf { c ->
                c.observation.kind == CandidateObservation.Kind.DIRECTORY ||
                    c.observation.kind == CandidateObservation.Kind.MISSING
            }
            if (ordinary == null) notes.add(state(entry, draft))
            else if (draft.discovery?.let { r -> r.generation != ordinary.observation.generation } == true) {
                notes.add("Earlier observation")
            }
            val advice = adviceSummary(entry)
            if (advice == " · Mixed advice" || advice == " · Usually unnecessary") notes.add(advice.substring(3))
            return notes.joinToString(" · ")
        }

        private fun adviceSummary(entry: SetupDraft.Entry): String {
            val values = definitions(entry).stream().map { d -> Optional.ofNullable(d.advice) }.distinct().toList()
            if (values.stream().flatMap { value -> value.stream() }.distinct().count() > 1) return " · Mixed advice"
            if (values.size > 1) return " · " + advice(values.stream().filter { value -> value.isPresent }.findFirst().orElseThrow()) +
                "; some advice omitted"
            return if (values.isEmpty() || values.first().isEmpty) "" else " · " + advice(values.first())
        }

        private fun advice(advice: Optional<CandidateDefinition.Advice>): String = advice.map { a ->
            when (a) {
                CandidateDefinition.Advice.CONSIDER -> "Consider"
                CandidateDefinition.Advice.USUALLY_UNNECESSARY -> "Usually unnecessary"
            }
        }.orElse("Not supplied")

        private fun state(entry: SetupDraft.Entry, draft: SetupDraft): String = entry.discovery?.let { c ->
            kind(c.observation.kind) +
                if (draft.discovery?.let { r -> r.generation != c.observation.generation } == true) " (earlier observation)" else ""
        } ?: "Not observed"

        private fun kind(kind: CandidateObservation.Kind): String = when (kind) {
            CandidateObservation.Kind.PENDING -> "Pending"
            CandidateObservation.Kind.DIRECTORY -> "Directory"
            CandidateObservation.Kind.LINK -> "Symbolic link"
            CandidateObservation.Kind.MISSING -> "Not created yet"
            CandidateObservation.Kind.REGULAR_FILE -> "Regular file"
            CandidateObservation.Kind.OTHER -> "Other file type"
            CandidateObservation.Kind.INACCESSIBLE -> "Inaccessible"
            CandidateObservation.Kind.BLOCKED_BY_LINK -> "Blocked by parent link"
            CandidateObservation.Kind.BLOCKED_BY_NON_DIRECTORY -> "Parent is not a directory"
            CandidateObservation.Kind.UNKNOWN -> "Unknown"
        }

        private fun detailLines(lines: MutableList<DetailViewport.Line>, entry: SetupDraft.Entry, draft: SetupDraft) {
            lines.add(
                DetailViewport.Line(
                    membership(entry) + (if (entry.outsideRoot) " · Outside this source root" else ""), Color.CYAN, true,
                ),
            )
            lines.add(DetailViewport.Line("Source: " + literal(checkNotNull(entry.sourcePath).toString())))
            entry.configured?.let { r ->
                lines.add(DetailViewport.Line("Saved target: " + literal(r.targetPath.toString())))
                lines.add(
                    DetailViewport.Line(
                        "Saved policies: both directories: " + (r.whenSourceAndTargetDirectoriesExist?.let { v ->
                            when (v) {
                                WhenSourceAndTargetDirectoriesExist.DISCARD -> "Discard both"
                                WhenSourceAndTargetDirectoriesExist.PROMPT, WhenSourceAndTargetDirectoriesExist.ADOPT,
                                WhenSourceAndTargetDirectoriesExist.LEAVE_UNCHANGED -> v.value.replace('-', ' ')
                            }
                        } ?: "Default (prompt)") +
                            "; only target: " + (r.whenOnlyTargetExists?.value?.replace('-', ' ') ?: "Default (prompt)") +
                            "; adopt target: " + (r.whenAdoptingTarget?.value?.replace('-', ' ') ?: "Default (prompt)"),
                    ),
                )
                r.sourceArchiveRoot?.let { p -> lines.add(DetailViewport.Line("Saved archive root: " + literal(p.toString()))) }
            }
            entry.draft?.let { r ->
                lines.add(
                    DetailViewport.Line(
                        "Draft target: " + literal(draft.targetRoot.resolve(r.targetRelative).normalize().toString()),
                    ),
                )
            }
            lines.add(DetailViewport.Line("Metadata: " + state(entry, draft)))
            if (entry.discovery?.let { c -> c.observation.kind == CandidateObservation.Kind.MISSING } == true) {
                lines.add(DetailViewport.Line("Not found under the source root. You can configure it before the app creates it."))
                lines.add(DetailViewport.Line("On Apply, if source and target are both missing: create the target directory and source link."))
                lines.add(DetailViewport.Line("If only the target exists: follow the row's policy (Prompt by default, or Adopt target)."))
                lines.add(DetailViewport.Line("Save writes configuration only. Apply checks the paths again."))
            }
            lines.add(DetailViewport.Line("Size: not estimated · Ownership: not evaluated"))
            entry.discovery?.let { candidate ->
                val observation = candidate.observation
                if (observation.kind != CandidateObservation.Kind.PENDING) lines.add(
                    DetailViewport.Line(
                        "Observed: " + observation.observedAt +
                            if (draft.discovery?.let { r -> r.generation == observation.generation } == true) " · This request"
                            else " · Earlier request",
                    ),
                )
                observation.rawLinkTarget?.let { path ->
                    lines.add(DetailViewport.Line("Link text: " + literal(path.toString()) + " · Target not checked"))
                }
                observation.diagnostics.forEach { d ->
                    lines.add(DetailViewport.Line("Metadata note: " + literal(d.detail) + " · " + literal(d.path.toString())))
                }
                candidate.ancestors.forEach { path ->
                    lines.add(DetailViewport.Line("Overlaps catalog parent: " + literal(path.toString())))
                }
                draft.discovery?.let { result ->
                    result.candidates.stream().filter { c -> c.ancestors.contains(candidate.catalog.sourcePath) }
                        .forEach { c ->
                            lines.add(DetailViewport.Line("Overlaps catalog child: " + literal(c.catalog.sourcePath.toString())))
                        }
                }
            }
            if (entry.configured != null) lines.add(DetailViewport.Line("Inspection only. Saved target and policies remain authoritative."))
            else if (entry.draft != null) lines.add(DetailViewport.Line("Target and policies remain editable in Row details."))
            else if (canAdd(entry, draft)) lines.add(DetailViewport.Line("Adding uses a matching target path and Default (prompt) policies."))
            else lines.add(
                DetailViewport.Line("Add needs a directory or missing path observed in this request. Manual entry is available from Relocations."),
            )
            attribution(lines, entry, draft)
        }

        @JvmStatic
        fun attribution(lines: MutableList<DetailViewport.Line>, entry: SetupDraft.Entry, draft: SetupDraft) {
            val current = definitions(entry)
            lines.add(DetailViewport.Line("Advice is optional, not a safety assessment or a requirement.", Color.GRAY, false))
            if (current.isEmpty()) lines.add(DetailViewport.Line("No current catalog attribution."))
            else {
                lines.add(DetailViewport.Line("Discovery attribution · source status below", Color.CYAN, true))
                for (definition in current) {
                    val status = draft.discovery?.sources.orEmpty().stream()
                        .filter { s -> s.source == definition.source }
                        .map { s -> sourceState(s.status) }.findFirst().orElse("unavailable")
                    definition(lines, definition, status)
                }
            }
            val history = entry.lastKnownDefinitions.stream().filter { d -> !current.contains(d) }.toList()
            if (!history.isEmpty()) {
                lines.add(DetailViewport.Line("Historical attribution · no longer in current discovery", Color.YELLOW, true))
                history.forEach { d -> definition(lines, d, "historical; not current advice") }
            }
        }

        private fun definition(lines: MutableList<DetailViewport.Line>, definition: CandidateDefinition, freshness: String) {
            lines.add(
                DetailViewport.Line(
                    literal(definition.app ?: "Ungrouped") + " · " + sourceName(definition.source) + " · " + freshness,
                ),
            )
            lines.add(DetailViewport.Line("Advice: " + advice(Optional.ofNullable(definition.advice))))
            definition.reason?.let { reason -> lines.add(DetailViewport.Line("Reason: " + literal(reason))) }
            lines.add(
                DetailViewport.Line(
                    "From: " + literal(definition.source.location) + " · " + literal(definition.location) +
                        " · original path: " + literal(definition.originalPath),
                ),
            )
        }

        private fun sourceName(source: CandidateSource): String =
            if (source.kind == CandidateSource.Kind.BUNDLED) "Bundled" else "Shared"

        private fun sourceState(status: CandidateDiscovery.SourceStatus): String = when (status) {
            CandidateDiscovery.SourceStatus.CURRENT -> "current"
            CandidateDiscovery.SourceStatus.PENDING -> "pending"
            CandidateDiscovery.SourceStatus.STALE -> "stale"
            CandidateDiscovery.SourceStatus.FAILED -> "unavailable"
        }

        private fun sourceSummary(draft: SetupDraft): String = draft.discovery?.let { result ->
            result.sources.stream().map { s -> sourceName(s.source) + ": " + sourceState(s.status) }.toList().joinToString(" · ")
        } ?: "Discovery has not started"

        private fun sourceDetails(lines: MutableList<DetailViewport.Line>, draft: SetupDraft) {
            lines.add(DetailViewport.Line("Manual editing, saving and exit do not wait for discovery."))
            draft.discovery?.let { result ->
                for (source in result.sources) {
                    lines.add(DetailViewport.Line(sourceName(source.source) + ": " + sourceState(source.status), Color.CYAN, true))
                    lines.add(DetailViewport.Line("Location: " + literal(source.source.location)))
                    if (source.status == CandidateDiscovery.SourceStatus.STALE) {
                        lines.add(DetailViewport.Line("Retaining earlier definitions; this read supplied no replacement."))
                    }
                    source.problems.forEach { problem ->
                        lines.add(
                            DetailViewport.Line(
                                when (problem.kind) {
                                    CandidateDiscovery.SourceProblem.Kind.MISSING ->
                                        "The list was not found. Check its location or clear the optional field."
                                    CandidateDiscovery.SourceProblem.Kind.UNREADABLE ->
                                        "The list could not be read. Check access permissions."
                                    CandidateDiscovery.SourceProblem.Kind.NOT_REGULAR ->
                                        "Choose a regular YAML file, not a directory or special file."
                                    CandidateDiscovery.SourceProblem.Kind.IO_ERROR ->
                                        "Reading the list failed. Retry when storage is available."
                                    CandidateDiscovery.SourceProblem.Kind.DEADLINE ->
                                        "No response within five seconds. Manual setup remains available."
                                    CandidateDiscovery.SourceProblem.Kind.PREVIOUS_PENDING ->
                                        "Previous read still pending; manual setup remains available."
                                },
                                Color.YELLOW, false,
                            ),
                        )
                        lines.add(DetailViewport.Line("Diagnostic: " + literal(problem.detail)))
                    }
                    if (!source.diagnostics.isEmpty()) lines.add(DetailViewport.Line("List rejected. Fix the YAML and refresh.", Color.YELLOW, false))
                    source.diagnostics.forEach { d ->
                        lines.add(
                            DetailViewport.Line(
                                literal(d.message) + (if (d.location.isEmpty()) "" else " · " + literal(d.location)) +
                                    " · input line " + d.line + ", column " + d.column,
                                Color.YELLOW, false,
                            ),
                        )
                    }
                }
                result.rootFailure?.let { d ->
                    lines.add(DetailViewport.Line("Root: " + literal(d.detail) + " · " + literal(d.path.toString())))
                }
            }
        }

        @JvmStatic
        fun literal(text: String): String {
            val escaped = StringBuilder()
            text.codePoints().forEach { point ->
                if (Character.isISOControl(point) || Character.getType(point) == Character.FORMAT.toInt()) {
                    escaped.append(String.format("\\u%04x", point))
                } else escaped.appendCodePoint(point)
            }
            return escaped.toString()
        }
    }
}
