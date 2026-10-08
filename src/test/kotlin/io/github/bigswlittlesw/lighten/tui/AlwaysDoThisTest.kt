package io.github.bigswlittlesw.lighten.tui

import dev.tamboui.tui.event.KeyCode
import io.github.bigswlittlesw.lighten.application.ConfigurationEvaluation
import io.github.bigswlittlesw.lighten.application.DecisionChoice
import io.github.bigswlittlesw.lighten.application.LightenSession
import io.github.bigswlittlesw.lighten.config.ConfigurationLoader
import io.github.bigswlittlesw.lighten.config.WhenAdoptingTarget
import io.github.bigswlittlesw.lighten.config.WhenOnlyTargetExists
import io.github.bigswlittlesw.lighten.config.WhenSourceAndTargetDirectoriesExist
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** `s: Always do this` on the Workspace saves a one-time choice as the relocation's rule (tui-design §5). */
class AlwaysDoThisTest {
    @TempDir lateinit var temporary: Path
    private lateinit var root: Path
    private lateinit var config: Path

    /** One relocation whose source and target are both directories, so it offers the Both exist choices. */
    @BeforeEach
    fun fixture() {
        root = temporary.toRealPath()
        for (name in listOf("home/cache", "local/cache")) {
            Files.writeString(Files.createDirectories(root.resolve(name)).resolve("file"), name)
        }
        config = Files.writeString(root.resolve("config.json"), file())
    }

    @Test
    fun theHelpLineOffersSOnlyWithAChoiceAndFitsAt80Columns() {
        val ui = HeadlessTui(LightenSession(config))
        ui.press(KeyCode.TAB)
        assertFalse(ui.screen().contains(ALWAYS_DO_THIS), ui.screen())
        ui.press(' ')
        // Details scroll at 80x24, so `[/]` joins the line too; it still fits, and the commands are on the next row.
        for (focus in listOf(WORKSPACE_DETAILS, WORKSPACE_LIST)) {
            assertEquals(focus, ui.focused())
            val rows = ui.screen().lines()
            assertTrue(rows[22].contains(ALWAYS_DO_THIS) && rows[22].contains("[/]: Scroll"), rows.joinToString("\n"))
            assertTrue(rows[23].startsWith("a: Review & apply"), rows.joinToString("\n"))
            ui.press(KeyCode.ESCAPE)
        }
    }

    @Test
    fun savingWritesTheRuleAndTheRuleThenDecides() {
        val ui = chosen()
        ui.press('s')
        val dialog = squeezed(ui.screen(200, 50))
        for (line in alwaysDoThis(root.resolve("home/cache"), choice = chosenChoice, config = config) + ALWAYS_DO_THIS_KEYS) {
            assertTrue(dialog.contains(squeezed(line)), "$line\n$dialog")
        }
        assertTrue(dialog.contains(squeezed("keep target, delete source")), dialog)
        assertTrue(dialog.contains(squeezed("Comments in it are not kept.")), dialog)
        // Deleting the source is for good, so the dialog warns, in the warning color.
        for (line in alwaysDoThisWarning(chosenChoice)) assertTrue(dialog.contains(squeezed(line)), "$line\n$dialog")
        val rows = ui.screen(200, 50).lines()
        val y = rows.indexOfFirst { it.contains("⚠ This rule deletes") }
        assertEquals(palette.warn, ui.frame(200, 50).get(rows[y].indexOf("⚠"), y).style().fg().orElse(null))
        // At 80 columns the dialog, warning included, fits.
        val narrow = ui.screen().lines()
        assertTrue(narrow.any { it.contains("including with lighten apply --yes.") }, narrow.joinToString("\n"))
        assertEquals(file(), Files.readString(config))

        ui.press('y')
        val saved = ConfigurationLoader().read(config).file.relocations.single()
        assertEquals(WhenSourceAndTargetDirectoriesExist.ADOPT, saved.whenSourceAndTargetDirectoriesExist)
        assertEquals(WhenAdoptingTarget.DISCARD_SOURCE, saved.whenAdoptingTarget)
        assertEquals(WhenOnlyTargetExists.PROMPT, saved.whenOnlyTargetExists)
        // Checked again: the rule decides, so the one-time choice is gone and `s` has nothing to save.
        assertTrue(draft(ui).isEmpty())
        val after = ui.screen(200, 50)
        assertTrue(squeezed(after).contains(squeezed(ruleDecision("Keep target, delete source"))), after)
        assertFalse(after.contains("this run only"), after)
        assertTrue(after.contains("Saved. 1 relocation will change: press a to review and apply."), after)
        assertFalse(after.contains(ALWAYS_DO_THIS), after)
        // Back on the list, with Details from the top, so the Decision line shows at 80x24 too.
        assertEquals(WORKSPACE_LIST, ui.focused())
        assertTrue(squeezed(ui.screen()).contains(squeezed("Decision: keep target, delete source")), ui.screen())
    }

