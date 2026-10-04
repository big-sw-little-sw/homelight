package io.github.bigswlittlesw.homelight.tui.prototype

// THROWAWAY PROTOTYPE. Answers: can the TUI use TamboUI 0.5.0's FocusManager + form/list/tree/dialog elements
// instead of hand-written focus, field and list code? Not for merging.

import dev.tamboui.buffer.Buffer
import dev.tamboui.layout.Rect
import dev.tamboui.style.Color
import dev.tamboui.terminal.Frame
import dev.tamboui.toolkit.Toolkit
import dev.tamboui.toolkit.app.ToolkitRunner
import dev.tamboui.toolkit.element.DefaultRenderContext
import dev.tamboui.toolkit.element.Element
import dev.tamboui.toolkit.element.ElementRegistry
import dev.tamboui.toolkit.element.StyledElement
import dev.tamboui.toolkit.elements.ListElement
import dev.tamboui.toolkit.elements.TreeElement
import dev.tamboui.toolkit.event.EventResult
import dev.tamboui.toolkit.event.EventRouter
import dev.tamboui.toolkit.event.GlobalEventHandler
import dev.tamboui.toolkit.focus.FocusManager
import dev.tamboui.tui.TuiConfig
import dev.tamboui.tui.event.KeyCode
import dev.tamboui.tui.event.KeyEvent
import dev.tamboui.tui.event.KeyModifiers
import dev.tamboui.widgets.form.FormState
import dev.tamboui.widgets.tree.TreeNode
import io.github.bigswlittlesw.homelight.tui.KEY_BINDINGS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.Supplier

// ───────────────────────────── model (in-memory only) ─────────────────────────────

private val BOTH_EXIST = listOf(
    "Ask each time", "Keep target, delete source", "Keep target, archive source",
    "Keep target, ask about source", "Leave both as they are", "Delete both, start empty",
)
private val ONLY_TARGET = listOf("Ask each time", "Keep target, link source")

private data class FieldSpec(val name: String, val label: String, val placeholder: String = "", val choices: List<String>? = null) {
    val id get() = "field-$name"
}

/** One list entry. Values live in TamboUI's [FormState]; `originals` is our dirty baseline. */
private class Item(val label: String, val group: String?, val specs: List<FieldSpec>, val form: FormState) {
    var originals = snapshot()
    fun value(spec: FieldSpec): String = if (spec.choices != null) form.selectValue(spec.name) else form.textValue(spec.name)
    fun snapshot(): Map<String, String> = specs.associate { it.name to value(it) }
    fun changes(): Int = specs.count { value(it) != originals[it.name] }
}

private fun storage(): Item {
    val specs = listOf(FieldSpec("sourceRoot", "Source root"), FieldSpec("targetRoot", "Target root"), FieldSpec("sharedList", "Shared list"))
    val form = FormState.builder().textField("sourceRoot", "~").textField("targetRoot", "/Volumes/Data/home")
        .textField("sharedList", "~/.homelight-shared.txt").build()
    return Item("Storage locations", null, specs, form)
}

private fun relocation(source: String, group: String): Item {
    val specs = listOf(
        FieldSpec("source", "Source"),
        FieldSpec("target", "Target", "(blank: from target root)"),
        FieldSpec("archive", "Archive root", "(blank: default)"),
        FieldSpec("both", "Both exist", choices = BOTH_EXIST),
        FieldSpec("onlyTarget", "Only target", choices = ONLY_TARGET),
    )
    val form = FormState.builder().textField("source", source).textField("target", "").textField("archive", "")
        .selectField("both", BOTH_EXIST, 0).selectField("onlyTarget", ONLY_TARGET, 0).build()
    return Item(source, group, specs, form)
}

// ───────────────────────────── the screen ─────────────────────────────

private const val LIST_ID = "config-list"
private const val DIALOG_ID = "dialog"

private enum class Dialog { SAVE, DISCARD }
private enum class Screen { WORKSPACE, CONFIGURATION }

