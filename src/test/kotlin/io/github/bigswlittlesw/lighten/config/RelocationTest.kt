package io.github.bigswlittlesw.lighten.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path

class RelocationTest {
    private val source = Path.of("/home/alex/cache")
    private val target = Path.of("/local/cache")

    @Test
    fun refusesARelativeOrUnnormalizedPath() {
        assertThrows<IllegalArgumentException> { Relocation(Path.of("cache"), target) }
        assertThrows<IllegalArgumentException> { Relocation(Path.of("/home/alex/../alex/cache"), target) }
        assertThrows<IllegalArgumentException> { Relocation(source, Path.of("/local/./cache")) }
        assertThrows<IllegalArgumentException> { Relocation(source, target, stagingRoot = Path.of("staging")) }
        assertThrows<IllegalArgumentException> { Relocation(source, target, archiveRoot = Path.of("/archive/..")) }
        assertThrows<IllegalArgumentException> { Relocation(source, target).copy(sourcePath = Path.of("/a/b/..")) }
    }

    @Test
    fun defaultsTheArchiveRootBesideTheSource() {
        assertEquals(Path.of("/home/alex/.lighten-archive"), Relocation(source, target).archiveRoot)
    }

    @Test
    fun aFilesystemRootSourceIsBuiltSoTheLoaderCanRefuseItWithItsOwnMessage() {
        assertEquals(Path.of(".lighten-archive"), Relocation(Path.of("/"), target).archiveRoot)
    }
}
