package io.github.bigswlittlesw.homelight.cli;

import com.fasterxml.jackson.core.JsonFactory;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationExecutor;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

/// Renders execution results for people or automation without exposing executor bookkeeping.
final class ApplyRenderer {
    private final JsonFactory jsonFactory = new JsonFactory();

    void render(ReconciliationExecutor.ExecutionResult result, boolean json, boolean noColor, PrintWriter output) {
        if (json) {
            output.println(toJson(result));
            return;
        }
        var style = new TerminalStyle(noColor);
        for (var relocation : result.relocations()) {
            var configuredRelocation = relocation.relocation().relocation();
            var source = configuredRelocation.sourcePath().toString();
            var target = configuredRelocation.targetPath().toString();
            var failed = relocation.actions().stream()
                    .filter(action -> action.status() == ReconciliationExecutor.ActionStatus.FAILED)
                    .findFirst();
            if (failed.isPresent()) {
                output.println(style.error("✗ Could not update " + source));
                output.println("  " + failed.orElseThrow().message());
                continue;
            }
            output.println(style.success("✓ " + description(relocation, source, target)));
        }
        var summary = result.succeeded()
                ? "Applied " + result.relocations().size() + plural(result.relocations().size(), "relocation") + "."
                : "Application stopped. Review the failed relocation and run plan again.";
        output.println();
        output.println(result.succeeded() ? style.success(summary) : style.error(summary));
    }

    private String description(ReconciliationExecutor.RelocationExecution relocation, String source, String target) {
        if (relocation.actions().stream().anyMatch(action -> action.action() instanceof ReconciliationAction.Move)) {
            return "Relocated " + source + " → " + target;
        }
        if (relocation.actions().stream().anyMatch(action -> action.action() instanceof ReconciliationAction.NoOp)) {
            return "Already configured " + source;
        }
        if (relocation.actions().stream().anyMatch(action -> action.action() instanceof ReconciliationAction.ReplaceSymlink)) {
            return "Repaired link " + source + " → " + target;
        }
        return "Created " + target + " and linked " + source;
    }

    private String toJson(ReconciliationExecutor.ExecutionResult result) {
        var json = new StringWriter();
        try (var generator = jsonFactory.createGenerator(json)) {
            generator.writeStartObject();
            generator.writeBooleanField("succeeded", result.succeeded());
            generator.writeArrayFieldStart("relocations");
            for (var relocation : result.relocations()) {
                var configuredRelocation = relocation.relocation().relocation();
                generator.writeStartObject();
                generator.writeStringField("source", configuredRelocation.sourcePath().toString());
                generator.writeStringField("target", configuredRelocation.targetPath().toString());
                generator.writeArrayFieldStart("actions");
                for (var action : relocation.actions()) {
                    generator.writeStartObject();
                    generator.writeStringField("type", actionType(action.action()));
                    generator.writeStringField("path", action.action().path().toString());
                    generator.writeStringField("status", action.status().name().toLowerCase());
                    generator.writeStringField("message", action.message());
                    generator.writeEndObject();
                }
                generator.writeEndArray();
                generator.writeEndObject();
            }
            generator.writeEndArray();
            generator.writeEndObject();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to render apply result as JSON", exception);
        }
        return json.toString();
    }

    private static String plural(int count, String noun) {
        return count == 1 ? " " + noun : " " + noun + "s";
    }

    private static String actionType(ReconciliationAction action) {
        return switch (action) {
            case ReconciliationAction.CreateDirectory ignored -> "create-directory";
            case ReconciliationAction.EnsureDirectory ignored -> "ensure-directory";
            case ReconciliationAction.Move ignored -> "move";
            case ReconciliationAction.CreateSymlink ignored -> "create-symlink";
            case ReconciliationAction.ReplaceSymlink ignored -> "replace-symlink";
            case ReconciliationAction.NoOp ignored -> "no-op";
            case ReconciliationAction.Blocked ignored -> "blocked";
        };
    }
}
