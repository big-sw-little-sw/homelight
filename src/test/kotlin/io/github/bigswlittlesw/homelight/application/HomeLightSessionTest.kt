package io.github.bigswlittlesw.homelight.application

import io.github.bigswlittlesw.homelight.config.isJavaBlank
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class HomeLightSessionTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun rejectedWorkerRetainsResultAndRefreshesStatusWithoutMutation() {
        val root = directory.toRealPath()
        val session = HomeLightSession(configuration(root, "", "cache"))
        assertTrue(session.requestApply())
        Files.createDirectories(root.resolve("local/cache"))
        val completion = session.confirmApply { task ->
            throw java.util.concurrent.RejectedExecutionException("worker unavailable")
        }
        completion.join()
        session.awaitExecution()
        val result = assertInstanceOf(ApplyModel.Result::class.java, session.applyModel())
        assertEquals("worker unavailable", result.diagnostics.first())
        assertNull(result.execution)
        assertFalse(session.isApplying())
        assertSame(completion, session.confirmApply { task -> fail<Unit>("Must not restart") })
        assertFalse(Files.exists(root.resolve("home")))
        assertEquals(1, assertInstanceOf(PlanModel.Configured::class.java, session.planModel()).items.size)
    }

    @Test
    fun invalidConfigurationAtStatusRefreshDoesNotEraseExecutionResult() {
        val root = directory.toRealPath()
        val config = configuration(root, "", "cache")
        val session = HomeLightSession(config)
        assertTrue(session.requestApply())
        Files.writeString(config, "homelight: [invalid")
        session.confirmApply(Runnable::run).join()
        assertTrue(assertInstanceOf(ApplyModel.Result::class.java, session.applyModel()).succeeded())
        assertInstanceOf(PlanModel.Invalid::class.java, session.planModel())
        assertTrue(Files.isSymbolicLink(root.resolve("home/cache")))
    }

    @Test
    fun visualDelayHoldsEachActionBeforeCompletion() {
        val root = directory.toRealPath()
        val session = HomeLightSession(configuration(root, "", "cache"), 20)
        val actionCount = assertInstanceOf(PlanModel.Configured::class.java, session.planModel()).plan.actions().size
        session.requestApply()
        val started = System.nanoTime()
        session.confirmApply().get(10, TimeUnit.SECONDS)
        assertTrue(System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(20L * actionCount))
        assertTrue(assertInstanceOf(ApplyModel.Result::class.java, session.applyModel()).succeeded())
    }

    @Test
    fun confirmsTheExactReviewedPlanAndRetainsTheResultUntilReplanning() {
        val root = directory.toRealPath()
        val source = Files.createDirectories(root.resolve("home/cache"))
        Files.writeString(source.resolve("entry"), "keep this content")
        val config = configuration(root, "", "cache")
        val session = HomeLightSession(config)
        val reviewed = assertInstanceOf(PlanModel.Configured::class.java, session.planModel()).plan

        assertTrue(session.requestApply())
        assertSame(reviewed, assertInstanceOf(ApplyModel.Confirmation::class.java, session.applyModel()).plan)
        assertFalse(Files.exists(root.resolve("local/cache")))

        // A later config read must not silently substitute a different plan at confirmation.
        Files.writeString(config, Files.readString(config).replace("home/cache", "home/other"))
        session.confirmApply().get(10, TimeUnit.SECONDS)

        val result = assertInstanceOf(ApplyModel.Result::class.java, session.applyModel())
        assertSame(reviewed, result.plan)
        assertSame(reviewed.relocations.first(), checkNotNull(result.execution).relocations.first().relocation)
        assertTrue(result.succeeded())
        assertTrue(Files.isSymbolicLink(source))
        assertEquals("keep this content", Files.readString(root.resolve("local/cache/entry")))
        assertFalse(Files.exists(root.resolve("home/other")))
        assertFalse(session.requestApply())
        session.confirmApply(Runnable::run)
        assertSame(result, session.applyModel())
        session.cancelApply()
        assertSame(result, session.applyModel())

        configuration(root, "", "cache")
        session.refresh()
        assertInstanceOf(ApplyModel.Idle::class.java, session.applyModel())
        assertEquals(1, assertInstanceOf(PlanModel.Configured::class.java, session.planModel()).summary.inSync)
        val repeated = assertInstanceOf(PlanModel.Configured::class.java, session.planModel()).plan
        assertNotSame(reviewed, repeated)
        assertFalse(repeated.actions().stream().anyMatch(ReconciliationAction::mutatesFilesystem))
        assertTrue(session.requestApply())
        session.confirmApply(Runnable::run).join()
        assertTrue(assertInstanceOf(ApplyModel.Result::class.java, session.applyModel()).succeeded())
    }

    @Test
    fun cancellationAndLeavingConfirmationDoNotMutate() {
        val root = directory.toRealPath()
        val session = HomeLightSession(configuration(root, "", "cache"))
        session.requestApply()
        session.cancelApply()
        session.confirmApply(Runnable::run)
        assertInstanceOf(ApplyModel.Idle::class.java, session.applyModel())
        assertTrue(session.requestApply())
        session.cancelApply()
        session.confirmApply(Runnable::run)
        assertFalse(Files.exists(root.resolve("home/cache")))
        assertFalse(Files.exists(root.resolve("local/cache")))
    }

    @Test
    fun unresolvedAndBlockedPlansCannotBeConfirmed() {
        val root = directory.toRealPath()
        Files.createDirectories(root.resolve("local/cache"))
        val session = HomeLightSession(configuration(root, "", "cache"))
        assertFalse(session.requestApply())
        session.confirmApply(Runnable::run)
        assertFalse(Files.exists(root.resolve("home/cache")))

        Files.createDirectories(root.resolve("home"))
        Files.writeString(root.resolve("home/cache"), "unsupported source file")
        session.refresh()
        assertFalse(session.requestApply())
        session.confirmApply(Runnable::run)
        assertEquals("unsupported source file", Files.readString(root.resolve("home/cache")))
    }

    @Test
    fun preflightsEveryRelocationBeforeAnyMutationAndRequiresExplicitReplanning() {
        val root = directory.toRealPath()
        val session = HomeLightSession(configuration(root, "", "first", "second"))
        session.requestApply()
        Files.createDirectories(root.resolve("local/second"))
        session.confirmApply(Runnable::run).join()

        val stale = assertInstanceOf(ApplyModel.Result::class.java, session.applyModel())
        assertTrue(stale.stale)
        assertNull(stale.execution)
        assertTrue(stale.diagnostics.first().contains("local/second"))
        assertTrue(stale.steps.stream().allMatch { step -> step.status == ApplyModel.StepStatus.PENDING })
        assertFalse(Files.exists(root.resolve("local/first")))
        assertFalse(Files.exists(root.resolve("home")))
        assertFalse(session.requestApply())

        session.refresh()
        assertTrue(session.hasConflicts())
        val second = assertInstanceOf(PlanModel.Configured::class.java, session.planModel()).items.stream()
                .filter { item -> item.relocation.sourcePath.endsWith("second") }.findFirst().orElseThrow()
        session.resolveDecision(second.relocation, DecisionChoice.ADOPT_TARGET)
        assertTrue(session.requestApply())
        session.confirmApply(Runnable::run).join()
        assertTrue(assertInstanceOf(ApplyModel.Result::class.java, session.applyModel()).succeeded())
        assertEquals(2, assertInstanceOf(PlanModel.Configured::class.java, session.planModel()).summary.inSync)
    }

    @Test
    fun preflightRetainsArchiveDestinationObservation() {
        val root = directory.toRealPath()
        val source = Files.createDirectories(root.resolve("home/cache"))
        Files.writeString(source.resolve("entry"), "source")
        Files.createDirectories(root.resolve("local/cache"))
        val config = configuration(root, "", "cache")
        // The Java text block kept six spaces of indentation and its final newline.
        Files.writeString(config, Files.readString(config) + ("""
                when-source-and-target-directories-exist: adopt
                when-adopting-target: archive-source
                source-archive-root: %s
                """.trimIndent().prependIndent("      ") + "\n").format(root.resolve("archive")))
        val session = HomeLightSession(config)
        assertTrue(session.requestApply())
        val archive = root.resolve("archive").resolve(source.root.relativize(source))
        Files.createDirectories(archive)
        Files.writeString(archive.resolve("entry"), "external archive")
        session.confirmApply(Runnable::run).join()

        assertTrue(assertInstanceOf(ApplyModel.Result::class.java, session.applyModel()).stale)
        assertEquals("source", Files.readString(source.resolve("entry")))
        assertEquals("external archive", Files.readString(archive.resolve("entry")))
    }

    @Test
    fun runningSnapshotIsImmutableAndRepeatedIntentsCannotStartOrReplaceExecution() {
        val root = directory.toRealPath()
        val session = HomeLightSession(configuration(root, "", "cache"))
        val tasks = ArrayList<Runnable>()
        session.requestApply()
        val execution = session.confirmApply(tasks::add)
        val running = assertInstanceOf(ApplyModel.Running::class.java, session.applyModel())
        assertEquals(1, tasks.size)
        assertSame(execution, session.confirmApply(tasks::add))
        session.refresh()
        session.cancelApply()
        session.cancelApply()
        assertFalse(session.requestApply())
        assertSame(running, session.applyModel())
        assertEquals(1, tasks.size)

        tasks.first().run()
        execution.join()
        assertTrue(running.steps.stream().allMatch { step -> step.status == ApplyModel.StepStatus.PENDING })
        val result = assertInstanceOf(ApplyModel.Result::class.java, session.applyModel())
        assertTrue(result.steps.stream().allMatch { step -> step.status == ApplyModel.StepStatus.COMPLETED })
        assertEquals(1, assertInstanceOf(PlanModel.Configured::class.java, session.planModel()).summary.inSync)
    }

    @Test
    fun partialFailureRetainsCompletedFailedAndPendingActionsAndRetryUsesANewPlan() {
        val root = directory.toRealPath()
        Files.createDirectories(root.resolve("home/second"))
        val staging = Files.createDirectories(root.resolve("local")).resolve("staging-file")
        val config = configuration(root, "  staging-root: $staging\n", "first", "second", "third")
        val session = HomeLightSession(config)
        assertTrue(session.requestApply(), session.planModel().toString())
        Files.writeString(staging, "not a directory")
        session.confirmApply(Runnable::run).join()

        val result = assertInstanceOf(ApplyModel.Result::class.java, session.applyModel())
        assertFalse(result.succeeded())
        assertTrue(result.steps.stream().anyMatch { step -> step.status == ApplyModel.StepStatus.COMPLETED })
        val failed = result.steps.stream().filter { step -> step.status == ApplyModel.StepStatus.FAILED }.findFirst().orElseThrow()
        assertTrue(failed.relocation.relocation.sourcePath.endsWith("second"))
        assertFalse(failed.message.isJavaBlank())
        assertTrue(result.steps.stream().anyMatch { step -> step.status == ApplyModel.StepStatus.PENDING })
        assertTrue(Files.isSymbolicLink(root.resolve("home/first")))
        assertFalse(Files.exists(root.resolve("home/third")))
        assertFalse(session.requestApply())

        Files.delete(staging)
        session.refresh()
        assertTrue(session.requestApply())
        session.confirmApply(Runnable::run).join()
        val retried = assertInstanceOf(ApplyModel.Result::class.java, session.applyModel())
        assertNotSame(result.plan, retried.plan)
        assertTrue(retried.succeeded())
        assertEquals(3, assertInstanceOf(PlanModel.Configured::class.java, session.planModel()).summary.inSync)
    }

    private fun configuration(root: Path, globals: String, vararg names: String): Path {
        val yaml = StringBuilder("homelight:\n  target-root: " + root.resolve("local") + "\n" + globals + "  relocations:\n")
        for (name in names) {
            yaml.append("    - source-path: ").append(root.resolve("home").resolve(name)).append('\n')
            yaml.append("      target-path: ").append(root.resolve("local").resolve(name)).append('\n')
        }
        return Files.writeString(root.resolve("config.yaml"), yaml)
    }
}
