package io.github.bigswlittlesw.homelight.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ConfigurationLoaderTest {
    @Test void loadsStateSpecificDecisions() throws Exception { var file=Files.createTempFile("homelight", ".yaml"); Files.writeString(file, """
        homelight:
          target-root: /local
          relocations:
            - source-path: /home/cache
              target-path: /local/cache
              when-source-and-target-directories-exist: adopt
              when-adopting-target: archive-source
              source-archive-root: /archive
        """); var relocation=new ConfigurationLoader().load(file).relocations().getFirst(); assertEquals(WhenSourceAndTargetDirectoriesExist.ADOPT,relocation.whenSourceAndTargetDirectoriesExist().orElseThrow()); assertEquals(WhenAdoptingTarget.ARCHIVE_SOURCE,relocation.whenAdoptingTarget().orElseThrow()); }
    @Test void rejectsArchiveWithoutRoot() throws Exception { var file=Files.createTempFile("homelight", ".yaml"); Files.writeString(file, """
        homelight:
          target-root: /local
          relocations:
            - source-path: /home/cache
              target-path: /local/cache
              when-adopting-target: archive-source
        """); assertThrows(ConfigurationLoader.ConfigurationException.class,()->new ConfigurationLoader().load(file)); }

    @Test void rejectsTheRemovedExistingSetting() throws Exception { var file=Files.createTempFile("homelight", ".yaml"); Files.writeString(file, """
        homelight:
          target-root: /local
          relocations:
            - source-path: /home/cache
              target-path: /local/cache
              existing: move
        """); assertThrows(RuntimeException.class,()->new ConfigurationLoader().load(file)); }

    @TempDir Path temporary;

    @Test void reportsMissingRequiredKeysWithLocation() throws Exception {
        assertEquals("Line 2, column 3: Missing required key homelight.target-root", failure("""
                homelight:
                  relocations: []
                """));
        assertEquals("Line 2, column 3: Missing required key homelight.target-root", failure("""
                homelight:
                  target-root:
                """));
        assertEquals("Line 4, column 7: Missing required key homelight.relocations[0].source-path", failure("""
                homelight:
                  target-root: /local
                  relocations:
                    - target-path: /local/cache
                """));
        assertEquals("Missing required key homelight", failure(""));
    }

    @Test void reportsValuesOfTheWrongShape() throws Exception {
        assertEquals("Line 2, column 16: Expected a string for homelight.target-root", failure("""
                homelight:
                  target-root: [/local]
                """));
        assertEquals("Line 3, column 16: Expected a list for homelight.relocations", failure("""
                homelight:
                  target-root: /local
                  relocations: /home/cache
                """));
        assertEquals("Line 4, column 7: Expected a mapping for homelight.relocations[0]", failure("""
                homelight:
                  target-root: /local
                  relocations:
                    - /home/cache
                """));
        assertEquals("Line 1, column 12: Expected a mapping for homelight", failure("""
                homelight: /local
                """));
    }

    @Test void rejectsUnknownAndDuplicateKeysAtEveryLevel() throws Exception {
        assertEquals("Line 1, column 1: Unknown key other", failure("""
                other: 1
                homelight:
                  target-root: /local
                """));
        assertEquals("Line 3, column 3: Unknown key homelight.target", failure("""
                homelight:
                  target-root: /local
                  target: /local
                """));
        assertEquals("Line 6, column 7: Unknown key homelight.relocations[0].existing", failure("""
                homelight:
                  target-root: /local
                  relocations:
                    - source-path: /home/cache
                      target-path: /local/cache
                      existing: move
                """));
        assertEquals("Line 4, column 5: Unknown key homelight.discovery.shared", failure("""
                homelight:
                  target-root: /local
                  discovery:
                    shared: /shared.yaml
                """));
        assertEquals("Line 3, column 3: Duplicate key homelight.target-root", failure("""
                homelight:
                  target-root: /local
                  target-root: /other
                """));
    }

    @Test void acceptsOnlyTheKebabCasePolicyValues() throws Exception {
        assertEquals("Line 6, column 34: Invalid value 'ADOPT_TARGET' for homelight.relocations[0].when-only-target-exists;"
                + " expected one of prompt, adopt-target", failure("""
                homelight:
                  target-root: /local
                  relocations:
                    - source-path: /home/cache
                      target-path: /local/cache
                      when-only-target-exists:   ADOPT_TARGET
                """));
        var relocation = load("""
                homelight:
                  target-root: /local
                  relocations:
                    - source-path: /home/cache
                      target-path: /local/cache
                      when-source-and-target-directories-exist: leave-unchanged
                      when-only-target-exists: adopt-target
                      when-adopting-target: discard-source
                """).relocations().getFirst();
        assertEquals(Optional.of(WhenSourceAndTargetDirectoriesExist.LEAVE_UNCHANGED), relocation.whenSourceAndTargetDirectoriesExist());
        assertEquals(Optional.of(WhenOnlyTargetExists.ADOPT_TARGET), relocation.whenOnlyTargetExists());
        assertEquals(Optional.of(WhenAdoptingTarget.DISCARD_SOURCE), relocation.whenAdoptingTarget());
    }

    @Test void reportsMalformedYamlWithLocation() throws Exception {
        assertEquals("Line 2, column 1: Malformed YAML: expected the node content, but found '<stream end>'",
                failure("homelight: [\n"));
    }

    @Test void resolvesOptionalSettingsAndTreatsEmptyValuesAsAbsent() throws Exception {
        var home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        var configuration = load("""
                homelight:
                  target-root: /local
                  staging-root: /local/staging/../.staging
                  ignored-source-paths:
                    - ~/ignored
                  discovery:
                    shared-list: /shared/candidates.yaml
                  relocations:
                    - source-path: ~/cache
                      target-path: ""
                      when-adopting-target:
                """);
        var relocation = configuration.relocations().getFirst();
        assertEquals(home.resolve("cache"), relocation.sourcePath());
        assertEquals(Path.of("/local").resolve(home.relativize(home.resolve("cache"))), relocation.targetPath());
        assertEquals(Optional.of(Path.of("/local/.staging")), relocation.stagingRoot());
        assertEquals(Optional.empty(), relocation.whenAdoptingTarget());
        assertEquals(List.of(home.resolve("ignored")), configuration.ignoredSourcePaths());
        assertEquals(Optional.of(Path.of("/shared/candidates.yaml")), configuration.sharedList());
        assertEquals(List.of(), load("homelight:\n  target-root: /local\n  relocations:\n").relocations());
    }

    @Test void pathOverrideReplacesOnlyTheFirstRelocationPaths() throws Exception {
        var file = write("""
                homelight:
                  target-root: /local
                  relocations:
                    - source-path: /home/cache
                      target-path: /local/cache
                      when-only-target-exists: adopt-target
                    - source-path: /home/second
                      target-path: /local/second
                """);
        var override = new ConfigurationLoader.PathOverride(Path.of("/override/source"), Path.of("/override/target"));
        var relocations = new ConfigurationLoader().load(file, Optional.of(override)).relocations();
        assertEquals(Path.of("/override/source"), relocations.getFirst().sourcePath());
        assertEquals(Path.of("/override/target"), relocations.getFirst().targetPath());
        assertEquals(Optional.of(WhenOnlyTargetExists.ADOPT_TARGET), relocations.getFirst().whenOnlyTargetExists());
        assertEquals(new Relocation(Path.of("/home/second"), Path.of("/local/second")), relocations.getLast());

        var empty = write("homelight:\n  target-root: /local\n");
        assertEquals(List.of(new Relocation(Path.of("/override/source"), Path.of("/override/target"))),
                new ConfigurationLoader().load(empty, Optional.of(override)).relocations());
    }

    @Test void loadsWhatThePublisherWrites() throws Exception {
        var relocation = new Relocation(temporary.resolve("home/it's"), temporary.resolve("local/it's"),
                Optional.of(WhenSourceAndTargetDirectoriesExist.ADOPT), Optional.of(WhenOnlyTargetExists.PROMPT),
                Optional.of(WhenAdoptingTarget.ARCHIVE_SOURCE), Optional.of(temporary.resolve("archive")));
        var draft = new ConfigurationDraft(temporary.resolve("local"), List.of(relocation),
                Optional.of(temporary.resolve("shared.yaml")));
        var loaded = new ConfigurationLoader().load(write(ConfigurationPublisher.yaml(draft)));
        assertEquals(new HomeLightConfiguration(draft.targetRoot(), draft.relocations(), List.of(), draft.sharedList()), loaded);
    }

    private HomeLightConfiguration load(String yaml) throws Exception {
        return new ConfigurationLoader().load(write(yaml));
    }

    private String failure(String yaml) throws Exception {
        var file = write(yaml);
        return assertThrows(ConfigurationLoader.ConfigurationException.class, () -> new ConfigurationLoader().load(file))
                .getMessage();
    }

    private Path write(String yaml) throws Exception {
        return Files.writeString(Files.createTempFile(temporary, "homelight", ".yaml"), yaml);
    }
}
