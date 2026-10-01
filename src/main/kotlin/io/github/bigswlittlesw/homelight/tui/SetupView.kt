package io.github.bigswlittlesw.homelight.tui

import dev.tamboui.style.Color
import dev.tamboui.toolkit.Toolkit
import dev.tamboui.toolkit.element.Element
import dev.tamboui.tui.event.KeyCode
import dev.tamboui.tui.event.KeyEvent
import io.github.bigswlittlesw.homelight.application.HomeLightSession
import io.github.bigswlittlesw.homelight.application.SetupDraft
import io.github.bigswlittlesw.homelight.config.ConfigurationPublisher
import io.github.bigswlittlesw.homelight.config.DiscoverySetting
import io.github.bigswlittlesw.homelight.config.WhenAdoptingTarget
import io.github.bigswlittlesw.homelight.config.WhenOnlyTargetExists
import io.github.bigswlittlesw.homelight.config.WhenSourceAndTargetDirectoriesExist
import io.github.bigswlittlesw.homelight.config.isJavaBlank
import io.github.bigswlittlesw.homelight.discovery.CandidateDiscovery
import java.nio.file.Path
import java.util.Optional
import java.util.function.Supplier

/**
 * Creation-only presentation. All draft changes and snapshot acceptance happen
 * on the UI thread; workers publish evidence without callbacks into this view.
 */
