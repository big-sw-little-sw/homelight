package io.github.bigswlittlesw.homelight.cli;

import com.fasterxml.jackson.core.JsonFactory;
import io.github.bigswlittlesw.homelight.application.ApplyModel;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationExecutor;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

final class ApplyRenderer {
    void renderJson(ApplyModel.Result result, PrintWriter output) {
        if (result.execution().isPresent()) {
            renderJson(result.execution().orElseThrow(), output);
            return;
        }
        // Preserve known action evidence without inventing an execution or implying zero mutation.
        var relocations = result.plan().relocations().stream().map(relocation ->
                new ReconciliationExecutor.RelocationExecution(relocation, result.steps().stream()
                        .filter(step -> step.relocation() == relocation)
                        .map(step -> new ReconciliationExecutor.ActionExecution(step.action(), switch (step.status()) {
                            case COMPLETED -> ReconciliationExecutor.ActionStatus.COMPLETED;
                            case FAILED, RUNNING -> ReconciliationExecutor.ActionStatus.FAILED;
                            case PENDING -> ReconciliationExecutor.ActionStatus.PENDING;
                        }, step.message())).toList())).toList();
        output.println(toJson(false, relocations, result.diagnostics(), result.stale()));
    }

    void renderJson(ReconciliationExecutor.ExecutionResult result, PrintWriter output) {
        output.println(toJson(result));
    }

    private final JsonFactory jsonFactory = new JsonFactory();

    private String toJson(ReconciliationExecutor.ExecutionResult result) {
        return toJson(result.succeeded(), result.relocations(), List.of(), false);
    }

    private String toJson(boolean succeeded, List<ReconciliationExecutor.RelocationExecution> relocations,
            List<String> diagnostics, boolean stale) {
        var json = new StringWriter();
        try (var generator = jsonFactory.createGenerator(json)) {
            generator.writeStartObject();
            generator.writeBooleanField("succeeded", succeeded);
            generator.writeArrayFieldStart("relocations");
            for (var relocation : relocations) {
                var configuredRelocation = relocation.relocation().relocation();
                generator.writeStartObject();
                generator.writeStringField("source", configuredRelocation.sourcePath().toString());
                generator.writeStringField("target", configuredRelocation.targetPath().toString());
                generator.writeStringField("outcome", relocation.outcome().value());
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
            if (!diagnostics.isEmpty()) {
                generator.writeBooleanField("stale", stale);
                generator.writeArrayFieldStart("diagnostics");
                for (var diagnostic : diagnostics) {
                    generator.writeString(diagnostic);
                }
                generator.writeEndArray();
            }
            generator.writeEndObject();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to render apply result as JSON", exception);
        }
        return json.toString();
    }

}
