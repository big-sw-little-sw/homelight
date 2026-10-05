package io.github.bigswlittlesw.homelight.tui

import dev.tamboui.toolkit.Toolkit
import dev.tamboui.toolkit.element.Element
import dev.tamboui.tui.event.KeyCode
import dev.tamboui.tui.event.KeyEvent
import io.github.bigswlittlesw.homelight.application.HomeLightSession
import io.github.bigswlittlesw.homelight.application.SetupDraft
import io.github.bigswlittlesw.homelight.config.ConfigurationPublisher
import io.github.bigswlittlesw.homelight.config.WhenAdoptingTarget
import io.github.bigswlittlesw.homelight.config.WhenOnlyTargetExists
import io.github.bigswlittlesw.homelight.config.WhenSourceAndTargetDirectoriesExist
import io.github.bigswlittlesw.homelight.config.defaultArchiveRoot
import io.github.bigswlittlesw.homelight.config.isJavaBlank
import io.github.bigswlittlesw.homelight.config.parseSharedList
import io.github.bigswlittlesw.homelight.discovery.CandidateDiscovery
import io.github.bigswlittlesw.homelight.tui.DetailViewport.Line
import java.nio.file.Path

/**
 * Creation-only presentation. All draft changes and snapshot acceptance happen
 * on the UI thread; workers publish evidence without callbacks into this view.
 */
