package io.github.bigswlittlesw.homelight.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigurationLoaderTest {

    @Test
    void loadsYamlAndExpandsHomeAndUser() throws Exception {
        var configFile = Files.createTempFile("homelight", ".yaml");
        Files.writeString(configFile, """
                homelight:
                  source-path: ~/.m2
                  target-path: /local/${USER}/storage
                """);

        var configuration = new ConfigurationLoader().load(configFile);

        assertEquals(System.getProperty("user.home") + "/.m2", configuration.sourcePath().toString());
        assertEquals("/local/" + System.getenv("USER") + "/storage", configuration.targetPath().toString());
    }

    @Test
    void commandLineValuesOverrideYamlValues() throws Exception {
        var configFile = Files.createTempFile("homelight", ".yaml");
        Files.writeString(configFile, """
                homelight:
                  source-path: /yaml/source
                  target-path: /yaml/target
                """);

        var configuration = new ConfigurationLoader().load(configFile, java.util.Map.of(
                "homelight.source-path", "/cli/source",
                "homelight.target-path", "/cli/target"));

        assertEquals("/cli/source", configuration.sourcePath().toString());
        assertEquals("/cli/target", configuration.targetPath().toString());
    }

    @Test
    void systemPropertiesOverrideYamlValues() throws Exception {
        var configFile = Files.createTempFile("homelight", ".yaml");
        Files.writeString(configFile, """
                homelight:
                  source-path: /yaml/source
                  target-path: /yaml/target
                """);
        System.setProperty("homelight.source-path", "/property/source");
        try {
            var configuration = new ConfigurationLoader().load(configFile);
            assertEquals("/property/source", configuration.sourcePath().toString());
        } finally {
            System.clearProperty("homelight.source-path");
        }
    }
}
