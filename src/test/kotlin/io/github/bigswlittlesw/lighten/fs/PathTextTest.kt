package io.github.bigswlittlesw.lighten.fs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path

class PathTextTest {
    @Test
    fun homeShowsAsTilde() {
        val home = Path.of("/home/me")
        assertEquals("~/.cache/uv", displayPath(Path.of("/home/me/.cache/uv"), home))
        assertEquals("~", displayPath(home, home))
        // A sibling that only shares the prefix as text is not under home.
        assertEquals("/home/meadow/.m2", displayPath(Path.of("/home/meadow/.m2"), home))
        assertEquals("/srv/cache", displayPath(Path.of("/srv/cache"), home))
        assertEquals("/srv/cache", displayPath(Path.of("/srv/cache"), Path.of("/")))
        // Only home is ~: a source root elsewhere is not.
        assertEquals("/data/me/.cache/uv", displayPath(Path.of("/data/me/.cache/uv"), home))
    }

    @Test
    fun peopleSeeTildeAndJsonSeesTheFullPath() {
        val home = Path.of(System.getProperty("user.home"))
        val text = PathText("relocation paths overlap: ", home.resolve(".cache/a"), " and ", Path.of("/srv/b"))
        assertEquals("relocation paths overlap: ~/.cache/a and /srv/b", text.shown())
        assertEquals("relocation paths overlap: $home/.cache/a and /srv/b", text.toString())
        assertEquals("relocation paths overlap: ~/.cache/a and /srv/b (through a symlink)", (text + " (through a symlink)").shown())
        assertEquals(PathText("x ", Path.of("/a")), PathText("x ", Path.of("/a")))
        assertThrows<IllegalArgumentException> { PathText("x", 1) }
    }
}
