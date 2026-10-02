package io.github.bigswlittlesw.homelight.concurrent

import io.github.bigswlittlesw.homelight.pollUntil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class BoundedTest {
    @Test fun resultsComeBackInInputOrderWhateverOrderWorkFinishes() {
        val items = (0 until 6).toList()
        // Each item finishes only after the next one has, so they finish in reverse order.
        val finished = items.map { CountDownLatch(1) } + CountDownLatch(0)
        val finishOrder = Collections.synchronizedList(mutableListOf<Int>())
        val outcomes = mapBounded(items, items.size, { false }) { item ->
            assertTrue(finished[item + 1].await(5, TimeUnit.SECONDS))
            finishOrder.add(item)
            finished[item].countDown()
            "item $item"
        }
        assertEquals(items.reversed(), finishOrder)
        assertEquals(items.map { Outcome.Completed("item $it") }, outcomes)
    }

    @Test fun unitWorkCompletesWithUnit() {
        val ran = AtomicInteger()
        val outcomes = mapBounded(listOf("a", "b", "c"), 2, { false }) { ran.incrementAndGet(); Unit }
        assertEquals(List(3) { Outcome.Completed(Unit) }, outcomes)
        assertEquals(3, ran.get())
    }

    @Test fun failedHoldsTheExceptionAndOtherItemsStillRun() {
        val failure = IllegalStateException("item 1")
        val outcomes = mapBounded((0 until 6).toList(), 2, { false }) { item ->
            if (item == 1) throw failure
            item * 10
        }
        assertSame(failure, (outcomes[1] as Outcome.Failed).error)
        assertEquals(
            listOf(0, 2, 3, 4, 5).map { Outcome.Completed(it * 10) },
            outcomes.filterIndexed { index, _ -> index != 1 },
        )
    }

    @Test fun cancellationLeavesUnclaimedItemsNotStartedAndLetsClaimedOnesFinish() {
        val cancelled = AtomicBoolean()
        val claimed = ConcurrentHashMap.newKeySet<Int>()
        val interrupted = AtomicBoolean()
        val outcomes = mapBounded((0 until 20).toList(), 3, cancelled::get) { item ->
            claimed.add(item)
            if (item == 4) cancelled.set(true)
            Thread.sleep(20)
            if (Thread.currentThread().isInterrupted) interrupted.set(true)
            item
        }
        assertTrue(4 in claimed)
        assertTrue(claimed.size < 20, "cancellation should stop new claims")
        outcomes.forEachIndexed { index, outcome ->
            val expected = if (index in claimed) Outcome.Completed(index) else Outcome.NotStarted
            assertEquals(expected, outcome, "item $index")
        }
        assertFalse(interrupted.get())
    }

    @Test fun cancellationWithOneThreadStopsRightAfterTheCancellingItem() {
        val cancelled = AtomicBoolean()
        val outcomes = mapBounded((0 until 10).toList(), 1, cancelled::get) { item ->
            if (item == 2) cancelled.set(true)
            item
        }
        assertEquals(listOf(0, 1, 2).map { Outcome.Completed(it) } + List(7) { Outcome.NotStarted }, outcomes)
    }

    /** Deterministic: every item holds its thread at a gate, so the window must fill and then stop. */
    @ParameterizedTest
    @CsvSource("3, 10", "1, 4", "8, 5")
    fun windowFillsToNAndNoFurtherItemStartsUntilOneFinishes(n: Int, size: Int) {
        val window = minOf(n, size)
        val started = AtomicInteger()
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        val gate = CountDownLatch(1)
        val outcomes = AtomicReference<List<Outcome<Int>>>()
        val caller = Thread.ofPlatform().start {
            outcomes.set(
                mapBounded((0 until size).toList(), n, { false }) { item ->
                    started.incrementAndGet()
                    peak.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                    try {
                        assertTrue(gate.await(5, TimeUnit.SECONDS))
                    } finally {
                        inFlight.decrementAndGet()
                    }
                    item
                },
            )
        }
        pollUntil("window did not fill") { inFlight.get() == window }
        // The only timing-based check: give an extra item a chance to start, which it must not.
        Thread.sleep(50)
        assertEquals(window, started.get())
        gate.countDown()
        caller.join(5_000)
        assertFalse(caller.isAlive)
        assertEquals(window, peak.get())
        assertEquals((0 until size).map { Outcome.Completed(it) }, outcomes.get())
    }

    /** Many short items with random durations: the window never exceeds [n] and still fills. */
    @ParameterizedTest
    @ValueSource(ints = [1, 2, 8])
    fun windowNeverExceedsNUnderChurn(n: Int) {
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        val outcomes = mapBounded((0 until 500).toList(), n, { false }) { item ->
            peak.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
            try {
                Thread.sleep(ThreadLocalRandom.current().nextLong(1, 3))
            } finally {
                inFlight.decrementAndGet()
            }
            item
        }
        assertTrue(peak.get() <= n, "peak ${peak.get()} exceeds $n")
        assertEquals(n, peak.get())
        assertEquals((0 until 500).map { Outcome.Completed(it) }, outcomes)
    }

    /** The bound comes from the number of threads, not from timing. */
    @ParameterizedTest
    @CsvSource("1, 50", "3, 50", "8, 5")
    fun workRunsOnAtMostMinOfNAndItemCountThreads(n: Int, size: Int) {
        val threads = ConcurrentHashMap.newKeySet<Thread>()
        mapBounded((0 until size).toList(), n, { false }) { threads.add(Thread.currentThread()) }
        assertTrue(threads.size <= minOf(n, size), "${threads.size} threads ran work")
        assertTrue(threads.all { it.isVirtual })
    }

    @Test fun slowItemDelaysOnlyItsOwnThread() {
        val release = CountDownLatch(1)
        val others = CountDownLatch(4)
        val outcomes = mapBounded((0 until 5).toList(), 2, { false }) { item ->
            if (item == 0) assertTrue(release.await(3, TimeUnit.SECONDS))
            else {
                others.countDown()
                if (item == 4) release.countDown()
            }
        }
        assertEquals(0, others.count)
        assertTrue(outcomes.all { it is Outcome.Completed })
    }

    @Test fun errorPropagatesOnceEveryWorkerHasFinished() {
        class Fatal : Error("fatal")
        val thrown = CountDownLatch(1)
        val otherFinished = AtomicBoolean()
        assertThrows<Fatal> {
            mapBounded(listOf(0, 1), 2, { false }) { item ->
                if (item == 0) {
                    thrown.countDown()
                    throw Fatal()
                }
                assertTrue(thrown.await(5, TimeUnit.SECONDS))
                Thread.sleep(50)
                otherFinished.set(true)
            }
        }
        assertTrue(otherFinished.get(), "returned before the other worker finished")
    }

    @Test fun interruptedCallerStillWaitsForEveryItemAndKeepsItsInterrupt() {
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        val outcomesAtReturn = AtomicReference<List<Outcome<Unit>>>()
        val interruptedAtReturn = AtomicBoolean()
        val caller = Thread.ofPlatform().start {
            outcomesAtReturn.set(
                mapBounded(listOf(0, 1), 2, { false }) {
                    started.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                },
            )
            interruptedAtReturn.set(Thread.currentThread().isInterrupted)
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        caller.interrupt()
        Thread.sleep(100)
        assertTrue(caller.isAlive, "returned before its items finished")
        release.countDown()
        caller.join(5_000)
        assertFalse(caller.isAlive)
        assertEquals(List(2) { Outcome.Completed(Unit) }, outcomesAtReturn.get())
        assertTrue(interruptedAtReturn.get())
    }

    @Test fun emptyListReturnsNoOutcomes() {
        assertEquals(listOf<Outcome<Nothing>>(), mapBounded(listOf<Int>(), 4, { false }) { throw AssertionError("No items") })
    }

    @ParameterizedTest
    @ValueSource(ints = [0, -1])
    fun nonPositiveBoundIsRejected(n: Int) {
        assertThrows<IllegalArgumentException> { mapBounded(listOf(1), n, { false }) { } }
    }
}
