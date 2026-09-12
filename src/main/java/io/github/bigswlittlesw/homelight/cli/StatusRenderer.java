package io.github.bigswlittlesw.homelight.cli;

import com.fasterxml.jackson.core.JsonFactory;
import io.github.bigswlittlesw.homelight.domain.RelocationSourceState;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;

/// Machine-readable JSON renderer for relocation status.
final class StatusRenderer {
    private final JsonFactory jsonFactory = new JsonFactory();

    void renderJson(List<StatusSnapshot> snapshots, PrintWriter output) {
        output.println(toJson(snapshots));
    }

    void renderUnconfiguredJson(Path config, PrintWriter output) {
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