internal class SetupView(
    private val session: HomeLightSession,
    private val discoveryFactory: () -> CandidateDiscovery,
) : AutoCloseable {
    private enum class Mode { LOCATIONS, TABLE, ROW, CANDIDATES }

    private val viewport = DetailViewport()
    private val browser = CandidateBrowser()
    private var discovery: CandidateDiscovery? = null
    private var mode = Mode.LOCATIONS
    private var field = 0
    private var row = 0
    private var discard = false
    var closed = false
        private set
    private var locationsChanged = false
    private var sourceRoot: String = System.getProperty("user.home")
    private var targetRoot = ""
    private var sharedList = ""
    private var archiveText = ""
    private var message = "Draft not saved. Validation: not run."

    // Unfinished location text is independent of the last valid model roots.
    private val draft = Path.of(sourceRoot).toAbsolutePath().let { home -> SetupDraft(home, home, null, listOf()) }

    fun render(): Element {
        if (!closed) discovery?.let { draft.accept(it.snapshot()) }
        val content = if (mode == Mode.CANDIDATES) browser.render(draft, !discard)
        else {
            val lines = mutableListOf<Line>()
            lines.add(Line("Create configuration", palette.text, true))
            lines.add(Line("Config: " + literal(session.configPath.toString())))
            val anchor = when (mode) {
                Mode.LOCATIONS -> locations(lines)
                Mode.TABLE -> table(lines)
                Mode.ROW -> rowDetails(lines)
                Mode.CANDIDATES -> error("The candidate browser renders itself")
            }
            lines.add(Line(literal(message), palette.warn, false))
            Toolkit.column(viewport.render("Setup", lines, !discard, anchor), viewport.help(help(), commands(), !discard)).fill()
        }
        val header = Toolkit.row(Toolkit.text("⌂ HOMELIGHT  ").fg(palette.brand).bold(), Toolkit.text("[Setup]").fg(palette.focus).bold())
        // Setup keeps its own field focus until the configuration editor replaces it, so the screen is one focusable.
        return Toolkit.column(header, content).fill().id(SETUP_SCREEN).focusable(!discard)
    }

    /** The discard question, while it is open. */
    fun dialog(): Element? = if (!discard) null else confirmDialog(
        "Discard setup draft?", listOf("Nothing has been written.", "Discard all locations and relocation choices?"),
        "y: Discard draft · n/Esc: Keep editing", onYes = ::close, onNo = { discard = false },
    )

    private fun locations(lines: MutableList<Line>): Int {
        lines.add(Line("Storage locations", palette.text, true))
        listOf("Source root" to sourceRoot, "Target root" to targetRoot, "Shared candidate list (optional)" to sharedList)
            .forEachIndexed { i, (name, value) -> choice(lines, "$name: $value", i == field) }
        lines.add(
            Line(
                when (field) {
                    0 -> "Source paths are relative to this root. Changing it re-resolves existing rows."
                    1 -> "Target paths are relative to this root. Use an absolute path."
                    else -> "Blank: bundled candidates only. Otherwise use an absolute path or ~/path."
                },
            ),
        )
        if (!sharedList.isJavaBlank()) {
            try { lines.add(Line("Resolved list: " + parseSharedList(sharedList))) }
            catch (error: IllegalArgumentException) { lines.add(Line(shown(error), palette.warn, false)) }
        }
        return 3 + field
    }

    private fun table(lines: MutableList<Line>): Int {
        lines.add(Line("Storage locations", palette.text, true))
        lines.add(Line("Source root: $sourceRoot"))
        lines.add(Line("Target root: $targetRoot"))
        lines.add(Line("Relocations", palette.text, true))
        lines.add(Line("  Source (relative)        Target (relative)        Policies", palette.dim, true))
        if (draft.rows.isEmpty()) lines.add(Line("No relocations yet. Add a directory manually or browse candidates."))
        draft.rows.forEachIndexed { i, value ->
            choice(lines, cell(value.sourceRelative, 23) + "  " + cell(value.targetRelative, 23) + "  " + policies(value), i == row)
        }
        return 7 + row
    }

    private fun rowDetails(lines: MutableList<Line>): Int {
        val value = draft.rows[row]
        lines.add(Line("Edit relocation " + (row + 1), palette.text, true))
        listOf(
            "Source path" to value.sourceRelative, "Target path" to value.targetRelative, "Both directories" to bothLabel(value.both),
            "Only target" to onlyTargetLabel(value.onlyTarget), "Adopt target" to adoptingLabel(value.adopting),
            "Archive root" to archiveText.ifEmpty { defaultArchive(sourceRoot, value.sourceRelative) },
        ).forEachIndexed { i, (name, text) ->
            choice(lines, "$name: $text", i == field)
            if (i == 2 && discardPolicyFocused()) lines.add(Line(bothConsequence(value.both), palette.warn, true))
        }
        lines.add(Line("Paths (resolved)", palette.text, true))
        lines.add(Line("Source: " + resolved(sourceRoot, value.sourceRelative)))
        lines.add(Line("Target: " + resolved(targetRoot, value.targetRelative)))
        attribution(lines, draft.entries()[row], draft)
        return 3 + field
    }

    private fun help(): String = when (mode) {
        Mode.LOCATIONS -> "↑/↓: Field · Type: Edit · Ctrl-U: Clear"
        Mode.TABLE -> if (draft.rows.isEmpty()) "e: Edit locations · Esc: Back"
        else "↑/↓: Row · Enter: Details · d: Remove · e: Locations · Esc: Back"
        Mode.ROW -> when (field) {
            0 -> "Source is relative; matching target follows until edited."
            1 -> "Target is relative to the target root."
            5 -> "Archive root is optional; use an absolute path on the source's filesystem."
            2 -> if (discardPolicyFocused()) "Save writes configuration only; Apply requires review."
            else "Both exist: " + bothConsequence(draft.rows[row].both)
            3 -> "When only target exists: " +
                if (draft.rows[row].onlyTarget == WhenOnlyTargetExists.PROMPT)
                    "prompt before acting." else "link the source to that target."
            else -> "When adopting: " + when (draft.rows[row].adopting) {
                WhenAdoptingTarget.PROMPT -> "prompt for what to do with source contents."
                WhenAdoptingTarget.DISCARD_SOURCE -> "delete source contents."
                WhenAdoptingTarget.ARCHIVE_SOURCE -> "move source contents to the archive root."
            }
        }
        Mode.CANDIDATES -> ""
    }

    private fun commands(): String = when (mode) {
        Mode.LOCATIONS -> "Enter: Relocations · Esc: Cancel without writing"
        Mode.TABLE -> "a: Add manual · b: Browse candidates · v: Validate · s: Save · q: Discard"
        Mode.ROW -> "↑/↓: Field · " + (if (textField()) "Type: Edit · Ctrl-U: Clear" else "Space: Policy · d: Remove") +
            " · Esc: Table"
        Mode.CANDIDATES -> ""
    }

    fun key(key: KeyEvent) {
        if (closed) return
        discovery?.let { draft.accept(it.snapshot()) }
        if (key.isKey(KeyCode.ESCAPE)) {
            when (mode) {
                Mode.CANDIDATES -> if (!browser.back()) changeMode(Mode.TABLE)
                Mode.ROW -> changeMode(Mode.TABLE)
                Mode.TABLE -> changeMode(Mode.LOCATIONS)
                Mode.LOCATIONS -> close()
            }
            return
        }
        if (editsText(key)) {
            if (mode == Mode.LOCATIONS) editLocation(key) else editRow(key)
            return
        }
        // Letter commands match with or without Ctrl or Alt, so without this Ctrl+D would remove a row and
        // Ctrl+U would reveal hidden candidates.
        if ((key.hasCtrl() || key.hasAlt()) && !key.isQuit()) return
        if (mode == Mode.CANDIDATES) {
            if (key.isQuit()) discard = true
            else if (key.isCharIgnoreCase('r')) refreshDiscovery()
            else {
                val previousCount = draft.rows.size
                val editRow = browser.key(key, draft)
                if (draft.rows.size != previousCount) invalidated()
                if (editRow >= 0) { row = editRow; changeMode(Mode.ROW); invalidated() }
            }
            return
        }
        if (key.isQuit()) { discard = true; return }
        if (key.isChar('[') || key.isChar(']')) { viewport.scroll(if (key.isChar(']')) 1 else -1); return }
        if (mode == Mode.LOCATIONS) { locationsKey(key); return }
        if (mode == Mode.TABLE) { tableKey(key); return }
        // Not Tab: TamboUI takes it to move focus before any handler sees it, and setup's fields are not focusable.
        if (key.isDown() || key.isUp()) {
            field = (field + (if (key.isUp()) -1 else 1)).mod(6); viewport.followChoice(); return
        }
        if (textField()) return
        if (key.isCharIgnoreCase('d')) removeRow()
        else if (key.isChar(' ')) cyclePolicy()
    }

    private fun locationsKey(key: KeyEvent) {
        if (key.isDown() || key.isUp()) {
            field = (field + (if (key.isUp()) -1 else 1)).mod(3); viewport.followChoice()
        } else if (key.isKey(KeyCode.ENTER)) {
            // Manual incomplete drafts remain editable even before roots validate.
            try { applyLocations() }
            catch (error: IllegalArgumentException) { message = shown(error) }
            changeMode(Mode.TABLE)
        }
    }

    private fun editLocation(key: KeyEvent) {
        val current = when (field) { 0 -> sourceRoot; 1 -> targetRoot; else -> sharedList }
        val next = edit(current, key)
        if (current == next) return
        when (field) { 0 -> sourceRoot = next; 1 -> targetRoot = next; else -> sharedList = next }
        locationsChanged = true
        // Invalidate immediately, including an edit away from and back to a root.
        discovery?.cancel()
        draft.roots(draft.sourceRoot, draft.targetRoot)
        invalidated()
    }

    private fun tableKey(key: KeyEvent) {
        if (key.isCharIgnoreCase('a')) {
            draft.append(SetupDraft.Row("", "")); row = draft.rows.size - 1
            changeMode(Mode.ROW); invalidated()
        } else if (key.isCharIgnoreCase('b')) {
            try {
                applyLocations()
                if (discovery == null) { discovery = discoveryFactory(); refreshDiscovery() }
                changeMode(Mode.CANDIDATES)
            } catch (error: IllegalArgumentException) { message = shown(error) }
        } else if (key.isCharIgnoreCase('e')) changeMode(Mode.LOCATIONS)
        else if (key.isCharIgnoreCase('v')) validate()
        else if (key.isCharIgnoreCase('s')) save()
        else if (key.isCharIgnoreCase('d') && draft.rows.isNotEmpty()) removeRow()
        else if (key.isKey(KeyCode.ENTER) && draft.rows.isNotEmpty()) changeMode(Mode.ROW)
        else if (key.isUp() || key.isDown()) {
            row = (row + (if (key.isUp()) -1 else 1)).coerceIn(0, maxOf(0, draft.rows.size - 1))
            viewport.followChoice()
        }
    }

    private fun applyLocations() {
        require(!sourceRoot.isJavaBlank() && !targetRoot.isJavaBlank()) { "Enter both storage roots" }
        val source = Path.of(sourceRoot)
        val target = Path.of(targetRoot)
        require(source.isAbsolute && target.isAbsolute) { "Storage roots must be absolute" }
        val shared = parseSharedList(sharedList)
        if (!locationsChanged && source.normalize() == draft.sourceRoot && target.normalize() == draft.targetRoot &&
            shared == draft.sharedList
        ) return
        draft.roots(source, target)
        draft.sharedList(sharedList)
        locationsChanged = false
        if (discovery != null) refreshDiscovery()
    }

    private fun refreshDiscovery() {
        // Browsing starts discovery before the browser opens, and only close() clears it.
        val current = discovery!!
        draft.refresh(current)
        draft.accept(current.snapshot())
    }

    private fun editRow(key: KeyEvent) {
        val value = draft.rows[row]
        try {
            val next = when (field) {
                0 -> {
                    val source = edit(value.sourceRelative, key)
                    val mirrored = value.targetRelative == value.sourceRelative || value.targetRelative.isJavaBlank()
                    value.copy(sourceRelative = source, targetRelative = if (mirrored) source else value.targetRelative)
                }
                1 -> value.copy(targetRelative = edit(value.targetRelative, key))
                else -> {
                    val text = edit(archiveText, key)
                    val archive = if (text.isJavaBlank()) null else Path.of(text)
                    archiveText = text
                    value.copy(archiveRoot = archive)
                }
            }
            if (next != value) { draft.edit(row, next); invalidated() }
        } catch (error: IllegalArgumentException) { message = "Invalid path: " + error.message }
    }

    private fun cyclePolicy() {
        val value = draft.rows[row]
        draft.edit(
            row,
            when (field) {
                2 -> value.copy(both = next(value.both, WhenSourceAndTargetDirectoriesExist.entries))
                3 -> value.copy(onlyTarget = next(value.onlyTarget, WhenOnlyTargetExists.entries))
                4 -> value.copy(adopting = next(value.adopting, WhenAdoptingTarget.entries))
                else -> value
            },
        )
        invalidated()
    }

    private fun removeRow() {
        draft.remove(row); row = minOf(row, maxOf(0, draft.rows.size - 1))
        changeMode(Mode.TABLE); message = "Draft not saved. Validation: not run. Relocation removed."
    }

    private fun validate() {
        try {
            applyLocations(); draft.validate()
            message = "Draft not saved. Validation: valid. Save explicitly to create the configuration."
        } catch (error: RuntimeException) { message = "Draft not saved. Validation: invalid. " + error.message }
        viewport.scroll(Int.MAX_VALUE)
    }

    private fun save() {
        try {
            applyLocations()
            ConfigurationPublisher().saveNew(session.configPath, draft.validate())
        } catch (error: RuntimeException) { message = "Save failed: " + error.message; viewport.scroll(Int.MAX_VALUE); return }
        close()
        // Outside the catch: the file is saved, and a bug while checking it again must not read as a failed save.
        session.refresh()
    }

    override fun close() {
        discovery?.close()
        discovery = null
        closed = true
    }

    private fun changeMode(next: Mode) {
        mode = next; field = 0; viewport.reset()
        if (next == Mode.ROW) archiveText = draft.rows[row].archiveRoot?.toString() ?: ""
    }

    private fun textField(): Boolean = field == 0 || field == 1 || field == 5

    /**
     * A focused path field takes its editing keys before any binding or letter command: `q`, `Q` and Space are
     * bindings, and letters such as `d` and `b` are setup commands. `[` and `]` stay scroll keys on every setup
     * screen.
     */
    private fun editsText(key: KeyEvent): Boolean =
        (mode == Mode.LOCATIONS || mode == Mode.ROW && textField()) &&
            (clears(key) || key.isKey(KeyCode.BACKSPACE) || typed(key).let { c -> c != null && c != '[' && c != ']' })

    private fun invalidated() { message = "Draft not saved. Validation: not run." }

    private fun discardPolicyFocused(): Boolean =
        mode == Mode.ROW && field == 2 && draft.rows[row].both == WhenSourceAndTargetDirectoriesExist.DISCARD
}

