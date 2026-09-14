package io.github.bigswlittlesw.homelight.tui;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import io.github.bigswlittlesw.homelight.application.ApplyModel;
import io.github.bigswlittlesw.homelight.application.ConfigurationEvaluation;
import io.github.bigswlittlesw.homelight.application.HomeLightSession;
import io.github.bigswlittlesw.homelight.application.PlanModel;
import io.github.bigswlittlesw.homelight.config.Relocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceViewTest {
    @TempDir Path temporary;

    @Test
    void partitionsAllSixRelocationsAndKeepsIndependentRisksAfterExecution() throws Exception {
        var session = new HomeLightSession(fixture(temporary));
        var model = assertInstanceOf(PlanModel.Configured.class, session.planModel());
        var summary = WorkspaceView.summary(model.items());
        assertEquals("6 relocations · ⚡ 3 actionable · ⚠ 1 conflict · ✖ 0 blocked\n✔ 1 in sync · ─ 1 unchanged", summary.getFirst());
        assertTrue(summary.getLast().contains("1 with warnings"), summary.toString());
        assertEquals(5, WorkspaceView.visibleItems(model, false).size());
        assertEquals(6, WorkspaceView.visibleItems(model, true).size());
        var app = new HomeLightApp(session);
        app.handleKeyEvent(KeyEvent.ofChar('l'));
        app.handleKeyEvent(KeyEvent.ofChar(' '));
        app.handleKeyEvent(KeyEvent.ofChar('2'));
        session.confirmApply(Runnable::run).join();
        var result = assertInstanceOf(ApplyModel.Result.class, session.applyModel());
        assertTrue(result.succeeded());
        app.handleKeyEvent(KeyEvent.ofKey(KeyCode.ENTER));
        var refreshed = assertInstanceOf(PlanModel.Configured.class, session.planModel());
        assertTrue(WorkspaceView.summary(refreshed.items()).getFirst().contains("5 in sync · ─ 1 unchanged"));
        app.handleKeyEvent(KeyEvent.ofChar('2'));
        assertSame(result, session.applyModel());
        app.handleKeyEvent(KeyEvent.ofChar('r'));
        assertInstanceOf(ApplyModel.Idle.class, session.applyModel());
        assertFalse(assertInstanceOf(PlanModel.Configured.class, session.planModel()).plan().hasChanges());
    }

    @Test
    void everyChoiceAndConsequenceStaysAccessibleAcrossResizeAndCancel() throws Exception {
        var session = new HomeLightSession(fixture(temporary));
        var app = new HomeLightApp(session);
        var model = assertInstanceOf(PlanModel.Configured.class, session.planModel());
        var source = model.items().getFirst().relocation().sourcePath();
        var choices = model.items().getFirst().availableResolutions();
        assertEquals(4, choices.size());
        app.handleKeyEvent(KeyEvent.ofChar('l'));
        for (int choice = 0; choice < choices.size(); choice++) {
            for (var size : List.of(new int[]{80, 24}, new int[]{120, 30}, new int[]{200, 50}, new int[]{120, 30}, new int[]{80, 24})) {
                var screen = render(app.render(), size[0], size[1]);
                assertTrue(screen.contains("Details"), screen);
                assertTrue(screen.contains("❯ (○)"), screen);
                assertTrue(screen.contains("Review unavailable"), screen);
                assertTrue(screen.contains("q: Quit"), screen);
                assertTrue(screen.contains("1 unchanged"), screen);
                var details = rightPane(screen, size[0]);
                assertTrue(details.contains(choices.get(choice).label()), details);
                assertTrue(details.replace(" ", "").contains(choices.get(choice).description().replace(" ", "")), screen);
            }
            if (choice < choices.size() - 1) app.handleKeyEvent(KeyEvent.ofChar('j'));
        }
        app.handleKeyEvent(KeyEvent.ofChar(' '));
        var loaded = assertInstanceOf(ConfigurationEvaluation.Loaded.class, session.evaluation());
        var draft = loaded.draft();
        app.handleKeyEvent(KeyEvent.ofChar('2'));
        assertInstanceOf(ApplyModel.Confirmation.class, session.applyModel());
        app.handleKeyEvent(KeyEvent.ofKey(KeyCode.ENTER));
        assertInstanceOf(ApplyModel.Confirmation.class, session.applyModel());
        app.handleKeyEvent(KeyEvent.ofChar('n'));
        assertEquals(PaneFocus.DETAIL, app.paneFocus());
        assertEquals(3, app.detailSelectedIndex());
        var visible = WorkspaceView.visibleItems(assertInstanceOf(PlanModel.Configured.class, session.planModel()), app.showInSync());
        assertEquals(source, visible.get(app.selectedIndex()).relocation().sourcePath());
        assertEquals(draft, assertInstanceOf(ConfigurationEvaluation.Loaded.class, session.evaluation()).draft());
        assertFalse(Files.isSymbolicLink(source));
    }

    @Test
    void completePathsPoliciesAndDiagnosticsCanBeScrolledWithoutChangingChoice() throws Exception {
        var session = new HomeLightSession(fixture(temporary));
        var model = assertInstanceOf(PlanModel.Configured.class, session.planModel());
        for (var size : List.of(new int[]{80, 24}, new int[]{120, 30})) {
            var viewport = new DetailViewport();
            var all = new StringBuilder();
            for (int line = 0; line < 160; line++) {
                all.append(rightPane(render(WorkspaceView.render(session, 0, false, PaneFocus.DETAIL, 0, viewport), size[0], size[1]), size[0]));
                viewport.scroll(1);
            }
            var item = model.items().getFirst();
            assertTrue(all.toString().contains(item.relocation().sourcePath().toString()), all.toString());
            assertTrue(all.toString().contains(item.relocation().targetPath().toString()));
            assertTrue(all.toString().contains("Saved policy:"));
            assertTrue(all.toString().replace(" ", "").contains("Draft(notsaved):None;usingsavedpolicy"));
            assertTrue(all.toString().contains("Expected outcome:"));
            assertTrue(all.toString().contains("archive-destination-distinguishing-suffix"));
        }
    }

    @Test
    void adoptionPolicyDetailsFollowTheSavedEnum() throws Exception {
        var session = new HomeLightSession(fixture(temporary));
        var item = assertInstanceOf(PlanModel.Configured.class, session.planModel()).items().stream()
                .filter(candidate -> candidate.relocation().sourcePath().endsWith("adopt")).findFirst().orElseThrow();
        var policy = WorkspaceView.policy(new Relocation(item.relocation().sourcePath(), item.relocation().targetPath(),
                java.util.Optional.of(io.github.bigswlittlesw.homelight.config.WhenSourceAndTargetDirectoriesExist.ADOPT),
                java.util.Optional.empty(), java.util.Optional.of(io.github.bigswlittlesw.homelight.config.WhenAdoptingTarget.DISCARD_SOURCE),
                java.util.Optional.empty(), java.util.Optional.empty()), item);
        assertEquals("Adopt target; discard source.", policy);
    }

    static String render(Element element, int width, int height) throws Exception {
        var marker = Class.forName("dev.tamboui.tui.RenderThread").getDeclaredMethod("markAsRenderThread");
        var clear = Class.forName("dev.tamboui.tui.RenderThread").getDeclaredMethod("clearRenderThread");
        marker.setAccessible(true);
        clear.setAccessible(true);
        marker.invoke(null);
        try {
            var buffer = Buffer.empty(Rect.of(width, height));
            element.render(Frame.forTesting(buffer), Rect.of(width, height), RenderContext.empty());
            var text = new StringBuilder();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) text.append(buffer.get(x, y).symbol());
                text.append('\n');
            }
            return text.toString();
        } finally { clear.invoke(null); }
    }

    static String rightPane(String screen, int width) {
        var text = new StringBuilder();
        for (var row : screen.split("\n")) {
            int start = row.indexOf('│', width * 45 / 100 - 1);
            if (start < 0) continue;
            // Adjacent panel borders; preserve character-wrapped text without introducing spaces.
            if (start + 1 < row.length() && row.charAt(start + 1) == '│') start++;
            int end = row.lastIndexOf('│');
            if (end > start) text.append(row.substring(start + 1, end).replace("█", "").replace("│", "").stripTrailing());
        }
        return text.toString();
    }

    static Path fixture(Path directory) throws Exception {
        var root = directory.toRealPath();
        var body = new StringBuilder("homelight:\n  target-root: " + root.resolve("local") + "\n  relocations:\n");
        for (var name : List.of("conflict", "migrate", "adopt", "discard", "synced", "unchanged")) {
            var source = root.resolve("home/" + name);
            var target = root.resolve("local/" + name);
            Files.createDirectories(source.getParent());
            Files.createDirectories(target.getParent());
            if (!name.equals("synced")) { Files.createDirectories(source); Files.writeString(source.resolve("payload"), "source"); }
            if (!name.equals("migrate")) { Files.createDirectories(target); Files.writeString(target.resolve("payload"), "target"); }
            if (name.equals("synced")) Files.createSymbolicLink(source, target);
            body.append("    - source-path: ").append(source).append("\n      target-path: ").append(target).append('\n');
            if (name.equals("adopt")) body.append("      when-source-and-target-directories-exist: adopt\n      when-adopting-target: discard-source\n");
            if (name.equals("discard")) body.append("      when-source-and-target-directories-exist: discard\n");
            if (name.equals("unchanged")) body.append("      when-source-and-target-directories-exist: leave-unchanged\n");
            if (name.equals("conflict")) body.append("      source-archive-root: ").append(root.resolve("archive-destination-distinguishing-suffix")).append('\n');
        }
        return Files.writeString(root.resolve("config.yaml"), body);
    }
}
