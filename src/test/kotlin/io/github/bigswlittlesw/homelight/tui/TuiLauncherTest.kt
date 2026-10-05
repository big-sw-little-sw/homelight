package io.github.bigswlittlesw.homelight.tui

import dev.tamboui.layout.Position
import dev.tamboui.layout.Size
import dev.tamboui.terminal.AbstractBackend
import dev.tamboui.tui.TuiConfig
import dev.tamboui.tui.event.KeyEvent
import io.github.bigswlittlesw.homelight.application.ApplyModel
import io.github.bigswlittlesw.homelight.application.ConfigurationEvaluation
import io.github.bigswlittlesw.homelight.application.HomeLightSession
import io.github.bigswlittlesw.homelight.pollUntil
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlanner
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentLinkedQueue
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
    fun runnerReadsKeysWithVimBindingsEvenWithACustomConfig() {
        assertSame(KEY_BINDINGS, tuiConfig().bindings())
        val backend = LifecycleBackend()
        val custom = tuiConfig(config(backend))
        assertSame(KEY_BINDINGS, custom.bindings())
        assertSame(backend, custom.backend())
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
            val backend = LifecycleBackend("q")
            if (exceptional) {
                assertThrows<CompletionException> { runTui(session, config(backend)) }
            } else {
                runTui(session, config(backend))
            }
            assertSame(result, session.applyModel())
            assertEquals(listOf("raw", "alternate", "hide", "hide", "show", "leave", "close"), backend.lifecycle)
            assertTrue(Files.isSymbolicLink(root.resolve("source")))
        }
    }

    @Test
    fun aFailedFrameWaitsForActiveWorkBeforeRestoringTerminal() {
        val session = HomeLightSession(HomeLightExitTest.configuration(temporary))
        session.requestApply()
        val tasks = mutableListOf<Runnable>()
        val completion = session.confirmApply(Executor { tasks.add(it) })
        val backend = LifecycleBackend(failFlush = IllegalStateException("render fixture failure"))
        val thrown = AtomicReference<Throwable>()
        val stopped = CountDownLatch(1)
        val ui = Thread.ofPlatform().start {
            try {
                runTui(session, config(backend))
            } catch (failure: Throwable) {
                thrown.set(failure)
            } finally {
                stopped.countDown()
            }
        }
        try {
            // Parked in awaitExecution: the UI thread's only untimed wait.
            pollUntil("the UI thread waits for the apply") { ui.state == Thread.State.WAITING }
            assertEquals(listOf("raw", "alternate", "hide", "hide"), backend.lifecycle)
            assertFalse(completion.isDone)
            assertTrue(ui.isAlive)
        } finally {
            tasks.first().run()
        }
        assertTrue(stopped.await(5, TimeUnit.SECONDS))
        // The failure itself propagates, so the CLI can name its type and message.
        assertEquals("render fixture failure", assertInstanceOf(IllegalStateException::class.java, thrown.get()).message)
        assertEquals(listOf("raw", "alternate", "hide", "hide", "show", "leave", "close"), backend.lifecycle)
        assertTrue(assertInstanceOf(ApplyModel.Result::class.java, session.applyModel()).succeeded())
        assertFalse(completion.isCancelled)
        assertTrue(Files.isSymbolicLink(temporary.resolve("source")))
    }

    @Test
    fun aConfigurationEvaluationBugPropagatesAfterRestoringTheTerminal() {
        val failing = AtomicBoolean()
        val planner = ReconciliationPlanner()
        val evaluator = ConfigurationEvaluation(plan = { states ->
            if (failing.get()) throw IllegalStateException("injected planner bug") else planner.plan(states)
        })
        val session = HomeLightSession(HomeLightExitTest.configuration(temporary), evaluator = evaluator)
        // `r` checks again, which loads the configuration while the TUI runs.
        val backend = LifecycleBackend("r")
        failing.set(true)

        val thrown = assertThrows<IllegalStateException> { runTui(session, config(backend)) }
        assertEquals("injected planner bug", thrown.message)
        assertEquals(listOf("show", "leave", "close"), backend.lifecycle.takeLast(3))
    }

    @Test
    fun anApplyBugShowsTheInternalErrorThenPropagatesAfterRestoringTheTerminal() {
        val planner = ReconciliationPlanner()
        // A plan without its review snapshot makes the executor's preflight fail a `require`: a bug.
        val evaluator = ConfigurationEvaluation(plan = { states -> planner.plan(states).copy(expectedStates = listOf()) })
        val session = HomeLightSession(HomeLightExitTest.configuration(temporary), evaluator = evaluator)
        val ui = HeadlessTui(session)
        ui.app.switchScreen(Screen.APPLY)
        session.confirmApply(Executor(Runnable::run))

        val result = assertInstanceOf(ApplyModel.Result::class.java, session.applyModel())
        val line = "Internal error (please report): IllegalArgumentException: Plan has no complete review snapshot"
        assertEquals(listOf(line), result.diagnostics)
        for ((width, height) in listOf(80 to 24, 120 to 30)) {
            val screen = ui.screen(width, height)
            assertTrue(screen.contains("Internal error (please report)"), screen)
            assertTrue(screen.contains("Stopped unexpectedly"), screen)
        }
        val backend = LifecycleBackend("q")
        val thrown = assertThrows<CompletionException> { runTui(session, config(backend)) }
        assertInstanceOf(IllegalArgumentException::class.java, thrown.cause)
        assertEquals(listOf("show", "leave", "close"), backend.lifecycle.takeLast(3))
        assertFalse(Files.exists(temporary.resolve("source")))
    }

    /** Reads [input] one character at a time, then nothing. Every flush throws [failFlush] when one is given. */
    private class LifecycleBackend(input: String = "", private val failFlush: RuntimeException? = null) : AbstractBackend() {
        val lifecycle: MutableList<String> = CopyOnWriteArrayList()
        private val input = ConcurrentLinkedQueue(input.toList())

        override fun flush() { failFlush?.let { throw it } }
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
            input.poll()?.let { return it.code }
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
