package io.github.bigswlittlesw.homelight.reconcile

import io.github.bigswlittlesw.homelight.cli.renderApplyJson
import io.github.bigswlittlesw.homelight.concurrent.RELOCATION_CONCURRENCY
import io.github.bigswlittlesw.homelight.config.Relocation
import io.github.bigswlittlesw.homelight.fs.PathInspector
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationExecutor.ActionExecution
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationExecutor.ActionStatus
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationExecutor.ExecutionOutcome
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationExecutor.StagingStep
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.PathWalkOption
import kotlin.io.path.walk

class ConcurrentExecutionTest {
    @TempDir
    lateinit var root: Path

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
        // `home` and `store` exist, and the migrations share the default staging root `store/.homelight-staging`.
        Files.createDirectories(root.resolve("store"))
        val staging = root.resolve("store/.homelight-staging")
        val relocations = listOf("a", "b", "c").map { name -> migration("home/.$name", "store/$name", files = 20) }
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
        assertEquals(relocations.map { lockOf(stagedCopy(staging, it.targetPath)) }.toSet(), entries(staging).toSet())
    }

    /**
     * Pauses one migration of target T after [step]. Another process and this one both try T and fail without
     * changing T's operation or freeing its lock, and both publish another target through the same staging root.
     */
    @ParameterizedTest
    @EnumSource(StagingStep::class)
    internal fun anInFlightTargetIsLockedWhileOtherTargetsShareItsStagingRoot(step: StagingStep) {
        Files.createDirectories(root.resolve("store"))
        val staging = root.resolve("store/.homelight-staging")
        val target = root.resolve("store/paused")
        val paused = plan(listOf(migration("home/.paused", "store/paused", files = 5)))
        val rival = migration("home/.rival", "store/unused", files = 1).sourcePath
        val reached = CompletableFuture<Path>()
        val release = CountDownLatch(1)
        val executor = ReconciliationExecutor(RELOCATION_CONCURRENCY) { at, copy ->
            if (at == step) {
                reached.complete(copy)
                assertTrue(release.await(10, TimeUnit.SECONDS))
            }
        }
        val pausedRun = CompletableFuture.supplyAsync { executor.execute(paused) }
        try {
            val copy = reached.get(10, TimeUnit.SECONDS)
            assertEquals(stagedCopy(staging, target), copy)
            val lock = lockOf(copy)
            val before = snapshot(copy)
            // Once T is published, its own guard refuses first.
            fun refusal(lockMessage: String) =
                if (step == StagingStep.PUBLISHED) "expected absent at $target but found directory" else lockMessage
            ForeignStagingProcess().use { foreign ->
                assertEquals(refusal("another HomeLight is publishing $target"), foreign.migrate(rival, target))
                assertEquals(before, snapshot(copy))
                assertTrue(foreign.lockIsHeld(lock))

                // This process must refuse before it opens the lock file: closing a channel to it would free the lock.
                val again = ReconciliationExecutor().execute(migrationOnly(rival, target)).relocations.single()
                assertEquals(refusal("this HomeLight is already publishing $target"), again.actions.single().message)
                assertEquals(before, snapshot(copy))
                assertTrue(foreign.lockIsHeld(lock))

                val foreignSource = migration("home/.foreign", "store/unused", files = 5).sourcePath
                assertEquals("completed", foreign.migrate(foreignSource, root.resolve("store/foreign")))
                val other = plan(listOf(migration("home/.other", "store/other", files = 5)))
                assertTrue(ReconciliationExecutor().execute(other).succeeded())
                assertEquals(before, snapshot(copy))
                assertTrue(foreign.lockIsHeld(lock))
            }
        } finally {
            release.countDown()
        }

        assertTrue(pausedRun.get(10, TimeUnit.SECONDS).succeeded())
        assertTrue(Files.isSymbolicLink(root.resolve("home/.paused")))
        val targets = listOf(target, root.resolve("store/foreign"), root.resolve("store/other"))
        targets.forEach { published -> assertEquals(5, entries(published).size, published.toString()) }
        assertEquals(targets.map { lockOf(stagedCopy(staging, it)) }.toSet(), entries(staging).toSet())
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
        // Sibling targets in `local`, which does not exist yet, so every relocation claims it.
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
        val nestedStaging = listOf(migration("a/one", "store/one", "e/.staging"), migration("b/two", "f/two", "e/.staging/b"))
        // A shared staging root inside a third relocation's target still overlaps it.
        val stagingInTarget = defaultStaging + relocation("j/source", "store/.homelight-staging/k")
        val archiveIntoFirst = relocation("h/source", "c/target",
            ReconciliationAction.ArchiveDirectory(root.resolve("h/source"), root.resolve("b/target/archive/h/source")))
        val independent = relocation("j/source", "k/target")
        val siblings = listOf(relocation("a/one", "store/one"), relocation("a/two", "store/two"))
        val missingParent = listOf(relocation("a/one", "new/one"), relocation("a/two", "new/two"))
        // Overlaps `first`'s source and `second`'s source.
        val bridge = relocation("a/source/bridge", "d/source/bridge")

        assertEquals(listOf(listOf(0, 1)), independentGroups(listOf(first, nested)))
        assertEquals(listOf(listOf(0), listOf(1)), independentGroups(sharedStaging))
        assertEquals(listOf(listOf(0), listOf(1)), independentGroups(defaultStaging))
        assertEquals(listOf(listOf(0, 1)), independentGroups(nestedStaging))
        assertEquals(listOf(listOf(0, 1, 2)), independentGroups(stagingInTarget))
        assertEquals(listOf(listOf(0, 1)), independentGroups(listOf(first, archiveIntoFirst)))
        assertEquals(listOf(listOf(0), listOf(1)), independentGroups(siblings))
        assertEquals(listOf(listOf(0, 1)), independentGroups(missingParent))
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), independentGroups(listOf(first, second, independent)))
        // A relocation that depends on two groups merges them, and groups keep plan order.
        assertEquals(listOf(listOf(0, 1, 3), listOf(2)), independentGroups(listOf(first, second, independent, bridge)))
    }

    /** Paths spelled through a symlinked ancestor (#128) are compared as the real places they are; no `toRealPath()`. */
    @Test
    fun groupsRelocationsThatAliasThroughASymlinkedAncestor() {
        val real = Files.createDirectories(root.resolve("real/store"))
        val link = Files.createSymbolicLink(root.resolve("link"), real.parent)
        Files.createDirectories(root.resolve("home"))
        fun relocation(source: String, target: Path, staging: Path? = null) = RelocationPlan(
            Relocation(root.resolve(source), target), RelocationOutcome.CONVERGED,
            listOf(ReconciliationAction.MigrateDirectoryForPublication(root.resolve(source), target, staging)), listOf())

        val sameTarget = listOf(relocation("home/a", link.resolve("store/x")), relocation("home/b", real.resolve("x")))
        val nested = listOf(relocation("home/a", link.resolve("store/x")), relocation("home/b", real.resolve("x/inner")))
        val missingParent = listOf(relocation("home/a", link.resolve("store/new/a")), relocation("home/b", real.resolve("new/b")))
        val defaultStaging = listOf(relocation("home/a", link.resolve("store/a")), relocation("home/b", real.resolve("b")))

        assertEquals(listOf(listOf(0, 1)), independentGroups(sameTarget))
        assertEquals(listOf(listOf(0, 1)), independentGroups(nested))
        assertEquals(listOf(listOf(0, 1)), independentGroups(missingParent))
        // One staging root under two spellings is one shared root.
        assertEquals(listOf(listOf(0), listOf(1)), independentGroups(defaultStaging))
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
                    throw IOException("injected failure")
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
    fun aBugPropagatesOnceRunningRelocationsFinish() {
        val relocations = (1..3).map { n -> migration("s$n/data", "t$n/data", files = 5) }
        val plan = plan(relocations)
        val (first, second) = plan.relocations
        val secondStarted = CountDownLatch(1)
        val firstThrew = CountDownLatch(1)

        val bug = assertThrows<IllegalStateException> {
            ReconciliationExecutor().execute(plan, object : ReconciliationExecutor.ProgressListener {
                override fun started(relocation: RelocationPlan, action: ReconciliationAction) {
                    if (action !is ReconciliationAction.MigrateDirectoryForPublication) return
                    if (relocation === first) {
                        assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
                        firstThrew.countDown()
                        error("injected bug")
                    }
                    if (relocation === second) {
                        secondStarted.countDown()
                        assertTrue(firstThrew.await(5, TimeUnit.SECONDS))
                    }
                }
            })
        }

        assertEquals("injected bug", bug.message)
        assertTrue(Files.isDirectory(relocations[0].sourcePath) && !Files.isSymbolicLink(relocations[0].sourcePath))
        assertTrue(Files.isSymbolicLink(relocations[1].sourcePath), "the running relocation finished first")
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

    private fun entries(directory: Path): List<Path> = Files.list(directory).use { it.toList() }

    /**
     * Each entry under [copy], relative to it, with a regular file's contents; empty while there is no copy. The lock
     * file is never opened here: closing a channel to it would release the operation's lock.
     */
    private fun snapshot(copy: Path): Map<Path, String?> =
        if (Files.notExists(copy)) mapOf() else copy.walk(PathWalkOption.INCLUDE_DIRECTORIES).associate { entry ->
            copy.relativize(entry) to if (Files.isRegularFile(entry)) Files.readString(entry) else null
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