/**
 * Focus lives in TamboUI's [FocusManager] (ids: [LIST_ID], `field-*`, [DIALOG_ID]). Elements consume their own keys
 * first (list/tree navigation, text editing, the modal dialog); everything they leave is routed to [onKey], which is
 * installed as the router's single global handler and returns HANDLED for every key, so:
 *
 * - TamboUI's built-in quit-on-`q` (ToolkitRunner.handleEvent) and Escape-clears-focus (EventRouter) never fire.
 * - The router's "offer the key to every unfocused element" pass never runs. That pass matters because
 *   ListElement/TreeElement act on keys even when unfocused.
 */
internal class ConfigEditorPrototype(
    private val focus: FocusManager,
    private val useTree: Boolean = false,
    private val quit: () -> Unit,
) {
    private val items = listOf(
        storage(),
        relocation("~/.m2", "Package caches"),
        relocation("~/.cache/uv", "Package caches"),
        relocation("~/.cargo", "Package caches"),
        relocation("~/.config/agent-tools", "Agent tools"),
    )
    private var screen = Screen.CONFIGURATION
    private var dialog: Dialog? = null
    private var focusBeforeDialog: String? = null
    private var focusBeforeClose: String? = null
    var quitRequested = false
        private set

    // ListElement/TreeElement keep selection in a private state object, so the instance must persist across frames.
    private val list: ListElement<Item> = Toolkit.list().data(items) { item -> listRow(item) }
        .title("Configuration").rounded().highlightSymbol("❯ ").highlightColor(Color.CYAN).id(LIST_ID)
    private val tree: TreeElement<Item> = Toolkit.tree<Item>().apply {
        add(TreeNode.of<Item>(items[0].label, items[0]).leaf())
        for ((group, members) in items.drop(1).groupBy { it.group!! }) {
            val node = TreeNode.of<Item>(group).expanded()
            members.forEach { node.add(TreeNode.of<Item>(it.label, it).leaf()) }
            add(node)
        }
    }.title("Configuration").rounded().highlightSymbol("❯ ").highlightColor(Color.CYAN).id(LIST_ID)
        // Tree lambdas run before the tree's own keys (TreeElement.handleKeyEvent calls super first): Enter on a leaf
        // would otherwise be swallowed by toggleSelected().
        .onKeyEvent { e -> if (e.isConfirm() && selected() != null) { enterFields(); EventResult.HANDLED } else EventResult.UNHANDLED }

    private fun listRow(item: Item): StyledElement<*> =
        Toolkit.text((if (item.changes() > 0) "● " else "  ") + item.label)

    private fun selected(): Item? = if (useTree) tree.selectedNode()?.data() else items[list.selected().coerceIn(items.indices)]
    private fun current(): Item = selected() ?: items[0]
    private fun changes(): Int = items.sumOf { it.changes() }
    private fun focusedSpec(): FieldSpec? = current().specs.firstOrNull { it.id == focus.focusedId() }

    // ─────────── rendering ───────────

    fun render(): Element {
        val base = if (screen == Screen.CONFIGURATION) configuration() else workspace()
        val open = dialog ?: return base
        return Toolkit.stack(base, dialogElement(open))
    }

    private fun configuration(): Element {
        val interactive = dialog == null
        val listFocused = focus.focusedId() == LIST_ID
        val master: StyledElement<*> = (if (useTree) tree else list).also {
            it.focusable(interactive)
            it.borderColorOf(if (listFocused) Color.CYAN else Color.GRAY)
        }
        val item = current()
        val fields = item.specs.map { spec -> field(item, spec, interactive) }
        val detail = Toolkit.panel(item.label, *fields.toTypedArray()).rounded()
            .borderColor(if (focusedSpec() != null) Color.CYAN else Color.GRAY)
        val (help1, help2) = help()
        return Toolkit.column(
            Toolkit.text("⌂ HOMELIGHT  [1: Workspace]  [Configuration]").cyan().bold().length(1),
            Toolkit.text("~/.homelight.json · existing file · ${changes()} unsaved changes").gray().length(1),
            Toolkit.row(master.percent(45), detail.percent(55)).fill(),
            Toolkit.text(help1).length(1),
            Toolkit.text(help2).gray().length(1),
        ).fill()
    }

    private fun field(item: Item, spec: FieldSpec, interactive: Boolean): Element {
        val focused = focus.focusedId() == spec.id
        // Plain-text captures cannot show the reversed cursor cell, so the label carries a focus marker.
        val label = (if (focused) "❯ " else "  ") + spec.label
        if (spec.choices != null) {
            // Not FormFieldElement(SELECT): it consumes Up/Down (and vim j/k) to cycle options, so Up/Down could not
            // move between fields (FormFieldElement.java:731-737). Two rows: the longest option does not fit
            // beside the label in the 80-column detail pane.
            val value = Toolkit.text("    ‹ " + item.value(spec) + " ›").apply { if (focused) cyan() }
            return Toolkit.column(Toolkit.text(label), value).id(spec.id).focusable(interactive).length(2)
        }
        return Toolkit.formField(label, item.form.textField(spec.name))
            .placeholder(spec.placeholder).labelWidth(16).spacing(0)
            .onSubmit { moveField(1) }
            .id(spec.id).focusable(interactive).length(1)
    }

    private fun help(): Pair<String, String> = when {
        dialog != null -> "y/n: Choose  ·  Esc: Cancel" to "The dialog takes every key"
        focus.focusedId() == LIST_ID -> "↑/↓: Select  ·  Enter/→/Tab: Edit fields" + (if (useTree) "  ·  ←: Collapse" else "") to
            "s: Save  ·  Esc: Close  ·  q: Quit"
        focusedSpec()?.choices != null -> "Space/→: Next option  ·  ←: Previous  ·  ↑/↓: Field" to
            "Esc: Back to list  ·  s: Save  ·  q: Quit"
        else -> "Type to edit  ·  ←/→ Home/End  ·  Ctrl+U: Clear  ·  ↑/↓/Enter: Field" to "Esc: Back to list"
    }

    private fun dialogElement(open: Dialog): Element {
        val (title, body, keys) = when (open) {
            Dialog.SAVE -> Triple("Replace ~/.homelight.json?", "Write ${changes()} unsaved changes to the file.", "y: Replace  ·  n/Esc: Cancel")
            Dialog.DISCARD -> Triple("Discard changes?", "${changes()} unsaved changes will be lost.", "y: Discard and quit  ·  n/Esc: Keep editing")
        }
        return Toolkit.dialog(title, Toolkit.text(body), Toolkit.text(""), Toolkit.text(keys).gray())
            .doubleBorder().borderColor(Color.YELLOW)
            // DialogElement.calculateWidth ignores child widths (DialogElement.java:492), so set it.
            .width(maxOf(title.length, body.length, keys.length) + 6)
            .id(DIALOG_ID).focusable()
            .onKeyEvent { e ->
                when {
                    e.isCharIgnoreCase('y') -> { confirm(open); EventResult.HANDLED }
                    e.isCharIgnoreCase('n') -> { closeDialog(); EventResult.HANDLED }
                    else -> EventResult.UNHANDLED // DialogElement then handles Esc (onCancel) and consumes the rest.
                }
            }
            .onCancel { closeDialog() }
    }

    private fun workspace(): Element = Toolkit.column(
        Toolkit.text("⌂ HOMELIGHT  [Workspace]  [2: Configuration]").cyan().bold().length(1),
        Toolkit.panel("Workspace", Toolkit.text("(stub) Configuration has ${changes()} unsaved changes")).rounded().fill(),
        Toolkit.text("c: Configuration  ·  Esc: (nothing to close)  ·  q: Quit").length(1),
        Toolkit.text("").length(1),
    ).fill()

    // ─────────── keys the focused element left unhandled ───────────

    fun onKey(event: KeyEvent): EventResult {
        // A dialog consumes every key itself, except the router's own Tab handling, which reaches nothing here.
        if (dialog == null) {
            if (screen == Screen.WORKSPACE) workspaceKey(event) else configurationKey(event)
        }
        return EventResult.HANDLED
    }

    private fun workspaceKey(e: KeyEvent) {
        when {
            e.isCharIgnoreCase('c') || e.isChar('2') -> { screen = Screen.CONFIGURATION; focus.setFocus(focusBeforeClose ?: LIST_ID) }
            e.isQuit() -> requestQuit()
        }
    }

    private fun configurationKey(e: KeyEvent) {
        val id = focus.focusedId()
        val spec = focusedSpec()
        when {
            spec != null && e.code() == KeyCode.ESCAPE -> focus.setFocus(LIST_ID)
            spec != null && (e.isUp() || e.isDown()) -> moveField(if (e.isUp()) -1 else 1)
            spec != null && spec.choices == null && clears(e) -> current().form.textField(spec.name).clear()
            spec?.choices != null && (e.isChar(' ') || e.isRight() || e.isConfirm()) -> cycle(spec, 1)
            spec?.choices != null && e.isLeft() -> cycle(spec, -1)
            id == LIST_ID && e.code() == KeyCode.ESCAPE -> { focusBeforeClose = id; screen = Screen.WORKSPACE }
            id == LIST_ID && (e.isConfirm() || e.isRight()) -> enterFields()
            e.isChar('1') -> { focusBeforeClose = id; screen = Screen.WORKSPACE }
            e.isCharIgnoreCase('s') && changes() > 0 -> openDialog(Dialog.SAVE)
            e.isQuit() -> requestQuit()
        }
    }

    private fun clears(e: KeyEvent) = e.isChar('\u0015') || e.hasCtrl() && e.isCharIgnoreCase('u')

    private fun enterFields() { focus.setFocus(current().specs.first().id) }

    private fun moveField(delta: Int) {
        val specs = current().specs
        val index = specs.indexOfFirst { it.id == focus.focusedId() }
        if (index >= 0) focus.setFocus(specs[(index + delta).coerceIn(specs.indices)].id)
    }

    private fun cycle(spec: FieldSpec, delta: Int) {
        val state = current().form.selectField(spec.name)
        state.selectIndex((state.selectedIndex() + delta).mod(state.size()))
    }

    private fun requestQuit() { if (changes() > 0) openDialog(Dialog.DISCARD) else { quitRequested = true; quit() } }

    private fun openDialog(open: Dialog) {
        focusBeforeDialog = focus.focusedId()
        dialog = open
        focus.setFocus(DIALOG_ID)
    }

    private fun closeDialog() {
        dialog = null
        focus.setFocus(focusBeforeDialog)
    }

    private fun confirm(open: Dialog) {
        closeDialog()
        when (open) {
            Dialog.SAVE -> items.forEach { it.originals = it.snapshot() }
            Dialog.DISCARD -> { quitRequested = true; quit() }
        }
    }
}

