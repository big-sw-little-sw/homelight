package io.github.bigswlittlesw.homelight.config;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
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
}
