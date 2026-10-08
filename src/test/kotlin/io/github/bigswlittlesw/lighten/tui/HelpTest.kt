package io.github.bigswlittlesw.lighten.tui

import dev.tamboui.markdown.MarkdownStyles
import dev.tamboui.toolkit.Toolkit
import dev.tamboui.tui.event.KeyCode
import dev.tamboui.tui.event.MouseButton
import dev.tamboui.tui.event.MouseEvent
import io.github.bigswlittlesw.lighten.application.LightenSession
import io.github.bigswlittlesw.lighten.application.userGuide
import io.github.bigswlittlesw.lighten.discovery.CandidateDiscovery
import io.github.bigswlittlesw.lighten.discovery.CandidateObservation
import io.github.bigswlittlesw.lighten.discovery.SetupDiscoveryFixture
import io.github.bigswlittlesw.lighten.pollUntil
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
        val ui = HeadlessTui(LightenSession(conflictConfiguration()))
        checkThisScreen(
            ui, WORKSPACE_NAME, PURPOSE_WORKSPACE, Step.WORKSPACE, "c", "PageUp/PageDown",
            pinned = mapOf(
                "q" to "Quit Lighten; asks first if choices are not applied or changes are running",
                "↑/↓" to "Select a relocation",
            ),
        )
        ui.press(KeyCode.TAB)
        checkThisScreen(ui, place(WORKSPACE_NAME, DETAILS_NAME), PURPOSE_WORKSPACE, Step.WORKSPACE, "↑/↓", "Tab/←")
        ui.press(KeyCode.ENTER)
        ui.press(KeyCode.ESCAPE)
        ui.press('a')
        checkThisScreen(ui, REVIEW_NAME, PURPOSE_REVIEW, Step.REVIEW, "y", "Home/End")

        val setup = HeadlessTui(LightenSession(temporary.resolve("new.json")))
        setup.press('i')
        checkThisScreen(setup, place(CONFIGURATION_NAME, TARGET_ROOT_LABEL), PURPOSE_CONFIGURATION, Step.CONFIGURE, "←/→", "Home/End")
        setup.press(KeyCode.ESCAPE)
        checkThisScreen(
            setup, CONFIGURATION_NAME, PURPOSE_CONFIGURATION, Step.CONFIGURE, "PageUp/PageDown",
            pinned = mapOf("s" to "Create the configuration file, then check again"),
        )
        setup.press('a')
        setup.press(KeyCode.DOWN)
        setup.press(KeyCode.DOWN)
        checkThisScreen(setup, place(CONFIGURATION_NAME, BOTH_EXIST_LABEL), PURPOSE_CONFIGURATION, Step.CONFIGURE)

        val empty = Files.writeString(
            temporary.resolve("empty.json"), "{\"lighten\": {\"target-root\": \"$temporary\", \"relocations\": []}}\n",
        )
        checkThisScreen(HeadlessTui(LightenSession(empty)), WORKSPACE_NAME, PURPOSE_NO_RELOCATIONS, Step.WORKSPACE, "r")
    }

    /**
     * Help › This screen stays true in every Browse view: on a category, on an app, on a row to add or remove, in
     * details and lists.
     */
    @Test
    fun thisScreenListsBrowseKeysInEveryView() {
        val home = Files.createDirectories(temporary.resolve("home"))
        Files.createDirectories(home.resolve(".m2"))
        val shared = Files.copy(Path.of("docs/research/session-b-fixtures/nested/shared.json"), temporary.resolve("shared.json"))
        val config = Files.writeString(
            temporary.resolve("config.json"),
            """{"lighten": {"source-root": "$home", "target-root": "${temporary.resolve("local")}", "suggestion-list": "$shared",
              "relocations": []}}""",
        )
        SetupDiscoveryFixture().use { workers ->
            val ui = HeadlessTui(LightenSession(config), discoveryFactory = workers::get)
            ui.press('e')
            ui.press('b')
            pollUntil("Discovery did not settle") {
                val result = workers.workers.last().snapshot()
                result.sources.none { it.status == CandidateDiscovery.SourceStatus.PENDING } &&
                    result.candidates.none { it.observation.kind == CandidateObservation.Kind.PENDING }
            }
            val browse = place(CONFIGURATION_NAME, BROWSE_NAME)
            checkThisScreen(
                ui, browse, PURPOSE_BROWSE, Step.CONFIGURE, "Home/End",
                pinned = mapOf("Space" to ADD_CATEGORY, "r" to CHECK_LISTS_AGAIN, "i" to SEE_LISTS),
            )
            ui.press(KeyCode.DOWN)
            checkThisScreen(ui, browse, PURPOSE_BROWSE, Step.CONFIGURE, pinned = mapOf("Space" to ADD_GROUP))
            ui.press(KeyCode.DOWN)
            checkThisScreen(ui, browse, PURPOSE_BROWSE, Step.CONFIGURE, pinned = mapOf("Space" to ADD_SUGGESTION, "Enter" to INSPECT_SUGGESTION))
            ui.press(' ')
            checkThisScreen(ui, browse, PURPOSE_BROWSE, Step.CONFIGURE, pinned = mapOf("Space" to REMOVE_SUGGESTION, "e" to EDIT_SUGGESTION))
            ui.press(KeyCode.ENTER)
            checkThisScreen(ui, browse, PURPOSE_BROWSE, Step.CONFIGURE, "[/]", pinned = mapOf("Esc" to BACK_TO_SUGGESTIONS))
            ui.press(KeyCode.ESCAPE)
            ui.press('i')
            checkThisScreen(ui, browse, PURPOSE_BROWSE, Step.CONFIGURE, "[/]", pinned = mapOf("Esc" to BACK_TO_SUGGESTIONS))
            ui.app.closeEditor()
        }
    }

    /**
     * Opens Help over the screen as it is and checks This screen: the place in the pane's title, the purpose, the
     * current step, the "Keys on" heading and its lead-in, and a `key  description` row for each key the help lines
     * show (Help's own key aside) and for the `extra` keys they leave out. `pinned` maps keys to the exact description
     * their row must have. Then Esc must return to the screen exactly as it was.
     */
    private fun checkThisScreen(
        ui: HeadlessTui, place: String, purpose: String, step: Step, vararg extra: String,
        pinned: Map<String, String> = mapOf(),
    ) {
        val before = ui.screen(80, 24)
        val focused = ui.focused()
        val shown = helpLines(before).filter { it != "?: Help" && it != "F1: Help" }
        // F1 opens Help from a text field too, where `?` would type.
        ui.press(KeyCode.F1)
        assertEquals(HELP_THIS_SCREEN, ui.focused(), place)
        // Tall enough to show the whole tab.
        val help = ui.screen(100, 80)
        val rows = help.lines()
        assertTrue(rows[0].startsWith("⌂ LIGHTEN  [Help]"), help)
        assertTrue(rows[1].contains(THIS_SCREEN_TAB) && rows[1].contains(GUIDE_TAB), help)
        assertTrue(rows[2].startsWith("┏$place━"), help)
        val text = paneText(help)
        assertTrue(text.contains(purpose), help)
        assertTrue(text.contains("$STEP: Configure › Workspace › Review › Apply › Results"), help)
        assertTrue(text.contains(keysOn(place) + " " + KEYS_LEAD_IN), help)
        assertTrue(paneRows(help).contains(MOVE_AROUND) && paneRows(help).contains(DO_KEYS), help)
        assertFalse(paneRows(help).any { it.startsWith("? ") }, "Help's own key is left out: $help")
        assertEquals(stepLabel(step), markedStep(ui), help)
        // A key row is the key, padding of at least two cells, then its description.
        fun description(keys: String) =
            paneRows(help).firstOrNull { row -> row.startsWith("$keys  ") }?.substringAfter("$keys ")?.trim()
        for (hint in shown) {
            val keys = hint.substringBefore(": ")
            assertFalse(description(keys).isNullOrEmpty(), "$place lists $hint: $help")
        }
        for (keys in extra) assertFalse(description(keys).isNullOrEmpty(), "$place lists $keys: $help")
        for ((keys, expected) in pinned) assertEquals(expected, description(keys), "$place: $keys")
        ui.press(KeyCode.ESCAPE)
        assertEquals(focused, ui.focused(), place)
        assertEquals(before, ui.screen(80, 24), place)
    }

    /** The step in the focus color on the "Step:" row of the open Help screen. */
    private fun markedStep(ui: HeadlessTui): String {
        val buffer = ui.frame(100, 80)
        val screen = ui.screen(100, 80).lines()
        val y = screen.indexOfFirst { it.contains("$STEP:") }
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
    fun tabSwitchesTabsAndEachKeepsItsScroll() {
        val ui = HeadlessTui(LightenSession(conflictConfiguration()))
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
        assertTrue(guide.lines()[2].startsWith("┏$GUIDE_TAB━"), guide)
        assertTrue(paneText(guide).contains("Lighten frees space in your home directory."), guide)
        ui.press(KeyCode.PAGE_DOWN)
        val paged = ui.screen(80, 24)
        // A page keeps one line of context: the last line of the first page is now the first.
        assertEquals(paneRows(guide).last(), paneRows(paged).first(), paged)

        ui.press(KeyCode.TAB)
        assertEquals(thisScreen, ui.screen(80, 24))
        ui.press(KeyCode.TAB)
        assertEquals(paged, ui.screen(80, 24), "the guide keeps its scroll position")
        ui.press(KeyCode.END)
        assertTrue(paneText(ui.screen(80, 24)).contains("lighten guide | less"))

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

    /** At either edge of either tab the scroll keys do nothing; only Tab switches tabs. */
    @Test
    fun scrollKeysAtAnEdgeKeepTheTab() {
        val ui = HeadlessTui(LightenSession(conflictConfiguration()))
        ui.press('?')
        for (tab in listOf(HELP_THIS_SCREEN, HELP_GUIDE)) {
            assertEquals(tab, ui.focused())
            for (key in listOf(KeyCode.HOME, KeyCode.UP, KeyCode.PAGE_UP, KeyCode.END, KeyCode.DOWN, KeyCode.PAGE_DOWN)) {
                ui.press(key)
                assertEquals(tab, ui.focused(), "$key")
            }
            ui.press(KeyCode.TAB)
        }
        // A tab that fits has nothing to scroll: each key is at both edges at once.
        val setup = HeadlessTui(LightenSession(temporary.resolve("new.json")))
        setup.press('i')
        setup.press(KeyCode.F1)
        setup.screen(120, 30)
        for (key in listOf(KeyCode.UP, KeyCode.DOWN, KeyCode.PAGE_UP, KeyCode.PAGE_DOWN, KeyCode.HOME, KeyCode.END)) {
            setup.press(key)
            assertEquals(HELP_THIS_SCREEN, setup.focused(), "$key")
        }
    }

    /** ← and → switch Help's tabs, like Tab, and each tab keeps its scroll position. */
    @Test
    fun leftAndRightSwitchTabsAndEachKeepsItsScroll() {
        val ui = HeadlessTui(LightenSession(conflictConfiguration()))
        ui.press('?')
        ui.press(KeyCode.DOWN)
        val thisScreen = ui.screen(80, 24)
        ui.press(KeyCode.RIGHT)
        assertEquals(HELP_GUIDE, ui.focused())
        ui.press(KeyCode.PAGE_DOWN)
        val guide = ui.screen(80, 24)
        ui.press(KeyCode.LEFT)
        assertEquals(HELP_THIS_SCREEN, ui.focused())
        assertEquals(thisScreen, ui.screen(80, 24))
        ui.press(KeyCode.LEFT)
        assertEquals(HELP_GUIDE, ui.focused())
        assertEquals(guide, ui.screen(80, 24))
    }

    /**
     * The mouse is captured for its wheel only. Wheel up and down at both edges of both tabs scroll or do nothing,
     * and never switch the tab; sideways scrolling, clicks and drags change nothing anywhere on the screen.
     */
    @Test
    fun theMouseOnlyScrollsAndNeverSwitchesTabs() {
        val ui = HeadlessTui(LightenSession(conflictConfiguration()))
        ui.press('?')
        for (tab in listOf(HELP_THIS_SCREEN, HELP_GUIDE)) {
            val top = ui.screen(80, 24)
            repeat(3) { ui.press(MouseEvent.scrollUp(20, 10)) }
            assertEquals(tab, ui.focused())
            assertEquals(top, ui.screen(80, 24), "already at the top")
            ui.press(MouseEvent.scrollDown(20, 10))
            assertEquals(tab, ui.focused())
            if (tab == HELP_GUIDE) assertNotEquals(top, ui.screen(80, 24), "the wheel scrolls")
            ui.press(KeyCode.END)
            val bottom = ui.screen(80, 24)
            repeat(3) { ui.press(MouseEvent.scrollDown(20, 10)) }
            assertEquals(tab, ui.focused())
            assertEquals(bottom, ui.screen(80, 24), "already at the bottom")
            for (event in listOf(
                MouseEvent.scrollLeft(20, 10), MouseEvent.scrollRight(20, 10),
                MouseEvent.press(MouseButton.LEFT, 5, 1), MouseEvent.release(MouseButton.LEFT, 5, 1),
                MouseEvent.press(MouseButton.LEFT, 20, 10), MouseEvent.drag(MouseButton.LEFT, 30, 12),
            )) {
                ui.press(event)
                assertEquals(tab, ui.focused(), "$event")
                assertEquals(bottom, ui.screen(80, 24), "$event")
            }
            ui.press(KeyCode.HOME)
            ui.press(KeyCode.TAB)
        }
    }

    /** On the Workspace the wheel scrolls Details or moves the list's selection, and clicks never move focus. */
    @Test
    fun theWheelScrollsThePaneUnderThePointerOnTheWorkspace() {
        val ui = HeadlessTui(LightenSession(conflictConfiguration()))
        val start = ui.screen(80, 24)
        // Over the list (left), the wheel moves the selection; focus stays on the list.
        ui.press(MouseEvent.scrollDown(5, 7))
        assertEquals(1, ui.app.selectedIndex())
        assertEquals(WORKSPACE_LIST, ui.focused())
        ui.press(MouseEvent.scrollUp(5, 7))
        assertEquals(0, ui.app.selectedIndex())
        // Over Details (right), it scrolls Details without focusing them.
        ui.press(MouseEvent.scrollDown(60, 10))
        assertEquals(WORKSPACE_LIST, ui.focused())
        assertNotEquals(start, ui.screen(80, 24))
        ui.press(MouseEvent.scrollUp(60, 10))
        assertEquals(start, ui.screen(80, 24))
        // A click on Details does not focus them; sideways scrolling does nothing.
        for (event in listOf(MouseEvent.press(MouseButton.LEFT, 60, 10), MouseEvent.release(MouseButton.LEFT, 60, 10),
            MouseEvent.scrollLeft(60, 10), MouseEvent.scrollRight(5, 7))) {
            ui.press(event)
            assertEquals(WORKSPACE_LIST, ui.focused(), "$event")
            assertEquals(start, ui.screen(80, 24), "$event")
        }
    }

    /** The focused pane has a thick border and the others a plain one, so focus shows without color. */
    @Test
    fun theFocusedPaneShowsWithoutColor() {
        val ui = HeadlessTui(LightenSession(conflictConfiguration()))
        var rows = ui.screen(80, 24).lines()
        val top = rows.first { it.contains("Relocations") }
        assertTrue(top.startsWith("┏Relocations"), top)
        assertTrue(top.contains("┌Details"), top)
        ui.press(KeyCode.TAB)
        rows = ui.screen(80, 24).lines()
        val after = rows.first { it.contains("Relocations") }
        assertTrue(after.startsWith("┌Relocations"), after)
        assertTrue(after.contains("┏Details"), after)
        // Choosing for the relocation that needs it makes the plan ready to review.
        ui.press(KeyCode.ENTER)
        ui.press('a')
        val review = ui.screen(80, 24).lines().first { it.contains("Plan") }
        assertTrue(review.startsWith("┏Plan") && review.contains("┌Details"), review)
    }

    /**
     * A file Lighten can't read: the Workspace and Help › This screen both say why and how to fix it, and the help
     * lines offer only the keys that work: `r`, `?` and `q`.
     */
    @Test
    fun anUnreadableConfigurationSaysWhyAndHowToFixIt() {
        val cases = mapOf(
            "lighten" to listOf("It isn't valid JSON: line 1, column 1 should start with \"{\" but starts with \"l\".", "correct that line"),
            """{"lighten": {"target-root": "/local", "relocations": [{"source-path": "/a", "existing": "move"}]}}""" to listOf(
                "Line 1: relocations[0] has an unknown setting \"existing\". Check its spelling or remove it.", "correct that line",
            ),
            """{"lighten": {"target-root": "local"}}""" to
                listOf("lighten.target-root: Use a full path, or one starting with ~/", "correct that setting"),
            """{"lighten": {"relocations": []}}""" to
                listOf("target-root is missing. Add it under \"lighten\".", "correct that setting"),
            """{"lighten": {"target-root": 5}}""" to
                listOf("Line 1: target-root should be text, but it is a number.", "correct that line"),
            """{"lighten": {"target-root": "/local", "relocations": [{"source-path": "/a", "when-adopting-target": "x"}]}}""" to
                listOf(
                    "relocations[0].when-adopting-target can't be \"x\". Use one of: prompt, discard-source, archive-source.",
                    "correct that setting",
                ),
        )
        for ((content, expected) in cases) {
            val (problem, correct) = expected
            val config = Files.writeString(temporary.resolve("config.json"), content)
            val explanation = listOf(
                "Lighten can't read ${displayPath(config)}", problem,
                "To fix it: open the file in a text editor, $correct, then press r to check again.",
                "To start over: rename or delete the file, then press r. Lighten then offers i to create a new one.",
            )
            val ui = HeadlessTui(LightenSession(config))
            // Wide enough that no sentence wraps inside the temporary path.
            val workspace = paneText(ui.screen(200, 24))
            for (line in explanation) assertTrue(workspace.contains(line), "$line\n$workspace")
            assertEquals(listOf("r: Check again", "?: Help", "q: Quit"), helpLines(ui.screen(80, 24)), content)
            ui.press('?')
            assertEquals(HELP_THIS_SCREEN, ui.focused(), content)
            val thisScreen = paneText(ui.screen(200, 60))
            for (line in explanation) assertTrue(thisScreen.contains(line), "$line\n$thisScreen")
            assertTrue(thisScreen.contains(explanation.last() + " $STEP: "), thisScreen)
        }
    }

    @Test
    fun aFirstRunOpensTheGuide() {
        val ui = HeadlessTui(LightenSession(temporary.resolve("missing.json")))
        val empty = ui.screen(80, 24)
        assertTrue(empty.contains(FIRST_RUN_HINT), empty)
        ui.press('?')
        assertEquals(HELP_GUIDE, ui.focused())
        val guide = ui.screen(80, 24)
        assertTrue(paneText(guide).contains("Lighten frees space"), guide)
        ui.press(KeyCode.TAB)
        assertTrue(paneText(ui.screen(100, 60)).contains(PURPOSE_NO_CONFIGURATION))
    }

    @Test
    fun f1OpensHelpFromATextFieldWhereQuestionMarkTypes() {
        val ui = HeadlessTui(LightenSession(temporary.resolve("new.json")))
        ui.press('i')
        ui.type("/srv/what?")
        val field = ui.screen(80, 24)
        assertTrue(field.contains("  /srv/what?"), field)
        assertFalse(field.contains("[Help]"), field)
        assertTrue(helpLines(field).contains("F1: Help"), field)
        assertFalse(helpLines(field).contains("?: Help"), field)

        ui.press(KeyCode.F1)
        assertTrue(ui.screen(80, 24).startsWith("⌂ LIGHTEN  [Help]"))
        // Still no configuration file, but Help opened from Configuration starts on This screen.
        assertEquals(HELP_THIS_SCREEN, ui.focused())
        // At this size This screen fits, so no scroll key is offered.
        val fits = ui.screen(120, 30).lines()
        assertTrue(fits[28].isBlank(), fits.joinToString("\n"))
        assertEquals("Tab/←/→: Other tab · Esc/q: Back to Configuration", fits[29].trimEnd(), fits.joinToString("\n"))
        val help = ui.screen(100, 60)
        val targetRoot = place(CONFIGURATION_NAME, "Target root")
        assertTrue(help.lines()[2].startsWith("┏$targetRoot━"), help)
        assertTrue(paneText(help).contains(keysOn(targetRoot) + " " + KEYS_LEAD_IN), help)
        assertFalse(paneRows(help).any { it.startsWith("F1 ") }, "Help's own key is left out: $help")
        val esc = paneRows(help).single { it.startsWith("Esc ") }.substringAfter("Esc ").trim()
        assertEquals("Back to the list", esc)
        ui.press(KeyCode.F1)
        assertEquals(field, ui.screen(80, 24))
    }

    /** As in less, man and other help screens, `q` leaves Help: it never quits Lighten or discards a draft. */
    @Test
    fun qInHelpGoesBack() {
        val ui = HeadlessTui(LightenSession(conflictConfiguration()))
        val workspace = ui.screen(80, 24)
        ui.press('?')
        ui.press('q')
        assertFalse(ui.app.exitRequested())
        assertEquals(workspace, ui.screen(80, 24))

        val setup = HeadlessTui(LightenSession(temporary.resolve("new.json")))
        setup.press('i')
        setup.type("/srv")
        setup.press(KeyCode.ESCAPE)
        val table = setup.screen(80, 24)
        setup.press('?')
        val help = setup.screen(80, 24)
        assertEquals("Tab/←/→: Other tab · Esc/q: Back to Configuration", help.lines()[23].trimEnd(), help)
        setup.press('q')
        assertEquals(table, setup.screen(80, 24), "no discard question")
        assertFalse(setup.app.exitRequested())

        // From a text field too, where `q` would type: Help takes it, and the field is unchanged.
        setup.press(KeyCode.ENTER)
        val locations = setup.screen(80, 24)
        setup.press(KeyCode.F1)
        setup.press('q')
        assertEquals(locations, setup.screen(80, 24))
    }

    /** Ctrl+C quits from Help as it does everywhere, so a draft or unapplied choices still get their question. */
    @Test
    fun ctrlCInHelpQuitsThroughTheUsualQuestions() {
        val plain = HeadlessTui(LightenSession(conflictConfiguration()))
        plain.press('?')
        plain.ctrl('c')
        assertTrue(plain.app.exitRequested(), "no choices, nothing running: it quits at once")

        val choices = HeadlessTui(LightenSession(conflictConfiguration(temporary.resolve("second"))))
        choices.press(KeyCode.TAB)
        choices.press(KeyCode.ENTER)
        choices.press('?')
        choices.ctrl('c')
        assertFalse(choices.app.exitRequested())
        assertTrue(choices.screen(80, 24).contains("╔$QUIT_TITLE"))

        val setup = HeadlessTui(LightenSession(temporary.resolve("new.json")))
        setup.press('i')
        setup.type("/srv")
        setup.press(KeyCode.F1)
        setup.ctrl('c')
        assertFalse(setup.app.exitRequested())
        assertTrue(setup.screen(80, 24).contains("╔$DISCARD_SETUP_TITLE"))
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
        val ui = HeadlessTui(LightenSession(temporary.resolve("missing.json")))
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
        val ui = HeadlessTui(LightenSession(temporary.resolve("missing.json")))
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
            Path.of("src/main/resources/META-INF/native-image/io.github.bigswlittlesw/lighten/reachability-metadata.json"),
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
    private fun paneRows(screen: String): List<String> = lightBorders(screen).lines().filter { it.startsWith("│") }
        .map { row -> row.removePrefix("│").removeSuffix("│").trimEnd('│', '█', ' ') }

    /** The Help pane's text with wrapped lines joined, for matching sentences. */
    private fun paneText(screen: String): String = paneRows(screen).joinToString(" ").replace(Regex("\\s+"), " ")

    /** One relocation that needs a choice, one to move and one in sync. */
    private fun conflictConfiguration(directory: Path = temporary): Path {
        val root = Files.createDirectories(directory).toRealPath()
        for (path in listOf("home/both", "local/both", "home/move", "local/synced")) Files.createDirectories(root.resolve(path))
        Files.createSymbolicLink(root.resolve("home/synced"), root.resolve("local/synced"))
        val relocations = listOf("both", "move", "synced").joinToString(",\n") { name ->
            "{\"source-path\": \"${root.resolve("home/$name")}\", \"target-path\": \"${root.resolve("local/$name")}\"}"
        }
        return Files.writeString(
            root.resolve("config.json"),
            "{\"lighten\": {\"target-root\": \"${root.resolve("local")}\", \"relocations\": [\n$relocations\n]}}\n",
        )
    }
}
