package io.github.bigswlittlesw.homelight.tui

import dev.tamboui.tui.event.KeyCode
import dev.tamboui.tui.event.KeyEvent
import dev.tamboui.tui.event.KeyModifiers
import io.github.bigswlittlesw.homelight.application.ApplyModel
import io.github.bigswlittlesw.homelight.application.HomeLightSession
import io.github.bigswlittlesw.homelight.pollUntil
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class HomeLightExitTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun escapeNavigatesWithoutExitingOrMutating() {
        val config = configuration(temporary)
        val ui = HeadlessTui(HomeLightSession(config))
        for (screen in Screen.values()) {
            ui.app.switchScreen(screen)
            ui.press(KeyCode.ESCAPE)
            assertEquals(Screen.WORKSPACE, ui.app.activeScreen)
            assertFalse(ui.app.exitRequested())
        }
        ui.app.switchScreen(Screen.WORKSPACE)
        ui.press('a')
        ui.press(KeyCode.ESCAPE)
        assertEquals(Screen.WORKSPACE, ui.app.activeScreen)
        assertInstanceOf(ApplyModel.Idle::class.java, ui.app.session.applyModel())
        assertFalse(Files.exists(temporary.resolve("source")))

        Files.createDirectories(temporary.resolve("target"))
        ui.press('r')
        ui.press(KeyCode.TAB)
        assertEquals(WORKSPACE_DETAILS, ui.focused())
        ui.press(KeyCode.ESCAPE)
        assertEquals(WORKSPACE_LIST, ui.focused())
        // At Workspace's list Escape does nothing: TamboUI would otherwise clear focus.
        ui.press(KeyCode.ESCAPE)
        assertEquals(WORKSPACE_LIST, ui.focused())
        assertTrue(ui.app.session.hasConflicts())
        assertFalse(ui.app.exitRequested())
    }

    @Test
    fun quittingWithUnappliedChoicesAsksFirstAndCountsThem() {
        val root = temporary.toRealPath()
        val names = listOf("one", "two")
        for (name in names) {
            Files.createDirectories(root.resolve("home/$name"))
            Files.createDirectories(root.resolve("local/$name"))
        }
        val relocations = names.joinToString(",\n") { name ->
            "{\"source-path\": \"${root.resolve("home/$name")}\", \"target-path\": \"${root.resolve("local/$name")}\"}"
        }
        val config = Files.writeString(root.resolve("config.json"),
            "{\"homelight\": {\"target-root\": \"${root.resolve("local")}\", \"relocations\": [\n$relocations\n]}}\n")
        val ui = HeadlessTui(HomeLightSession(config))
        fun choose() {
            ui.press(KeyCode.TAB)
            ui.press(KeyCode.ENTER)
        }

        choose()
        ui.press('q')
        for ((width, height) in listOf(80 to 24, 120 to 30)) {
            val text = ui.screen(width, height)
            assertTrue(text.contains("╔Quit HomeLight?"), text)
            assertTrue(text.contains("You have 1 choice that is not applied yet. Quitting forgets it."), text)
            assertTrue(text.contains("Press n to go back. You can keep choosing, or press a to review and apply."), text)
            assertTrue(text.contains("y: Quit · n/Esc: Go back"), text)
            assertEquals(DIALOG, ui.focused())
        }
        ui.press('n')
        assertFalse(ui.app.exitRequested())
        assertEquals(WORKSPACE_DETAILS, ui.focused())

        ui.press(KeyCode.ESCAPE)
        // Rows that still need a choice sort first.
        ui.press(KeyCode.HOME)
        choose()
        ui.press('q')
        val plural = ui.screen()
        assertTrue(plural.contains("You have 2 choices that are not applied yet. Quitting forgets them."), plural)
        ui.press(KeyCode.ESCAPE)
        assertFalse(ui.app.exitRequested())
        assertFalse(ui.screen().contains("Quit HomeLight?"))

        // Review keeps the choices unapplied, so it asks there too.
        ui.press('a')
        assertEquals(Screen.APPLY, ui.app.activeScreen)
        ui.press('q')
        assertTrue(ui.screen().contains("You have 2 choices"))
        ui.press('y')
        assertTrue(ui.app.exitRequested())
        names.forEach { name -> assertTrue(Files.isDirectory(root.resolve("home/$name"), java.nio.file.LinkOption.NOFOLLOW_LINKS)) }
    }

    @Test
    fun quittingWithoutChoicesExitsAtOnceEvenWithAPlanToApply() {
        val ui = HeadlessTui(HomeLightSession(configuration(temporary)))
        assertTrue(ui.app.session.isPlanReady())
        ui.press('q')
        assertTrue(ui.app.exitRequested())
    }

    @Test
    fun completionNeverAnswersOrDismissesTheQuitDialog() {
        for (answer in listOf('n', 'y')) {
            val root = Files.createDirectory(temporary.resolve("case-$answer"))
            val ui = HeadlessTui(HomeLightSession(configuration(root)))
            ui.app.switchScreen(Screen.APPLY)
            val tasks = mutableListOf<Runnable>()
            ui.app.session.confirmApply(Executor { tasks.add(it) })
            ui.press('q')
            tasks.first().run()
            for ((width, height) in listOf(80 to 24, 120 to 30)) {
                val text = ui.screen(width, height)
                assertTrue(text.contains("╔Quit HomeLight?"), text)
                assertTrue(text.contains("y: Exit when it finishes · n/Esc: Keep running"), text)
                assertTrue(text.contains("not kept after you exit"), text)
                assertEquals(DIALOG, ui.focused())
                assertFalse(ui.app.exitRequested())
            }
            ui.press(answer)
            assertEquals(answer == 'y', ui.app.exitRequested())
            assertEquals(1, tasks.size)
            assertTrue(Files.isSymbolicLink(root.resolve("source")))
            if (answer == 'n') {
                assertFalse(ui.screen().contains("Quit HomeLight?"))
                assertEquals(REVIEW_LIST, ui.focused())
                ui.press(KeyCode.ESCAPE)
                assertEquals(Screen.APPLY, ui.app.activeScreen)
                assertFalse(ui.app.exitRequested())
            }
        }
    }

    @Test
    fun theQuitDialogTakesEveryKeyAndEscapeReturnsToTheFocusedPane() {
        val ui = HeadlessTui(HomeLightSession(configuration(temporary)))
        ui.press('a')
        val tasks = mutableListOf<Runnable>()
        ui.app.session.confirmApply(Executor { tasks.add(it) })
        ui.press(KeyCode.TAB)
        assertEquals(REVIEW_DETAILS, ui.focused())
        val ctrlC = KeyEvent.ofChar('c', KeyModifiers.CTRL, KEY_BINDINGS)
        assertTrue(ctrlC.isQuit)
        ui.press(ctrlC)
        val open = ui.screen()
        assertTrue(open.contains("Quit HomeLight?"), open)
        // Help behind the dialog would advertise keys that do nothing.
        assertFalse(open.contains("q: Quit"), open)
        for (key in listOf(KeyCode.ENTER, KeyCode.TAB, KeyCode.DOWN, KeyCode.LEFT, KeyCode.HOME)) ui.press(key)
        for (c in "qQ12rajY ") ui.press(c)
        ui.press(ctrlC)
        assertEquals(DIALOG, ui.focused())
        assertEquals(open, ui.screen())
        assertFalse(ui.app.exitRequested())

        ui.press(KeyCode.ESCAPE)
        assertFalse(ui.screen().contains("Quit HomeLight?"))
        assertEquals(REVIEW_DETAILS, ui.focused())
        tasks.first().run()
        ui.frame()
        assertFalse(ui.app.exitRequested())
        assertTrue(assertInstanceOf(ApplyModel.Result::class.java, ui.app.session.applyModel()).succeeded())
    }

    @Test
    fun deferredExitWaitsForWorkerAndOnlyTheUiRequestsExit() {
        val ui = HeadlessTui(HomeLightSession(configuration(temporary)))
        ui.app.switchScreen(Screen.APPLY)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val interrupted = AtomicBoolean()
        val completion = ui.app.session.confirmApply(Executor { task ->
            Thread.ofPlatform().start {
                started.countDown()
                try {
                    release.await()
                    task.run()
                } catch (exception: InterruptedException) {
                    interrupted.set(true)
                }
            }
        })
        assertTrue(started.await(5, TimeUnit.SECONDS))
        try {
            ui.press('q')
            ui.press('y')
            for (c in "qyra123") ui.press(c)
            ui.press(KeyCode.ESCAPE)
            assertTrue(ui.screen().contains("HomeLight will exit when the changes finish."))
            assertFalse(ui.app.exitRequested())
            assertFalse(completion.isDone)
            assertFalse(Files.exists(temporary.resolve("source")))
        } finally {
            release.countDown()
        }
        completion.get(5, TimeUnit.SECONDS)
        assertFalse(ui.app.exitRequested(), "worker must not touch UI exit intent")
        ui.frame()
        assertTrue(ui.app.exitRequested())
        ui.frame()
        assertTrue(ui.app.exitRequested())
        assertFalse(interrupted.get())
        assertFalse(completion.isCancelled)
        assertTrue(Files.isSymbolicLink(temporary.resolve("source")))
    }

    @Test
    fun publishedResultDoesNotMeanCompletionHasSettled() {
        val session = HomeLightSession(configuration(temporary))
        session.requestApply()
        val ui = HeadlessTui(session)
        // The re-check after an apply takes the session's monitor, so holding it keeps a published result unsettled.
        val completion = synchronized(session) {
            val completion = session.confirmApply(Executor { task -> Thread.ofPlatform().start(task) })
            pollUntil("the result is published") { session.applyModel() is ApplyModel.Result }
            assertFalse(session.executionSettled())
            ui.press('q')
            ui.press('y')
            assertFalse(ui.app.exitRequested())
            completion
        }
        pollUntil("the re-check settles") { completion.isDone }
        ui.frame()
        assertTrue(ui.app.exitRequested())
        assertTrue(assertInstanceOf(ApplyModel.Result::class.java, session.applyModel()).succeeded())
    }

    @Test
    fun rejectionWhileQuitDialogOpensKeepsRunningAndNeverReschedules() {
        val ui = HeadlessTui(HomeLightSession(configuration(temporary)))
        ui.app.switchScreen(Screen.APPLY)
        val completion = ui.app.session.confirmApply(Executor {
            ui.press('q')
            throw RejectedExecutionException("worker unavailable")
        })
        assertTrue(completion.isDone)
        assertTrue(ui.screen().contains("Quit HomeLight?"))
        assertFalse(ui.app.exitRequested())
        ui.press('n')
        ui.press('y')
        assertSame(completion, ui.app.session.confirmApply(Executor { fail<Unit>("Must not reschedule") }))
        assertFalse(assertInstanceOf(ApplyModel.Result::class.java, ui.app.session.applyModel()).succeeded())
        assertFalse(Files.exists(temporary.resolve("source")))
    }

    @Test
    fun deferredExitIncludesFailureAndExceptionalCompletionWithRetainedEvidence() {
        val ui = HeadlessTui(HomeLightSession(configuration(temporary)))
        ui.app.switchScreen(Screen.APPLY)
        val tasks = mutableListOf<Runnable>()
        val completion = ui.app.session.confirmApply(Executor { tasks.add(it) })
        ui.press('q')
        ui.press('y')
        Files.createDirectories(temporary.resolve("target"))
        tasks.first().run()
        val result = assertInstanceOf(ApplyModel.Result::class.java, ui.app.session.applyModel())
        assertFalse(result.succeeded())
        assertTrue(result.stale)
        val failure = IllegalStateException("exceptional settlement")
        completion.obtrudeException(failure)
        ui.frame()
        assertTrue(ui.app.exitRequested())
        assertSame(result, ui.app.session.applyModel())
        assertSame(failure, assertThrows<CompletionException> { ui.app.session.awaitExecution() }.cause)
        assertFalse(Files.exists(temporary.resolve("source")))
    }

    companion object {
        fun configuration(root: Path): Path =
            Files.writeString(root.resolve("config.json"), ("""
                {"homelight": {
                  "target-root": "%s",
                  "relocations": [
                    {"source-path": "%s", "target-path": "%s"}
                  ]
                }}
                """.trimIndent() + "\n").format(root, root.resolve("source"), root.resolve("target")))
    }
}
