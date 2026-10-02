package io.github.bigswlittlesw.homelight.tui

import dev.tamboui.buffer.Buffer
import dev.tamboui.layout.Rect
import dev.tamboui.terminal.Frame
import dev.tamboui.toolkit.element.RenderContext
import dev.tamboui.tui.event.KeyCode
import dev.tamboui.tui.event.KeyEvent
import dev.tamboui.tui.event.KeyModifiers
import io.github.bigswlittlesw.homelight.application.ApplyModel
import io.github.bigswlittlesw.homelight.application.HomeLightSession
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
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
        val app = HomeLightApp(HomeLightSession(config))
        for (screen in Screen.values()) {
            app.switchScreen(screen)
            key(app, KeyCode.ESCAPE)
            assertEquals(Screen.WORKSPACE, app.activeScreen)
            assertFalse(app.exitRequested())
        }
        app.switchScreen(Screen.WORKSPACE)
        app.handleKeyEvent(KeyEvent.ofChar('a', KEY_BINDINGS))
        key(app, KeyCode.ESCAPE)
        assertEquals(Screen.WORKSPACE, app.activeScreen)
        assertInstanceOf(ApplyModel.Idle::class.java, app.session.applyModel())
        assertFalse(Files.exists(temporary.resolve("source")))

        Files.createDirectories(temporary.resolve("target"))
        app.handleKeyEvent(KeyEvent.ofChar('r', KEY_BINDINGS))
        key(app, KeyCode.TAB)
        assertEquals(PaneFocus.DETAIL, app.paneFocus())
        key(app, KeyCode.ESCAPE)
        assertEquals(PaneFocus.MASTER, app.paneFocus())
        assertTrue(app.session.hasConflicts())
        assertFalse(app.exitRequested())
    }

    @Test
    fun completionNeverSelectsExitOrDismissesAnOpenDialog() {
        for (selectExit in booleanArrayOf(false, true)) {
            val root = Files.createDirectory(temporary.resolve("case-$selectExit"))
            val app = HomeLightApp(HomeLightSession(configuration(root)))
            app.switchScreen(Screen.APPLY)
            val tasks = mutableListOf<Runnable>()
            app.session.confirmApply(Executor { tasks.add(it) })
            app.handleKeyEvent(KeyEvent.ofChar('q', KEY_BINDINGS))
            if (selectExit) key(app, KeyCode.DOWN)
            tasks.first().run()
            for (width in intArrayOf(80, 120)) {
                val text = render(app, width, if (width == 80) 24 else 30)
                assertTrue(text.contains("Quit HomeLight?"), text)
                assertTrue(text.contains("❯ " + (if (selectExit) "Exit when execution finishes" else "Keep running")), text)
                assertTrue(text.contains("won't remain available after exit"), text)
                assertFalse(app.exitRequested())
            }
            key(app, KeyCode.ENTER)
            assertEquals(selectExit, app.exitRequested())
            assertEquals(1, tasks.size)
            assertTrue(Files.isSymbolicLink(root.resolve("source")))
            if (!selectExit) {
                key(app, KeyCode.ESCAPE)
                assertEquals(Screen.APPLY, app.activeScreen)
                assertFalse(app.exitRequested())
            }
        }
    }

    @Test
    fun dialogDefaultsToKeepRunningAndEscapeCancelsEvenAfterSelectingExit() {
        val app = HomeLightApp(HomeLightSession(configuration(temporary)))
        app.switchScreen(Screen.APPLY)
        val tasks = mutableListOf<Runnable>()
        app.session.confirmApply(Executor { tasks.add(it) })
        val ctrlC = KeyEvent.ofChar('c', KeyModifiers.CTRL, KEY_BINDINGS)
        assertTrue(ctrlC.isQuit)
        app.handleKeyEvent(ctrlC)
        key(app, KeyCode.ENTER)
        assertFalse(render(app, 80, 24).contains("Quit HomeLight?"))
        assertFalse(app.exitRequested())
        app.handleKeyEvent(KeyEvent.ofChar('q', KEY_BINDINGS))
        key(app, KeyCode.TAB)
        key(app, KeyCode.ESCAPE)
        tasks.first().run()
        app.render()
        assertFalse(app.exitRequested())
        assertTrue(assertInstanceOf(ApplyModel.Result::class.java, app.session.applyModel()).succeeded())
    }

    @Test
    fun deferredExitWaitsForWorkerAndOnlyTheUiRequestsExit() {
        val app = HomeLightApp(HomeLightSession(configuration(temporary)))
        app.switchScreen(Screen.APPLY)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val interrupted = AtomicBoolean()
        val completion = app.session.confirmApply(Executor { task ->
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
            app.handleKeyEvent(KeyEvent.ofChar('q', KEY_BINDINGS))
            key(app, KeyCode.DOWN)
            key(app, KeyCode.ENTER)
            for (c in "qyra123") app.handleKeyEvent(KeyEvent.ofChar(c, KEY_BINDINGS))
            key(app, KeyCode.ESCAPE)
            assertTrue(render(app, 80, 24).contains("Will exit after execution settles"))
            assertFalse(app.exitRequested())
            assertFalse(completion.isDone)
            assertFalse(Files.exists(temporary.resolve("source")))
        } finally {
            release.countDown()
        }
        completion.get(5, TimeUnit.SECONDS)
        assertFalse(app.exitRequested(), "worker must not touch UI exit intent")
        app.render()
        assertTrue(app.exitRequested())
        app.render()
        assertTrue(app.exitRequested())
        assertFalse(interrupted.get())
        assertFalse(completion.isCancelled)
        assertTrue(Files.isSymbolicLink(temporary.resolve("source")))
    }

    @Test
    fun publishedResultDoesNotMeanCompletionHasSettled() {
        val settlement = CompletableFuture<Void>()
        val session = object : HomeLightSession(configuration(temporary)) {
            @Synchronized override fun executionSettled(): Boolean = settlement.isDone
        }
        session.requestApply()
        session.confirmApply(Executor(Runnable::run)).join()
        val app = HomeLightApp(session)
        app.handleKeyEvent(KeyEvent.ofChar('q', KEY_BINDINGS))
        key(app, KeyCode.DOWN)
        key(app, KeyCode.ENTER)
        app.render()
        assertFalse(app.exitRequested())
        settlement.completeExceptionally(IllegalStateException("refresh failed"))
        app.render()
        assertTrue(app.exitRequested())
        assertTrue(assertInstanceOf(ApplyModel.Result::class.java, session.applyModel()).succeeded())
    }

    @Test
    fun rejectionWhileQuitDialogOpensKeepsTheDefaultAndNeverReschedules() {
        val app = HomeLightApp(HomeLightSession(configuration(temporary)))
        app.switchScreen(Screen.APPLY)
        val completion = app.session.confirmApply(Executor {
            app.handleKeyEvent(KeyEvent.ofChar('q', KEY_BINDINGS))
            throw RejectedExecutionException("worker unavailable")
        })
        assertTrue(completion.isDone)
        assertTrue(render(app, 80, 24).contains("❯ Keep running"))
        assertFalse(app.exitRequested())
        key(app, KeyCode.ENTER)
        app.handleKeyEvent(KeyEvent.ofChar('y', KEY_BINDINGS))
        assertSame(completion, app.session.confirmApply(Executor { fail<Unit>("Must not reschedule") }))
        assertFalse(assertInstanceOf(ApplyModel.Result::class.java, app.session.applyModel()).succeeded())
        assertFalse(Files.exists(temporary.resolve("source")))
    }

    @Test
    fun deferredExitIncludesFailureAndExceptionalCompletionWithRetainedEvidence() {
        val app = HomeLightApp(HomeLightSession(configuration(temporary)))
        app.switchScreen(Screen.APPLY)
        val tasks = mutableListOf<Runnable>()
        val completion = app.session.confirmApply(Executor { tasks.add(it) })
        app.handleKeyEvent(KeyEvent.ofChar('q', KEY_BINDINGS))
        key(app, KeyCode.DOWN)
        key(app, KeyCode.ENTER)
        Files.createDirectories(temporary.resolve("target"))
        tasks.first().run()
        val result = assertInstanceOf(ApplyModel.Result::class.java, app.session.applyModel())
        assertFalse(result.succeeded())
        assertTrue(result.stale)
        val failure = IllegalStateException("exceptional settlement")
        completion.obtrudeException(failure)
        app.render()
        assertTrue(app.exitRequested())
        assertSame(result, app.session.applyModel())
        assertSame(failure, assertThrows<CompletionException> { app.session.awaitExecution() }.cause)
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

        private fun key(app: HomeLightApp, code: KeyCode) {
            app.handleKeyEvent(KeyEvent.ofKey(code, KEY_BINDINGS))
        }

        private fun render(app: HomeLightApp, width: Int, height: Int): String {
            val marker = Class.forName("dev.tamboui.tui.RenderThread").getDeclaredMethod("markAsRenderThread")
            val clear = Class.forName("dev.tamboui.tui.RenderThread").getDeclaredMethod("clearRenderThread")
            marker.isAccessible = true
            clear.isAccessible = true
            marker.invoke(null)
            try {
                val area = Rect.of(width, height)
                val buffer = Buffer.empty(area)
                app.render().render(Frame.forTesting(buffer), area, RenderContext.empty())
                val text = StringBuilder()
                for (y in 0 until height) {
                    for (x in 0 until width) text.append(buffer.get(x, y).symbol())
                    text.append('\n')
                }
                return text.toString()
            } finally {
                clear.invoke(null)
            }
        }
    }
}