internal class SetupView(
    private val session: HomeLightSession,
    private val discoveryFactory: Supplier<CandidateDiscovery>,
) : AutoCloseable {
    private enum class Mode { LOCATIONS, TABLE, ROW, CANDIDATES }

    private val draft: SetupDraft
    private val viewport = DetailViewport()
    private val browser = CandidateBrowser()
    private var discovery: CandidateDiscovery? = null
    private var mode = Mode.LOCATIONS
    private var field = 0
    private var row = 0
    private var discard = false
    private var closed = false
    private var locationsChanged = false
    private var sourceRoot: String = System.getProperty("user.home")
    private var targetRoot = ""
    private var sharedList = ""
    private var archiveText = ""
    private var message = "Draft not saved. Validation: not run."

    init {
        // Unfinished location text is independent of the last valid model roots.
        val home = Path.of(sourceRoot).toAbsolutePath()
        draft = SetupDraft(home, home, Optional.empty(), listOf())
    }

    fun closed(): Boolean = closed

    fun render(): Element {
        if (discovery != null && !closed) draft.accept(discovery!!.snapshot())
        var content: Element
        if (mode == Mode.CANDIDATES) content = browser.render(draft)
        else {
            val lines = ArrayList<DetailViewport.Line>()
            lines.add(DetailViewport.Line("Create configuration", Color.CYAN, true))
            lines.add(DetailViewport.Line("Config: " + CandidateBrowser.literal(session.configPath().toString())))
            val anchor = when (mode) {
                Mode.LOCATIONS -> locations(lines)
                Mode.TABLE -> table(lines)
                Mode.ROW -> rowDetails(lines)
                Mode.CANDIDATES -> throw IllegalStateException()
            }
            lines.add(DetailViewport.Line(CandidateBrowser.literal(message), Color.YELLOW, false))
            content = Toolkit.column(viewport.render("Setup", lines, true, anchor), viewport.help(help(), commands())).fill()
        }
        if (discard) {
            // One contextual help area while the modal consumes input.
            content = Toolkit.column(
                viewport.render(
                    "Discard setup draft?",
                    listOf(
                        DetailViewport.Line("Nothing has been written."),
                        DetailViewport.Line("Discard all locations and relocation choices?"),
                    ),
                    true, 0,
                ),
                Toolkit.text("Enter: Discard draft · Esc: Keep editing").gray(),
            ).fill()
        }
        return Toolkit.column(Toolkit.text("⌂ HOMELIGHT  [Setup]").cyan().bold(), content).fill()
    }

    private fun locations(lines: ArrayList<DetailViewport.Line>): Int {
        lines.add(DetailViewport.Line("Storage locations", Color.CYAN, true))
        val names = listOf("Source root", "Target root", "Shared candidate list (optional)")
        val values = listOf(sourceRoot, targetRoot, sharedList)
        for (i in names.indices) choice(lines, names[i] + ": " + values[i], i == field)
        lines.add(
            DetailViewport.Line(
                when (field) {
                    0 -> "Source paths are relative to this root. Changing it re-resolves existing rows."
                    1 -> "Target paths are relative to this root. Use an absolute path."
                    else -> "Blank: bundled candidates only. Otherwise use an absolute path or ~/path."
                },
            ),
        )
        if (!sharedList.isJavaBlank()) {
            try { lines.add(DetailViewport.Line("Resolved list: " + DiscoverySetting.parse(sharedList).orElseThrow())) }
            catch (error: IllegalArgumentException) { lines.add(DetailViewport.Line(error.message!!, Color.YELLOW, false)) }
        }
        return 3 + field
    }

    private fun table(lines: ArrayList<DetailViewport.Line>): Int {
        lines.add(DetailViewport.Line("Storage locations", Color.CYAN, true))
        lines.add(DetailViewport.Line("Source root: $sourceRoot"))
        lines.add(DetailViewport.Line("Target root: $targetRoot"))
        lines.add(DetailViewport.Line("Relocations", Color.CYAN, true))
        lines.add(DetailViewport.Line("  Source (relative)        Target (relative)        Policies", Color.GRAY, true))
        if (draft.rows().isEmpty()) lines.add(DetailViewport.Line("No relocations yet. Add a directory manually or browse candidates."))
        for (i in draft.rows().indices) {
            val value = draft.rows()[i]
            choice(
                lines, cell(value.sourceRelative, 23) + "  " + cell(value.targetRelative, 23) +
                    "  " + policies(value),
                i == row,
            )
        }
        return 7 + row
    }

    private fun rowDetails(lines: ArrayList<DetailViewport.Line>): Int {
        val value = draft.rows()[row]
        lines.add(DetailViewport.Line("Edit relocation " + (row + 1), Color.CYAN, true))
        val names = listOf("Source path", "Target path", "Both directories", "Only target", "Adopt target", "Archive root")
        val values = listOf(
            value.sourceRelative, value.targetRelative, both(value.both),
            only(value.onlyTarget), adopting(value.adopting), archiveText,
        )
        for (i in names.indices) {
            choice(lines, names[i] + ": " + values[i], i == field)
            if (i == 2 && discardPolicyFocused()) {
                lines.add(DetailViewport.Line(bothConsequence(value.both), Color.YELLOW, true))
            }
        }
        lines.add(DetailViewport.Line("Paths (resolved)", Color.CYAN, true))
        lines.add(DetailViewport.Line("Source: " + resolved(sourceRoot, value.sourceRelative)))
        lines.add(DetailViewport.Line("Target: " + resolved(targetRoot, value.targetRelative)))
        val entry = draft.entries()[row]
        CandidateBrowser.attribution(lines, entry, draft)
        return 3 + field
    }

    private fun help(): String = when (mode) {
        Mode.LOCATIONS -> "↑/↓/Tab: Field · Type: Edit · Ctrl-U: Clear"
        Mode.TABLE -> if (draft.rows().isEmpty()) "e: Edit locations · Esc: Back"
        else "↑/↓: Row · Enter: Details · d: Remove · e: Locations · Esc: Back"
        Mode.ROW -> when (field) {
            0 -> "Source is relative; matching target follows until edited."
            1 -> "Target is relative to the target root."
            5 -> "Archive root is optional; use an absolute path."
            2 -> if (discardPolicyFocused()) "Save writes configuration only; Apply requires review."
            else "Both exist: " + bothConsequence(draft.rows()[row].both)
            3 -> "When only target exists: " +
                if (draft.rows()[row].onlyTarget.orElse(WhenOnlyTargetExists.PROMPT) == WhenOnlyTargetExists.PROMPT)
                    "ask before acting." else "link the source to that target."
            else -> "When adopting: " + when (draft.rows()[row].adopting.orElse(WhenAdoptingTarget.PROMPT)) {
                WhenAdoptingTarget.PROMPT -> "ask what to do with source contents."
                WhenAdoptingTarget.DISCARD_SOURCE -> "delete source contents."
                WhenAdoptingTarget.ARCHIVE_SOURCE -> "move source contents to the archive root."
            }
        }
        Mode.CANDIDATES -> ""
    }

    private fun commands(): String = when (mode) {
        Mode.LOCATIONS -> "Enter: Relocations · Esc: Cancel without writing"
        Mode.TABLE -> "a: Add manual · b: Browse candidates · v: Validate · s: Save · q: Discard"
        Mode.ROW -> "↑/↓/Tab: Field · " + (if (textField()) "Type: Edit · Ctrl-U: Clear" else "Space: Policy · d: Remove") +
            " · Esc: Table"
        Mode.CANDIDATES -> ""
    }

    // KeyEvent.character() is deprecated in TamboUI; kept as in the Java original (javac only noted it).
    @Suppress("DEPRECATION")
    fun key(key: KeyEvent) {
        if (closed) return
        if (discovery != null) draft.accept(discovery!!.snapshot())
        if (discard) {
            if (key.isKey(KeyCode.ESCAPE) || key.isCharIgnoreCase('n')) discard = false
            else if (key.isKey(KeyCode.ENTER) || key.isCharIgnoreCase('y')) close()
            return
        }
        if (key.isKey(KeyCode.ESCAPE)) {
            if (mode == Mode.CANDIDATES) {
                if (!browser.back()) changeMode(Mode.TABLE)
            } else if (mode == Mode.ROW) changeMode(Mode.TABLE)
            else if (mode == Mode.TABLE) changeMode(Mode.LOCATIONS)
            else close()
            return
        }
        if (mode == Mode.CANDIDATES) {
            if (key.isCharIgnoreCase('q') || key.isQuit()) discard = true
            else if (key.isCharIgnoreCase('r')) refreshDiscovery()
            else {
                val previousCount = draft.rows().size
                val editRow = browser.key(key, draft)
                if (draft.rows().size != previousCount) invalidated()
                if (editRow >= 0) { row = editRow; changeMode(Mode.ROW); invalidated() }
            }
            return
        }
        if (key.isQuit() && key.character() != 'q') { discard = true; return }
        if (key.isChar('[') || key.isChar(']')) { viewport.scroll(if (key.isChar(']')) 1 else -1); return }
        if (mode == Mode.LOCATIONS) { locationsKey(key); return }
        if (mode == Mode.TABLE) { tableKey(key); return }
        if (key.isKey(KeyCode.TAB) || key.isChar('\t') || key.isDown() || key.isUp()) {
            field = Math.floorMod(field + (if (key.isUp()) -1 else 1), 6); viewport.followChoice(); return
        }
        if (textField()) { editRow(key); return }
        if (key.isCharIgnoreCase('q')) discard = true
        else if (key.isCharIgnoreCase('d')) removeRow()
        else if (key.isChar(' ')) cyclePolicy()
    }

    private fun locationsKey(key: KeyEvent) {
        if (key.isKey(KeyCode.TAB) || key.isChar('\t') || key.isDown() || key.isUp()) {
            field = Math.floorMod(field + (if (key.isUp()) -1 else 1), 3); viewport.followChoice()
        } else if (key.isKey(KeyCode.ENTER)) {
            // Manual incomplete drafts remain editable even before roots validate.
            try { applyLocations() }
            catch (error: IllegalArgumentException) { message = error.message!! }
            changeMode(Mode.TABLE)
        } else {
            val current = when (field) { 0 -> sourceRoot; 1 -> targetRoot; else -> sharedList }
            val next = edit(current, key)
            if (current == next) return
            when (field) { 0 -> sourceRoot = next; 1 -> targetRoot = next; else -> sharedList = next }
            locationsChanged = true
            // Invalidate immediately, including an edit away from and back to a root.
            if (discovery != null) discovery!!.cancel()
            draft.roots(draft.sourceRoot(), draft.targetRoot())
            invalidated()
        }
    }

    private fun tableKey(key: KeyEvent) {
        if (key.isCharIgnoreCase('a')) {
            draft.append(SetupDraft.Row("", "")); row = draft.rows().size - 1
            changeMode(Mode.ROW); invalidated()
        } else if (key.isCharIgnoreCase('b')) {
            try {
                applyLocations()
                if (discovery == null) { discovery = discoveryFactory.get(); refreshDiscovery() }
                changeMode(Mode.CANDIDATES)
            } catch (error: IllegalArgumentException) { message = error.message!! }
        } else if (key.isCharIgnoreCase('e')) changeMode(Mode.LOCATIONS)
        else if (key.isCharIgnoreCase('q')) discard = true
        else if (key.isCharIgnoreCase('v')) validate()
        else if (key.isCharIgnoreCase('s')) save()
        else if (key.isCharIgnoreCase('d') && !draft.rows().isEmpty()) removeRow()
        else if (key.isKey(KeyCode.ENTER) && !draft.rows().isEmpty()) changeMode(Mode.ROW)
        else if (key.isUp() || key.isCharIgnoreCase('k') || key.isDown() || key.isCharIgnoreCase('j')) {
            row = Math.clamp(
                (row + (if (key.isUp() || key.isCharIgnoreCase('k')) -1 else 1)).toLong(), 0, Math.max(0, draft.rows().size - 1),
            )
            viewport.followChoice()
        }
    }

    private fun applyLocations() {
        if (sourceRoot.isJavaBlank() || targetRoot.isJavaBlank()) throw IllegalArgumentException("Enter both storage roots")
        val source = Path.of(sourceRoot)
        val target = Path.of(targetRoot)
        if (!source.isAbsolute || !target.isAbsolute) throw IllegalArgumentException("Storage roots must be absolute")
        val shared = DiscoverySetting.parse(sharedList)
        if (!locationsChanged && source.normalize() == draft.sourceRoot() && target.normalize() == draft.targetRoot() &&
            shared == draft.sharedList()
        ) return
        draft.roots(source, target)
        draft.sharedList(sharedList)
        locationsChanged = false
        if (discovery != null) refreshDiscovery()
    }

    private fun refreshDiscovery() {
        draft.refresh(discovery!!)
        draft.accept(discovery!!.snapshot())
    }

    private fun editRow(key: KeyEvent) {
        val value = draft.rows()[row]
        var source = value.sourceRelative
        var target = value.targetRelative
        var archive = value.archiveRoot
        try {
            if (field == 0) {
                val mirrored = target == source || target.isJavaBlank()
                source = edit(source, key); if (mirrored) target = source
            } else if (field == 1) target = edit(target, key)
            else {
                val text = edit(archiveText, key)
                archive = if (text.isJavaBlank()) Optional.empty() else Optional.of(Path.of(text))
                archiveText = text
            }
            val next = SetupDraft.Row(source, target, value.both, value.onlyTarget, value.adopting, archive)
            if (next != value) { draft.edit(row, next); invalidated() }
        } catch (error: IllegalArgumentException) { message = "Invalid path: " + error.message }
    }

    private fun cyclePolicy() {
        val value = draft.rows()[row]
        draft.edit(
            row,
            SetupDraft.Row(
                value.sourceRelative, value.targetRelative,
                if (field == 2) next(value.both, WhenSourceAndTargetDirectoriesExist.values()) else value.both,
                if (field == 3) next(value.onlyTarget, WhenOnlyTargetExists.values()) else value.onlyTarget,
                if (field == 4) next(value.adopting, WhenAdoptingTarget.values()) else value.adopting, value.archiveRoot,
            ),
        )
        invalidated()
    }

    private fun removeRow() {
        draft.remove(row); row = Math.min(row, Math.max(0, draft.rows().size - 1))
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
            ConfigurationPublisher().saveNew(session.configPath(), draft.validate())
            close()
            session.refresh()
        } catch (error: RuntimeException) { message = "Save failed: " + error.message; viewport.scroll(Int.MAX_VALUE) }
    }

    override fun close() {
        if (discovery != null) { discovery!!.close(); discovery = null }
        closed = true
    }

    private fun changeMode(next: Mode) {
        mode = next; field = 0; viewport.reset()
        if (next == Mode.ROW) archiveText = draft.rows()[row].archiveRoot.map(Path::toString).orElse("")
    }

    private fun textField(): Boolean = field == 0 || field == 1 || field == 5
    private fun invalidated() { message = "Draft not saved. Validation: not run." }

    private fun discardPolicyFocused(): Boolean =
        mode == Mode.ROW && field == 2 &&
            draft.rows()[row].both == Optional.of(WhenSourceAndTargetDirectoriesExist.DISCARD)

    companion object {
        private fun resolved(root: String, relative: String): String {
            try { return Path.of(root).resolve(relative).normalize().toString() }
            catch (error: IllegalArgumentException) { return "Invalid path: " + error.message }
        }

        @Suppress("DEPRECATION")
        private fun edit(value: String, key: KeyEvent): String {
            if (key.isChar('\u0015') || key.hasCtrl() && key.isCharIgnoreCase('u')) return ""
            if (key.isKey(KeyCode.BACKSPACE)) return if (value.isEmpty()) value else value.substring(0, value.length - 1)
            return if (key.character() != '\u0000' && !Character.isISOControl(key.character())) value + key.character() else value
        }

        private fun <T : Enum<T>> next(current: Optional<T>, values: Array<T>): Optional<T> =
            current.map { value ->
                if (value.ordinal + 1 == values.size) Optional.empty<T>() else Optional.of(values[value.ordinal + 1])
            }.orElse(Optional.of(values[0]))

        private fun choice(lines: MutableList<DetailViewport.Line>, value: String, focused: Boolean) {
            lines.add(
                DetailViewport.Line(
                    (if (focused) "❯ " else "  ") + CandidateBrowser.literal(value), if (focused) Color.CYAN else Color.GRAY, focused,
                ),
            )
        }

        private fun cell(value: String, width: Int): String {
            val text = CandidateBrowser.literal(if (value.isJavaBlank()) "(new relocation)" else value)
            return if (text.length > width) text.substring(0, width - 1) + "…" else text + " ".repeat(width - text.length)
        }

        private fun policies(row: SetupDraft.Row): String {
            val values = ArrayList<String>()
            if (row.both.isPresent) values.add(both(row.both))
            if (row.onlyTarget.isPresent) values.add(only(row.onlyTarget))
            if (row.adopting.isPresent) values.add(adopting(row.adopting))
            return if (values.isEmpty()) "Default (prompt)" else values.joinToString(", ")
        }

        private fun both(value: Optional<WhenSourceAndTargetDirectoriesExist>): String = value.map { v ->
            when (v) {
                WhenSourceAndTargetDirectoriesExist.PROMPT -> "Prompt"
                WhenSourceAndTargetDirectoriesExist.ADOPT -> "Adopt target"
                WhenSourceAndTargetDirectoriesExist.LEAVE_UNCHANGED -> "Leave unchanged"
                WhenSourceAndTargetDirectoriesExist.DISCARD -> "Discard both"
            }
        }.orElse("Default (prompt)")

        private fun only(value: Optional<WhenOnlyTargetExists>): String = value.map { v ->
            when (v) {
                WhenOnlyTargetExists.PROMPT -> "Prompt"
                WhenOnlyTargetExists.ADOPT_TARGET -> "Adopt target"
            }
        }.orElse("Default (prompt)")

        private fun adopting(value: Optional<WhenAdoptingTarget>): String = value.map { v ->
            when (v) {
                WhenAdoptingTarget.PROMPT -> "Prompt"
                WhenAdoptingTarget.DISCARD_SOURCE -> "Discard source"
                WhenAdoptingTarget.ARCHIVE_SOURCE -> "Archive source"
            }
        }.orElse("Default (prompt)")

        private fun bothConsequence(value: Optional<WhenSourceAndTargetDirectoriesExist>): String =
            when (value.orElse(WhenSourceAndTargetDirectoriesExist.PROMPT)) {
                WhenSourceAndTargetDirectoriesExist.PROMPT -> "ask before acting."
                WhenSourceAndTargetDirectoriesExist.ADOPT -> "use target contents; choose source disposition below."
                WhenSourceAndTargetDirectoriesExist.LEAVE_UNCHANGED -> "leave both paths unmanaged."
                WhenSourceAndTargetDirectoriesExist.DISCARD ->
                    "Permanently delete both source and target directory trees. Create an empty target directory and link the source to it."
            }
    }
}
