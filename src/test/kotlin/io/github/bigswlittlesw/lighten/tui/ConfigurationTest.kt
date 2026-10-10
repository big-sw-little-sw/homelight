package io.github.bigswlittlesw.lighten.tui

import dev.tamboui.tui.event.KeyCode
import io.github.bigswlittlesw.lighten.application.ApplyModel
import io.github.bigswlittlesw.lighten.application.ConfigurationEvaluation
import io.github.bigswlittlesw.lighten.application.LightenSession
import io.github.bigswlittlesw.lighten.config.CandidateCatalog
import io.github.bigswlittlesw.lighten.config.ConfigurationLoader
import io.github.bigswlittlesw.lighten.discovery.SetupDiscoveryFixture
import io.github.bigswlittlesw.lighten.config.WhenAdoptingTarget
import io.github.bigswlittlesw.lighten.config.WhenSourceAndTargetDirectoriesExist
import io.github.bigswlittlesw.lighten.application.PlanBadge
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Configuration creates and edits the file end to end, through real sessions and TamboUI's focus. */
class ConfigurationTest {
    @TempDir lateinit var temporary: Path

    /** The first-run journey: `i`, storage, a relocation, `s`; the Workspace checks again and says the next step. */
    @Test fun createsANewFileAndSaysTheNextStep() {
        val root = fixture()
        val config = root.resolve("new/config.json")
        val ui = HeadlessTui(LightenSession(config))
        ui.press('i')
        // A new file opens on its one required field.
        assertEquals("config-target-root", ui.focused())
        assertTrue(ui.screen(240, 50).contains("new file · no unsaved changes"), ui.screen(240, 50))
        ui.type(root.resolve("local").toString())
        ui.press(KeyCode.UP)
        ui.ctrl('u')
        ui.type(root.resolve("home").toString())
        ui.press(KeyCode.ESCAPE)
        // Leaving the storage locations of a new file opens Browse once; Esc goes back to the list.
        assertEquals(CONFIG_BROWSE, ui.focused())
        ui.press(KeyCode.ESCAPE)
        assertEquals(CONFIG_LIST, ui.focused())
        ui.press('a')
        // The source root is filled in, and the cursor is after it.
        assertEquals("config-source", ui.focused())
        ui.type("team-cache")
        val editing = ui.screen(240, 50)
        assertTrue(editing.contains("Target: " + root.resolve("local/team-cache")), editing)
        assertTrue(editing.contains("new file · 3 unsaved changes"), editing)
        ui.press(KeyCode.DOWN)
        ui.press(KeyCode.DOWN)
        repeat(5) { ui.press(KeyCode.RIGHT) }
        val discard = ui.screen(240, 50)
        assertTrue(discard.contains("‹ Delete both, start empty ›"), discard)
        assertTrue(discard.contains(DISCARD_BOTH_WARNING), discard)
        ui.press('s')

        assertTrue(ui.screen(240, 50).contains("[1: Workspace]"))
        val written = Files.readString(config)
        assertTrue(written.contains("\"source-path\": \"${root.resolve("home/team-cache")}\""), written)
        assertFalse(written.contains("target-path"), "a blank Target stays derived: $written")
        val plan = assertInstanceOf(ConfigurationEvaluation.Loaded::class.java, ui.app.session.evaluation())
        val source = root.resolve("home/team-cache")
        assertEquals(root.resolve("local/team-cache"), plan.savedConfiguration.relocations.single().targetPath)
        assertEquals(PlanBadge.MIGRATE, plan.items.single().badge())
        assertTrue(ui.screen(240, 50).contains(savedNextStep(1, 0, 0)), ui.screen(240, 50))
        assertEquals("unchanged", Files.readString(source.resolve("payload")), "saving changes nothing on disk")
        assertInstanceOf(ApplyModel.Idle::class.java, ui.app.session.applyModel())
        // Moving in the list keeps the message; the next command clears it.
        ui.press(KeyCode.DOWN)
        assertTrue(ui.screen(240, 50).contains("Saved."))
        ui.press('r')
        assertFalse(ui.screen(240, 50).contains("Saved."))
    }

