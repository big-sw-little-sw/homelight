package io.github.bigswlittlesw.lighten.tui

import dev.tamboui.text.CharWidth
import dev.tamboui.toolkit.Toolkit
import dev.tamboui.toolkit.element.Element
import dev.tamboui.toolkit.event.EventResult
import io.github.bigswlittlesw.lighten.config.ConfigurationChangedException
import io.github.bigswlittlesw.lighten.config.ConfigurationException
import io.github.bigswlittlesw.lighten.config.ConfigurationPublisher

/**
 * A TamboUI dialog that asks one question: `y` confirms, `n` or Esc cancels, and it takes every other key. `warning`
 * lines follow the body in the warning color.
 *
 * While it is open, the screen behind must render non-focusable and show no help: TamboUI moves focus on Tab before
 * any element sees the key, so a focusable element behind the dialog would take focus from it.
 */
internal fun confirmDialog(
    title: String, body: List<String>, keys: String, onYes: () -> Unit, onNo: () -> Unit,
    warning: List<String> = listOf(),
): Element {
    // One blank cell of padding on every side.
    val rows = listOf(Toolkit.text("")) + body.map { Toolkit.text(" $it") } +
        (if (warning.isEmpty()) listOf() else listOf(Toolkit.text("")) + warning.map { Toolkit.text(" $it").fg(palette.warn) }) +
        listOf(Toolkit.text(""), Toolkit.text(" $keys").fg(palette.dim), Toolkit.text(""))
    // DialogElement lays its children out without their widths or its padding, so the width is set here: the widest
    // line and its padding, plus the border.
    val width = (body + warning + keys + title).maxOf { CharWidth.of(it) } + 4
    return Toolkit.dialog(title, *rows.toTypedArray())
        .doubleBorder().borderColor(palette.dialog).width(width)
        .id(DIALOG).focusable()
        .onKeyEvent { key ->
            when {
                key.isChar('y') -> { onYes(); EventResult.HANDLED }
                key.isCharIgnoreCase('n') -> { onNo(); EventResult.HANDLED }
                // DialogElement then cancels on Esc and takes every other key, Enter included: no onConfirm is set.
                else -> EventResult.UNHANDLED
            }
        }
        .onCancel(onNo)
}

/**
 * Runs `write`, a [ConfigurationPublisher] save that a dialog's `y` started, and says why nothing was saved, or
 * returns null when it was saved: `changed` when the file changed since it was read. Configuration and the Workspace
 * share it.
 */
internal fun saveProblem(changed: String, write: () -> Unit): String? = try {
    write()
    null
} catch (_: ConfigurationChangedException) {
    changed
} catch (error: ConfigurationException) {
    notSaved(error.text.shown())
} catch (error: IllegalArgumentException) {
    notSaved(error.message.orEmpty())
}
