package io.github.bigswlittlesw.homelight.cli;

import com.fasterxml.jackson.core.JsonFactory;
import io.github.bigswlittlesw.homelight.domain.RelocationSourceState;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;

final class StatusRenderer {
    private final JsonFactory jsonFactory = new JsonFactory();

    void render(List<StatusSnapshot> snapshots, boolean json, PrintWriter output) {
        if (json) {
            output.println(toJson(snapshots));
            return;
        }
        for (var snapshot : snapshots) {
            output.printf("source: %s%n", snapshot.sourcePath());
            output.printf("target: %s%n", snapshot.targetPath());
            output.printf("status: %s%n", snapshot.state().name().toLowerCase().replace('_', ' '));
        }
    }

    void renderUnconfigured(Path config, boolean json, PrintWriter output) {
        if (json) {
            var jsonOutput = new StringWriter();
            try (var generator = jsonFactory.createGenerator(jsonOutput)) {
                generator.writeStartObject();
                generator.writeBooleanField("configured", false);
                generator.writeStringField("configPath", config.toAbsolutePath().normalize().toString());
                generator.writeArrayFieldStart("relocations");
                generator.writeEndArray();
                generator.writeEndObject();
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to render unconfigured status as JSON", exception);
            }
            output.println(jsonOutput);
            return;
        }
        output.printf("No HomeLight configuration found at %s.%n", config.toAbsolutePath().normalize());
        output.println("No paths are currently managed.");
        output.println("Run `./homelight init` to configure relocations.");
    }

    private String toJson(List<StatusSnapshot> snapshots) {
        var json = new StringWriter();
        try (var generator = jsonFactory.createGenerator(json)) {
            generator.writeStartArray();
            for (var snapshot : snapshots) {
                generator.writeStartObject();
                generator.writeStringField("sourcePath", snapshot.sourcePath().toString());
                generator.writeStringField("targetPath", snapshot.targetPath().toString());
                generator.writeStringField("state", snapshot.state().name().toLowerCase());
                generator.writeEndObject();
            }
            generator.writeEndArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to render status as JSON", exception);
        }
        return json.toString();
    }
}

record StatusSnapshot(Path sourcePath, Path targetPath, RelocationSourceState state) {
}