    /**
     * On the first run, `i` opens Configuration when `HOME` is set and `user.home` is `?`, as the static musl binary
     * sets it for a user from LDAP or SSSD.
     */
    @Test fun createOpensWhenUserHomeIsUnusable() {
        val config = temporary.resolve("new/config.json")
        val real = System.getProperty("user.home")
        System.setProperty("user.home", "?")
        try {
            val ui = HeadlessTui(LightenSession(config))
            ui.press('i')

            val screen = ui.screen(240, 50)
            assertEquals("config-target-root", ui.focused(), screen)
            assertFalse(screen.contains("Internal error"), screen)
        } finally {
            System.setProperty("user.home", real)
        }
    }

    /** `e` opens the file as it is; `s` asks before replacing it and keeps what the editor does not show. */
    @Test fun editsAnExistingFileAndReplacesItAfterAsking() {
        val root = fixture()
        val config = existing(root)
        val ui = HeadlessTui(LightenSession(config))
        ui.press('e')
        assertEquals(CONFIG_LIST, ui.focused())
        assertTrue(ui.screen(240, 50).contains("existing file · no unsaved changes"), ui.screen(240, 50))
        ui.press(KeyCode.DOWN)
        ui.press(KeyCode.ENTER)
        ui.press(KeyCode.DOWN)
        ui.ctrl('u')
        ui.type(root.resolve("local/custom").toString())
        ui.press(KeyCode.DOWN)
        ui.press(KeyCode.RIGHT)
        assertTrue(ui.screen(240, 50).contains("existing file · 1 unsaved change"), ui.screen(240, 50))
        ui.press('s')
        val question = ui.screen(240, 50)
        assertTrue(question.contains("╔" + replaceConfigurationTitle(config)), question)
        assertTrue(question.contains(REPLACE_CONFIGURATION_KEYS), question)
        ui.press('n')
        assertEquals("config-both-exist", ui.focused(), "n goes back where the user was")
        assertTrue(Files.readString(config).contains("// Written by hand"), "nothing written yet")

        ui.press('s')
        ui.press('y')
        assertTrue(ui.screen(240, 50).contains("[1: Workspace]"))
        val read = ConfigurationLoader().read(config).file
        val relocation = read.relocations.single()
        assertEquals(root.resolve("local/custom").toString(), relocation.targetPath)
        assertEquals(WhenSourceAndTargetDirectoriesExist.ADOPT, relocation.whenSourceAndTargetDirectoriesExist)
        assertEquals(WhenAdoptingTarget.DISCARD_SOURCE, relocation.whenAdoptingTarget)
        // What the editor does not show is kept, and `~` stays as written.
        assertEquals(listOf("~/never-moved"), read.ignoredSourcePaths)
        assertEquals("~", read.sourceRoot)
        assertFalse(Files.readString(config).contains("// Written by hand"), "comments are not kept")
        assertTrue(ui.screen(240, 50).contains("Saved."), ui.screen(240, 50))
    }

    /** A file changed after Configuration opened it is never replaced; the draft stays for the user to decide. */
    @Test fun refusesToReplaceAFileChangedSinceItWasOpened() {
        val root = fixture()
        val config = existing(root)
        val ui = HeadlessTui(LightenSession(config))
        ui.press('e')
        ui.press(KeyCode.DOWN)
        ui.press(KeyCode.ENTER)
        ui.press(KeyCode.DOWN)
        ui.ctrl('u')
        ui.type(root.resolve("local/custom").toString())
        val changed = Files.readString(config).replace("Written by hand", "Changed by someone else")
        Files.writeString(config, changed)
        ui.press(KeyCode.ESCAPE)
        ui.press('s')
        ui.press('y')
        val refused = ui.screen(240, 50)
        assertTrue(refused.contains("[Configuration]"), refused)
        assertTrue(lightBorders(refused).replace("\n", " ").replace(Regex("\\s+"), " ")
            .contains(CHANGED_SINCE_LOADED.replace(Regex("\\s+"), " ")), refused)
        assertTrue(refused.contains("1 unsaved change"), refused)
        assertEquals(changed, Files.readString(config))
    }

