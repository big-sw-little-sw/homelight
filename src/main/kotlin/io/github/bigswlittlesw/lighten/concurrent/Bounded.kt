package io.github.bigswlittlesw.lighten.concurrent

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Candidate metadata inspections in flight at once. The limit bounds the threads that a hung network mount can
 * hold in the kernel (a blocked `stat` ignores interrupts) and the load placed on NFS. Not measured; tune later.
 */
internal const val DISCOVERY_CONCURRENCY = 8

/**
 * Directory sizing jobs in flight at once, for the on-demand `du` (issue #7). Each job walks a whole tree, so the
 * limit is lower than [DISCOVERY_CONCURRENCY]. Not measured; tune later.
 */
internal const val SIZING_CONCURRENCY = 2

/**
 * Independent relocations applied at once (issue #10). Each copies a whole tree, so, as with [SIZING_CONCURRENCY],
 * a low limit keeps the copies from competing for the same disks. Not measured; tune later.
 */
internal const val RELOCATION_CONCURRENCY = 2

/** What happened to one item of [mapBounded]. */
internal sealed interface Outcome<out U> {
    data class Completed<U>(val value: U) : Outcome<U>

    data class Failed(val error: Exception) : Outcome<Nothing>

    /** Cancelled before any thread claimed the item, so its work never ran. */
    data object NotStarted : Outcome<Nothing>
}

/**
 * Runs [work] for each of [items] on at most [n] virtual threads, and returns one [Outcome] per item, in input
 * order, once every thread has finished.
 *
 * This is a sliding window: each thread takes the next unclaimed item, so a slow item delays only its own thread.
 * Items start in list order; they may finish in any order.
 *
 * - [cancelled] is checked before each item is claimed. Once it returns `true` no further item starts, but running
 *   items are not interrupted, and this call still waits for them. A claimed item always runs, so only unclaimed
 *   items are [Outcome.NotStarted].
 * - An [Exception] from one item becomes its [Outcome.Failed] and does not stop the others. An [Error] stops its
 *   thread and is rethrown here once every thread has finished.
 *
 * The caller's thread blocks until the last item returns, so a caller that must stay responsive runs this on a
 * thread of its own. The wait ignores interrupts: an interrupted caller still waits for every item, so no worker
 * outlives this call, and this call then re-sets the caller's interrupt flag. Interrupts never reach the workers.
 */
internal fun <T, U> mapBounded(items: List<T>, n: Int, cancelled: () -> Boolean, work: (T) -> U): List<Outcome<U>> {
    require(n > 0) { "Concurrency must be positive" }
    val next = AtomicInteger()
    // Each slot has one writer, the thread that claimed its index; the joins below publish the slots to this thread.
    val outcomes = arrayOfNulls<Outcome<U>>(items.size)
    val errors = ConcurrentLinkedQueue<Error>()
    val threads = List(minOf(n, items.size)) {
        Thread.ofVirtual().start {
            while (!cancelled()) {
                val index = next.getAndIncrement()
                if (index >= items.size) break
                outcomes[index] = try {
                    Outcome.Completed(work(items[index]))
                } catch (e: Exception) {
                    Outcome.Failed(e)
                } catch (e: Error) {
                    errors.add(e)
                    break
                }
            }
        }
    }
    var interrupted = false
    for (thread in threads) {
        while (true) {
            try {
                thread.join()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
    }
    if (interrupted) Thread.currentThread().interrupt()
    errors.poll()?.let { first -> throw first.apply { errors.forEach(::addSuppressed) } }
    return outcomes.map { it ?: Outcome.NotStarted }
}