private fun StyledElement<*>.borderColorOf(color: Color) {
    when (this) {
        is ListElement<*> -> borderColor(color)
        is TreeElement<*> -> borderColor(color)
    }
}

// ───────────────────────────── interactive entry point ─────────────────────────────

/** `--tree` shows the grouped TreeElement variant of the left list. */
fun main(args: Array<String>) {
    val config = TuiConfig.defaults().toBuilder().bindings(KEY_BINDINGS).build()
    ToolkitRunner.create(config).use { runner ->
        val app = ConfigEditorPrototype(runner.focusManager(), "--tree" in args) { runner.quit() }
        runner.eventRouter().addGlobalHandler(GlobalEventHandler { e -> if (e is KeyEvent) app.onKey(e) else EventResult.UNHANDLED })
        runner.run(Supplier { app.render() })
    }
}

// ───────────────────────────── headless driver ─────────────────────────────

/**
 * ToolkitRunner needs a terminal (TuiRunner.create opens a backend), so this replicates its per-frame loop with the
 * same public pieces: FocusManager + EventRouter + DefaultRenderContext. ToolkitRunner.run() lines: clear
 * focusables/router, render root, register root, auto-focus first focusable if focus is stale; handleEvent: route,
 * then quit on an unhandled isQuit().
 */