    @Test fun closingAsksOnlyWithUnsavedChanges() {
        val root = fixture()
        val config = existing(root)
        val ui = HeadlessTui(LightenSession(config))
        ui.press('e')
        ui.press(KeyCode.ESCAPE)
        assertTrue(ui.screen(240, 50).contains("[1: Workspace]"), "nothing changed, so Esc closes at once")

        ui.press('e')
        ui.press(KeyCode.DOWN)
        ui.press('d')
        assertTrue(ui.screen(240, 50).contains("1 unsaved change"), ui.screen(240, 50))
        ui.press(KeyCode.ESCAPE)
        val question = ui.screen(240, 50)
        assertTrue(question.contains("╔$DISCARD_CHANGES_TITLE"), question)
        assertTrue(question.contains("Your 1 unsaved change will be lost."), question)
        ui.press('n')
        assertEquals(CONFIG_LIST, ui.focused())
        ui.press('q')
        ui.press('y')
        assertTrue(ui.screen(240, 50).contains("[1: Workspace]"))
        assertTrue(Files.readString(config).contains("// Written by hand"), "a discard writes nothing")

        // Changing a field back to what the file says is no change.
        ui.press('e')
        ui.press(KeyCode.ENTER)
        ui.press(KeyCode.DOWN)
        ui.type("x")
        assertTrue(ui.screen(240, 50).contains("1 unsaved change"))
        ui.press(KeyCode.BACKSPACE)
        assertTrue(ui.screen(240, 50).contains("no unsaved changes"), ui.screen(240, 50))
        ui.press(KeyCode.ESCAPE)
        ui.press(KeyCode.ESCAPE)
        assertTrue(ui.screen(240, 50).contains("[1: Workspace]"))

        // A new file asks once anything is typed.
        val fresh = HeadlessTui(LightenSession(root.resolve("fresh.json")))
        fresh.press('i')
        fresh.type("/srv")
        fresh.press(KeyCode.ESCAPE)
        // The first Esc to the list opens Browse.
        fresh.press(KeyCode.ESCAPE)
        fresh.press(KeyCode.ESCAPE)
        assertTrue(fresh.screen(240, 50).contains("╔$DISCARD_SETUP_TITLE"), fresh.screen(240, 50))
        fresh.press('y')
        assertFalse(Files.exists(root.resolve("fresh.json")))
    }

    /** In a text field every printable key types, `?` included; F1 opens Help, and Ctrl chords never act as letters. */
    @Test fun textFieldsTakeEveryLetterAndF1OpensHelp() {
        val root = fixture()
        val ui = HeadlessTui(LightenSession(existing(root)))
        ui.press('e')
        ui.press(KeyCode.DOWN)
        ui.press(KeyCode.ENTER)
        ui.press(KeyCode.DOWN)
        // Quit, Configuration's commands, Help and the former vim keys.
        val letters = "qQabdesr?[]hjklgGx "
        ui.type(letters)
        ui.ctrl('d')
        ui.alt('d')
        var screen = ui.screen(240, 50)
        assertTrue(screen.contains("team-cache$letters"), screen)
        assertEquals("config-target", ui.focused())
        assertFalse(screen.contains("[Help]") || screen.contains("╔"), screen)
        val help = screen.lines().takeLast(3).joinToString("\n")
        assertTrue(help.contains("F1: Help") && !help.contains("?: Help"), help)
        ui.ctrl('u')
        screen = ui.screen(240, 50)
        assertFalse(screen.contains(letters.trim()), "Ctrl+U clears: $screen")

        ui.press(KeyCode.F1)
        val thisScreen = ui.screen(100, 40)
        assertTrue(thisScreen.startsWith("⌂ LIGHTEN  [Help]"), thisScreen)
        assertTrue(thisScreen.contains(place(CONFIGURATION_NAME, TARGET_LABEL)), thisScreen)
        assertTrue(thisScreen.contains("Clear the field"), thisScreen)
        ui.press(KeyCode.F1)
        assertEquals("config-target", ui.focused())
    }

