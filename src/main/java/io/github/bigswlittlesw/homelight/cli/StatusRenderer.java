package io.github.bigswlittlesw.homelight.cli;

import com.fasterxml.jackson.core.JsonFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

final class StatusRenderer {
    private final JsonFactory jsonFactory = new JsonFactory();

    void render(StatusSnapshot snapshot, boolean json, PrintWriter output) {
        if (json) {
            output.println(toJson(snapshot));
            return;
        }
        output.printf("source: %s%n", snapshot.sourcePath());
        output.printf("target: %s%n", snapshot.targetPath());
        output.printf("status: %s%n", snapshot.state().name().toLowerCase().replace('_', ' '));
    }

    private String toJson(StatusSnapshot snapshot) {
        var json = new StringWriter();
        try (var generator = jsonFactory.createGenerator(json)) {
            generator.writeStartObject();
            generator.writeStringField("sourcePath", snapshot.sourcePath().toString());
            generator.writeStringField("targetPath", snapshot.targetPath().toString());
            generator.writeStringField("state", snapshot.state().name().toLowerCase());
            generator.writeEndObject();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to render status as JSON", exception);
        }
        return json.toString();
    }
}