internal class Headless(private val width: Int, private val height: Int, tree: Boolean = false) {
    val focus = FocusManager()
    private val router = EventRouter(focus, ElementRegistry())
    private val context = DefaultRenderContext(focus, router).apply { setBindings(KEY_BINDINGS) }
    var runnerQuit = false
        private set
    val app = ConfigEditorPrototype(focus, tree) { runnerQuit = true }
    var screen = ""
        private set

    init {
        router.addGlobalHandler(GlobalEventHandler { e -> if (e is KeyEvent) app.onKey(e) else EventResult.UNHANDLED })
        frame()
    }

    fun frame(): String = onRenderThread {
        focus.clearFocusables()
        router.clear()
        val area = Rect.of(width, height)
        val buffer = Buffer.empty(area)
        val root = app.render()
        root.render(Frame.forTesting(buffer), area, context)
        context.registerElement(root, area)
        val order = focus.focusOrder()
        if (order.isNotEmpty() && focus.focusedId() !in order) focus.setFocus(order[0])
        screen = buildString {
            for (y in 0 until height) {
                for (x in 0 until width) append(buffer.get(x, y).symbol())
                append('\n')
            }
        }
        screen
    }

    fun key(event: KeyEvent) {
        val result = router.route(event)
        if (result.isUnhandled && event.isQuit()) runnerQuit = true
        frame()
    }

