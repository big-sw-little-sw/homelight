package io.github.bigswlittlesw.homelight.tui;

import dev.tamboui.toolkit.Toolkit;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import io.github.bigswlittlesw.homelight.application.ApplyModel;
import io.github.bigswlittlesw.homelight.application.HomeLightSession;
import io.github.bigswlittlesw.homelight.application.PlanModel;
import io.github.bigswlittlesw.homelight.application.PlanSummary;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class D3PolishTest {
    @TempDir Path temporary;

    @Test
    void readerScrollbarTracksWrappedContentAndDisappearsOnGrowth() throws Exception {
        var viewport = new DetailViewport();
        var lines = IntStream.range(0, 28).mapToObj(i -> new DetailViewport.Line("Row " + i)).toList();
        var view = Toolkit.column(viewport.render("Details", lines, true, 0), viewport.help("Tab: Back", "q: Quit")).fill();
        var small = WorkspaceViewTest.render(view, 80, 24);
        assertTrue(small.contains("█"), small);
        assertTrue(small.contains("[/]: Scroll"), small);
        viewport.scroll(Integer.MAX_VALUE);
        var bottom = WorkspaceViewTest.render(view, 80, 24);
        assertTrue(bottom.contains("Row 27"), bottom);
        assertNotEquals(small.indexOf('█'), bottom.indexOf('█'));
        for (var size : List.of(new int[]{120, 30}, new int[]{200, 50})) {
            var grown = WorkspaceViewTest.render(view, size[0], size[1]);
            if (size[1] == 50) {
                assertFalse(grown.contains("█"), grown);
                assertFalse(grown.contains("[/]: Scroll"), grown);
                assertTrue(grown.contains("Row 0"));
            }
            assertFalse(grown.matches("(?s).*\\d+–\\d+/\\d+.*"));
        }
        assertTrue(WorkspaceViewTest.render(view, 80, 24).contains("█"));
        assertEquals(List.of("one two", "three"), DetailViewport.wrap("one two three", 8));
        assertEquals("/unbreakable/path", String.join("", DetailViewport.wrap("/unbreakable/path", 5)));
    }

    @Test
    void reviewIsDiscoverableFromBothPanesAndNumberOnePreservesDraftFocus() throws Exception {
        var session = new HomeLightSession(WorkspaceViewTest.fixture(temporary));
        var app = new HomeLightApp(session);
        app.handleKeyEvent(KeyEvent.ofChar('2'));
        assertEquals(Screen.WORKSPACE, app.activeScreen());
        app.handleKeyEvent(KeyEvent.ofChar('l'));
        app.handleKeyEvent(KeyEvent.ofChar(' '));
        for (var size : List.of(new int[]{80, 24}, new int[]{120, 30}, new int[]{200, 50}, new int[]{80, 24})) {
            var screen = WorkspaceViewTest.render(app.render(), size[0], size[1]);
            assertTrue(screen.contains("a: Review & apply"), screen);
            assertTrue(screen.contains("[2: Review]"), screen);
            assertFalse(screen.contains("3: "));
            assertFalse(screen.contains("never quit"));
            assertFalse(screen.contains("Planned actions"));
            app.handleKeyEvent(KeyEvent.ofChar('a'));
            var review = WorkspaceViewTest.render(app.render(), size[0], size[1]);
            assertTrue(review.contains("planned changes"), review);
            assertFalse(review.contains("actions completed"), review);
            assertEquals(1, review.split("Cancel review", -1).length - 1, review);
            assertFalse(review.contains("Esc: Back"), review);
            app.handleKeyEvent(KeyEvent.ofChar('2'));
            app.handleKeyEvent(KeyEvent.ofKey(KeyCode.ENTER));
            assertInstanceOf(ApplyModel.Confirmation.class, session.applyModel());
            app.handleKeyEvent(KeyEvent.ofChar('1'));
            assertEquals(PaneFocus.DETAIL, app.paneFocus());
            assertFalse(Files.isSymbolicLink(temporary.resolve("home/conflict")));
        }
        app.handleKeyEvent(KeyEvent.ofKey(KeyCode.ESCAPE));
        assertTrue(WorkspaceViewTest.render(app.render(), 80, 24).contains("a: Review & apply"));
        app.handleKeyEvent(KeyEvent.ofChar('j'));
        int selected = app.selectedIndex();
        app.handleKeyEvent(KeyEvent.ofChar('1'));
        assertEquals(selected, app.selectedIndex());
    }

    @Test
    void emptyWorkspaceAndUnresolvedWorkspaceHaveDifferentExplanations() throws Exception {
        var config = Files.writeString(temporary.resolve("empty.yaml"),
                "homelight:\n  target-root: " + temporary.resolve("target") + "\n  relocations: []\n");
        var session = new HomeLightSession(config) {
            @Override public PlanModel planModel() {
                return new PlanModel.Configured(config, temporary, new ReconciliationPlan(List.of(), List.of()),
                        List.of(), PlanSummary.from(List.of()));
            }
            @Override public boolean isPlanReady() { return true; }
        };
        var empty = WorkspaceViewTest.render(new HomeLightApp(session).render(), 80, 24);
        assertTrue(empty.contains("No configured relocations."), empty);
        assertFalse(empty.contains("already in sync"), empty);
        assertFalse(empty.contains("in sync hidden"), empty);
        var conflict = WorkspaceViewTest.render(new HomeLightApp(WorkspaceViewTest.fixture(temporary)).render(), 200, 50);
        assertTrue(conflict.contains("Choose a decision to see planned changes."), conflict);
        assertFalse(conflict.contains("Planned actions"), conflict);
        assertFalse(conflict.contains("Overlapping risks"), conflict);
    }
}
