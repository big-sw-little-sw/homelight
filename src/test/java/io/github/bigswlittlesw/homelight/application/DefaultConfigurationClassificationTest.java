package io.github.bigswlittlesw.homelight.application;

import io.github.bigswlittlesw.homelight.cli.HomeLightCommand;
import io.github.bigswlittlesw.homelight.config.ConfigurationLoader;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultConfigurationClassificationTest {
    @ParameterizedTest
    @ValueSource(strings = {"default-directory", "default-missing", "default-malformed", "explicit-directory"})
    void callersAgreeOnClassificationWithAnIsolatedHome(String scenario, @TempDir Path temporary) throws Exception {
        // DEFAULT_PATH is initialized once per JVM. Isolate user.home before class loading, without touching the real home.
        var output = temporary.resolve("probe.log");
        var process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Duser.home=" + temporary.toRealPath(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                Probe.class.getName(), scenario)
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Classification probe timed out");
            assertEquals(0, process.exitValue(), () -> {
                try {
                    return Files.readString(output);
                } catch (java.io.IOException exception) {
                    return exception.toString();
                }
            });
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    public static class Probe {
        public static void main(String[] args) throws Exception {
            var defaultPath = ConfigurationLoader.DEFAULT_PATH;
            var config = args[0].equals("explicit-directory")
                    ? defaultPath.resolveSibling("explicit.yaml") : defaultPath;
            switch (args[0]) {
                case "default-directory", "explicit-directory" -> Files.createDirectory(config);
                case "default-malformed" -> Files.writeString(config, "homelight: [");
                case "default-missing" -> { }
                default -> throw new IllegalArgumentException(args[0]);
            }
            boolean unconfigured = args[0].equals("default-directory") || args[0].equals("default-missing");
            var evaluator = new ConfigurationEvaluation();
            // Include a lexically different spelling of the same default path.
            for (var path : List.of(config, config.getParent().resolve(".").resolve(config.getFileName()))) {
                var evaluation = evaluator.load(path);
                var session = new HomeLightSession(path);
                assertClassification(evaluation, session.planModel(), unconfigured);
                assertClassification(evaluation, new PlanWorkflow().loadPlan(path), unconfigured);
                session.refresh();
                assertClassification(session.evaluation(), session.planModel(), unconfigured);
                assertFalse(session.requestApply());
            }
            for (var command : List.of("status", "plan", "apply")) {
                var cli = HomeLightCommand.createCommandLine();
                var out = new StringWriter();
                var err = new StringWriter();
                cli.setOut(new PrintWriter(out, true));
                cli.setErr(new PrintWriter(err, true));
                var arguments = new ArrayList<>(List.of(command, "--config", config.toString(), "--json"));
                if (command.equals("apply")) {
                    arguments.add("--yes");
                }
                int exit = cli.execute(arguments.toArray(String[]::new));
                if (unconfigured) {
                    assertEquals(0, exit, err.toString());
                    assertTrue(out.toString().contains("\"relocations\":[]"), out.toString());
                    if (command.equals("status")) {
                        assertTrue(out.toString().contains("\"configured\":false"), out.toString());
                    } else if (command.equals("apply")) {
                        assertEquals("{\"succeeded\":true,\"relocations\":[]}", out.toString().strip());
                    }
                } else {
                    assertNotEquals(0, exit);
                    assertTrue(out.toString().isEmpty(), out.toString());
                    assertFalse(err.toString().isEmpty());
                }
            }
            if (args[0].equals("default-directory")) {
                var retained = evaluator.load(config);
                Files.delete(config);
                Files.writeString(config, "homelight: [");
                // Adapting a retained result must not reclassify it using a later filesystem state.
                assertInstanceOf(PlanModel.Unconfigured.class, PlanWorkflow.from(retained));
                assertInstanceOf(ConfigurationEvaluation.Invalid.class, evaluator.replan(retained).evaluation());
            }
        }

        private static void assertClassification(ConfigurationEvaluation.Evaluation evaluation,
                PlanModel plan, boolean unconfigured) {
            if (unconfigured) {
                assertInstanceOf(ConfigurationEvaluation.Unconfigured.class, evaluation);
                assertInstanceOf(PlanModel.Unconfigured.class, plan);
            } else {
                assertInstanceOf(ConfigurationEvaluation.Invalid.class, evaluation);
                assertInstanceOf(PlanModel.Invalid.class, plan);
            }
        }
    }
}
