package io.github.bigswlittlesw.homelight.tui;

import io.github.bigswlittlesw.homelight.application.HomeLightSession;
import io.github.bigswlittlesw.homelight.discovery.SetupDiscoveryFixture;

import java.nio.file.Path;

/// Uses the real application, renderer and JLine with deterministic discovery inputs.
/// No test controls are exposed by the production CLI.
public final class CandidateSetupPty {
    public static void main(String[] args) throws Exception {
        try (var fixture = new SetupDiscoveryFixture()) {
            fixture.realTime = true;
            if (args.length > 1) { fixture.block = true; fixture.releaseFile = Path.of(args[1]); }
            var app = new HomeLightApp(new HomeLightSession(Path.of(args[0])), fixture);
            app.handleKeyEvent(dev.tamboui.tui.event.KeyEvent.ofChar('i'));
            app.run();
        }
    }
}