    /**
     * Both help lines stay whole at 80x24 on every focus: each is one row and says exactly what [ScreenHelp] holds,
     * however long the focused field's help is.
     */
    @Test fun bothHelpLinesFitAt80ColumnsOnEveryFocus() {
        val root = fixture()
        val ui = HeadlessTui(LightenSession(existing(root)))
        ui.press('e')
        val seen = mutableSetOf<String?>()
        repeat(2) { row ->
            repeat(7) {
                val screen = ui.screen(80, 24).lines()
                val help = checkNotNull(ui.app.configurationHelp())
                assertEquals(helpLine(help.navigation), screen[22].trimEnd(), screen.joinToString("\n"))
                assertEquals(helpLine(help.commands), screen[23].trimEnd(), screen.joinToString("\n"))
                seen.add(ui.focused())
                ui.press(KeyCode.TAB)
            }
            ui.press(KeyCode.ESCAPE)
            if (row == 0) ui.press(KeyCode.DOWN)
        }
        assertTrue(seen.containsAll(listOf(CONFIG_LIST, "config-source-root", "config-source", "config-both-exist")), seen.toString())
    }

    /**
     * Saving checks the draft as the loader would read it: the first field it rejects is selected and named in plain
     * words, and nothing is written until the draft loads.
     */
    @Test fun saveRefusesWhatTheLoaderRejectsAndSaysWhere() {
        val root = fixture()
        val config = root.resolve("config.json")
        val ui = HeadlessTui(LightenSession(config))
        ui.press('i')
        ui.press(KeyCode.ESCAPE)
        ui.press('a')
        ui.press(KeyCode.ESCAPE)
        ui.press('s')
        var screen = ui.screen(240, 50)
        assertTrue(screen.contains(notSavedIn(place(STORAGE_LOCATIONS, TARGET_ROOT_LABEL), "Target root must not be blank")), screen)
        assertTrue(screen.contains("❯ Storage locations"), "the row with the problem is selected: $screen")
        assertFalse(Files.exists(config))

        // A source outside the source root needs a Target.
        ui.press(KeyCode.ENTER)
        ui.press(KeyCode.DOWN)
        ui.type(root.resolve("local").toString())
        ui.press(KeyCode.ESCAPE)
        ui.press(KeyCode.DOWN)
        ui.press(KeyCode.ENTER)
        ui.ctrl('u')
        ui.type(root.resolve("elsewhere/cache").toString())
        ui.press(KeyCode.ESCAPE)
        ui.press('s')
        screen = ui.screen(240, 50)
        assertTrue(screen.contains(notSavedIn(place(root.resolve("elsewhere/cache").toString(), TARGET_LABEL), OUTSIDE_SOURCE_ROOT)), screen)
        assertFalse(Files.exists(config))

        // Overlapping relocations are refused as a whole.
        ui.press(KeyCode.ENTER)
        ui.press(KeyCode.DOWN)
        ui.type(root.resolve("local/cache").toString())
        ui.press(KeyCode.ESCAPE)
        ui.press('a')
        ui.ctrl('u')
        ui.type(root.resolve("elsewhere/cache/inner").toString())
        ui.press(KeyCode.DOWN)
        ui.type(root.resolve("local/inner").toString())
        ui.press(KeyCode.ESCAPE)
        ui.press('s')
        screen = ui.screen(240, 50)
        assertTrue(screen.contains("Not saved: relocation paths overlap"), screen)
        assertFalse(Files.exists(config))

        // The discard question takes only y; every other key leaves it open.
        ui.press('q')
        val question = ui.screen(240, 50)
        for (key in listOf(KeyCode.ENTER, KeyCode.TAB, KeyCode.DOWN)) ui.press(key)
        ui.press('d')
        assertEquals(question, ui.screen(240, 50))
        ui.press('y')
        assertFalse(Files.exists(config))
    }

