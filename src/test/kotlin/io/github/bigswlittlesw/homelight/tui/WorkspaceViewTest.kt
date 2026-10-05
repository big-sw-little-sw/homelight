package io.github.bigswlittlesw.homelight.tui

import dev.tamboui.buffer.Buffer
import dev.tamboui.layout.Rect
import dev.tamboui.terminal.Frame
import dev.tamboui.toolkit.element.Element
import dev.tamboui.toolkit.element.RenderContext
import dev.tamboui.tui.event.KeyCode
import dev.tamboui.tui.event.KeyEvent
import io.github.bigswlittlesw.homelight.application.ApplyModel
import io.github.bigswlittlesw.homelight.application.ConfigurationEvaluation
import io.github.bigswlittlesw.homelight.application.HomeLightSession
import io.github.bigswlittlesw.homelight.config.Relocation
import io.github.bigswlittlesw.homelight.config.WhenAdoptingTarget
import io.github.bigswlittlesw.homelight.config.WhenOnlyTargetExists
import io.github.bigswlittlesw.homelight.config.WhenSourceAndTargetDirectoriesExist
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executor

class WorkspaceViewTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun partitionsAllSixRelocationsAndKeepsIndependentRisksAfterExecution() {
        val session = HomeLightSession(fixture(temporary))
        val model = assertInstanceOf(ConfigurationEvaluation.Loaded::class.java, session.evaluation())
        val summary = WorkspaceView.summary(model.items)
        assertEquals(
            listOf(listOf("6 relocations", "⚡ 3 actionable", "⚠ 1 conflict", "✖ 0 blocked"), listOf("✔ 1 in sync", "─ 1 unchanged")),
            summary.counts.map { row -> row.map { it.text } },
        )
        assertTrue(summary.risks.contains("1 with warnings"), summary.toString())
        assertEquals(5, WorkspaceView.visibleItems(model, false).size)
        assertEquals(6, WorkspaceView.visibleItems(model, true).size)
        val ui = HeadlessTui(session)
        ui.press(KeyCode.RIGHT)
        ui.press(' ')
        ui.press('2')
        session.confirmApply(Executor(Runnable::run)).join()
        val result = assertInstanceOf(ApplyModel.Result::class.java, session.applyModel())
        assertTrue(result.succeeded())
        ui.press(KeyCode.ENTER)
        val refreshed = assertInstanceOf(ConfigurationEvaluation.Loaded::class.java, session.evaluation())
        assertEquals(listOf("✔ 5 in sync", "─ 1 unchanged"), WorkspaceView.summary(refreshed.items).counts.last().map { it.text })
        ui.press('2')
        assertSame(result, session.applyModel())
        ui.press('r')
        assertInstanceOf(ApplyModel.Idle::class.java, session.applyModel())
        assertFalse(assertInstanceOf(ConfigurationEvaluation.Loaded::class.java, session.evaluation()).plan.hasChanges())
    }

    @Test
    fun everyChoiceAndConsequenceStaysAccessibleAcrossResizeAndCancel() {
        val session = HomeLightSession(fixture(temporary))
        val ui = HeadlessTui(session)
        val model = assertInstanceOf(ConfigurationEvaluation.Loaded::class.java, session.evaluation())
        val source = model.items.first().relocation.sourcePath
        val choices = model.items.first().availableResolutions
        assertEquals(4, choices.size)
        ui.press(KeyCode.RIGHT)
        for (choice in 0 until choices.size) {
            for (size in listOf(intArrayOf(80, 24), intArrayOf(120, 30), intArrayOf(200, 50), intArrayOf(120, 30), intArrayOf(80, 24))) {
                val screen = ui.screen(size[0], size[1])
                assertTrue(screen.contains("Details"), screen)
                assertTrue(screen.contains("❯ (○)"), screen)
                assertTrue(screen.contains("Review unavailable"), screen)
                assertTrue(screen.contains("q: Quit"), screen)
                assertTrue(screen.contains("1 unchanged"), screen)
                val details = rightPane(screen, size[0])
                assertTrue(details.contains(choices[choice].label), details)
                assertTrue(details.replace(" ", "").contains(choices[choice].description.replace(" ", "")), screen)
            }
            if (choice < choices.size - 1) ui.press(KeyCode.DOWN)
        }
        ui.press(' ')
        val loaded = assertInstanceOf(ConfigurationEvaluation.Loaded::class.java, session.evaluation())
        val draft = loaded.draft
        ui.press('2')
        assertInstanceOf(ApplyModel.Confirmation::class.java, session.applyModel())
        ui.press(KeyCode.ENTER)
        assertInstanceOf(ApplyModel.Confirmation::class.java, session.applyModel())
        ui.press('n')
        assertEquals(WORKSPACE_DETAILS, ui.focused())
        assertEquals(3, ui.app.detailSelectedIndex)
        val visible = WorkspaceView.visibleItems(assertInstanceOf(ConfigurationEvaluation.Loaded::class.java, session.evaluation()), ui.app.showInSync)
        assertEquals(source, visible[ui.app.selectedIndex()].relocation.sourcePath)
        assertEquals(draft, assertInstanceOf(ConfigurationEvaluation.Loaded::class.java, session.evaluation()).draft)
        assertFalse(Files.isSymbolicLink(source))
    }

    @Test
    fun completePathsPoliciesAndDiagnosticsCanBeScrolledWithoutChangingChoice() {
        val session = HomeLightSession(fixture(temporary))
        val model = assertInstanceOf(ConfigurationEvaluation.Loaded::class.java, session.evaluation())
        for (size in listOf(intArrayOf(80, 24), intArrayOf(120, 30))) {
            val viewport = DetailViewport()
            val all = StringBuilder()
            repeat(160) {
                all.append(rightPane(render(WorkspaceView.render(session, WorkspaceView.list(), false, WORKSPACE_DETAILS, true, 0, viewport), size[0], size[1]), size[0]))
                viewport.scroll(1)
            }
            val item = model.items.first()
            assertTrue(all.toString().contains(item.relocation.sourcePath.toString()), all.toString())
            assertTrue(all.toString().contains(item.relocation.targetPath.toString()))
            assertTrue(all.toString().contains("Saved policy:"))
            assertTrue(all.toString().replace(" ", "").contains("Draft(notsaved):None;usingsavedpolicy"))
            assertTrue(all.toString().contains("Expected outcome:"))
            assertTrue(all.toString().contains("archive-destination-distinguishing-suffix"))
        }
    }

    @Test
    fun adoptionPolicyDetailsFollowTheSavedEnum() {
        val session = HomeLightSession(fixture(temporary))
        val item = assertInstanceOf(ConfigurationEvaluation.Loaded::class.java, session.evaluation()).items
            .first { candidate -> candidate.relocation.sourcePath.endsWith("adopt") }
        val policy = WorkspaceView.policy(Relocation(item.relocation.sourcePath, item.relocation.targetPath,
            WhenSourceAndTargetDirectoriesExist.ADOPT, whenAdoptingTarget = WhenAdoptingTarget.DISCARD_SOURCE), item)
        assertEquals("Adopt target; discard source.", policy)
    }

    @ParameterizedTest
    @CsvSource(
        "PROMPT, PROMPT, Prompt.",
        "LEAVE_UNCHANGED, PROMPT, Leave unchanged.",
        "DISCARD, PROMPT, Discard both.",
        "ADOPT, PROMPT, Adopt target; prompt for source.",
        "ADOPT, DISCARD_SOURCE, Adopt target; discard source.",
        "ADOPT, ARCHIVE_SOURCE, Adopt target; archive source.",
    )
    fun bothDirectoriesPolicyUsesTheConfigurationWords(
        both: WhenSourceAndTargetDirectoriesExist, adopting: WhenAdoptingTarget, expected: String,
    ) {
        val session = HomeLightSession(fixture(temporary))
        val item = assertInstanceOf(ConfigurationEvaluation.Loaded::class.java, session.evaluation()).items
            .first { candidate -> candidate.relocation.sourcePath.endsWith("conflict") }
        val relocation = Relocation(item.relocation.sourcePath, item.relocation.targetPath, both, whenAdoptingTarget = adopting)
        assertEquals(expected, WorkspaceView.policy(relocation, item))
    }

    @ParameterizedTest
    @CsvSource(
        "PROMPT, Prompt before adopting the existing target.",
        "ADOPT_TARGET, Adopt target and create a source link.",
    )
    fun onlyTargetPolicyUsesTheConfigurationWords(onlyTarget: WhenOnlyTargetExists, expected: String) {
        val root = temporary.toRealPath()
        val source = root.resolve("home/only")
        val target = Files.createDirectories(root.resolve("local/only"))
        Files.createDirectories(source.parent)
        val config = Files.writeString(root.resolve("config.json"),
            "{\"homelight\": {\"target-root\": \"${target.parent}\", \"relocations\":[{\"source-path\": \"$source\", \"target-path\": \"$target\"}]}}\n")
        val item = assertInstanceOf(ConfigurationEvaluation.Loaded::class.java, HomeLightSession(config).evaluation()).items.single()
        assertEquals(expected, WorkspaceView.policy(Relocation(source, target, whenOnlyTargetExists = onlyTarget), item))
    }

    @Test
    fun leftUnchangedRelocationReadsUnchangedEverywhere() {
        val session = HomeLightSession(fixture(temporary))
        val model = assertInstanceOf(ConfigurationEvaluation.Loaded::class.java, session.evaluation())
        val index = WorkspaceView.visibleItems(model, false).indexOfFirst { it.relocation.sourcePath.endsWith("unchanged") }
        val screen = render(WorkspaceView.render(session, WorkspaceView.list().selected(index), false, WORKSPACE_DETAILS, true, 0, DetailViewport()), 200, 50)
        assertTrue(screen.contains("[Unchanged] "), screen)
        assertTrue(screen.contains("Saved policy: Leave unchanged."), screen)
        assertTrue(screen.contains("Expected outcome: No changes; source and target left unchanged by choice."), screen)
        assertTrue(screen.contains("(●) Leave source and target unchanged"), screen)
        assertFalse(screen.contains("unmanaged") || screen.contains("Skipped"), screen)
    }

    companion object {
        fun render(element: Element, width: Int, height: Int): String {
            val marker = Class.forName("dev.tamboui.tui.RenderThread").getDeclaredMethod("markAsRenderThread")
            val clear = Class.forName("dev.tamboui.tui.RenderThread").getDeclaredMethod("clearRenderThread")
            marker.isAccessible = true
            clear.isAccessible = true
            marker.invoke(null)
            try {
                val buffer = Buffer.empty(Rect.of(width, height))
                element.render(Frame.forTesting(buffer), Rect.of(width, height), RenderContext.empty())
                val text = StringBuilder()
                for (y in 0 until height) {
                    for (x in 0 until width) text.append(buffer.get(x, y).symbol())
                    text.append('\n')
                }
                return text.toString()
            } finally { clear.invoke(null) }
        }

        fun rightPane(screen: String, width: Int): String {
            val text = StringBuilder()
            // Kotlin's split keeps the trailing empty row that Java's drops; it has no border, so it is skipped.
            for (row in screen.split("\n")) {
                var start = row.indexOf('│', width * 45 / 100 - 1)
                if (start < 0) continue
                // Adjacent panel borders; preserve character-wrapped text without introducing spaces.
                if (start + 1 < row.length && row[start + 1] == '│') start++
                val end = row.lastIndexOf('│')
                // Java's stripTrailing: Kotlin hides it, and Kotlin's trimEnd also strips no-break spaces.
                if (end > start) text.append(row.substring(start + 1, end).replace("█", "").replace("│", "").trimEnd { Character.isWhitespace(it) })
            }
            return text.toString()
        }

        fun fixture(directory: Path): Path {
            val root = directory.toRealPath()
            val body = StringBuilder("{\"homelight\": {\"target-root\": \"" + root.resolve("local") + "\", \"relocations\": [\n")
            for (name in listOf("conflict", "migrate", "adopt", "discard", "synced", "unchanged")) {
                val source = root.resolve("home/$name")
                val target = root.resolve("local/$name")
                Files.createDirectories(source.parent)
                Files.createDirectories(target.parent)
                if (name != "synced") { Files.createDirectories(source); Files.writeString(source.resolve("payload"), "source") }
                if (name != "migrate") { Files.createDirectories(target); Files.writeString(target.resolve("payload"), "target") }
                if (name == "synced") Files.createSymbolicLink(source, target)
                body.append("  {\"source-path\": \"").append(source).append("\", \"target-path\": \"").append(target).append('"')
                if (name == "adopt") body.append(", \"when-source-and-target-directories-exist\": \"adopt\", \"when-adopting-target\": \"discard-source\"")
                if (name == "discard") body.append(", \"when-source-and-target-directories-exist\": \"discard\"")
                if (name == "unchanged") body.append(", \"when-source-and-target-directories-exist\": \"leave-unchanged\"")
                if (name == "conflict") body.append(", \"archive-root\": \"")
                    .append(root.resolve("archive-destination-distinguishing-suffix")).append('"')
                body.append("},\n") // The parser accepts the trailing comma after the last relocation.
            }
            return Files.writeString(root.resolve("config.json"), body.append("]}}\n"))
        }
    }
}
