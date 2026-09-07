package io.github.bigswlittlesw.homelight.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigurationLoaderTest {

    @Test
    void shipsCommonMachineLocalCandidates() {
        var candidates = CandidateCatalog.defaults();

        assertEquals("~/.m2", candidates.getFirst().sourcePath());
        assertEquals("~/.cache/uv", candidates.stream()
                .filter(candidate -> candidate.sourcePath().equals("~/.cache/uv"))
                .findFirst()
                .orElseThrow()
                .sourcePath());
    }

    @Test
    void loadsYamlAndExpandsHomeAndUser() throws Exception {
        var configFile = Files.createTempFile("homelight", ".yaml");
        Files.writeString(configFile, """
                homelight:
                  target-root: /local/${USER}
                  relocations:
                    - source-path: ~/.m2
                    - source-path: ~/.cache/uv
                      target-path: /fast/uv
                """);

        var configuration = new ConfigurationLoader().load(configFile);

        assertEquals("/local/" + System.getenv("USER"), configuration.targetRoot().toString());
        assertEquals(2, configuration.relocations().size());
        assertEquals(System.getProperty("user.home") + "/.m2", configuration.relocations().getFirst().sourcePath().toString());
        assertEquals("/local/" + System.getenv("USER") + "/.m2", configuration.relocations().getFirst().targetPath().toString());
        assertEquals("/fast/uv", configuration.relocations().get(1).targetPath().toString());
    }

    @Test
    void commandLineValuesOverrideYamlValues() throws Exception {
        var configFile = Files.createTempFile("homelight", ".yaml");
        Files.writeString(configFile, """
                homelight:
                  target-root: /yaml/root
                  relocations:
                    - source-path: /yaml/source
                      target-path: /yaml/target
                """);

        var configuration = new ConfigurationLoader().load(configFile, java.util.Map.of(
                "homelight.target-root", "/cli/root",
                "homelight.relocations[0].source-path", "/cli/source",
                "homelight.relocations[0].target-path", "/cli/target"));

        assertEquals("/cli/root", configuration.targetRoot().toString());
        assertEquals("/cli/source", configuration.relocations().getFirst().sourcePath().toString());
        assertEquals("/cli/target", configuration.relocations().getFirst().targetPath().toString());
    }

    @Test
    void systemPropertiesOverrideYamlValues() throws Exception {
        var configFile = Files.createTempFile("homelight", ".yaml");
        Files.writeString(configFile, """
                homelight:
                  target-root: /yaml/root
                  relocations:
                    - source-path: /yaml/source
                      target-path: /yaml/target
                """);
        System.setProperty("homelight.target-root", "/property/root");
        try {
            var configuration = new ConfigurationLoader().load(configFile);
            assertEquals("/property/root", configuration.targetRoot().toString());
        } finally {
            System.clearProperty("homelight.target-root");
        }
    }

    @Test
    void rejectsNonHomeSourceWithoutExplicitTarget() throws Exception {
        var configFile = Files.createTempFile("homelight", ".yaml");
        Files.writeString(configFile, """
                homelight:
                  target-root: /local
                  relocations:
                    - source-path: /external/cache
                """);

        org.junit.jupiter.api.Assertions.assertThrows(ConfigurationLoader.ConfigurationException.class,
                () -> new ConfigurationLoader().load(configFile));
    }
}