    fun char(c: Char) = key(KeyEvent.ofChar(c, KEY_BINDINGS))
    fun code(c: KeyCode) = key(KeyEvent.ofKey(c, KEY_BINDINGS))
    fun ctrl(c: Char) = key(KeyEvent.ofChar(c, KeyModifiers.CTRL, KEY_BINDINGS))
    fun type(text: String) = text.forEach { char(it) }

    private fun <T> onRenderThread(block: () -> T): T {
        val thread = Class.forName("dev.tamboui.tui.RenderThread")
        val mark = thread.getDeclaredMethod("markAsRenderThread").apply { isAccessible = true }
        val clear = thread.getDeclaredMethod("clearRenderThread").apply { isAccessible = true }
        mark.invoke(null)
        try { return block() } finally { clear.invoke(null) }
    }
}

// ───────────────────────────── scripted verification ─────────────────────────────

class FocusPrototypeScript {
    private val out: Path = Path.of(System.getProperty("proto.dir")
        ?: "/private/tmp/claude-501/-Users-jsiva-sw-code-homelight/617a1415-ede8-4267-ae8b-e75703c04bfb/scratchpad/proto")

    private fun save(h: Headless, step: String, w: Int, ht: Int) {
        Files.createDirectories(out)
        val meta = "focus=${h.focus.focusedId()}  focusOrder=${h.focus.focusOrder()}  runnerQuit=${h.runnerQuit}  appQuit=${h.app.quitRequested}\n"
        Files.writeString(out.resolve("$step-${w}x$ht.txt"), h.screen + "---\n" + meta)
    }

    private fun row(screen: String, needle: String): String = screen.lines().first { needle in it }

    @Test fun sequence80x24() = sequence(80, 24)
    @Test fun sequence120x30() = sequence(120, 30)