    /** A choice changed and changed back is no change, on both choice fields, whatever the file's other rule says. */
    @Test fun choicesChangedBackAreNoChange() {
        val root = fixture()
        val config = Files.writeString(
            root.resolve("rules.json"),
            """
            {"lighten": {"source-root": "${root.resolve("home")}", "target-root": "${root.resolve("local")}", "relocations": [
              {"source-path": "${root.resolve("home/team-cache")}", "when-source-and-target-directories-exist": "leave-unchanged",
               "when-adopting-target": "archive-source"}]}}
            """.trimIndent(),
        )
        val ui = HeadlessTui(LightenSession(config))
        ui.press('e')
        ui.press(KeyCode.DOWN)
        ui.press(KeyCode.ENTER)
        ui.press(KeyCode.DOWN)
        ui.press(KeyCode.DOWN)
        // Both exist has six values and Only target two, so every step short of a full turn is a change.
        for ((field, turn) in listOf("config-both-exist" to 6, "config-only-target" to 2)) {
            assertEquals(field, ui.focused())
            for (steps in 1 until turn) {
                repeat(steps) { ui.press(KeyCode.RIGHT) }
                assertTrue(ui.screen(240, 50).contains("existing file · 1 unsaved change "), "$field $steps: " + ui.screen(240, 50))
                repeat(steps) { ui.press(KeyCode.LEFT) }
                assertTrue(ui.screen(240, 50).contains("no unsaved changes"), "$field $steps: " + ui.screen(240, 50))
            }
            ui.press(KeyCode.DOWN)
        }
        // Both changed counts the relocation once.
        ui.press(KeyCode.RIGHT)
        ui.press(KeyCode.UP)
        ui.press(KeyCode.RIGHT)
        assertTrue(ui.screen(240, 50).contains("1 unsaved change "), ui.screen(240, 50))
    }

    /** Each saved rule shows as its label, never as its value in the file. */
    @Test fun rulesShowTheirLabelsAndNoRawValues() {
        val root = fixture()
        val rules = listOf(
            "" to listOf(ASK_EACH_TIME, ASK_EACH_TIME),
            "\"when-source-and-target-directories-exist\": \"adopt\", \"when-adopting-target\": \"discard-source\", " +
                "\"when-only-target-exists\": \"adopt-target\"" to listOf("Keep target, delete source", "Keep target, link source"),
            "\"when-source-and-target-directories-exist\": \"adopt\", \"when-adopting-target\": \"archive-source\"" to
                listOf("Keep target, archive source", ASK_EACH_TIME),
            "\"when-source-and-target-directories-exist\": \"adopt\"" to listOf("Keep target, ask about source", ASK_EACH_TIME),
            "\"when-source-and-target-directories-exist\": \"leave-unchanged\", \"when-adopting-target\": \"archive-source\"" to
                listOf("Leave both as they are", ASK_EACH_TIME),
            "\"when-source-and-target-directories-exist\": \"discard\"" to listOf("Delete both, start empty", ASK_EACH_TIME),
        )
        val relocations = rules.mapIndexed { i, (fields, _) ->
            "{\"source-path\": \"${root.resolve("home/r$i")}\"" + (if (fields.isEmpty()) "" else ", $fields") + "}"
        }.joinToString(",\n")
        val config = Files.writeString(
            root.resolve("rules.json"),
            "{\"lighten\": {\"source-root\": \"${root.resolve("home")}\", \"target-root\": \"${root.resolve("local")}\", " +
                "\"relocations\": [\n$relocations\n]}}",
        )
        val ui = HeadlessTui(LightenSession(config))
        ui.press('e')
        for ((_, labels) in rules) {
            ui.press(KeyCode.DOWN)
            val screen = ui.screen(240, 50)
            assertEquals(labels, Regex("‹ ([^›]+) ›").findAll(screen).map { it.groupValues[1] }.toList(), screen)
            for (raw in listOf("prompt", "adopt", "leave-unchanged", "discard", "archive-source", "discard-source")) {
                assertFalse(screen.contains("‹ $raw"), screen)
            }
        }
        assertTrue(ui.screen(240, 50).contains("no unsaved changes"), "showing a rule changes nothing")
    }

