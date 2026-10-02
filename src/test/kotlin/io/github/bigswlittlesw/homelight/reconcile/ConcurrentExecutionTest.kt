package io.github.bigswlittlesw.homelight.reconcile

import io.github.bigswlittlesw.homelight.cli.renderApplyJson
import io.github.bigswlittlesw.homelight.concurrent.RELOCATION_CONCURRENCY
import io.github.bigswlittlesw.homelight.config.Relocation
import io.github.bigswlittlesw.homelight.fs.PathInspector
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationExecutor.ActionExecution
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationExecutor.ActionStatus
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationExecutor.ExecutionOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.walk

class ConcurrentExecutionTest {
    @TempDir
    lateinit var temporary: Path

    private val root: Path by lazy { temporary.toRealPath() }

    @Test
    fun runsIndependentRelocationsConcurrentlyButNeverAboveTheWindow() {
        // Targets share a missing ancestor (`new`), which concurrent migrations create together.
        val relocations = (1..5).map { n -> migration("s$n/data", "new/t$n/data", files = 20) }
        val plan = plan(relocations)
        assertEquals(5, independentGroups(plan.relocations).size)
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        val full = CountDownLatch(3)
        val started = Collections.synchronizedList(mutableListOf<Pair<RelocationPlan, ReconciliationAction>>())

        val result = ReconciliationExecutor(3).execute(plan, object : ReconciliationExecutor.ProgressListener {
            override fun started(relocation: RelocationPlan, action: ReconciliationAction) {
                started.add(relocation to action)
                if (action is ReconciliationAction.MigrateDirectoryForPublication) {
                    peak.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                    full.countDown()
                    // The first three copies proceed only once all three are in flight, so the window must fill.
                    assertTrue(full.await(5, TimeUnit.SECONDS))
                }
            }

            override fun finished(relocation: RelocationPlan, action: ActionExecution) {
                if (action.action is ReconciliationAction.ReplaceDirectoryWithSymlink) inFlight.decrementAndGet()
            }
        })

        assertTrue(result.succeeded())
        assertEquals(3, peak.get())
        for ((relocation, plannedRelocation) in relocations.zip(plan.relocations)) {
            assertTrue(Files.isSymbolicLink(relocation.sourcePath))
            assertEquals(20, Files.list(relocation.targetPath).use { it.count() }.toInt())
            assertEquals(plannedRelocation.actions, started.filter { it.first === plannedRelocation }.map { it.second })
        }
    }