    private fun sequence(w: Int, ht: Int) {
        val h = Headless(w, ht)
        save(h, "a-initial", w, ht)
        assertEquals("config-list", h.focus.focusedId())

        h.code(KeyCode.DOWN)
        save(h, "b-down-to-m2", w, ht)
        assertTrue("❯   ~/.m2" in h.screen || "❯ ~/.m2" in h.screen, h.screen)

        h.code(KeyCode.ENTER)
        assertEquals("field-source", h.focus.focusedId())
        h.code(KeyCode.DOWN)
        assertEquals("field-target", h.focus.focusedId())
        h.type("/data/jkhlgGx q")
        save(h, "c-typed-target", w, ht)
        assertEquals("field-target", h.focus.focusedId(), "vim letters must not move focus")
        assertTrue("/data/jkhlgGx q" in row(h.screen, "Target"), row(h.screen, "Target"))
        assertFalse(h.runnerQuit || h.app.quitRequested)

        // d) cursor editing: ← ← Home End Backspace Delete Ctrl-U
        h.code(KeyCode.LEFT); h.code(KeyCode.LEFT); h.char('Z')
        save(h, "d1-left-left-Z", w, ht)
        assertTrue("/data/jkhlgGxZ q" in row(h.screen, "Target"), row(h.screen, "Target"))
        h.code(KeyCode.HOME); h.char('A')
        h.code(KeyCode.END); h.code(KeyCode.BACKSPACE)
        save(h, "d2-home-A-end-backspace", w, ht)
        assertTrue("A/data/jkhlgGxZ " in row(h.screen, "Target"), row(h.screen, "Target"))
        h.code(KeyCode.HOME); h.code(KeyCode.DELETE)
        save(h, "d3-home-delete", w, ht)
        assertTrue("/data/jkhlgGxZ " in row(h.screen, "Target") && "A/data" !in row(h.screen, "Target"))
        h.ctrl('u')
        save(h, "d4-ctrl-u", w, ht)
        assertTrue("(blank: from target root)" in row(h.screen, "Target"), row(h.screen, "Target"))
        h.type("/data/m2")
        // choice field: ↓ ↓ to Both exist, Space twice, ← once
        h.code(KeyCode.DOWN); h.code(KeyCode.DOWN); h.char(' '); h.char(' '); h.code(KeyCode.LEFT)
        save(h, "d5-choice-cycled", w, ht)
        assertEquals("field-both", h.focus.focusedId())
        assertTrue("‹ Keep target, delete source ›" in h.screen, h.screen)

        h.code(KeyCode.ESCAPE)
        save(h, "e-esc-to-list", w, ht)
        assertEquals("config-list", h.focus.focusedId())
        assertTrue("2 unsaved changes" in h.screen, h.screen)

        h.char('s')
        save(h, "f-save-dialog", w, ht)
        assertEquals("dialog", h.focus.focusedId())
        assertTrue("Replace ~/.homelight.json?" in h.screen)
        val behind = h.screen

        h.char('j'); h.char('k'); h.code(KeyCode.TAB); h.char('q')
        save(h, "g-keys-in-dialog", w, ht)
        assertEquals("dialog", h.focus.focusedId())
        assertEquals(behind, h.screen, "nothing behind the dialog changed")
        assertFalse(h.runnerQuit || h.app.quitRequested)

        h.char('n')
        save(h, "h-n-closes-dialog", w, ht)
        assertEquals("config-list", h.focus.focusedId())
        assertTrue("❯ ● ~/.m2" in h.screen, h.screen)

        h.code(KeyCode.ESCAPE)
        save(h, "i-esc-at-top-level", w, ht)
        assertFalse(h.runnerQuit || h.app.quitRequested, "Escape never exits")
        h.code(KeyCode.ESCAPE)
        save(h, "i2-esc-again-on-workspace", w, ht)
        assertFalse(h.runnerQuit || h.app.quitRequested)
        h.char('c')
        save(h, "i3-back-to-configuration", w, ht)
        assertEquals("config-list", h.focus.focusedId())

        h.char('q')
        save(h, "j-q-discard-dialog", w, ht)
        assertEquals("dialog", h.focus.focusedId())
        assertTrue("Discard changes?" in h.screen)
        assertFalse(h.runnerQuit || h.app.quitRequested)
        h.code(KeyCode.ESCAPE)
        assertEquals("config-list", h.focus.focusedId())
        h.char('q'); h.char('y')
        assertTrue(h.app.quitRequested)
    }

    @Test fun treeVariant() {
        val (w, ht) = 80 to 24
        val h = Headless(w, ht, tree = true)
        save(h, "tree-a-initial", w, ht)
        h.code(KeyCode.DOWN)                     // "Package caches" group
        h.code(KeyCode.LEFT)                     // collapse
        save(h, "tree-b-collapsed", w, ht)
        assertFalse("~/.cargo" in h.screen)
        h.char('l')                              // vim right: expand
        h.char('j')                              // ~/.m2
        h.code(KeyCode.ENTER)                    // into fields via the tree's lambda
        assertEquals("field-source", h.focus.focusedId())
        h.char('k')                              // types into the field
        save(h, "tree-c-into-fields", w, ht)
        assertTrue("~/.m2k" in h.screen, h.screen)
        h.code(KeyCode.ESCAPE)
        assertEquals("config-list", h.focus.focusedId())
        h.code(KeyCode.ENTER)
        assertEquals("field-source", h.focus.focusedId())
    }
}

// ───────────────────────────── probes: TamboUI behavior without the shims ─────────────────────────────

