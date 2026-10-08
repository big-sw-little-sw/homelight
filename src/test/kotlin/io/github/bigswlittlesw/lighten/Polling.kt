package io.github.bigswlittlesw.lighten

import org.junit.jupiter.api.fail
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/** Checks [condition] every millisecond until it holds, and fails with [message] once four seconds pass. */
internal fun pollUntil(message: String, condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
    while (!condition()) {
        if (System.nanoTime() >= deadline) fail(message)
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1))
    }
}
