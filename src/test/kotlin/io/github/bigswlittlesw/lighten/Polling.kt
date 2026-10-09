package io.github.bigswlittlesw.lighten

import org.junit.jupiter.api.assertTimeoutPreemptively
import org.junit.jupiter.api.fail
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/**
 * How long a test waits before it calls a wait a hang.
 *
 * Only a hang should reach it: tests wait on the condition itself, never on how fast the machine is, so a loaded
 * build stays green.
 */
internal val HANG_LIMIT: Duration = Duration.ofSeconds(30)

/** Checks [condition] every millisecond until it holds, and fails with [message] after [HANG_LIMIT]. */
internal fun pollUntil(message: String, condition: () -> Boolean) {
    val deadline = System.nanoTime() + HANG_LIMIT.toNanos()
    while (!condition()) {
        if (System.nanoTime() >= deadline) fail(message)
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1))
    }
}

/**
 * Runs [block] and fails if it has not returned within [HANG_LIMIT].
 *
 * For a step that must not wait on something the test keeps stalled: such a wait never ends, so the step runs on
 * another thread that is abandoned when the limit passes.
 */
internal fun <T> withoutHanging(block: () -> T): T = assertTimeoutPreemptively(HANG_LIMIT, block)
