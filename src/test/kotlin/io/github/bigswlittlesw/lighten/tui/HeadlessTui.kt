package io.github.bigswlittlesw.lighten.tui

import dev.tamboui.buffer.Buffer
import dev.tamboui.layout.Rect
import dev.tamboui.terminal.Frame
import dev.tamboui.toolkit.element.DefaultRenderContext
import dev.tamboui.toolkit.element.ElementRegistry
import dev.tamboui.toolkit.event.EventRouter
import dev.tamboui.toolkit.focus.FocusManager
import dev.tamboui.tui.event.Event
import dev.tamboui.tui.event.KeyCode
import dev.tamboui.tui.event.KeyEvent
import dev.tamboui.tui.event.KeyModifiers
import io.github.bigswlittlesw.lighten.application.LightenSession
import io.github.bigswlittlesw.lighten.discovery.CandidateDiscovery

/**
 * Runs a [LightenApp] the way `ToolkitRunner` does, without a terminal: TamboUI's own [FocusManager] and
 * [EventRouter] route each key, and a frame renders after every key, so focus, Tab and dialogs behave as they do
 * on screen.
 *
 * `ToolkitRunner.run` per frame: clear focusables and routes, render the root, register it, then focus the first
 * focusable when focus is missing or stale.
 */
internal class HeadlessTui(
    session: LightenSession,
    openConfiguration: Boolean = false,
    discoveryFactory: () -> CandidateDiscovery = { CandidateDiscovery() },
    private val width: Int = 80,
    private val height: Int = 24,
) {
    val focus = FocusManager()
    private val router = EventRouter(focus, ElementRegistry())
    private val context = DefaultRenderContext(focus, router).apply { setBindings(KEY_BINDINGS) }
    val app = LightenApp(session, focus, openConfiguration, discoveryFactory)

    init {
        router.addGlobalHandler(app.keyHandler)
        frame()
    }

    fun focused(): String? = focus.focusedId()

    fun frame(width: Int = this.width, height: Int = this.height): Buffer = onRenderThread {
        focus.clearFocusables()
        router.clear()
        val area = Rect.of(width, height)
        val buffer = Buffer.empty(area)
        val root = app.render()
        root.render(Frame.forTesting(buffer), area, context)
        context.registerElement(root, area)
        val order = focus.focusOrder()
        if (order.isNotEmpty() && focus.focusedId() !in order) focus.setFocus(order[0])
        buffer
    }

    /** Renders a frame at this size and returns its text, one line per row. */
    fun screen(width: Int = this.width, height: Int = this.height): String {
        val buffer = frame(width, height)
        return buildString {
            for (y in 0 until height) {
                for (x in 0 until width) append(buffer.get(x, y).symbol())
                append('\n')
            }
        }
    }

    /** Routes a key or mouse event as the runner does, then renders a frame. */
    fun press(event: Event) {
        router.route(event)
        frame()
    }

    fun press(code: KeyCode) = press(KeyEvent.ofKey(code, KEY_BINDINGS))
    fun press(c: Char) = press(KeyEvent.ofChar(c, KEY_BINDINGS))
    fun ctrl(c: Char) = press(KeyEvent.ofChar(c, KeyModifiers.CTRL, KEY_BINDINGS))
    fun alt(c: Char) = press(KeyEvent.ofChar(c, KeyModifiers.ALT, KEY_BINDINGS))
    fun type(text: String) = text.forEach { press(it) }

    companion object {
        // TamboUI checks that rendering happens on its render thread; it marks that thread through a private API.
        fun <T> onRenderThread(block: () -> T): T {
            val thread = Class.forName("dev.tamboui.tui.RenderThread")
            val mark = thread.getDeclaredMethod("markAsRenderThread").apply { isAccessible = true }
            val clear = thread.getDeclaredMethod("clearRenderThread").apply { isAccessible = true }
            mark.invoke(null)
            try {
                return block()
            } finally {
                clear.invoke(null)
            }
        }
    }
}

/**
 * `text` with the focused pane's thick border drawn light, for tests that read what is inside a pane whichever pane
 * has focus. A test of the focus cue itself reads the screen as it is.
 */
internal fun lightBorders(text: String): String = text.map { c ->
    when (c) {
        '┏' -> '┌'
        '┓' -> '┐'
        '┗' -> '└'
        '┛' -> '┘'
        '━' -> '─'
        '┃' -> '│'
        else -> c
    }
}.joinToString("")
