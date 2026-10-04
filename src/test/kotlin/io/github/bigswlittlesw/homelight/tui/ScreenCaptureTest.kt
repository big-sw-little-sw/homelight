package io.github.bigswlittlesw.homelight.tui

import dev.tamboui.tui.event.KeyCode
import dev.tamboui.tui.event.KeyEvent
import io.github.bigswlittlesw.homelight.application.ApplyModel
import io.github.bigswlittlesw.homelight.application.DecisionChoice
import io.github.bigswlittlesw.homelight.application.HomeLightSession
import io.github.bigswlittlesw.homelight.application.PlanModel
import io.github.bigswlittlesw.homelight.application.SetupDraft
import io.github.bigswlittlesw.homelight.config.Relocation
import io.github.bigswlittlesw.homelight.config.WhenAdoptingTarget
import io.github.bigswlittlesw.homelight.config.WhenOnlyTargetExists
import io.github.bigswlittlesw.homelight.config.WhenSourceAndTargetDirectoriesExist
import io.github.bigswlittlesw.homelight.discovery.CandidateDiscovery
import io.github.bigswlittlesw.homelight.discovery.CandidateObservation
import io.github.bigswlittlesw.homelight.discovery.SetupDiscoveryFixture
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/** Throwaway: writes plain-text screen captures for a design audit. Not for merging. */
class ScreenCaptureTest {
    @TempDir lateinit var temporary: Path

    private val out: Path = Path.of(System.getProperty("capture.dir")
        ?: "/private/tmp/claude-501/-Users-jsiva-sw-code-homelight/617a1415-ede8-4267-ae8b-e75703c04bfb/scratchpad/screens")

