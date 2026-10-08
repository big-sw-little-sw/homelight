package io.github.bigswlittlesw.lighten

import org.junit.jupiter.api.Assertions.assertEquals
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path

/**
 * Leaves a socket file at [path], as a program does that is killed while listening: closing the channel does not
 * delete it. A socket's path must fit in about 100 bytes, which a test's temporary directory may not, so it is bound
 * under `/tmp` and renamed into place; both are on one filesystem on Linux and macOS.
 */
internal fun socketAt(path: Path): Path {
    val short = Files.createTempDirectory(Path.of("/tmp"), "lighten")
    try {
        val bound = short.resolve("s")
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { it.bind(UnixDomainSocketAddress.of(bound)) }
        return Files.move(bound, path)
    } finally {
        Files.deleteIfExists(short)
    }
}

/** Makes a named pipe at [path] with `mkfifo`, which Java has no call for. */
internal fun fifoAt(path: Path): Path {
    val mkfifo = ProcessBuilder("mkfifo", path.toString()).inheritIO().start()
    assertEquals(0, mkfifo.waitFor(), "mkfifo $path")
    return path
}
