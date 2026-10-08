package io.github.bigswlittlesw.lighten.update

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
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
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.isExecutable
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

/**
 * `lighten update` against a local server laid out as GitHub Releases, each release with the repository's
 * `install.sh`. Each release's binary is a shell script that prints its `--version`, so the script's download,
 * check, run and rename work on the JVM. `latest/download/` redirects to the latest release, as GitHub does.
 *
 * Running `install.sh` needs Linux, as releases do; those tests are skipped elsewhere.
 */
class SelfUpdateTest {

    @TempDir
    lateinit var root: Path

    private lateinit var server: HttpServer
    private val requests = CopyOnWriteArrayList<String>()
    private lateinit var bin: Path
    private lateinit var binary: Path

    // The asset install.sh picks from uname -m on Linux; any one will do elsewhere, where only Lighten's checks run.
    private val platform = releasePlatform(System.getProperty("os.name"), System.getProperty("os.arch"))
        ?: "linux-x86_64-musl"

    @BeforeEach
    fun serveReleases() {
        // Messages show the real path; on macOS the temporary directory is under a symlink.
        root = root.toRealPath()
        publish("0.0.1")
        publish("0.0.2")
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path.removePrefix("/")
            requests.add(path)
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
    fun runsTheLatestInstallScriptOnTheBinarysDirectory() {
        assumeLinux()
        val run = install(installed = "0.0.1")
        assertEquals(Run(0, "", ""), run)
        assertEquals(scriptText("0.0.2"), binary.readText())
        assertTrue(binary.isExecutable())
        assertEquals(listOf(binary), bin.listDirectoryEntries())
        assertTrue("download/v0.0.2/install.sh" in requests, requests.toString())
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
        assertFalse(requests.any { it.endsWith("install.sh") }, requests.toString())
    }

    @Test
    fun checkWorksWithWget() {
        val wget = onPath("wget")
        assumeTrue(wget != null, "wget is not installed")
        val tools = Files.createDirectories(root.resolve("wget-only"))
        Files.createSymbolicLink(tools.resolve("wget"), wget!!) // checked just above
        val run = check(installed = "0.0.1", env = mapOf("PATH" to tools.toString()))
        assertEquals(0, run.exit, run.toString())
        assertEquals("Latest:    lighten 0.0.2", run.out.lines()[1])
    }

    @Test
    fun anUpToDateInstallDoesNotRunTheScript() {
        script(binary, "0.0.2")
        assertEquals(Run(0, "Lighten 0.0.2 is up to date.\n", ""), install(installed = "0.0.2"))
        assertEquals(scriptText("0.0.2"), binary.readText())
        assertFalse(requests.any { it.endsWith("install.sh") }, requests.toString())
    }

    @Test
    fun neverDowngradesWithoutVersion() {
        script(binary, "0.1.0")
        val run = install(installed = "0.1.0")
        assertEquals(Run(0, "The installed lighten 0.1.0 is newer than the latest release, 0.0.2. To install that, " +
            "run: lighten update --version 0.0.2\n", ""), run)
        assertEquals(scriptText("0.1.0"), binary.readText())
        assertFalse(requests.any { it.endsWith("install.sh") }, requests.toString())
    }

    @Test
    fun versionRunsThatReleasesScriptAndSaysWhenItIsOlder() {
        assumeLinux()
        script(binary, "0.0.2")
        val run = install(installed = "0.0.2", requested = "0.0.1")
        assertEquals(Run(0, "Installing lighten 0.0.1, older than the installed 0.0.2.\n", ""), run)
        assertEquals(scriptText("0.0.1"), binary.readText())
        assertTrue("download/v0.0.1/install.sh" in requests, requests.toString())
        assertFalse(requests.any { it.startsWith("latest/") }, requests.toString())
    }

    @Test
    fun anUnpublishedVersionStops() {
        val run = install(installed = "0.0.1", requested = "9.9.9")
        assertEquals(1, run.exit)
        val lines = run.err.lines()
        assertEquals("Could not download ${baseUrl()}/download/v9.9.9/install.sh.", lines.first())
        assertEquals("Check that 9.9.9 is a published release: $RELEASES_URL", lines.last { it.isNotEmpty() })
        assertEquals(scriptText("0.0.1"), binary.readText())
    }

    @Test
    fun theScriptsChecksumFailureIsPassedOnAndReplacesNothing() {
        assumeLinux()
        Files.writeString(root.resolve("releases/download/v0.0.2/lighten-0.0.2-$platform"), "corrupt")
        val run = install(installed = "0.0.1")
        assertEquals(1, run.exit)
        assertEquals(scriptText("0.0.1"), binary.readText())
        assertEquals(listOf(binary), bin.listDirectoryEntries())
    }

    @Test
    fun aSymlinkedBinaryIsUpdatedAtItsRealPath() {
        assumeLinux()
        val real = script(Files.createDirectories(root.resolve("opt/lighten")).resolve("lighten"), "0.0.1")
        val link = Files.createSymbolicLink(bin.resolve("linked"), real)
        assertEquals(0, install(installed = "0.0.1", binary = link).exit)
        assertTrue(Files.isSymbolicLink(link))
        assertEquals(scriptText("0.0.2"), link.readText())
        assertEquals(scriptText("0.0.1"), binary.readText(), "the script installs into the real directory only")
    }

    @Test
    fun aBinaryNotNamedLightenIsLeftAlone() {
        val other = script(bin.resolve("lighten-0.0.1"), "0.0.1")
        val run = install(installed = "0.0.1", binary = other)
        assertEquals(1, run.exit)
        assertTrue(run.err.startsWith("The running binary is $other."), run.err)
        assertTrue(requests.isEmpty(), requests.toString())
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
            assertTrue(requests.isEmpty(), requests.toString())
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
            val lines = run.err.lines().filter { it.isNotEmpty() }
            assertEquals("Could not download $offline/latest/download/SHA256SUMS.", lines.first())
            assertEquals("Check your network connection, or see $RELEASES_URL", lines.last())
        }
        assertEquals(scriptText("0.0.1"), binary.readText())
    }