    /** `lighten init` and `config` open the file that is there, for editing. */
    @Test fun openingOnConfigurationLoadsAnExistingFile() {
        val root = fixture()
        val ui = HeadlessTui(LightenSession(existing(root)), openConfiguration = true)
        val screen = ui.screen(240, 50)
        assertTrue(screen.contains("existing file · no unsaved changes"), screen)
        assertTrue(screen.contains("❯ Storage locations"), screen)
        assertEquals(CONFIG_LIST, ui.focused())
    }

    /**
     * A new file opens Browse once, with a note on typing what it does not list, when the user leaves the storage
     * locations for the list; Esc then returns to the list.
     */
    @Test fun aNewFileOpensBrowseOnceAfterTheStorageLocations() {
        val root = fixture()
        SetupDiscoveryFixture().use { workers ->
            val ui = HeadlessTui(LightenSession(root.resolve("new.json")), discoveryFactory = workers::get)
            ui.press('i')
            ui.type(root.resolve("local").toString())
            ui.press(KeyCode.UP)
            ui.ctrl('u')
            ui.type(root.resolve("home").toString())
            // Moving between the storage locations' fields does not open it, so a list of your own can be set first.
            ui.press(KeyCode.DOWN)
            ui.press(KeyCode.DOWN)
            assertEquals("config-suggestion-list", ui.focused())
            ui.press(KeyCode.TAB)
            assertEquals(CONFIG_BROWSE, ui.focused())
            val browse = ui.screen(80, 24).lines()
            assertTrue(browse[0].contains("[Configuration › Browse]"), browse.joinToString("\n"))
            // One row each at 80 columns, under the header and over the Lists lines.
            assertEquals(FIRST_BROWSE_NOTE, browse.subList(1, 3).map { it.trim() }, browse.joinToString("\n"))
            assertTrue(browse[3].startsWith("Built-in list"), browse.joinToString("\n"))
            ui.press(KeyCode.ESCAPE)
            assertEquals(CONFIG_LIST, ui.focused())
            assertTrue(ui.screen(80, 24).contains("❯ Storage locations"), ui.screen(80, 24))
            // Once only: leaving the fields again stays on the list, and b opens Browse without the note.
            ui.press(KeyCode.ENTER)
            ui.press(KeyCode.ESCAPE)
            assertEquals(CONFIG_LIST, ui.focused())
            ui.press('b')
            assertEquals(CONFIG_BROWSE, ui.focused())
            assertFalse(FIRST_BROWSE_NOTE.any { ui.screen(80, 24).contains(it) }, ui.screen(80, 24))
            ui.app.closeEditor()
        }
    }

    /** An existing file opens on its list as before, even with no relocations, and leaving its fields stays there. */
    @Test fun anExistingFileNeverOpensBrowseByItself() {
        val root = fixture()
        val config = Files.writeString(
            root.resolve("config.json"), "{\"lighten\": {\"target-root\": \"${root.resolve("local")}\", \"relocations\": []}}\n",
        )
        SetupDiscoveryFixture().use { workers ->
            val ui = HeadlessTui(LightenSession(config), discoveryFactory = workers::get)
            ui.press('e')
            assertEquals(CONFIG_LIST, ui.focused())
            ui.press(KeyCode.ENTER)
            ui.press(KeyCode.DOWN)
            ui.press(KeyCode.ESCAPE)
            assertEquals(CONFIG_LIST, ui.focused())
            assertFalse(ui.screen(80, 24).contains("[Configuration › Browse]"), ui.screen(80, 24))
            assertTrue(workers.workers.isEmpty(), "no suggestions were checked")
        }
    }