/** Bare router, no global handler: shows what each shim in [ConfigEditorPrototype] protects against. */
class TamboUiProbes {
    private class Bare(private val root: () -> Element) {
        val focus = FocusManager()
        private val router = EventRouter(focus, ElementRegistry())
        private val context = DefaultRenderContext(focus, router).apply { setBindings(KEY_BINDINGS) }
        fun frame() {
            val thread = Class.forName("dev.tamboui.tui.RenderThread")
            thread.getDeclaredMethod("markAsRenderThread").apply { isAccessible = true }.invoke(null)
            try {
                focus.clearFocusables(); router.clear()
                val area = Rect.of(80, 24)
                val r = root()
                r.render(Frame.forTesting(Buffer.empty(area)), area, context)
                context.registerElement(r, area)
            } finally { thread.getDeclaredMethod("clearRenderThread").apply { isAccessible = true }.invoke(null) }
        }
        fun route(e: KeyEvent): EventResult = router.route(e).also { frame() }
    }

    @Test fun unfocusedListStillConsumesNavigation() {
        val list = Toolkit.list("a", "b", "c").id("list").focusable()
        val text = dev.tamboui.widgets.input.TextInputState("x")
        val b = Bare { Toolkit.column(list, Toolkit.formField("F", text).id("field")) }
        b.frame(); b.focus.setFocus("field")
        b.route(KeyEvent.ofKey(KeyCode.DOWN, KEY_BINDINGS))
        // The text field ignores Down; the router then offers it to every unfocused element and the list moves.
        assertEquals(1, list.selected(), "unfocused ListElement moved")
    }

    @Test fun arrowNavigationTurnsVimLettersIntoFocusMoves() {
        val text = dev.tamboui.widgets.input.TextInputState("")
        val b = Bare { Toolkit.column(Toolkit.formField("A", text).id("a").arrowNavigation(true), Toolkit.formField("B").id("b")) }
        b.frame(); b.focus.setFocus("a")
        b.route(KeyEvent.ofChar('j', KEY_BINDINGS))
        assertEquals("b", b.focus.focusedId(), "'j' moved focus instead of typing")
        assertEquals("", text.text())
    }

    @Test fun textInputIgnoresBindingSetsSoVimLettersType() {
        val text = dev.tamboui.widgets.input.TextInputState("")
        val b = Bare { Toolkit.textInput(text).id("t") }
        b.frame(); b.focus.setFocus("t")
        "jkhlgGxq".forEach { b.route(KeyEvent.ofChar(it, KEY_BINDINGS)) }
        assertEquals("jkhlgGxq", text.text())
    }

    @Test fun tabLeavesADialogWhoseBackgroundIsAFormElement() {
        val form = FormState.builder().textField("name", "").build()
        val b = Bare {
            Toolkit.stack(Toolkit.form(form).field("name", "Name"),
                Toolkit.dialog("Modal", Toolkit.text("body")).id("dialog").focusable())
        }
        b.frame(); b.focus.setFocus("dialog")
        b.route(KeyEvent.ofKey(KeyCode.TAB, KEY_BINDINGS))
        // FormElement's internal FormFieldElements are always focusable; Tab is handled before any element sees it.
        assertEquals("form-field-name", b.focus.focusedId())
    }

    @Test fun unhandledEscapeClearsFocusAndUnhandledQIsQuit() {
        val list = Toolkit.list("a", "b").id("list").focusable()
        val b = Bare { list }
        b.frame()
        b.focus.setFocus("list")
        val esc = b.route(KeyEvent.ofKey(KeyCode.ESCAPE, KEY_BINDINGS))
        assertTrue(esc.isHandled)
        // EventRouter cleared focus; the next frame's registerFocusable auto-focuses the first focusable again,
        // so an unhandled Escape silently jumps focus to the first element rather than exiting.
        assertEquals("list", b.focus.focusedId())
        b.focus.setFocus("list")
        val q = KeyEvent.ofChar('q', KEY_BINDINGS)
        assertTrue(b.route(q).isUnhandled && q.isQuit(), "ToolkitRunner.handleEvent would quit here")
    }
}
