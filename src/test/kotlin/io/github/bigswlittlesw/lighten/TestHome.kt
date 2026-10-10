package io.github.bigswlittlesw.lighten

import java.nio.file.Files
import java.nio.file.Path

/**
 * The home directory of the test JVM. build.gradle.kts points both `HOME` and `user.home` at an empty directory under
 * `build/`, so no test reads or writes the developer's real home. A process that a test starts inherits `HOME`.
 */
internal val testHome: Path = Path.of(System.getenv("HOME"))

/** [testHome] with its contents deleted, for a test that writes into the home directory. */
internal fun emptiedTestHome(): Path {
    Files.walk(testHome).use { paths ->
        paths.filter { it != testHome }.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
    return testHome
}