// Every IllegalArgumentException that the draft, parseSharedList and Path.of throw carries a message.
private fun shown(error: IllegalArgumentException): String = error.message!!

/** Placeholder text for an empty archive root field. */
private fun defaultArchive(root: String, relative: String): String =
    try { "(default: " + defaultArchiveRoot(Path.of(root).resolve(relative)) + ")" }
    catch (error: IllegalArgumentException) { "(default: beside the source)" }

private fun resolved(root: String, relative: String): String =
    try { Path.of(root).resolve(relative).normalize().toString() }
    catch (error: IllegalArgumentException) { "Invalid path: " + error.message }

private fun edit(value: String, key: KeyEvent): String {
    if (clears(key)) return ""
    if (key.isKey(KeyCode.BACKSPACE)) return value.dropLast(1)
    return typed(key)?.let { character -> value + character } ?: value
}

private fun clears(key: KeyEvent): Boolean = key.isChar('\u0015') || key.hasCtrl() && key.isCharIgnoreCase('u')

/** The printable character a key types; `null` for control characters and Ctrl or Alt chords. */
// TamboUI deprecates character() for codePoint(), which would type non-BMP input that character() maps to U+FFFD.
@Suppress("DEPRECATION")
private fun typed(key: KeyEvent): Char? =
    key.character().takeIf { c -> c != '\u0000' && !Character.isISOControl(c) && !key.hasCtrl() && !key.hasAlt() }