    @Test fun workspaceReviewRunningResults() {
        val root = temporary.toRealPath()
        val config = workspaceConfig(root)
        val session = HomeLightSession(config, debugStepDelayMillis = 2500)
        val app = HomeLightApp(session)

        for ((item, name) in listOf("conflict" to "workspace-conflict", "only-target" to "workspace-only-target",
            "adopt-default" to "workspace-adopt-default")) {
            select(app, item); tab(app); capture(app, name)
            capturePages(app, name)
        }

        resolve(app, "conflict", DecisionChoice.entries.first { it.label == "Adopt target and discard source" })
        resolve(app, "only-target", null)
        resolve(app, "adopt-default", DecisionChoice.entries.first { it.label == "Adopt target and discard source" })
        assertTrue(session.isPlanReady(), WorkspaceViewTest.render(app.render(), 120, 30))
        left(app); key(app, 'a')
        assertInstanceOf(ApplyModel.Confirmation::class.java, session.applyModel())
        capture(app, "review")

        key(app, 'y')
        var maxRunning = 0
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (System.nanoTime() < deadline) {
            val model = session.applyModel()
            if (model !is ApplyModel.Running) break
            val running = model.steps.count { it.status == ApplyModel.StepStatus.RUNNING }
            if (running > maxRunning) {
                maxRunning = running
                capture(app, "running")
                if (running >= 2) break
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5))
        }
        println("CAPTURE running: max concurrently RUNNING steps observed = $maxRunning")
        session.awaitExecution()
        assertInstanceOf(ApplyModel.Result::class.java, session.applyModel())
        capture(app, "results")
    }

    @Test fun noConfigAndSetupForms() {
        val root = temporary.toRealPath()
        val home = Files.createDirectories(root.resolve("home"))
        for (relative in listOf(".m2", ".cache/uv", "team-cache")) Files.createDirectories(home.resolve(relative))
        val shared = Files.copy(Path.of("docs/research/session-b-fixtures/nested/shared.json"), root.resolve("shared.json"))
        val app = HomeLightApp(HomeLightSession(root.resolve("config.json")))
        capture(app, "no-config")

        key(app, 'i')
        clear(app); type(app, home.toString()); down(app); type(app, root.resolve("local").toString())
        down(app); type(app, shared.toString())
        capture(app, "setup-locations")
        enter(app)

        // Row 1: every policy omitted.
        key(app, 'a'); type(app, ".m2"); escape(app)
        // Row 2: both directories = adopt, adopting = archive source.
        key(app, 'a'); type(app, ".cache/uv"); down(app); down(app); space(app, 2)
        down(app); down(app); space(app, 3); escape(app)
        // Row 3: all three explicit.
        key(app, 'a'); type(app, "team-cache"); down(app); clear(app); type(app, "shared-cache")
        down(app); space(app, 4); down(app); space(app, 2); down(app); space(app, 2); escape(app)
        up(app); up(app)
        capture(app, "setup-table")

        enter(app); down(app); down(app)
        capture(app, "setup-row")
        app.closeSetup()
    }

    @Test fun candidateBrowser() {
        val root = temporary.toRealPath()
        val home = Files.createDirectories(root.resolve("home"))
        for (relative in listOf(".m2", ".cache/uv", ".cache/example", ".local/share/uv/tools", "team-cache", "datasets"))
            Files.createDirectories(home.resolve(relative))
        Files.writeString(home.resolve("team-cache/payload"), "unchanged")
        val shared = Files.copy(Path.of("docs/research/session-b-fixtures/nested/shared.json"), root.resolve("shared.json"))
        SetupDiscoveryFixture().use { workers ->
            val app = HomeLightApp(HomeLightSession(root.resolve("config.json")), discoveryFactory = workers::get)
            key(app, 'i')
            clear(app); type(app, home.toString()); down(app); type(app, root.resolve("local").toString())
            down(app); type(app, shared.toString()); enter(app)
            key(app, 'a'); type(app, "team-cache"); escape(app)
            key(app, 'b'); await(workers, app)
            capture(app, "browse")
            key(app, 'i'); capture(app, "browse-sources"); escape(app)
            choose(app, ".m2"); enter(app); capture(app, "browse-details")
            app.closeSetup()
        }
        // The setup flow never seeds configured rows, so render the browser directly for a configured-style entry.
        SetupDiscoveryFixture().use { workers ->
            val configured = Relocation(home.resolve(".m2"), root.resolve("local/.m2"), WhenSourceAndTargetDirectoriesExist.DISCARD)
            val draft = SetupDraft(home, root.resolve("local"), shared, listOf(configured))
            draft.append(SetupDraft.Row("team-cache", "team-cache"))
            val discovery = workers.get()
            draft.refresh(discovery)
            waitFor { settled(discovery.snapshot()) && draft.accept(discovery.snapshot()) }
            val browser = CandidateBrowser()
            for ((w, h) in SIZES) write("browse-configured", w, h, WorkspaceViewTest.render(browser.render(draft), w, h))
        }
    }

    private fun workspaceConfig(root: Path): Path {
        val body = StringBuilder("{\"homelight\": {\"target-root\": \"${root.resolve("local")}\", \"relocations\": [\n")
        val entries = listOf("conflict", "only-target", "migrate", "synced", "adopt-default")
        for ((i, name) in entries.withIndex()) {
            val source = root.resolve("home/$name")
            val target = root.resolve("local/$name")
            Files.createDirectories(source.parent); Files.createDirectories(target.parent)
            if (name != "only-target" && name != "synced") { Files.createDirectories(source); Files.writeString(source.resolve("payload"), "source") }
            if (name != "migrate") { Files.createDirectories(target); Files.writeString(target.resolve("payload"), "target") }
            if (name == "synced") Files.createSymbolicLink(source, target)
            body.append("  {\"source-path\": \"$source\", \"target-path\": \"$target\"")
            if (name == "adopt-default") body.append(", \"when-source-and-target-directories-exist\": \"adopt\"")
            body.append("}").append(if (i < entries.size - 1) ",\n" else "\n")
        }
        return Files.writeString(root.resolve("config.json"), body.append("]}}\n"))
    }

    private fun select(app: HomeLightApp, name: String) {
        left(app)
        val visible = WorkspaceView.visibleItems(app.planModel() as PlanModel.Configured, app.showInSync)
        val index = visible.indexOfFirst { it.relocation.sourcePath.endsWith(name) }
        assertTrue(index >= 0, "$name not visible")
        app.handleKeyEvent(KeyEvent.ofKey(KeyCode.HOME, KEY_BINDINGS))
        repeat(index) { key(app, 'j') }
        assertEquals(index, app.selectedIndex())
    }

    /** `null` picks the first available resolution. */
    private fun resolve(app: HomeLightApp, name: String, choice: DecisionChoice?) {
        select(app, name)
        val item = WorkspaceView.visibleItems(app.planModel() as PlanModel.Configured, app.showInSync)[app.selectedIndex()]
        val index = if (choice == null) 0 else item.availableResolutions.indexOf(choice)
        assertTrue(index >= 0, "$choice not offered for $name: ${item.availableResolutions}")
        tab(app)
        app.handleKeyEvent(KeyEvent.ofKey(KeyCode.HOME, KEY_BINDINGS))
        repeat(index) { down(app) }
        enter(app)
    }

    private fun capture(app: HomeLightApp, name: String) {
        for ((w, h) in SIZES) write(name, w, h, WorkspaceViewTest.render(app.render(), w, h))
    }

    /** The focused details pane from its top, as `<name>-pN`, paging with `]` until the screen stops changing. */
    private fun capturePages(app: HomeLightApp, name: String) {
        for ((w, h) in SIZES) {
            repeat(300) { key(app, '[') }
            var previous = WorkspaceViewTest.render(app.render(), w, h)
            write("$name-p1", w, h, previous)
            for (page in 2..12) {
                repeat(h - 13) { key(app, ']') }
                val screen = WorkspaceViewTest.render(app.render(), w, h)
                if (screen == previous) break
                write("$name-p$page", w, h, screen)
                previous = screen
            }
        }
        repeat(300) { key(app, '[') }
    }

    private fun write(name: String, width: Int, height: Int, screen: String) {
        Files.createDirectories(out)
        val text = screen.removeSuffix("\n").lines().joinToString("\n", postfix = "\n") { it.trimEnd() }
        Files.writeString(out.resolve("$name-${width}x$height.txt"), text)
    }

    private fun await(workers: SetupDiscoveryFixture, app: HomeLightApp) {
        waitFor { settled(workers.workers.last().snapshot()) }
        WorkspaceViewTest.render(app.render(), 120, 30)
    }

    private fun settled(result: CandidateDiscovery.Result): Boolean =
        result.sources.none { it.status == CandidateDiscovery.SourceStatus.PENDING } &&
            result.candidates.none { it.observation.kind == CandidateObservation.Kind.PENDING }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            if (System.nanoTime() >= deadline) fail<Unit>("timed out")
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1))
        }
    }

    private fun choose(app: HomeLightApp, relative: String) {
        app.handleKeyEvent(KeyEvent.ofKey(KeyCode.HOME, KEY_BINDINGS))
        repeat(100) {
            val screen = WorkspaceViewTest.render(app.render(), 120, 30)
            if (screen.lines().any { it.contains("❯") && Regex("(\\[.\\]| − ) ${Regex.escape(relative)}( |│|$)").containsMatchIn(it) }) return
            key(app, 'j')
        }
        fail<Unit>("Could not focus $relative")
    }

    private fun key(app: HomeLightApp, c: Char) { app.handleKeyEvent(KeyEvent.ofChar(c, KEY_BINDINGS)) }
    private fun type(app: HomeLightApp, value: String) { value.forEach { key(app, it) } }
    private fun clear(app: HomeLightApp) { key(app, '\u0015') }
    private fun space(app: HomeLightApp, times: Int) { repeat(times) { key(app, ' ') } }
    private fun tab(app: HomeLightApp) { app.handleKeyEvent(KeyEvent.ofKey(KeyCode.TAB, KEY_BINDINGS)) }
    private fun left(app: HomeLightApp) { app.handleKeyEvent(KeyEvent.ofKey(KeyCode.LEFT, KEY_BINDINGS)) }
    private fun up(app: HomeLightApp) { app.handleKeyEvent(KeyEvent.ofKey(KeyCode.UP, KEY_BINDINGS)) }
    private fun down(app: HomeLightApp) { app.handleKeyEvent(KeyEvent.ofKey(KeyCode.DOWN, KEY_BINDINGS)) }
    private fun enter(app: HomeLightApp) { app.handleKeyEvent(KeyEvent.ofKey(KeyCode.ENTER, KEY_BINDINGS)) }
    private fun escape(app: HomeLightApp) { app.handleKeyEvent(KeyEvent.ofKey(KeyCode.ESCAPE, KEY_BINDINGS)) }

    private companion object {
        val SIZES = listOf(80 to 24, 120 to 30)
    }
}
