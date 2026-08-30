package io.github.bigswlittlesw.homelight.cli;

import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HomeLightCommandTest {

    @Test
    void shouldDisplayHelpWithLongOption() {var result = execute("--help");

        assertEquals(0, result.exitCode());
        String output = result.output();
        assertTrue(output.contains("Usage: homelight"));
        assertTrue(output.contains("--help"));
        assertTrue(output.contains("--version"));
    }

    @Test
    void shouldDisplayHelpWithShortOption() {
        var result = execute("-h");

        assertEquals(0, result.exitCode());
        String output = result.output();
        assertTrue(output.contains("Usage: homelight"));
    }

    @Test
    void shouldDisplayVersionWithLongOption() {
        var result = execute("--version");

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("homelight " + HomeLightVersionProvider.resolveVersion()));
    }

    @Test
    void shouldDisplayVersionWithShortOption() {
        var result = execute("-V");

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("homelight " + HomeLightVersionProvider.resolveVersion()));
    }

    @Test
    void shouldExecuteWithoutArgumentsAndDisplayUsage() {
        var result = execute();

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("Usage: homelight"));
    }

    @Test
    void shouldProvideVersionFromVersionProvider() {
        var provider = new HomeLightVersionProvider();
        String[] version = provider.getVersion();

        assertEquals(1, version.length);
        assertEquals("homelight " + HomeLightVersionProvider.resolveVersion(), version[0]);
    }

    private static CapturedOutput execute(String... args) {
        var commandLine = HomeLightCommand.createCommandLine();
        var output = new StringWriter();
        commandLine.setOut(new PrintWriter(output, true));

        int exitCode = commandLine.execute(args);
        return new CapturedOutput(exitCode, output.toString());
    }

    private record CapturedOutput(int exitCode, String output) {
    }
}
