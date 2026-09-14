package io.github.bigswlittlesw.homelight.tui;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;
import io.github.bigswlittlesw.homelight.application.ApplyModel;
import io.github.bigswlittlesw.homelight.application.HomeLightSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class HomeLightExitTest {
    @TempDir Path temporary;

    @Test
    void escapeNavigatesWithoutExitingOrMutating() throws Exception {
        var config = configuration(temporary);
        var app = new HomeLightApp(config);
        for (var screen : Screen.values()) {
            app.switchScreen(screen);
            key(app, KeyCode.ESCAPE);
            assertEquals(Screen.WORKSPACE, app.activeScreen());
            assertFalse(app.exitRequested());
        }
        app.switchScreen(Screen.WORKSPACE);
        app.handleKeyEvent(KeyEvent.ofChar('a'));
        key(app, KeyCode.ESCAPE);
        assertEquals(Screen.WORKSPACE, app.activeScreen());
        assertInstanceOf(ApplyModel.Idle.class, app.session().applyModel());
        assertFalse(Files.exists(temporary.resolve("source")));

        Files.createDirectories(temporary.resolve("target"));
        app.handleKeyEvent(KeyEvent.ofChar('r'));
        key(app, KeyCode.TAB);
        assertEquals(PaneFocus.DETAIL, app.paneFocus());
        key(app, KeyCode.ESCAPE);
        assertEquals(PaneFocus.MASTER, app.paneFocus());
        assertTrue(app.session().hasConflicts());
        assertFalse(app.exitRequested());
    }

    @Test
    void completionNeverSelectsExitOrDismissesAnOpenDialog() throws Exception {
        for (boolean selectExit : new boolean[] {false, true}) {
            var root = Files.createDirectory(temporary.resolve("case-" + selectExit));
            var app = new HomeLightApp(configuration(root));
            app.switchScreen(Screen.APPLY);
            var tasks = new ArrayList<Runnable>();
            app.session().confirmApply(tasks::add);
            app.handleKeyEvent(KeyEvent.ofChar('q'));
            if (selectExit) key(app, KeyCode.DOWN);
            tasks.getFirst().run();
            for (int width : new int[] {80, 120}) {
                var text = render(app, width, width == 80 ? 24 : 30);
                assertTrue(text.contains("Quit HomeLight?"), text);
                assertTrue(text.contains("❯ " + (selectExit ? "Exit when execution finishes" : "Keep running")), text);
                assertTrue(text.contains("won't remain available after exit"), text);
                assertFalse(app.exitRequested());
            }
            key(app, KeyCode.ENTER);
            assertEquals(selectExit, app.exitRequested());
            assertEquals(1, tasks.size());
            assertTrue(Files.isSymbolicLink(root.resolve("source")));
            if (!selectExit) {
                key(app, KeyCode.ESCAPE);
                assertEquals(Screen.APPLY, app.activeScreen());
                assertFalse(app.exitRequested());
            }
        }
    }

    @Test
    void dialogDefaultsToKeepRunningAndEscapeCancelsEvenAfterSelectingExit() throws Exception {
        var app = new HomeLightApp(configuration(temporary));
        app.switchScreen(Screen.APPLY);
        var tasks = new ArrayList<Runnable>();
        app.session().confirmApply(tasks::add);
        var ctrlC = KeyEvent.ofChar('c', KeyModifiers.CTRL);
        assertTrue(ctrlC.isQuit());
        app.handleKeyEvent(ctrlC);
        key(app, KeyCode.ENTER);
        assertFalse(render(app, 80, 24).contains("Quit HomeLight?"));
        assertFalse(app.exitRequested());
        app.handleKeyEvent(KeyEvent.ofChar('q'));
        key(app, KeyCode.TAB);
        key(app, KeyCode.ESCAPE);
        tasks.getFirst().run();
        app.render();
        assertFalse(app.exitRequested());
        assertTrue(assertInstanceOf(ApplyModel.Result.class, app.session().applyModel()).succeeded());
    }

    @Test
    void deferredExitWaitsForWorkerAndOnlyTheUiRequestsExit() throws Exception {
        var app = new HomeLightApp(configuration(temporary));
        app.switchScreen(Screen.APPLY);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();
        var completion = app.session().confirmApply(task -> Thread.ofPlatform().start(() -> {
            started.countDown();
            try {
                release.await();
                task.run();
            } catch (InterruptedException exception) {
                interrupted.set(true);
            }
        }));
        assertTrue(started.await(5, TimeUnit.SECONDS));
        try {
            app.handleKeyEvent(KeyEvent.ofChar('q'));
            key(app, KeyCode.DOWN);
            key(app, KeyCode.ENTER);
            for (char c : "qyra123".toCharArray()) app.handleKeyEvent(KeyEvent.ofChar(c));
            key(app, KeyCode.ESCAPE);
            assertTrue(render(app, 80, 24).contains("Will exit after execution settles"));
            assertFalse(app.exitRequested());
            assertFalse(completion.isDone());
            assertFalse(Files.exists(temporary.resolve("source")));
        } finally {
            release.countDown();
        }
        completion.get(5, TimeUnit.SECONDS);
        assertFalse(app.exitRequested(), "worker must not touch UI exit intent");
        app.render();
        assertTrue(app.exitRequested());
        app.render();
        assertTrue(app.exitRequested());
        assertFalse(interrupted.get());
        assertFalse(completion.isCancelled());
        assertTrue(Files.isSymbolicLink(temporary.resolve("source")));
    }

    @Test
    void publishedResultDoesNotMeanCompletionHasSettled() throws Exception {
        var settlement = new CompletableFuture<Void>();
        var session = new HomeLightSession(configuration(temporary)) {
            @Override public synchronized boolean executionSettled() { return settlement.isDone(); }
        };
        session.requestApply();
        session.confirmApply(Runnable::run).join();
        var app = new HomeLightApp(session);
        app.handleKeyEvent(KeyEvent.ofChar('q'));
        key(app, KeyCode.DOWN);
        key(app, KeyCode.ENTER);
        app.render();
        assertFalse(app.exitRequested());
        settlement.completeExceptionally(new IllegalStateException("refresh failed"));
        app.render();
        assertTrue(app.exitRequested());
        assertTrue(assertInstanceOf(ApplyModel.Result.class, session.applyModel()).succeeded());
    }

    @Test
    void rejectionWhileQuitDialogOpensKeepsTheDefaultAndNeverReschedules() throws Exception {
        var app = new HomeLightApp(configuration(temporary));
        app.switchScreen(Screen.APPLY);
        var completion = app.session().confirmApply(task -> {
            app.handleKeyEvent(KeyEvent.ofChar('q'));
            throw new java.util.concurrent.RejectedExecutionException("worker unavailable");
        });
        assertTrue(completion.isDone());
        assertTrue(render(app, 80, 24).contains("❯ Keep running"));
        assertFalse(app.exitRequested());
        key(app, KeyCode.ENTER);
        app.handleKeyEvent(KeyEvent.ofChar('y'));
        assertSame(completion, app.session().confirmApply(task -> fail("Must not reschedule")));
        assertFalse(assertInstanceOf(ApplyModel.Result.class, app.session().applyModel()).succeeded());
        assertFalse(Files.exists(temporary.resolve("source")));
    }

    @Test
    void deferredExitIncludesFailureAndExceptionalCompletionWithRetainedEvidence() throws Exception {
        var app = new HomeLightApp(configuration(temporary));
        app.switchScreen(Screen.APPLY);
        var tasks = new ArrayList<Runnable>();
        var completion = app.session().confirmApply(tasks::add);
        app.handleKeyEvent(KeyEvent.ofChar('q'));
        key(app, KeyCode.DOWN);
        key(app, KeyCode.ENTER);
        Files.createDirectories(temporary.resolve("target"));
        tasks.getFirst().run();
        var result = assertInstanceOf(ApplyModel.Result.class, app.session().applyModel());
        assertFalse(result.succeeded());
        assertTrue(result.stale());
        var failure = new IllegalStateException("exceptional settlement");
        completion.obtrudeException(failure);
        app.render();
        assertTrue(app.exitRequested());
        assertSame(result, app.session().applyModel());
        assertSame(failure, assertThrows(CompletionException.class, app.session()::awaitExecution).getCause());
        assertFalse(Files.exists(temporary.resolve("source")));
    }

    static Path configuration(Path root) throws Exception {
        return Files.writeString(root.resolve("config.yaml"), """
                homelight:
                  target-root: %s
                  relocations:
                    - source-path: %s
                      target-path: %s
                """.formatted(root, root.resolve("source"), root.resolve("target")));
    }

    private static void key(HomeLightApp app, KeyCode code) {
        app.handleKeyEvent(KeyEvent.ofKey(code));
    }

    private static String render(HomeLightApp app, int width, int height) throws Exception {
        var marker = Class.forName("dev.tamboui.tui.RenderThread").getDeclaredMethod("markAsRenderThread");
        var clear = Class.forName("dev.tamboui.tui.RenderThread").getDeclaredMethod("clearRenderThread");
        marker.setAccessible(true);
        clear.setAccessible(true);
        marker.invoke(null);
        try {
            var area = Rect.of(width, height);
            var buffer = Buffer.empty(area);
            app.render().render(Frame.forTesting(buffer), area, RenderContext.empty());
            var text = new StringBuilder();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) text.append(buffer.get(x, y).symbol());
                text.append('\n');
            }
            return text.toString();
        } finally {
            clear.invoke(null);
        }
    }
}
