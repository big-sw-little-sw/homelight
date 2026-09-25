package io.github.bigswlittlesw.homelight.discovery;

import io.github.bigswlittlesw.homelight.config.CandidateCatalog;
import io.github.bigswlittlesw.homelight.config.CandidateParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/// Test-only access to the accepted worker seams, shared by UI tests and PTYs.
public final class SetupDiscoveryFixture implements Supplier<CandidateDiscovery>, AutoCloseable {
    private final CandidateDiscovery.Lanes lanes = new CandidateDiscovery.Lanes();
    private final AtomicLong clock = new AtomicLong();
    public final CountDownLatch entered = new CountDownLatch(1);
    public final CountDownLatch release = new CountDownLatch(1);
    public final AtomicInteger reads = new AtomicInteger();
    public final List<CandidateDiscovery> workers = new ArrayList<>();
    public volatile boolean block;
    public Path releaseFile;
    public boolean realTime;

    @Override public CandidateDiscovery get() {
        var discovery = new CandidateDiscovery(lanes, realTime ? System::nanoTime : clock::get, path -> {
            reads.incrementAndGet();
            if (block) {
                entered.countDown();
                while (release.getCount() > 0 && (releaseFile == null || !Files.exists(releaseFile))) {
                    try { release.await(10, TimeUnit.MILLISECONDS); }
                    catch (InterruptedException ignored) { /* Model a non-interruptible read. */ }
                }
            }
            return Files.readAllBytes(path);
        }, root -> {
            try {
                return new CandidateParser().parse(CandidateCatalog.BUNDLED, root,
                        Files.readAllBytes(Path.of("docs/research/session-b-fixtures/nested/bundled.yaml")));
            } catch (IOException error) { throw new IllegalStateException(error); }
        }, new CandidateMetadata());
        workers.add(discovery);
        return discovery;
    }

    public void expire() { clock.addAndGet(5_000_000_001L); }

    @Override public void close() { release.countDown(); workers.forEach(CandidateDiscovery::close); }
}
