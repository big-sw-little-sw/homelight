package io.github.bigswlittlesw.homelight.application

import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicReference

class ReviewedExecutionTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun capturesWithoutIoAndStartsExactlyOnceWithImmutableSnapshots() {
        val plan = plan("cache")
        val review = ReviewedExecution(plan)
        review.awaitExecution()
        assertSame(plan, assertInstanceOf(ApplyModel.Confirmation::class.java, review.snapshot()).plan)
        assertFalse(Files.exists(directory.resolve("local")))
        val tasks = mutableListOf<Runnable>()
        val completion = review.start(tasks::add)
        val running = assertInstanceOf(ApplyModel.Running::class.java, review.snapshot())
        assertSame(completion, review.start(tasks::add))
        assertEquals(1, tasks.size)
        assertFalse(completion.isDone())
        // Kotlin's read-only `List` has no `clear`; the cast reaches the JDK list's mutator.
        assertThrows(UnsupportedOperationException::class.java) { (running.steps as MutableList<*>).clear() }

        // Execution owns the captured plan, with no configuration dependency after capture.
        Files.delete(directory.resolve("config.json"))
        val atCompletion = AtomicReference<ApplyModel>()
        val observed = completion.thenRun { atCompletion.set(review.snapshot()) }
        tasks.first().run()
        observed.join()
        review.awaitExecution()

        val result = assertInstanceOf(ApplyModel.Result::class.java, review.snapshot())
        assertSame(result, atCompletion.get())
        assertTrue(result.succeeded())
        assertSame(plan, result.plan)
        assertSame(plan.relocations.first(), result.steps.first().relocation)
        assertSame(plan.actions().first(), result.steps.first().action)
        assertTrue(running.steps.all { step -> step.status == ApplyModel.StepStatus.PENDING })
        assertSame(completion, review.start { task -> fail<Unit>("Must not reschedule a retained result") })
        assertSame(result, review.snapshot())
        assertTrue(Files.isSymbolicLink(directory.resolve("home/cache")))
    }

    @Test
    fun rejectionRetainsDiagnosticsAndSettlesWithoutRetryOrMutation() {
        val review = ReviewedExecution(plan("cache"))
        val completion = review.start { task -> throw RejectedExecutionException("worker unavailable") }
        assertTrue(completion.isDone())
        completion.join()
        review.awaitExecution()
        val result = assertInstanceOf(ApplyModel.Result::class.java, review.snapshot())
        assertFalse(result.succeeded())
        assertFalse(result.stale)
        assertNull(result.execution)
        assertEquals(java.util.List.of("worker unavailable"), result.diagnostics)
        assertTrue(result.steps.all { step -> step.status == ApplyModel.StepStatus.PENDING })
        assertSame(completion, review.start { task -> fail<Unit>("Rejected review cannot restart") })
        assertFalse(Files.exists(directory.resolve("local")))
    }

    @Test
    fun wholePlanDriftRejectsBeforeAnyAction() {
        val review = ReviewedExecution(plan("first", "second"))
        Files.createDirectories(directory.resolve("local/second"))
        review.start(Runnable::run).join()
        val result = assertInstanceOf(ApplyModel.Result::class.java, review.snapshot())
        assertTrue(result.stale)
        assertNull(result.execution)
        assertFalse(result.diagnostics.isEmpty())
        assertTrue(result.steps.all { step -> step.status == ApplyModel.StepStatus.PENDING })
        assertFalse(Files.exists(directory.resolve("local/first")))
        assertFalse(Files.exists(directory.resolve("home")))
    }

    @Test
    fun unresolvedAndBlockedPlansAreRejectedAtCapture() {
        Files.createDirectories(directory.resolve("local/cache"))
        assertThrows(IllegalArgumentException::class.java) { ReviewedExecution(plan("cache")) }
        Files.createDirectories(directory.resolve("home"))
        Files.writeString(directory.resolve("home/cache"), "unsupported source file")
        assertThrows(IllegalArgumentException::class.java) { ReviewedExecution(plan("cache")) }
        assertEquals("unsupported source file", Files.readString(directory.resolve("home/cache")))
    }

    @Test
    fun successfulRepeatUsesANewNoChangePlan() {
        ReviewedExecution(plan("cache")).start(Runnable::run).join()
        val repeated = plan("cache")
        assertFalse(repeated.hasChanges())
        val review = ReviewedExecution(repeated)
        review.start(Runnable::run).join()
        assertTrue(assertInstanceOf(ApplyModel.Result::class.java, review.snapshot()).succeeded())
    }

    @Test
    fun workerFailureRetainsFailedAndNotStartedEvidence() {
        val review = ReviewedExecution(plan("cache"), 1)
        val tasks = mutableListOf<Runnable>()
        val completion = review.start(tasks::add)
        // Exercise the existing debug-delay failure path deterministically, without a timing race.
        Thread.currentThread().interrupt()
        try {
            tasks.first().run()
        } finally {
            Thread.interrupted()
        }
        completion.join()
        val result = assertInstanceOf(ApplyModel.Result::class.java, review.snapshot())
        assertFalse(checkNotNull(result.execution).succeeded())
        assertEquals("Interrupted during visual-test delay", result.steps.first().message)
        assertEquals(ApplyModel.StepStatus.FAILED, result.steps.first().status)
        assertTrue(result.steps.drop(1).all { step -> step.status == ApplyModel.StepStatus.PENDING })
        assertFalse(result.stale)
        assertFalse(Files.exists(directory.resolve("local")))
    }

    private fun plan(vararg names: String): ReconciliationPlan {
        val root = directory.toRealPath()
        val relocations = names.joinToString(",\n") { name ->
            "    {\"source-path\": \"${root.resolve("home").resolve(name)}\"," +
                " \"target-path\": \"${root.resolve("local").resolve(name)}\"}"
        }
        val json = "{\"homelight\": {\"target-root\": \"${root.resolve("local")}\", \"relocations\": [\n$relocations\n]}}\n"
        val config = Files.writeString(root.resolve("config.json"), json)
        return assertInstanceOf(ConfigurationEvaluation.Loaded::class.java, ConfigurationEvaluation().load(config)).plan
    }
}