    @Test
    fun withoutCurlOrWgetItSaysSo() {
        val empty = Files.createDirectories(root.resolve("no-tools")).toString()
        val message = "lighten update needs curl or wget to download releases. Install one of them and run it again.\n"
        assertEquals(Run(1, "", message), check(installed = "0.0.1", env = mapOf("PATH" to empty)))
        assertEquals(Run(1, "", message), install(installed = "0.0.1", env = mapOf("PATH" to empty)))
        assertTrue(requests.isEmpty(), requests.toString())
    }

    @Test
    fun withoutShItSaysSo() {
        val curl = onPath("curl")
        assumeTrue(curl != null, "curl is not installed")
        val tools = Files.createDirectories(root.resolve("curl-only"))
        Files.createSymbolicLink(tools.resolve("curl"), curl!!) // checked just above
        val run = install(installed = "0.0.1", env = mapOf("PATH" to tools.toString()))
        assertEquals(Run(1, "", "lighten update runs the install script with sh, and there is no sh on PATH.\n"), run)
    }

    @Test
    fun aDevelopmentBuildIsNotReplaced() {
        val run = install(installed = "1.0-SNAPSHOT")
        assertEquals(1, run.exit)
        assertEquals("This lighten is a development build (1.0-SNAPSHOT), so lighten update does not replace it.",
            run.err.lines().first())
        assertTrue(requests.isEmpty(), requests.toString())
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
        assertTrue(requests.isEmpty(), requests.toString())
    }

    @Test
    fun theJvmAndOtherPlatformsHaveNothingToReplace() {
        val jvm = SelfUpdate(Installation("0.0.1", null, platform, "Linux amd64"), PrintWriter(StringWriter()),
            PrintWriter(StringWriter()))
        assertEquals(1, jvm.install(null))
        val err = StringWriter()
        val mac = SelfUpdate(Installation("0.0.1", binary, null, "Mac OS X aarch64"), PrintWriter(StringWriter()),
            PrintWriter(err))
        assertEquals(1, mac.check())
        assertEquals("There is no Lighten release for Mac OS X aarch64. Releases have Linux x86_64 and arm64 binaries.\n",
            err.toString())
        assertTrue(requests.isEmpty(), requests.toString())
    }

    private fun assumeLinux() = assumeTrue(System.getProperty("os.name") == "Linux", "install.sh runs on Linux only")

    private fun onPath(name: String): Path? = System.getenv("PATH").split(':').filter { it.isNotEmpty() }
        .map { Path.of(it, name) }.firstOrNull { Files.isExecutable(it) }

    private fun baseUrl() = "http://127.0.0.1:${server.address.port}"

    private fun install(
        installed: String, requested: String? = null, binary: Path = this.binary, base: String = baseUrl(),
        env: Map<String, String> = mapOf(),
    ): Run = run(installed, binary, base, env) { it.install(requested?.let(::ReleaseVersion)) }

    private fun check(installed: String, base: String = baseUrl(), env: Map<String, String> = mapOf()): Run =
        run(installed, binary, base, env) { it.check() }

    private fun run(installed: String, binary: Path, base: String, env: Map<String, String>, op: (SelfUpdate) -> Int): Run {
        val out = StringWriter()
        val err = StringWriter()
        val variables = mapOf("PATH" to System.getenv("PATH")) + env + (BASE_URL_VARIABLE to base)
        val update = SelfUpdate(Installation(installed, binary, platform, "Linux amd64"), PrintWriter(out),
            PrintWriter(err), variables::get)
        return Run(op(update), out.toString(), err.toString())
    }

    /** Publishes a release: `install.sh`, this machine's binary, which reports [version], and `SHA256SUMS`. */
    private fun publish(version: String) {
        val dir = Files.createDirectories(root.resolve("releases/download/v$version"))
        val asset = "lighten-$version-$platform"
        Files.writeString(dir.resolve(asset), scriptText(version))
        Files.writeString(dir.resolve("SHA256SUMS"), "${sha256(scriptText(version))}  $asset\n")
        Files.copy(Path.of("install.sh"), dir.resolve("install.sh"))
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
    }
}
