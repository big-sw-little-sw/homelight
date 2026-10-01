package io.github.bigswlittlesw.homelight.cli;

import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.PrintWriter;

/// Writes the JSON automation responses: one compact line each.
final class JsonOutput {
    // Jackson 3 defaults that would change the contract: alphabetical property order and escaped `/` in paths.
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .disable(JsonWriteFeature.ESCAPE_FORWARD_SLASHES)
            .build();

    private JsonOutput() {
    }

    static void print(Object response, PrintWriter output) {
        output.println(MAPPER.writeValueAsString(response));
    }
}
