package io.github.bigswlittlesw.lighten.discovery

import io.github.bigswlittlesw.lighten.config.CandidateCatalog
import io.github.bigswlittlesw.lighten.config.CandidateParser
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Supplier

/** Test-only access to the accepted worker seams, shared by UI tests. */
class SetupDiscoveryFixture : Supplier<CandidateDiscovery>, AutoCloseable {
    private val threads = Workers()
    private val clock = AtomicLong()

    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val reads = AtomicInteger()
    val workers = mutableListOf<CandidateDiscovery>()
    @Volatile var block = false

    override fun get(): CandidateDiscovery {
        val discovery = CandidateDiscovery(threads, clock::get, { path ->
            reads.incrementAndGet()
            if (block) {
                entered.countDown()
                while (release.count > 0) {
                    try { release.await(10, TimeUnit.MILLISECONDS) }
                    catch (ignored: InterruptedException) { /* Model a non-interruptible read. */ }
                }
            }
            Files.readAllBytes(path)
        }, { root ->
            try {
                CandidateParser().parse(CandidateCatalog.BUNDLED, root,
                        Files.readAllBytes(Path.of("src/test/resources/suggestion-lists/bundled.json")))
            } catch (error: IOException) { throw IllegalStateException(error) }
        }, CandidateMetadata())
        workers.add(discovery)
        return discovery
    }

    fun expire() { clock.addAndGet(5_000_000_001L) }

    override fun close() { release.countDown(); workers.forEach(CandidateDiscovery::close) }
}