    /**
     * With no relocations the list says how to add one: `b` with the built-in list's count and apps, or `a`. From a
     * field the keys follow Esc, since there they type.
     */
    @Test fun anEmptyListOffersTheBuiltInSuggestionsAndTyping() {
        val root = fixture()
        val ui = HeadlessTui(LightenSession(root.resolve("new.json")))
        ui.press('i')
        val (count, examples) = builtInFromCatalog()
        assertTrue(listPane(ui).contains(flat(noRelocationsYet(count, examples, inList = false))), listPane(ui))
        assertTrue(listPane(ui).contains("Esc, then b to pick from $count built-in suggestions"), listPane(ui))
        ui.press(KeyCode.ESCAPE)
        assertEquals(CONFIG_LIST, ui.focused())
        assertTrue(listPane(ui).contains(flat(noRelocationsYet(count, examples, inList = true))), listPane(ui))
        ui.press('a')
        assertFalse(listPane(ui).contains("No directories yet."), listPane(ui))
    }

    /** Suggestion list's Details say the built-in list is always there, with its count, and what the field adds. */
    @Test fun suggestionListDetailsSayTheBuiltInListIsIncluded() {
        val root = fixture()
        val ui = HeadlessTui(LightenSession(existing(root)))
        ui.press('e')
        ui.press(KeyCode.ENTER)
        ui.press(KeyCode.DOWN)
        ui.press(KeyCode.DOWN)
        assertEquals("config-suggestion-list", ui.focused())
        val screen = ui.screen(120, 30)
        assertTrue(screen.contains(SUGGESTION_LIST_PLACEHOLDER), screen)
        val (count, examples) = builtInFromCatalog()
        assertTrue(examples.isNotEmpty())
        val details = flat(lightBorders(screen).lines().joinToString(" ") { it.substringAfter("││").substringBefore("│") })
        assertTrue(details.contains(flat(suggestionListHelp(count, examples))), screen)
    }

    /** The built-in list's directories, as Browse counts them, and the first app of each category. */
    private fun builtInFromCatalog(): Pair<Int, List<String>> {
        val definitions = CandidateCatalog.bundled(temporary).definitions
        assertTrue(definitions.isNotEmpty())
        return definitions.map { it.sourcePath }.distinct().size to
            definitions.filter { it.category != null }.distinctBy { it.category }.mapNotNull { it.app }.take(4)
    }

    /** The left pane's text at 80x24, its rows joined and whitespace collapsed. */
    private fun listPane(ui: HeadlessTui): String = flat(
        lightBorders(ui.screen(80, 24)).lines().filter { it.startsWith("│") }.joinToString(" ") { it.substring(1).substringBefore("│") },
    )

    private fun flat(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    private fun fixture(): Path {
        val root = Files.createTempDirectory(temporary, "fixture-").toRealPath()
        Files.createDirectories(root.resolve("home/team-cache"))
        Files.writeString(root.resolve("home/team-cache/payload"), "unchanged")
        return root
    }

    /** A hand-written file with a comment, `~` and a setting the editor does not show. */
    private fun existing(root: Path): Path = Files.writeString(
        root.resolve("config.json"),
        """
        {"lighten": {
          // Written by hand
          "target-root": "${root.resolve("local")}",
          "ignored-source-paths": ["~/never-moved"],
          "relocations": [{"source-path": "${root.resolve("home/team-cache")}", "target-path": "${root.resolve("local/team-cache")}"}]
        }}
        """.trimIndent(),
    )
}
