package io.github.bigswlittlesw.homelight.concurrent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class BoundedTest {
    @Test fun fillsTheWindowButNeverExceedsItAndRunsEveryItem() {
        val inFlight = AtomicInteger()
        val most = AtomicInteger()
        val full = CountDownLatch(3)
        val done = Collections.synchronizedList(mutableListOf<Int>())
        forEachBounded((0 until 10).toList(), 3, { false }) { item ->
            most.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
            full.countDown()
            // The first three items finish only once all three are in flight, so the window must fill.
            assertTrue(full.await(3, TimeUnit.SECONDS))
            inFlight.decrementAndGet()
            done.add(item)
        }
        assertEquals(3, most.get())
        assertEquals((0 until 10).toList(), done.sorted())
    }

    @Test fun slowItemDelaysOnlyItsOwnThread() {
        val release = CountDownLatch(1)
        val others = CountDownLatch(4)
        forEachBounded((0 until 5).toList(), 2, { false }) { item ->
            if (item == 0) assertTrue(release.await(3, TimeUnit.SECONDS))
            else {
                others.countDown()
                if (item == 4) release.countDown()
            }
        }
        assertEquals(0, others.count)
    }

    @Test fun cancellationStopsBeforeTheNextItemWithoutInterruptingRunningOnes() {
        val cancelled = AtomicBoolean()
        val started = AtomicInteger()
        val interrupted = AtomicBoolean()
        forEachBounded((0 until 10).toList(), 1, cancelled::get) {
            started.incrementAndGet()
            cancelled.set(true)
            Thread.sleep(20)
            if (Thread.currentThread().isInterrupted) interrupted.set(true)
        }
        assertEquals(1, started.get())
        assertEquals(false, interrupted.get())
    }

    @Test fun failureInOneItemReachesTheHandlerAndOthersStillRun() {
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val done = AtomicInteger()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> failures.add(e) }
        try {
            forEachBounded((0 until 6).toList(), 2, { false }) { item ->
                if (item == 1) throw IllegalStateException("item 1")
                done.incrementAndGet()
            }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
        assertEquals(5, done.get())
        assertEquals(listOf("item 1"), failures.map { it.message })
    }

    @Test fun interruptedCallerStillWaitsForEveryItemAndKeepsItsInterrupt() {
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        val finished = AtomicInteger()
        val finishedAtReturn = AtomicInteger(-1)
        val interruptedAtReturn = AtomicBoolean()
        val caller = Thread.ofPlatform().start {
            forEachBounded(listOf(0, 1), 2, { false }) {
                started.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                finished.incrementAndGet()
            }
            finishedAtReturn.set(finished.get())
            interruptedAtReturn.set(Thread.currentThread().isInterrupted)
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        caller.interrupt()
        Thread.sleep(100)
        assertTrue(caller.isAlive, "returned before its items finished")
        release.countDown()
        caller.join(5_000)
        assertFalse(caller.isAlive)
        assertEquals(2, finishedAtReturn.get())
        assertTrue(interruptedAtReturn.get())
    }

    @Test fun emptyBatchReturnsAndNonPositiveBoundIsRejected() {
        forEachBounded(listOf<Int>(), 4, { false }) { throw AssertionError("No items") }
        assertThrows<IllegalArgumentException> { forEachBounded(listOf(1), 0, { false }) { } }
    }
}
