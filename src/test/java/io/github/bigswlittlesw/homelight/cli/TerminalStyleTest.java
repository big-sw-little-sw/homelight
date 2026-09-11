package io.github.bigswlittlesw.homelight.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalStyleTest {
    @Test
    void preservesPlainOutputWhenColourIsDisabled() {
        var style = new TerminalStyle(true);

        assertEquals("Complete", style.success("Complete"));
        assertEquals("Problem", style.error("Problem"));
    }

    @Test
    void emitsTambouiAnsiStylesWhenColourIsEnabled() {
        var rendered = new TerminalStyle(false).success("Complete");

        assertTrue(rendered.contains("\u001B["));
        assertTrue(rendered.contains("Complete"));
    }
}