    @Test
    fun runsSiblingsUnderAnExistingParentConcurrently() {
        // `home` and `store` exist. Each migration has its own staging root, because a shared one is a claimed path.
        Files.createDirectories(root.resolve("store"))
        val relocations = listOf("a", "b", "c").map { name ->
            migration("home/.$name", "store/$name", files = 20, stagingRoot = "store/.staging-$name")
        }
        val plan = plan(relocations)
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), independentGroups(plan.relocations))
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        val full = CountDownLatch(RELOCATION_CONCURRENCY)

        val result = ReconciliationExecutor().execute(plan, object : ReconciliationExecutor.ProgressListener {
            override fun started(relocation: RelocationPlan, action: ReconciliationAction) {
                if (action is ReconciliationAction.MigrateDirectoryForPublication) {
                    peak.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                    full.countDown()
                    assertTrue(full.await(5, TimeUnit.SECONDS))
                }
            }

            override fun finished(relocation: RelocationPlan, action: ActionExecution) {
                if (action.action is ReconciliationAction.ReplaceDirectoryWithSymlink) inFlight.decrementAndGet()
            }
        })

        assertTrue(result.succeeded())
        assertEquals(RELOCATION_CONCURRENCY, peak.get())
        for (relocation in relocations) {
            assertTrue(Files.isSymbolicLink(relocation.sourcePath))
            assertEquals(20, Files.list(relocation.targetPath).use { it.count() }.toInt())
        }
    }

    @Test
    fun siblingsWhoseParentIsMissingShareAGroupAndConverge() {
        // Absent sources and targets: each relocation ensures `links` and `store`, which do not exist yet.
        val relocations = listOf("a", "b", "c").map { name ->
            Relocation(root.resolve("links/$name"), root.resolve("store/$name"))
        }
        val plan = plan(relocations)
        assertEquals(listOf(listOf(0, 1, 2)), independentGroups(plan.relocations))

        val result = ReconciliationExecutor().execute(plan)

        assertTrue(result.succeeded())
        for (relocation in relocations) {
            assertTrue(Files.isSymbolicLink(relocation.sourcePath))
            assertTrue(Files.isDirectory(relocation.targetPath))
        }
    }

    @Test
    fun runsDependentRelocationsSequentially() {
        // Sibling targets in the existing `local` share the default staging root.
        Files.createDirectories(root.resolve("local"))
        val relocations = (1..3).map { n -> migration("home/s$n", "local/t$n", files = 5) }
        val plan = plan(relocations)
        assertEquals(listOf(listOf(0, 1, 2)), independentGroups(plan.relocations))
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()

        val result = ReconciliationExecutor().execute(plan, object : ReconciliationExecutor.ProgressListener {
            override fun started(relocation: RelocationPlan, action: ReconciliationAction) {
                peak.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                Thread.sleep(20)
            }

            override fun finished(relocation: RelocationPlan, action: ActionExecution) {
                inFlight.decrementAndGet()
            }
        })

        assertTrue(result.succeeded())
        assertEquals(1, peak.get())
    }

    @Test
    fun groupsRelocationsThatAreNotProvenIndependent() {
        fun relocation(source: String, target: String, vararg actions: ReconciliationAction) =
            RelocationPlan(Relocation(root.resolve(source), root.resolve(target)), RelocationOutcome.CONVERGED,
                actions.toList(), listOf())
        fun migration(source: String, target: String, staging: String? = null) = relocation(source, target,
            ReconciliationAction.MigrateDirectoryForPublication(root.resolve(source), root.resolve(target),
                staging?.let(root::resolve)))
        // Every parent below exists, so only the relocations' own paths can make them dependent.
        for (name in listOf("a", "b", "c", "d", "e", "f", "h", "j", "k", "store")) Files.createDirectories(root.resolve(name))
        val first = relocation("a/source", "b/target")
        // A target under another relocation's source; the planner rejects this, but the executor must not rely on it.
        val nested = relocation("c/source", "a/source/inner")
        val second = relocation("d/source", "e/target")
        val sharedStaging = listOf(migration("a/one", "store/one", "e/.staging"), migration("b/two", "f/two", "e/.staging"))
        val defaultStaging = listOf(migration("a/one", "store/one"), migration("b/two", "store/two"))
        val archiveIntoFirst = relocation("h/source", "c/target",
            ReconciliationAction.ArchiveDirectory(root.resolve("h/source"), root.resolve("b/target/archive/h/source")))
        val independent = relocation("j/source", "k/target")
        val siblings = listOf(relocation("a/one", "store/one"), relocation("a/two", "store/two"))
        val missingParent = listOf(relocation("a/one", "new/one"), relocation("a/two", "new/two"))
        // Overlaps `first`'s source and `second`'s source.
        val bridge = relocation("a/source/bridge", "d/source/bridge")

        assertEquals(listOf(listOf(0, 1)), independentGroups(listOf(first, nested)))
        assertEquals(listOf(listOf(0, 1)), independentGroups(sharedStaging))
        assertEquals(listOf(listOf(0, 1)), independentGroups(defaultStaging))
        assertEquals(listOf(listOf(0, 1)), independentGroups(listOf(first, archiveIntoFirst)))
        assertEquals(listOf(listOf(0), listOf(1)), independentGroups(siblings))
        assertEquals(listOf(listOf(0, 1)), independentGroups(missingParent))
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), independentGroups(listOf(first, second, independent)))
        // A relocation that depends on two groups merges them, and groups keep plan order.
        assertEquals(listOf(listOf(0, 1, 3), listOf(2)), independentGroups(listOf(first, second, independent, bridge)))
    }

    @Test
    fun failureStopsNewRelocationsWhileRunningOnesFinish() {
        val relocations = (1..3).map { n -> migration("s$n/data", "t$n/data", files = 5) }
        val plan = plan(relocations)
        val (first, second, third) = plan.relocations
        val secondStarted = CountDownLatch(1)
        val firstFailed = CountDownLatch(1)

        val result = ReconciliationExecutor().execute(plan, object : ReconciliationExecutor.ProgressListener {
            override fun started(relocation: RelocationPlan, action: ReconciliationAction) {
                if (action !is ReconciliationAction.MigrateDirectoryForPublication) return
                if (relocation === first) {
                    assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
                    throw IllegalStateException("injected failure")
                }
                if (relocation === second) {
                    secondStarted.countDown()
                    assertTrue(firstFailed.await(5, TimeUnit.SECONDS))
                }
            }

            override fun finished(relocation: RelocationPlan, action: ActionExecution) {
                if (action.status == ActionStatus.FAILED) firstFailed.countDown()
            }
        })

        assertFalse(result.succeeded())
        assertEquals(listOf(first, second, third), result.relocations.map { it.relocation })
        val (failed, finished, notStarted) = result.relocations
        assertEquals(listOf(ActionStatus.FAILED, ActionStatus.PENDING), failed.actions.map { it.status })
        assertEquals("injected failure", failed.actions.first().message)
        assertEquals(ExecutionOutcome.CONVERGED, finished.outcome())
        assertTrue(Files.isSymbolicLink(relocations[1].sourcePath))
        assertTrue(notStarted.actions.all { it.status == ActionStatus.PENDING && it.message == "not run after a previous failure" })
        assertTrue(Files.isDirectory(relocations[2].sourcePath) && !Files.isSymbolicLink(relocations[2].sourcePath))
        assertTrue(Files.notExists(relocations[2].targetPath))
    }

    @Test
    fun resultsAndJsonKeepPlanOrderWhateverOrderRelocationsFinish() {
        val relocations = (1..2).map { n -> migration("s$n/data", "t$n/data", files = 5) }
        val plan = plan(relocations)
        val (first, second) = plan.relocations
        val secondFinished = CountDownLatch(1)
        val finishOrder = Collections.synchronizedList(mutableListOf<RelocationPlan>())

        val result = ReconciliationExecutor().execute(plan, object : ReconciliationExecutor.ProgressListener {
            override fun started(relocation: RelocationPlan, action: ReconciliationAction) {
                if (relocation === first && action is ReconciliationAction.MigrateDirectoryForPublication) {
                    assertTrue(secondFinished.await(5, TimeUnit.SECONDS))
                }
            }

            override fun finished(relocation: RelocationPlan, action: ActionExecution) {
                if (action.action === relocation.actions.last()) {
                    finishOrder.add(relocation)
                    if (relocation === second) secondFinished.countDown()
                }
            }
        })

        assertEquals(listOf(second, first), finishOrder)
        assertEquals(listOf(first, second), result.relocations.map { it.relocation })
        assertEquals(plan.actions(), result.relocations.flatMap { it.actions }.map { it.action })
        val json = StringWriter().also { output -> PrintWriter(output).use { renderApplyJson(result, it) } }.toString()
        val sources = relocations.map { "\"source\":\"${it.sourcePath}\"" }
        assertTrue(sources.all(json::contains), json)
        assertTrue(json.indexOf(sources[0]) < json.indexOf(sources[1]), json)
    }

    @Test
    fun progressEventsAlternatePerRelocationInPlanOrder() {
        val plan = plan((1..4).map { n -> migration("s$n/data", "t$n/data", files = 5) })
        val events = Collections.synchronizedList(mutableListOf<Triple<RelocationPlan, ReconciliationAction, Boolean>>())

        ReconciliationExecutor().execute(plan, object : ReconciliationExecutor.ProgressListener {
            override fun started(relocation: RelocationPlan, action: ReconciliationAction) {
                events.add(Triple(relocation, action, true))
            }

            override fun finished(relocation: RelocationPlan, action: ActionExecution) {
                events.add(Triple(relocation, action.action, false))
            }
        })

        for (relocation in plan.relocations) {
            val expected = relocation.actions.flatMap { action -> listOf(action to true, action to false) }
            assertEquals(expected, events.filter { it.first === relocation }.map { it.second to it.third })
        }
    }

    @Test
    fun aBlockedActionDelaysOnlyItsOwnRelocationAndExecutionWaitsForIt() {
        val relocations = (1..3).map { n -> migration("s$n/data", "t$n/data", files = 5) }
        val plan = plan(relocations)
        val blocked = plan.relocations.first()
        val release = CountDownLatch(1)
        val othersFinished = CountDownLatch(2)

        val execution = CompletableFuture.supplyAsync {
            ReconciliationExecutor().execute(plan, object : ReconciliationExecutor.ProgressListener {
                override fun started(relocation: RelocationPlan, action: ReconciliationAction) {
                    if (relocation === blocked && action is ReconciliationAction.MigrateDirectoryForPublication) {
                        assertTrue(release.await(10, TimeUnit.SECONDS))
                    }
                }

                override fun finished(relocation: RelocationPlan, action: ActionExecution) {
                    if (relocation !== blocked && action.action === relocation.actions.last()) othersFinished.countDown()
                }
            })
        }

        assertTrue(othersFinished.await(5, TimeUnit.SECONDS))
        assertTrue(Files.isSymbolicLink(relocations[1].sourcePath))
        assertTrue(Files.isSymbolicLink(relocations[2].sourcePath))
        Thread.sleep(100)
        assertFalse(execution.isDone)
        assertFalse(Files.isSymbolicLink(relocations[0].sourcePath))
        release.countDown()
        assertTrue(execution.get(10, TimeUnit.SECONDS).succeeded())
        assertTrue(Files.isSymbolicLink(relocations[0].sourcePath))
    }

    @Test
    fun largeTreesRunOnTheWindowsThreadsWithoutATaskPerFile() {
        val relocations = (1..2).map { n -> migration("s$n/data", "t$n/data", files = 3_000) }
        val threads = Collections.synchronizedSet(mutableSetOf<Thread>())

        val result = ReconciliationExecutor().execute(plan(relocations), object : ReconciliationExecutor.ProgressListener {
            override fun started(relocation: RelocationPlan, action: ReconciliationAction) {
                threads.add(Thread.currentThread())
            }
        })

        assertTrue(result.succeeded())
        assertTrue(threads.size <= RELOCATION_CONCURRENCY, threads.toString())
        for (relocation in relocations) {
            assertEquals(3_000, relocation.targetPath.walk().count())
        }
    }

    /** A source directory with [files] files under [root], to migrate to an absent target. */
    private fun migration(source: String, target: String, files: Int, stagingRoot: String? = null): Relocation {
        val sourcePath = Files.createDirectories(root.resolve(source))
        repeat(files) { n -> Files.writeString(sourcePath.resolve("file-$n"), "content $n") }
        return Relocation(sourcePath, root.resolve(target), stagingRoot = stagingRoot?.let(root::resolve))
    }

    private fun plan(relocations: List<Relocation>): ReconciliationPlan {
        val inspector = PathInspector()
        val plan = ReconciliationPlanner().plan(relocations.map { relocation ->
            RelocationState(relocation, inspector.inspect(relocation.sourcePath), inspector.inspect(relocation.targetPath))
        })
        assertFalse(plan.hasBlockedActions() || plan.hasConflicts())
        return plan
    }
}
