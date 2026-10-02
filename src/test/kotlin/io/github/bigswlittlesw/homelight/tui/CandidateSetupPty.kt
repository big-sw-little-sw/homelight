package io.github.bigswlittlesw.homelight.tui

import io.github.bigswlittlesw.homelight.application.HomeLightSession
import io.github.bigswlittlesw.homelight.discovery.SetupDiscoveryFixture
import java.nio.file.Path

/**
 * Uses the real application, renderer and JLine with deterministic discovery inputs.
 * No test controls are exposed by the production CLI.
 */
object CandidateSetupPty {
    // JVM entry point: docs/research/session-b32b/pty-check.py launches this class by name.
    @JvmStatic
    fun main(args: Array<String>) {
        SetupDiscoveryFixture().use { fixture ->
            fixture.realTime = true
            if (args.size > 1) { fixture.block = true; fixture.releaseFile = Path.of(args[1]) }
            val app = HomeLightApp(HomeLightSession(Path.of(args[0])), discoveryFactory = fixture::get)
            app.handleKeyEvent(dev.tamboui.tui.event.KeyEvent.ofChar('i'))
            app.run()
        }
    }
}
