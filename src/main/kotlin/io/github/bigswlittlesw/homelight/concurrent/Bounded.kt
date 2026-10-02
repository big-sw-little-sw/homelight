package io.github.bigswlittlesw.homelight.concurrent

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

/**
 * Runs [work] for each of [items] on at most [n] virtual threads, and returns once every thread has finished.
 *
 * This is a sliding window: each thread takes the next unclaimed item, so a slow item delays only its own thread.
 * Items start in list order; they may finish in any order.
 *
 * - [cancelled] is checked before each item. Once it returns `true` no further item starts, but running items are
 *   not interrupted, and this call still waits for them.
 * - An exception from one item does not stop the others. It goes to the worker thread's uncaught exception
 *   handler; callers that need outcomes record them inside [work].
 *
 * The caller's thread blocks until the last item returns, so a caller that must stay responsive runs this on a
 * thread of its own.
 */
internal fun <T> forEachBounded(items: List<T>, n: Int, cancelled: () -> Boolean, work: (T) -> Unit) {
    require(n > 0) { "Concurrency must be positive" }
    val next = AtomicInteger()
    val threads = List(minOf(n, items.size)) {
        Thread.ofVirtual().start {
            while (!cancelled()) {
                val index = next.getAndIncrement()
                if (index >= items.size) break
                try {
                    work(items[index])
                } catch (e: Exception) {
                    val thread = Thread.currentThread()
                    thread.uncaughtExceptionHandler.uncaughtException(thread, e)
                }
            }
        }
    }
    threads.forEach(Thread::join)
}
