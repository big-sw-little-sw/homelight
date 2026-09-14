package io.github.bigswlittlesw.homelight.cli;

import com.fasterxml.jackson.core.JsonFactory;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationExecutor;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

final class ApplyRenderer {
    void renderJson(ReconciliationExecutor.ExecutionResult result, PrintWriter output) {
        output.println(toJson(result));
    }

    private final JsonFactory jsonFactory = new JsonFactory();

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
            generator.writeEndObject();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to render apply result as JSON", exception);
        }
        return json.toString();
    }

}
