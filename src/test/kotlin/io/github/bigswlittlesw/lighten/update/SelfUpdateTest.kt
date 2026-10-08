package io.github.bigswlittlesw.lighten.update

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.PrintWriter
import java.io.StringWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.isExecutable
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

/**
 * `lighten update` against a local server laid out as GitHub Releases. Each release's binary is a shell script that
 * prints its `--version`, so the whole download, check, run and rename can run on the JVM. `latest/download/`
 * redirects to the latest release, as GitHub does.
 */
class SelfUpdateTest {

    @TempDir
    lateinit var root: Path

    private lateinit var server: HttpServer
    private val requests = AtomicInteger()
    private lateinit var bin: Path
    private lateinit var binary: Path

    @BeforeEach
    fun serveReleases() {
        // Messages show the real path; on macOS the temporary directory is under a symlink.
        root = root.toRealPath()
        publish("0.0.1")
        publish("0.0.2")
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            val path = exchange.requestURI.path.removePrefix("/")
            val file = root.resolve("releases").resolve(path).normalize()
            when {
                path.startsWith("latest/download/") -> {
                    exchange.responseHeaders.add("Location", "/download/v$LATEST/" + path.substringAfterLast('/'))
                    exchange.sendResponseHeaders(302, -1)
                }
                Files.isRegularFile(file) -> {
                    exchange.sendResponseHeaders(200, Files.size(file))
                    exchange.responseBody.use { Files.copy(file, it) }
                }
                else -> exchange.sendResponseHeaders(404, -1)
            }
            exchange.close()
        }
        server.start()
        bin = Files.createDirectories(root.resolve("home/.local/bin"))
        binary = script(bin.resolve("lighten"), "0.0.1")
    }

    @AfterEach
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun updatesAnOlderBinaryToTheLatestRelease() {
        val run = install(installed = "0.0.1")
        assertEquals(0, run.exit, run.toString())
        assertEquals("""
            Updating lighten 0.0.1 to 0.0.2 for Linux amd64.
              From: ${baseUrl()}/latest/download/lighten-0.0.2-linux-x86_64-musl
              To:   $binary
            Checked the download against SHA256SUMS.
            Updated lighten from 0.0.1 to 0.0.2.

        """.trimIndent(), run.out)
        assertEquals("", run.err)
        assertEquals(scriptText("0.0.2"), binary.readText())
        assertTrue(binary.isExecutable())
        assertEquals(listOf(binary), bin.listDirectoryEntries())
    }

    @Test
    fun checkShowsBothVersionsAndChangesNothing() {
        val run = check(installed = "0.0.1")
        assertEquals(Run(0, "Installed: lighten 0.0.1\nLatest:    lighten 0.0.2\nRun lighten update to update.\n", ""),
            run)
        assertEquals(scriptText("0.0.1"), binary.readText())

        assertEquals("Lighten is up to date.", check(installed = "0.0.2").out.lines()[2])
        assertEquals("The installed version is newer than the latest release.", check(installed = "0.1.0").out.lines()[2])
        val development = check(installed = "1.0-SNAPSHOT")
        assertEquals(Run(0, "Installed: lighten 1.0-SNAPSHOT\nLatest:    lighten 0.0.2\n" +
            "This is a development build, which lighten update does not replace.\n", ""), development)
    }

    @Test
    fun anUpToDateInstallIsLeftAlone() {
        script(binary, "0.0.2")
        assertEquals(Run(0, "Lighten 0.0.2 is up to date.\n", ""), install(installed = "0.0.2"))
        assertEquals(scriptText("0.0.2"), binary.readText())
    }

    @Test
    fun neverDowngradesWithoutVersion() {
        script(binary, "0.1.0")
        val run = install(installed = "0.1.0")
        assertEquals(Run(0, "The installed lighten 0.1.0 is newer than the latest release, 0.0.2. To install that, " +
            "run: lighten update --version 0.0.2\n", ""), run)
        assertEquals(scriptText("0.1.0"), binary.readText())
    }

    @Test
    fun versionInstallsAnOlderReleaseAndSaysSo() {
        script(binary, "0.0.2")
        val run = install(installed = "0.0.2", requested = "0.0.1")
        assertEquals(0, run.exit, run.toString())
        assertTrue(run.out.startsWith("Installing lighten 0.0.1, older than the installed 0.0.2, for Linux amd64.\n" +
            "  From: ${baseUrl()}/download/v0.0.1/lighten-0.0.1-linux-x86_64-musl\n"), run.out)
        assertTrue(run.out.endsWith("Downgraded lighten from 0.0.2 to 0.0.1.\n"), run.out)
        assertEquals(scriptText("0.0.1"), binary.readText())
    }

    @Test
    fun versionReinstallsTheInstalledRelease() {
        val run = install(installed = "0.0.1", requested = "0.0.1")
        assertTrue(run.out.endsWith("Reinstalled lighten 0.0.1.\n"), run.toString())
    }

    @Test
    fun anUnpublishedVersionStops() {
        val run = install(installed = "0.0.1", requested = "9.9.9")
        assertEquals(1, run.exit)
        assertEquals("Lighten 9.9.9 is not a published release: there is no ${baseUrl()}/download/v9.9.9/SHA256SUMS.",
            run.err.lines().first())
        assertEquals(scriptText("0.0.1"), binary.readText())
    }

    @Test
    fun aChecksumMismatchReplacesNothing() {
        Files.writeString(root.resolve("releases/download/v0.0.2/$X86_ASSET".replace("VERSION", "0.0.2")), "corrupt")
        val run = install(installed = "0.0.1")
        assertEquals(1, run.exit)
        assertTrue(run.out.endsWith("  To:   $binary\n"), run.out)
        assertEquals("The download does not match SHA256SUMS. Nothing was installed.", run.err.lines().first())
        assertTrue(run.err.lines()[2].startsWith("  Got:      " + sha256("corrupt")), run.err)
        assertEquals(scriptText("0.0.1"), binary.readText())
        assertEquals(listOf(binary), bin.listDirectoryEntries())
    }

    @Test
    fun aDownloadThatReportsAnotherVersionReplacesNothing() {
        // The published 0.0.2 binary says it is 0.0.3, and SHA256SUMS matches it.
        publish("0.0.2", reports = "0.0.3")
        val run = install(installed = "0.0.1")
        assertEquals(1, run.exit)
        assertEquals("The downloaded lighten reports 'lighten 0.0.3', not 'lighten 0.0.2'. Nothing was installed.",
            run.err.trim())
        assertEquals(scriptText("0.0.1"), binary.readText())
        assertEquals(listOf(binary), bin.listDirectoryEntries())
    }

    @Test
    fun anUnwritableDirectorySaysHowToUpdateBeforeDownloading() {
        Files.setPosixFilePermissions(bin, PosixFilePermissions.fromString("r-xr-xr-x"))
        try {
            assumeFalse(Files.isWritable(bin), "root can write to any directory")
            val run = install(installed = "0.0.1")
            assertEquals(1, run.exit)
            assertEquals("", run.out)
            assertEquals("You cannot write to $bin, so lighten update cannot replace $binary.", run.err.lines().first())
            assertTrue("install.sh | sudo sh -s -- --dir $bin" in run.err, run.err)
            assertEquals(0, requests.get())
            assertEquals(scriptText("0.0.1"), binary.readText())
        } finally {
            Files.setPosixFilePermissions(bin, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }

    @Test
    fun offlineIsAPlainError() {
        val closedPort = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }
        val offline = "http://127.0.0.1:$closedPort"
        for (run in listOf(install(installed = "0.0.1", base = offline), check(installed = "0.0.1", base = offline))) {
            assertEquals(1, run.exit)
            assertEquals("Could not download $offline/latest/download/SHA256SUMS: could not connect.\n" +
                "Check your network connection, or see $RELEASES_URL\n", run.err)
        }
        assertEquals(scriptText("0.0.1"), binary.readText())
    }

    @Test
    fun aDevelopmentBuildIsNotReplaced() {
        val run = install(installed = "1.0-SNAPSHOT")
        assertEquals(1, run.exit)
        assertEquals("This lighten is a development build (1.0-SNAPSHOT), so lighten update does not replace it.",
            run.err.lines().first())
        assertEquals(0, requests.get())
    }

    @Test
    fun aSymlinkedBinaryIsReplacedAtItsRealPath() {
        val real = script(Files.createDirectories(root.resolve("opt")).resolve("lighten"), "0.0.1")
        val link = Files.createSymbolicLink(root.resolve("link"), real)
        val run = install(installed = "0.0.1", binary = link)
        assertEquals(0, run.exit, run.toString())
        assertTrue("  To:   ${real.toRealPath()}\n" in run.out, run.out)
        assertTrue(Files.isSymbolicLink(link))
        assertEquals(scriptText("0.0.2"), real.readText())
    }

    @Test
    fun anInstallByMiseIsLeftToMise() {
        val underMise = Files.createDirectories(root.resolve("home/.local/share/mise/installs/lighten/0.0.1"))
        val run = install(installed = "0.0.1", binary = script(underMise.resolve("lighten"), "0.0.1"))
        assertEquals(1, run.exit)
        assertTrue(run.err.startsWith("mise installed this lighten"), run.err)
        assertTrue("mise upgrade github:big-sw-little-sw/lighten" in run.err, run.err)

        val custom = Files.createDirectories(root.resolve("mise-data/installs/github-lighten/0.0.1"))
        val inCustom = install(installed = "0.0.1", binary = script(custom.resolve("lighten"), "0.0.1"),
            env = mapOf("MISE_DATA_DIR" to root.resolve("mise-data").toString()))
        assertTrue(inCustom.err.startsWith("mise installed this lighten"), inCustom.err)
        assertEquals(0, requests.get())
    }

    @Test
    fun theJvmAndOtherPlatformsHaveNothingToReplace() {
        val jvm = SelfUpdate(Installation("0.0.1", null, PLATFORM, "Linux amd64"), PrintWriter(StringWriter()),
            PrintWriter(StringWriter()))
        assertEquals(1, jvm.install(null))
        val err = StringWriter()
        val mac = SelfUpdate(Installation("0.0.1", binary, null, "Mac OS X aarch64"), PrintWriter(StringWriter()),
            PrintWriter(err))
        assertEquals(1, mac.check())
        assertEquals("There is no Lighten release for Mac OS X aarch64. Releases have Linux x86_64 and arm64 binaries.\n",
            err.toString())
        assertEquals(0, requests.get())
    }

    private fun baseUrl() = "http://127.0.0.1:${server.address.port}"

    private fun install(
        installed: String, requested: String? = null, binary: Path = this.binary, base: String = baseUrl(),
        env: Map<String, String> = mapOf(),
    ): Run = run(installed, binary, base, env) { it.install(requested?.let(::ReleaseVersion)) }

    private fun check(installed: String, base: String = baseUrl()): Run =
        run(installed, binary, base, mapOf()) { it.check() }

    private fun run(installed: String, binary: Path, base: String, env: Map<String, String>, op: (SelfUpdate) -> Int): Run {
        val out = StringWriter()
        val err = StringWriter()
        val variables = env + (BASE_URL_VARIABLE to base)
        val update = SelfUpdate(Installation(installed, binary, PLATFORM, "Linux amd64"), PrintWriter(out),
            PrintWriter(err), variables::get)
        return Run(op(update), out.toString(), err.toString())
    }

    /** Publishes a release with an x86_64 binary that reports [reports], and an arm64 one that must not be taken. */
    private fun publish(version: String, reports: String = version) {
        val dir = Files.createDirectories(root.resolve("releases/download/v$version"))
        val x86 = X86_ASSET.replace("VERSION", version)
        val arm = "lighten-$version-linux-aarch64-gnu"
        Files.writeString(dir.resolve(x86), scriptText(reports))
        Files.writeString(dir.resolve(arm), "not this one")
        Files.writeString(dir.resolve("SHA256SUMS"),
            "${sha256(scriptText(reports))}  $x86\n${sha256("not this one")}  $arm\n")
    }

    private fun script(path: Path, version: String): Path {
        Files.writeString(path, scriptText(version))
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"))
        return path
    }

    private fun scriptText(version: String) = "#!/bin/sh\necho 'lighten $version'\n"

    private fun sha256(text: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))

    private data class Run(val exit: Int, val out: String, val err: String)

    private companion object {
        const val LATEST = "0.0.2"
        const val PLATFORM = "linux-x86_64-musl"
        const val X86_ASSET = "lighten-VERSION-linux-x86_64-musl"
    }
}
