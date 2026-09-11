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
        render(result, json, noColor, false, output);
    }

    void render(ReconciliationExecutor.ExecutionResult result, boolean json, boolean noColor, boolean treeRendered, PrintWriter output) {
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
            if (!treeRendered) {
                output.println(style.success("✓ " + description(relocation.relocation(), source, target)));
            }
        }
        var changed = result.relocations().stream().filter(this::changed).count();
        var skipped = result.relocations().stream().filter(this::skipped).count();
        var unchanged = result.relocations().size() - changed - skipped;
        var summary = summary(result.succeeded(), changed, skipped, unchanged);
        output.println();
        output.println(result.succeeded() ? style.success(summary) : style.error(summary));
    }

    void renderUnconfigured(boolean json, PrintWriter output) {
        if (json) {
            output.println(toJson(new ReconciliationExecutor.ExecutionResult(java.util.List.of())));
        } else {
            output.println("No configuration available to apply.");
        }
    }

    private boolean changed(ReconciliationExecutor.RelocationExecution relocation) {
        return relocation.actions().stream().map(ReconciliationExecutor.ActionExecution::action)
                .anyMatch(ReconciliationAction::mutatesFilesystem);
    }

    private boolean skipped(ReconciliationExecutor.RelocationExecution relocation) {
        return relocation.actions().stream().map(ReconciliationExecutor.ActionExecution::action)
                .anyMatch(ReconciliationAction.Skip.class::isInstance);
    }

    private static String summary(boolean succeeded, long changed, long skipped, long unchanged) {
        if (!succeeded) {
            return "Application stopped. Review the failed relocation and run plan again.";
        }
        if (changed == 0 && skipped == 0) {
            return "No changes required. " + unchanged + plural((int) unchanged, "relocation") + " already configured.";
        }
        var result = new StringBuilder();
        if (changed > 0) {
            result.append("Applied ").append(changed).append(plural((int) changed, "relocation")).append('.');
        }
        if (skipped > 0) {
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append("Left ").append(skipped).append(plural((int) skipped, "relocation"))
                    .append(" unchanged by policy.");
        }
        if (unchanged > 0) {
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(unchanged).append(plural((int) unchanged, "relocation")).append(" already configured.");
        }
        return result.toString();
    }

    static String description(io.github.bigswlittlesw.homelight.reconcile.RelocationPlan relocation,
            String source, String target) {
        if (relocation.actions().stream().anyMatch(ReconciliationAction.NoOp.class::isInstance)) {
            return "Already configured " + source + " → " + target;
        }
        if (relocation.relocation().existingContentPolicy()
                .filter(io.github.bigswlittlesw.homelight.config.ExistingContentPolicy.ADOPT::equals).isPresent()) {
            return "Adopted " + target + " and linked " + source;
        }
        if (relocation.actions().stream().anyMatch(ReconciliationAction.CopyDirectory.class::isInstance)) {
            return "Copied " + source + " → " + target + "; inspect it and set existing: adopt to replace the source";
        }
        if (relocation.actions().stream().anyMatch(ReconciliationAction.DeleteDirectory.class::isInstance)) {
            return "Discarded existing content and linked " + source + " → " + target;
        }
        if (relocation.actions().stream().anyMatch(ReconciliationAction.Skip.class::isInstance)) {
            return "Left existing content unchanged at " + source;
        }
        if (relocation.actions().stream().anyMatch(ReconciliationAction.ReplaceSymlink.class::isInstance)) {
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
                var existingContentPolicy = configuredRelocation.existingContentPolicy();
                if (existingContentPolicy.isPresent()) {
                    generator.writeStringField("existing", existingContentPolicy.orElseThrow().value());
                }
                generator.writeArrayFieldStart("actions");
                for (var action : relocation.actions()) {
                    generator.writeStartObject();
                    ActionJson.writeFields(generator, action.action());
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

}