/** Cycles each value in order, then back to the first (`PROMPT`). */
private fun <T : Enum<T>> next(current: T, values: List<T>): T = values[(current.ordinal + 1) % values.size]

private fun choice(lines: MutableList<Line>, value: String, focused: Boolean) {
    lines.add(Line((if (focused) "❯ " else "  ") + literal(value), if (focused) palette.focus else palette.text, focused))
}

private fun cell(value: String, width: Int): String {
    val text = literal(if (value.isJavaBlank()) "(new relocation)" else value)
    return if (text.length > width) text.substring(0, width - 1) + "…" else text.padEnd(width)
}

/** Lists only the rules that decide something; a row whose rules all ask each time reads Prompt. */
private fun policies(row: SetupDraft.Row): String {
    val values = listOfNotNull(
        row.both.takeIf { it != WhenSourceAndTargetDirectoriesExist.PROMPT }?.let(::bothLabel),
        row.onlyTarget.takeIf { it != WhenOnlyTargetExists.PROMPT }?.let(::onlyTargetLabel),
        row.adopting.takeIf { it != WhenAdoptingTarget.PROMPT }?.let(::adoptingLabel),
    )
    return if (values.isEmpty()) "Prompt" else values.joinToString(", ")
}

private fun bothConsequence(value: WhenSourceAndTargetDirectoriesExist): String =
    when (value) {
        WhenSourceAndTargetDirectoriesExist.PROMPT -> "prompt before acting."
        WhenSourceAndTargetDirectoriesExist.ADOPT -> "use target contents; choose source disposition below."
        WhenSourceAndTargetDirectoriesExist.LEAVE_UNCHANGED -> "leave both paths unchanged."
        WhenSourceAndTargetDirectoriesExist.DISCARD ->
            "Permanently delete both source and target directory trees. Create an empty target directory and link the source to it."
    }
