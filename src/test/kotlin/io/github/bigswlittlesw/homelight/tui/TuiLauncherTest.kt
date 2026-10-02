package io.github.bigswlittlesw.homelight.tui

import dev.tamboui.layout.Position
import dev.tamboui.layout.Size
import dev.tamboui.terminal.AbstractBackend
import dev.tamboui.tui.TuiConfig
import dev.tamboui.tui.event.KeyEvent
import io.github.bigswlittlesw.homelight.application.ApplyModel
import io.github.bigswlittlesw.homelight.application.HomeLightSession
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletionException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class TuiLauncherTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun refusesNonInteractiveAndDumbTerminalsBeforeOpeningOne() {
        assertEquals(NOT_INTERACTIVE, terminalRefusal(false, "xterm-256color"))
        assertEquals(NOT_INTERACTIVE, terminalRefusal(false, "dumb"))
        assertEquals(DUMB_TERMINAL, terminalRefusal(true, "dumb"))
        assertEquals(DUMB_TERMINAL, terminalRefusal(true, "dumb-color"))
        assertNull(terminalRefusal(true, "xterm-256color"))
        assertNull(terminalRefusal(true, null))
    }

    @Test
    fun closesTerminalExactlyOnceAfterNormalAndExceptionalSettlement() {
        for (exceptional in booleanArrayOf(false, true)) {
            val root = Files.createDirectory(temporary.resolve("case-$exceptional"))
            val session = HomeLightSession(HomeLightExitTest.configuration(root))
            session.requestApply()
            val completion = session.confirmApply(Executor(Runnable::run))
            val result = session.applyModel()
            if (exceptional) completion.obtrudeException(IllegalStateException("settlement failed"))
            val backend = LifecycleBackend()
            val app = HomeLightApp(session, config(backend))
            app.handleKeyEvent(KeyEvent.ofChar('q'))
            if (exceptional) {
                assertThrows<CompletionException> { app.run() }
            } else {
                app.run()
            }
            assertSame(result, session.applyModel())
            assertEquals(listOf("raw", "alternate", "hide", "hide", "show", "leave", "close"), backend.lifecycle)
            assertTrue(Files.isSymbolicLink(root.resolve("source")))
        }
    }

    @Test
    fun renderingFailureWaitsForActiveWorkBeforeRestoringTerminal() {
        val failRendering = AtomicBoolean()
        val waiting = CountDownLatch(1)
        val session = object : HomeLightSession(HomeLightExitTest.configuration(temporary)) {
            @Synchronized override fun applyModel(): ApplyModel {
                if (failRendering.get()) throw IllegalStateException("render fixture failure")
                return super.applyModel()
            }
            override fun awaitExecution() {
                waiting.countDown()
                super.awaitExecution()
            }
        }
        session.requestApply()
        val tasks = mutableListOf<Runnable>()
        val completion = session.confirmApply(Executor { tasks.add(it) })
        val backend = LifecycleBackend()
        val app = HomeLightApp(session, config(backend))
        failRendering.set(true)
        val thrown = AtomicReference<Throwable>()
        val stopped = CountDownLatch(1)
        val ui = Thread.ofPlatform().start {
            try {
                app.run()
            } catch (failure: Throwable) {
                thrown.set(failure)
            } finally {
                stopped.countDown()
            }
        }
        try {
            assertTrue(waiting.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("raw", "alternate", "hide"), backend.lifecycle)
            assertFalse(completion.isDone)
            assertTrue(ui.isAlive)
        } finally {
            failRendering.set(false)
            tasks.first().run()
        }
        assertTrue(stopped.await(5, TimeUnit.SECONDS))
        assertEquals("Unable to render HomeLight", thrown.get().message)
        assertEquals(listOf("raw", "alternate", "hide", "show", "leave", "close"), backend.lifecycle)
        assertTrue(assertInstanceOf(ApplyModel.Result::class.java, session.applyModel()).succeeded())
        assertFalse(completion.isCancelled)
        assertTrue(Files.isSymbolicLink(temporary.resolve("source")))
    }

    private class LifecycleBackend : AbstractBackend() {
        val lifecycle: MutableList<String> = CopyOnWriteArrayList()

        override fun flush() { }
        override fun clear() { }
        override fun size(): Size = Size(80, 24)
        override fun showCursor() { lifecycle.add("show") }
        override fun hideCursor() { lifecycle.add("hide") }
        override fun getCursorPosition(): Position = Position(0, 0)
        override fun enterAlternateScreen() { lifecycle.add("alternate") }
        override fun leaveAlternateScreen() { lifecycle.add("leave") }
        override fun enableRawMode() { lifecycle.add("raw") }
        override fun disableRawMode() { }
        override fun writeRaw(data: String) { }
        override fun onResize(handler: Runnable) { }
        override fun read(timeoutMs: Int): Int {
            try {
                Thread.sleep(Math.max(1, timeoutMs).toLong())
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            return -1
        }
        override fun peek(timeoutMs: Int): Int = -1
        override fun close() { lifecycle.add("close") }
    }

    private companion object {
        fun config(backend: LifecycleBackend): TuiConfig =
            TuiConfig.builder().backend(backend).shutdownHook(false).build()
    }
}
