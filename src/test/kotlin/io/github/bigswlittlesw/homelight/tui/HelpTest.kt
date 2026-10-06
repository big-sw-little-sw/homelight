package io.github.bigswlittlesw.homelight.tui

import dev.tamboui.markdown.MarkdownStyles
import dev.tamboui.toolkit.Toolkit
import dev.tamboui.tui.event.KeyCode
import io.github.bigswlittlesw.homelight.application.HomeLightSession
import io.github.bigswlittlesw.homelight.application.userGuide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class HelpTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun thisScreenListsEveryKeyTheHelpLinesShow() {
        val ui = HeadlessTui(HomeLightSession(conflictConfiguration()))
        checkThisScreen(ui, WORKSPACE_NAME, PURPOSE_WORKSPACE, Step.WORKSPACE, "c", "PageUp/PageDown")
        ui.press(KeyCode.TAB)
        checkThisScreen(ui, place(WORKSPACE_NAME, DETAILS_NAME), PURPOSE_WORKSPACE, Step.WORKSPACE, "↑/↓", "←")
        ui.press(KeyCode.ENTER)
        ui.press(KeyCode.ESCAPE)
        ui.press('a')
        checkThisScreen(ui, REVIEW_NAME, PURPOSE_REVIEW, Step.REVIEW, "y", "Home/End")

        val empty = Files.writeString(
            temporary.resolve("empty.json"), "{\"homelight\": {\"target-root\": \"$temporary\", \"relocations\": []}}\n",
        )
        checkThisScreen(HeadlessTui(HomeLightSession(empty)), WORKSPACE_NAME, PURPOSE_NO_RELOCATIONS, Step.WORKSPACE, "r")
    }

    /**
     * Opens Help over the screen as it is and checks This screen: the place in the pane's title, the purpose, the
     * current step, and a `key  action` row for each key the help lines show (Help's own key aside) and for the
     * `extra` keys they leave out. Then Esc must return to the screen exactly as it was.
     */
    private fun checkThisScreen(ui: HeadlessTui, place: String, purpose: String, step: Step, vararg extra: String) {
        val before = ui.screen(80, 24)
        val focused = ui.focused()
        val shown = helpLines(before).filter { it != "?: Help" }
        ui.press('?')
        assertEquals(HELP_THIS_SCREEN, ui.focused(), place)
        // Tall enough to show the whole tab.
        val help = ui.screen(100, 80)
        val rows = help.lines()
        assertTrue(rows[0].startsWith("⌂ HOMELIGHT  [Help]"), help)
        assertTrue(rows[1].contains(THIS_SCREEN_TAB) && rows[1].contains(GUIDE_TAB), help)
        assertTrue(rows[2].startsWith("┌$place─"), help)
        val text = paneText(help)
        assertTrue(text.contains(purpose), help)
        assertTrue(text.contains("$YOU_ARE_HERE: Configure › Workspace › Review › Apply › Results"), help)
        assertTrue(text.contains(MOVE_AROUND) && text.contains(DO_KEYS), help)
        assertFalse(paneRows(help).any { it.startsWith("? ") }, "Help's own key is left out: $help")
        assertEquals(stepLabel(step), markedStep(ui), help)
        fun listed(keys: String, action: String?) = paneRows(help).any { row ->
            row.startsWith("$keys ") && (action == null || row.substringAfter("$keys ").trim() == action)
        }
        for (hint in shown) {
            val (keys, action) = hint.split(": ", limit = 2)
            assertTrue(listed(keys, action), "$place lists $hint: $help")
        }
        for (keys in extra) assertTrue(listed(keys, null), "$place lists $keys: $help")
        ui.press(KeyCode.ESCAPE)
        assertEquals(focused, ui.focused(), place)
        assertEquals(before, ui.screen(80, 24), place)
    }

    /** The step in the focus color on the "You are here" row of the open Help screen. */
    private fun markedStep(ui: HeadlessTui): String {
        val buffer = ui.frame(100, 80)
        val screen = ui.screen(100, 80).lines()
        val y = screen.indexOfFirst { it.contains("$YOU_ARE_HERE:") }
        val row = screen[y]
        fun cells(label: String): List<dev.tamboui.style.Style> {
            val x = row.indexOf(" $label", row.indexOf(":")) + 1
            return label.indices.map { i -> buffer.get(x + i, y).style() }
        }
        // Without color (a monochrome terminal) the step must still stand out: only the current one is bold.
        val bold = Step.entries.map(::stepLabel).filter { label ->
            cells(label).all { dev.tamboui.style.Modifier.BOLD in it.effectiveModifiers() }
        }
        val colored = Step.entries.map(::stepLabel).filter { label -> cells(label).all { it.fg().orElse(null) == palette.focus } }
        assertEquals(colored, bold, row)
        return bold.single()
    }

    @Test
    fun tabAndArrowsSwitchTabsAndEachKeepsItsScroll() {
        val ui = HeadlessTui(HomeLightSession(conflictConfiguration()))
        ui.press(KeyCode.DOWN)
        val before = ui.screen(80, 24)
        ui.press('?')
        val thisScreen = ui.screen(80, 24)
        val rows = thisScreen.lines()
        assertTrue(rows[22].startsWith("↑/↓/[/]: Scroll · PageUp/PageDown: Page · Home/End: Top/bottom"), thisScreen)
        assertEquals("Tab/←/→: Other tab · Esc/q: Back to Workspace", rows[23].trimEnd(), thisScreen)

        assertTabBar(ui, open = THIS_SCREEN_TAB, other = GUIDE_TAB)
        ui.press(KeyCode.TAB)
        assertEquals(HELP_GUIDE, ui.focused())
        assertTabBar(ui, open = GUIDE_TAB, other = THIS_SCREEN_TAB)
        val guide = ui.screen(80, 24)
        assertTrue(guide.lines()[2].startsWith("┌$GUIDE_TAB─"), guide)
        assertTrue(paneText(guide).contains("HomeLight frees space in your home directory."), guide)
        ui.press(KeyCode.PAGE_DOWN)
        val paged = ui.screen(80, 24)
        // A page keeps one line of context: the last line of the first page is now the first.
        assertEquals(paneRows(guide).last(), paneRows(paged).first(), paged)

        ui.press(KeyCode.LEFT)
        assertEquals(thisScreen, ui.screen(80, 24))
        ui.press(KeyCode.RIGHT)
        assertEquals(paged, ui.screen(80, 24), "the guide keeps its scroll position")
        ui.press(KeyCode.END)
        assertTrue(paneText(ui.screen(80, 24)).contains("homelight guide | less"))

        for (c in listOf('a', 'r', 'c', '2', 'i', 'y', '1')) ui.press(c)
        ui.press(KeyCode.ENTER)
        assertEquals(HELP_GUIDE, ui.focused())

        ui.press(KeyCode.F1)
        assertEquals(before, ui.screen(80, 24))
        assertEquals(1, ui.app.selectedIndex(), "the selection is kept")
        assertEquals(WORKSPACE_LIST, ui.focused())
    }

    /** The open tab is bold in the focus color; the other is dim. */
    private fun assertTabBar(ui: HeadlessTui, open: String, other: String) {
        val buffer = ui.frame(80, 24)
        val bar = ui.screen(80, 24).lines()[1]
        val openCell = buffer.get(bar.indexOf(open), 1).style()
        assertEquals(palette.focus, openCell.fg().orElse(null), bar)
        assertTrue(dev.tamboui.style.Modifier.BOLD in openCell.effectiveModifiers(), bar)
        val otherCell = buffer.get(bar.indexOf(other), 1).style()
        assertEquals(palette.dim, otherCell.fg().orElse(null), bar)
        // Without color the open tab still stands out: only it is bold.
        assertFalse(dev.tamboui.style.Modifier.BOLD in otherCell.effectiveModifiers(), bar)
    }

    @Test
    fun aFirstRunOpensTheGuide() {
        val ui = HeadlessTui(HomeLightSession(temporary.resolve("missing.json")))
        val empty = ui.screen(80, 24)
        assertTrue(empty.contains(FIRST_RUN_HINT), empty)
        ui.press('?')
        assertEquals(HELP_GUIDE, ui.focused())
        val guide = ui.screen(80, 24)
        assertTrue(paneText(guide).contains("HomeLight frees space"), guide)
        ui.press(KeyCode.TAB)
        assertTrue(paneText(ui.screen(100, 60)).contains(PURPOSE_NO_CONFIGURATION))
    }

    @Test
    fun f1OpensHelpFromATextFieldWhereQuestionMarkTypes() {
        val ui = HeadlessTui(HomeLightSession(temporary.resolve("new.json")))
        ui.press('i')
        ui.press(KeyCode.DOWN)
        ui.type("/srv/what?")
        val field = ui.screen(80, 24)
        assertTrue(field.contains("Target root: /srv/what?"), field)
        assertFalse(field.contains("[Help]"), field)
        assertTrue(helpLines(field).contains("F1: Help"), field)
        assertFalse(helpLines(field).contains("?: Help"), field)

        ui.press(KeyCode.F1)
        assertTrue(ui.screen(80, 24).startsWith("⌂ HOMELIGHT  [Help]"))
        // Still no configuration file, but Help opened from Configuration starts on This screen.
        assertEquals(HELP_THIS_SCREEN, ui.focused())
        // At this size This screen fits, so no scroll key is offered.
        val fits = ui.screen(120, 30).lines()
        assertTrue(fits[28].isBlank(), fits.joinToString("\n"))
        assertEquals("Tab/←/→: Other tab · Esc/q: Back to Configuration", fits[29].trimEnd(), fits.joinToString("\n"))
        val help = ui.screen(100, 60)
        assertTrue(help.lines()[2].startsWith("┌" + place(CONFIGURATION_NAME, "Target root") + "─"), help)
        assertFalse(paneRows(help).any { it.startsWith("F1 ") }, "Help's own key is left out: $help")
        ui.press(KeyCode.F1)
        assertEquals(field, ui.screen(80, 24))
    }

    /** As in less, man and other help screens, `q` leaves Help: it never quits HomeLight or discards a draft. */
    @Test
    fun qInHelpGoesBack() {
        val ui = HeadlessTui(HomeLightSession(conflictConfiguration()))
        val workspace = ui.screen(80, 24)
        ui.press('?')
        ui.press('q')
        assertFalse(ui.app.exitRequested())
        assertEquals(workspace, ui.screen(80, 24))

        val setup = HeadlessTui(HomeLightSession(temporary.resolve("new.json")))
        setup.press('i')
        setup.press(KeyCode.ENTER)
        val table = setup.screen(80, 24)
        setup.press('?')
        val help = setup.screen(80, 24)
        assertEquals("Tab/←/→: Other tab · Esc/q: Back to Configuration", help.lines()[23].trimEnd(), help)
        setup.press('q')
        assertEquals(table, setup.screen(80, 24), "no discard question")
        assertFalse(setup.app.exitRequested())

        // From a text field too, where `q` would type: Help takes it, and the field is unchanged.
        setup.press('e')
        val locations = setup.screen(80, 24)
        setup.press(KeyCode.F1)
        setup.press('q')
        assertEquals(locations, setup.screen(80, 24))
    }

    @Test
    fun theGuideIsForUsers() {
        val guide = userGuide()
        assertFalse(Regex("#\\d").containsMatchIn(guide), "no ticket numbers")
        assertFalse(guide.contains("PR "), "no pull requests")
        assertFalse(guide.contains("issue", ignoreCase = true), "no issues")
        val wide = guide.lines().filter { it.length > 78 }
        assertTrue(wide.isEmpty(), "lines over 78 columns: $wide")
    }

    /** TamboUI wraps between a code span and the punctuation after it, which would leave the punctuation alone. */
    @Test
    fun noGuideLineStartsWithPunctuationAtEitherSize() {
        val ui = HeadlessTui(HomeLightSession(temporary.resolve("missing.json")))
        ui.press('?')
        for ((width, height) in listOf(80 to 24, 120 to 30)) {
            // Page through at the real size, so the wrap width leaves room for the scrollbar as it does on screen.
            ui.press(KeyCode.HOME)
            val rows = generateSequence(paneRows(ui.screen(width, height))) { page ->
                ui.press(KeyCode.PAGE_DOWN)
                paneRows(ui.screen(width, height)).takeIf { it != page }
            }.flatten().toList()
            val orphans = rows.map { it.trimStart() }.filter { Regex("^[.,;:)](\\s|$)").containsMatchIn(it) }
            assertTrue(orphans.isEmpty(), "at ${width}x$height: $orphans")
        }
    }

    @Test
    fun theGuideRendersAsMarkdown() {
        val ui = HeadlessTui(HomeLightSession(temporary.resolve("missing.json")))
        ui.press('?')
        val text = paneRows(ui.screen(100, 400))
        assertTrue(text.contains("Free space on this machine"), text.toString())
        assertFalse(text.any { it.startsWith("#") }, "headings render without their markers: $text")
    }

    /**
     * Rendering Markdown with an HTML entity reads CommonMark's entities resource, which Native Image needs
     * registered. The guide has no entity, so this fixture keeps the rendering checked, and the registered paths must
     * exist where the registration says.
     */
    @Test
    fun entitiesRenderAndEveryRegisteredResourceExists() {
        val viewport = DetailViewport()
        val view = viewport.markdown("Fixture", "Save &amp; quit, then &copy; and &#x41;.", MarkdownStyles.DEFAULTS, "fixture", true)
        val screen = WorkspaceViewTest.render(Toolkit.column(view).fill(), 60, 5)
        assertTrue(screen.contains("Save & quit, then © and A."), screen)

        val metadata = Files.readString(
            Path.of("src/main/resources/META-INF/native-image/io.github.bigswlittlesw/homelight/reachability-metadata.json"),
        )
        val globs = Regex("\"glob\": \"([^\"*]+)\"").findAll(metadata).map { it.groupValues[1] }.toList()
        assertTrue("org/commonmark/internal/util/entities.txt" in globs, metadata)
        for (glob in globs) assertNotNull(javaClass.classLoader.getResource(glob), glob)
    }

    /** The two help lines of an 80x24 screen, split into their `keys: action` hints. */
    private fun helpLines(screen: String): List<String> = screen.lines().subList(22, 24)
        .flatMap { it.trim().split(" · ") }
        .map { it.replace("↑/↓/[/]: Scroll", "↑/↓: Scroll") }.filter { it.isNotEmpty() && it != "[/]: Scroll" }

    /** The rows inside the Help pane's border, without the scrollbar. */
    private fun paneRows(screen: String): List<String> = screen.lines().filter { it.startsWith("│") }
        .map { row -> row.removePrefix("│").removeSuffix("│").trimEnd('│', '█', ' ') }

    /** The Help pane's text with wrapped lines joined, for matching sentences. */
    private fun paneText(screen: String): String = paneRows(screen).joinToString(" ").replace(Regex("\\s+"), " ")

    /** One relocation that needs a choice, one to move and one in sync. */
    private fun conflictConfiguration(): Path {
        val root = temporary.toRealPath()
        for (path in listOf("home/both", "local/both", "home/move", "local/synced")) Files.createDirectories(root.resolve(path))
        Files.createSymbolicLink(root.resolve("home/synced"), root.resolve("local/synced"))
        val relocations = listOf("both", "move", "synced").joinToString(",\n") { name ->
            "{\"source-path\": \"${root.resolve("home/$name")}\", \"target-path\": \"${root.resolve("local/$name")}\"}"
        }
        return Files.writeString(
            root.resolve("config.json"),
            "{\"homelight\": {\"target-root\": \"${root.resolve("local")}\", \"relocations\": [\n$relocations\n]}}\n",
        )
    }
}
