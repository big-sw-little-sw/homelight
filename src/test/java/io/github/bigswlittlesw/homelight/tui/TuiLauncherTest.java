package io.github.bigswlittlesw.homelight.tui;

import dev.tamboui.layout.Position;
import dev.tamboui.layout.Size;
import dev.tamboui.terminal.AbstractBackend;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.event.KeyEvent;
import io.github.bigswlittlesw.homelight.application.ApplyModel;
import io.github.bigswlittlesw.homelight.application.HomeLightSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TuiLauncherTest {
    @TempDir Path temporary;

    @Test
    void closesTerminalExactlyOnceAfterNormalAndExceptionalSettlement() throws Exception {
        for (boolean exceptional : new boolean[] {false, true}) {
            var root = Files.createDirectory(temporary.resolve("case-" + exceptional));
            var session = new HomeLightSession(HomeLightExitTest.configuration(root));
            session.requestApply();
            var completion = session.confirmApply(Runnable::run);
            var result = session.applyModel();
            if (exceptional) completion.obtrudeException(new IllegalStateException("settlement failed"));
            var backend = new LifecycleBackend();
            var app = new HomeLightApp(session, config(backend));
            app.handleKeyEvent(KeyEvent.ofChar('q'));
            if (exceptional) {
                assertThrows(CompletionException.class, app::run);
            } else {
                app.run();
            }
            assertSame(result, session.applyModel());
            assertEquals(List.of("raw", "alternate", "hide", "hide", "show", "leave", "close"), backend.lifecycle);
            assertTrue(Files.isSymbolicLink(root.resolve("source")));
        }
    }

    @Test
    void renderingFailureWaitsForActiveWorkBeforeRestoringTerminal() throws Exception {
        var failRendering = new AtomicBoolean();
        var waiting = new CountDownLatch(1);
        var session = new HomeLightSession(HomeLightExitTest.configuration(temporary)) {
            @Override public synchronized ApplyModel applyModel() {
                if (failRendering.get()) throw new IllegalStateException("render fixture failure");
                return super.applyModel();
            }
            @Override public void awaitExecution() {
                waiting.countDown();
                super.awaitExecution();
            }
        };
        session.requestApply();
        var tasks = new ArrayList<Runnable>();
        var completion = session.confirmApply(tasks::add);
        var backend = new LifecycleBackend();
        var app = new HomeLightApp(session, config(backend));
        failRendering.set(true);
        var thrown = new AtomicReference<Throwable>();
        var stopped = new CountDownLatch(1);
        var ui = Thread.ofPlatform().start(() -> {
            try {
                app.run();
            } catch (Throwable failure) {
                thrown.set(failure);
            } finally {
                stopped.countDown();
            }
        });
        try {
            assertTrue(waiting.await(5, TimeUnit.SECONDS));
            assertEquals(List.of("raw", "alternate", "hide"), backend.lifecycle);
            assertFalse(completion.isDone());
            assertTrue(ui.isAlive());
        } finally {
            failRendering.set(false);
            tasks.getFirst().run();
        }
        assertTrue(stopped.await(5, TimeUnit.SECONDS));
        assertEquals("Unable to render HomeLight", thrown.get().getMessage());
        assertEquals(List.of("raw", "alternate", "hide", "show", "leave", "close"), backend.lifecycle);
        assertTrue(assertInstanceOf(ApplyModel.Result.class, session.applyModel()).succeeded());
        assertFalse(completion.isCancelled());
        assertTrue(Files.isSymbolicLink(temporary.resolve("source")));
    }

    private static TuiConfig config(LifecycleBackend backend) {
        return TuiConfig.builder().backend(backend).shutdownHook(false).build();
    }

    private static final class LifecycleBackend extends AbstractBackend {
        final List<String> lifecycle = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override public void flush() { }
        @Override public void clear() { }
        @Override public Size size() { return new Size(80, 24); }
        @Override public void showCursor() { lifecycle.add("show"); }
        @Override public void hideCursor() { lifecycle.add("hide"); }
        @Override public Position getCursorPosition() { return new Position(0, 0); }
        @Override public void enterAlternateScreen() { lifecycle.add("alternate"); }
        @Override public void leaveAlternateScreen() { lifecycle.add("leave"); }
        @Override public void enableRawMode() { lifecycle.add("raw"); }
        @Override public void disableRawMode() { }
        @Override public void writeRaw(String data) { }
        @Override public void onResize(Runnable handler) { }
        @Override public int read(int timeoutMs) {
            try {
                Thread.sleep(Math.max(1, timeoutMs));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return -1;
        }
        @Override public int peek(int timeoutMs) { return -1; }
        @Override public void close() { lifecycle.add("close"); }
    }
}