    @Test
    fun cancellingKeepsTheFileAndTheChoice() {
        val ui = chosen()
        for (cancel in listOf({ ui.press('n') }, { ui.press(KeyCode.ESCAPE) })) {
            ui.press('s')
            assertTrue(ui.screen().contains(ALWAYS_DO_THIS_TITLE))
            cancel()
            assertFalse(ui.screen().contains(ALWAYS_DO_THIS_TITLE))
            assertEquals(WORKSPACE_DETAILS, ui.focused())
            assertEquals(file(), Files.readString(config))
            assertEquals(1, draft(ui).size)
        }
    }

    @Test
    fun aFileChangedSinceItWasReadIsNotReplacedAndTheChoiceStays() {
        val ui = chosen()
        val changed = file().replace("\"relocations\"", "\"relocations\" ")
        Files.writeString(config, changed)
        ui.press('s')
        ui.press('y')
        assertEquals(changed, Files.readString(config))
        assertEquals(1, draft(ui).size)
        val refused = ui.screen(200, 50)
        assertTrue(squeezed(refused).contains(squeezed(CHOICE_NOT_SAVED)), refused)
    }

    @Test
    fun onlyARuleThatDeletesDataWarns() {
        assertEquals(2, alwaysDoThisWarning(DecisionChoice.DISCARD_BOTH).size)
        for (keeps in listOf(DecisionChoice.ADOPT_AND_ARCHIVE_SOURCE, DecisionChoice.LEAVE_UNCHANGED, DecisionChoice.ADOPT_TARGET)) {
            assertTrue(alwaysDoThisWarning(keeps).isEmpty(), keeps.toString())
        }
        val ui = chosen()
        ui.press(KeyCode.DOWN)
        ui.press(' ')
        assertEquals(DecisionChoice.ADOPT_AND_ARCHIVE_SOURCE, draft(ui).values.single())
        ui.press('s')
        val dialog = ui.screen(200, 50)
        assertTrue(dialog.contains(ALWAYS_DO_THIS_TITLE) && !dialog.contains("This rule deletes"), dialog)
    }

    /** Help lists `s` under Do, though the help line shows it beside the choice keys. */
    @Test
    fun helpListsSUnderDo() {
        val ui = chosen()
        ui.press('?')
        val rows = ui.screen(100, 80).lines()
        val move = rows.indexOfFirst { it.contains(MOVE_AROUND) }
        val doKeys = rows.indexOfFirst { it.contains(DO_KEYS) }
        val s = rows.indexOfFirst { it.contains("s  ") && it.contains("Save the choice as this relocation's rule") }
        assertTrue(move in 0 until doKeys && doKeys < s, rows.joinToString("\n"))
    }

    /** Leave both as they are, picked while the rule already says so, saves nothing, so `s` is not offered. */
    @Test
    fun aChoiceTheRuleAlreadyMakesCannotBeSaved() {
        Files.writeString(config, file(", \"when-source-and-target-directories-exist\": \"leave-unchanged\""))
        val ui = HeadlessTui(LightenSession(config))
        ui.press(KeyCode.TAB)
        ui.press(' ')
        assertEquals(1, draft(ui).size)
        assertTrue(ui.screen(200, 50).contains("this run only"))
        assertFalse(ui.screen().contains(ALWAYS_DO_THIS), ui.screen())
        ui.press('s')
        assertFalse(ui.screen().contains(ALWAYS_DO_THIS_TITLE), ui.screen())
    }

    private val chosenChoice = DecisionChoice.ADOPT_AND_DISCARD_SOURCE

    /** Details focused, with the first choice, Keep target, delete source, picked for this run. */
    private fun chosen(): HeadlessTui {
        val ui = HeadlessTui(LightenSession(config))
        ui.press(KeyCode.TAB)
        ui.press(' ')
        assertEquals(mapOf(root.resolve("home/cache") to chosenChoice), draft(ui))
        return ui
    }

    private fun draft(ui: HeadlessTui) =
        assertInstanceOf(ConfigurationEvaluation.Loaded::class.java, ui.app.session.evaluation()).draft

    private fun file(rule: String = "") = """
        {"lighten": {
          "target-root": "${root.resolve("local")}",
          "relocations": [
            {"source-path": "${root.resolve("home/cache")}", "target-path": "${root.resolve("local/cache")}"$rule}
          ]
        }}
        """.trimIndent() + "\n"

    /** Without spaces, so a line Details or a dialog wrapped still matches. */
    private fun squeezed(text: String) = text.replace(Regex("\\s+"), "").replace("║", "").replace("│", "").replace("┃", "")

    private companion object {
        const val ALWAYS_DO_THIS = "s: Always do this"
    }
}
