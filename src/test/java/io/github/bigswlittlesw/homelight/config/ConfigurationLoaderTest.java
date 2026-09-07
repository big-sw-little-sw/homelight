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
                  local-root: /local/${USER}/storage
                  relocation-path: ~/.m2
                """);

        var configuration = new ConfigurationLoader().load(configFile);

        assertEquals("/local/" + System.getenv("USER") + "/storage", configuration.localRoot().toString());
        assertEquals(System.getProperty("user.home") + "/.m2", configuration.relocationPath().toString());
    }

    @Test
    void commandLineValuesOverrideYamlValues() throws Exception {
        var configFile = Files.createTempFile("homelight", ".yaml");
        Files.writeString(configFile, """
                homelight:
                  local-root: /yaml/root
                  relocation-path: /yaml/path
                """);

        var configuration = new ConfigurationLoader().load(configFile, java.util.Map.of(
                "homelight.local-root", "/cli/root",
                "homelight.relocation-path", "/cli/path"));

        assertEquals("/cli/root", configuration.localRoot().toString());
        assertEquals("/cli/path", configuration.relocationPath().toString());
    }

    @Test
    void systemPropertiesOverrideYamlValues() throws Exception {
        var configFile = Files.createTempFile("homelight", ".yaml");
        Files.writeString(configFile, """
                homelight:
                  local-root: /yaml/root
                  relocation-path: /yaml/path
                """);
        System.setProperty("homelight.local-root", "/property/root");
        try {
            var configuration = new ConfigurationLoader().load(configFile);
            assertEquals("/property/root", configuration.localRoot().toString());
        } finally {
            System.clearProperty("homelight.local-root");
        }
    }
}
