package io.github.bigswlittlesw.homelight.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TerminalStyleTest {
    @Test
    fun preservesPlainOutputWhenColourIsDisabled() {
        val style = TerminalStyle(true)

        assertEquals("Complete", style.success("Complete"))
        assertEquals("Problem", style.error("Problem"))
    }

    @Test
    fun emitsTambouiAnsiStylesWhenColourIsEnabled() {
        val rendered = TerminalStyle(false).success("Complete")

        assertTrue(rendered.contains("\u001B["))
        assertTrue(rendered.contains("Complete"))
    }
}
