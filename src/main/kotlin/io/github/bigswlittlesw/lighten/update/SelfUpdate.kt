package io.github.bigswlittlesw.lighten.update

import io.github.bigswlittlesw.lighten.application.resolveVersion
import java.io.IOException
import java.io.OutputStream
import java.io.PrintWriter
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandler
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.HttpTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException

/** Where releases are published. Asset names are a contract: see "Release assets" in `docs/decisions.md`. */
internal const val RELEASES_URL = "https://github.com/big-sw-little-sw/lighten/releases"

/**
 * For tests only, with the meaning it has for `install.sh`: replaces [RELEASES_URL]. The server must serve
 * `latest/download/<asset>` and `download/v<version>/<asset>` under it, as GitHub does.
 */
internal const val BASE_URL_VARIABLE = "LIGHTEN_INSTALL_BASE_URL"

/**
 * The running Lighten, as `lighten update` sees it.
 *
 * - [binary]: the running executable, before symlinks are resolved; null on a JVM, whose executable is `java`.
 * - [platform]: the release asset suffix for this machine, such as `linux-x86_64-musl`; null where there is none.
 * - [machine]: the OS and architecture, for messages.
 */
internal data class Installation(val version: String, val binary: Path?, val platform: String?, val machine: String)

internal fun currentInstallation(): Installation {
    val os = System.getProperty("os.name")
    val arch = System.getProperty("os.arch")
    val native = System.getProperty("org.graalvm.nativeimage.imagecode") == "runtime"
    // Releases are Linux only, where /proc/self/exe links to the running executable.
    val binary = if (native && os == "Linux") Path.of("/proc/self/exe") else null
    return Installation(resolveVersion(), binary, releasePlatform(os, arch), "$os $arch")
}

/** The asset suffix for an OS and a JVM `os.arch`, as `install.sh` picks it from `uname -m`. */
internal fun releasePlatform(os: String, arch: String): String? = when {
    os != "Linux" -> null
    arch == "amd64" || arch == "x86_64" -> "linux-x86_64-musl"
    arch == "aarch64" || arch == "arm64" -> "linux-aarch64-gnu"
    else -> null
}

private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)

/** From sending a request to its response headers. */
private val RESPONSE_TIMEOUT: Duration = Duration.ofSeconds(30)

private val SUMS_DEADLINE: Duration = Duration.ofSeconds(60)

/** The whole binary, about 30 MB: generous for a slow link, but it ends a stalled download. */
private val BINARY_DEADLINE: Duration = Duration.ofMinutes(10)

private val VERSION_RUN_TIMEOUT: Duration = Duration.ofSeconds(10)

/**
 * `lighten update`: checks for a newer release, or downloads one and puts it in place of the running binary.
 *
 * The network is used only here. A download goes beside the binary, is checked against `SHA256SUMS`, must run and
 * report the expected `--version`, and only then is renamed over the binary in one step; any failure before that
 * leaves the installed binary as it was. This matches `install.sh`.
 *
 * Messages go to [out] and failures to [err]; each operation returns the exit code: 0, or 1 when it failed or
 * refused.
 */
internal class SelfUpdate(
    private val installation: Installation,
    private val out: PrintWriter,
    private val err: PrintWriter,
    env: (String) -> String? = System::getenv,
) {
    private val base = env(BASE_URL_VARIABLE)?.takeIf { it.isNotBlank() }?.trimEnd('/') ?: RELEASES_URL
    private val miseDataDir = env("MISE_DATA_DIR")?.takeIf { it.isNotBlank() }

    /** Prints the installed and the latest version. A newer release does not change the exit code. */
    fun check(): Int = reporting {
        val platform = requirePlatform()
        val latest = withClient { http -> latestOrRequested(http, platform, requested = null) }.version
        val installed = ReleaseVersion.parseOrNull(installation.version)
        out.println("Installed: lighten ${installation.version}")
        out.println("Latest:    lighten $latest")
        out.println(when {
            installed == null -> "This is a development build, which lighten update does not replace."
            installed < latest -> "Run lighten update to update."
            installed == latest -> "Lighten is up to date."
            else -> "The installed version is newer than the latest release."
        })
    }

    /** Installs [requested], or the latest release when it is null and newer than the installed one. */
    fun install(requested: ReleaseVersion?): Int = reporting {
        val installed = ReleaseVersion.parseOrNull(installation.version) ?: fail(
            "This lighten is a development build (${installation.version}), so lighten update does not replace it.",
            "Install a release with the install script instead: see $README_INSTALL",
        )
        val platform = requirePlatform()
        val binary = installation.binary?.let(::realPath) ?: fail(
            "This Lighten runs on a Java runtime, not as the lighten binary, so lighten update has nothing to replace.",
        )
        refuseOtherInstallers(binary)
        val dir = binary.parent
        if (!Files.isWritable(dir)) refuseUnwritable(binary)

        withClient { http ->
            val asset = latestOrRequested(http, platform, requested)
            if (requested == null && asset.version <= installed) {
                out.println(if (asset.version == installed) "Lighten $installed is up to date."
                else "The installed lighten $installed is newer than the latest release, ${asset.version}. " +
                    "To install that, run: lighten update --version ${asset.version}")
                return@withClient
            }
            val version = asset.version
            out.println(when {
                version > installed -> "Updating lighten $installed to $version for ${installation.machine}."
                version < installed -> "Installing lighten $version, older than the installed $installed, " +
                    "for ${installation.machine}."
                else -> "Reinstalling lighten $version for ${installation.machine}."
            })
            out.println("  From: ${releaseUrl(requested)}/${asset.name}")
            out.println("  To:   ${tilde(binary)}")
            out.flush()
            replace(http, binary, asset, "${releaseUrl(requested)}/${asset.name}")
            out.println(when {
                version > installed -> "Updated lighten from $installed to $version."
                version < installed -> "Downgraded lighten from $installed to $version."
                else -> "Reinstalled lighten $version."
            })
        }
    }

    private fun requirePlatform(): String = installation.platform ?: fail(
        "There is no Lighten release for ${installation.machine}. Releases have Linux x86_64 and arm64 binaries.",
    )

    private fun refuseOtherInstallers(binary: Path) {
        val underMise = "/mise/installs/" in binary.toString() ||
            miseDataDir?.let { binary.startsWith(Path.of(it).toAbsolutePath()) } == true
        if (underMise) fail(
            "mise installed this lighten (${tilde(binary)}), so update it with mise:",
            "",
            "  mise upgrade github:big-sw-little-sw/lighten",
        )
    }

    private fun refuseUnwritable(binary: Path): Nothing = fail(
        "You cannot write to ${tilde(binary.parent)}, so lighten update cannot replace ${tilde(binary)}.",
        "Lighten does not use sudo. To update it, run the install script for that directory as a user who can",
        "write to it, for example:",
        "",
        "  curl -fsSL $RELEASES_URL/latest/download/install.sh | sudo sh -s -- --dir ${binary.parent}",
        "",
        "Or download the binary by hand: see $README_INSTALL",
    )

    private fun releaseUrl(requested: ReleaseVersion?): String =
        if (requested == null) "$base/latest/download" else "$base/download/v$requested"

    private fun latestOrRequested(http: HttpClient, platform: String, requested: ReleaseVersion?): ReleaseAsset {
        val url = releaseUrl(requested)
        val response = http.get("$url/SHA256SUMS", BodyHandlers.ofString(), SUMS_DEADLINE)
        when {
            response.statusCode() == 404 && requested != null -> fail(
                "Lighten $requested is not a published release: there is no $url/SHA256SUMS.",
                "See the releases: $RELEASES_URL",
            )
            response.statusCode() == 404 -> fail(
                "Found no published release: there is no $url/SHA256SUMS.",
                "See the releases: $RELEASES_URL",
            )
            response.statusCode() != 200 -> fail(
                "Could not download $url/SHA256SUMS: the server answered ${response.statusCode()}.",
                "Try again later, or see $RELEASES_URL",
            )
        }
        val asset = findAsset(response.body(), platform) ?: fail("The release at $url has no $platform binary.")
        if (requested != null && asset.version != requested) {
            fail("The release v$requested lists ${asset.name}, not version $requested.")
        }
        return asset
    }

    /** Downloads [asset] beside [binary], checks it, and renames it over [binary]. */
    private fun replace(http: HttpClient, binary: Path, asset: ReleaseAsset, url: String) {
        // Beside the binary, so the rename stays on one filesystem and is atomic. The pid keeps two runs apart.
        val download = binary.resolveSibling(".lighten-update.${ProcessHandle.current().pid()}")
        try {
            val response = http.get(url, BodyHandlers.ofFile(download, CREATE, TRUNCATE_EXISTING, WRITE),
                BINARY_DEADLINE)
            if (response.statusCode() != 200) {
                fail("Could not download $url: the server answered ${response.statusCode()}. Nothing was installed.")
            }
            val actual = sha256(download)
            if (actual != asset.sha256) fail(
                "The download does not match SHA256SUMS. Nothing was installed.",
                "  Expected: ${asset.sha256}",
                "  Got:      $actual",
                "The download may be corrupt. Try again, and report it if it happens again: $RELEASES_URL",
            )
            out.println("Checked the download against SHA256SUMS.")
            Files.setPosixFilePermissions(download, PosixFilePermissions.fromString("rwxr-xr-x"))
            val reported = versionOutput(download)
            if (reported != "lighten ${asset.version}") fail(
                "The downloaded lighten reports '$reported', not 'lighten ${asset.version}'. Nothing was installed.",
            )
            Files.move(download, binary, ATOMIC_MOVE)
        } catch (e: IOException) {
            fail("Could not install the download as ${tilde(binary)}: ${e.message ?: e.javaClass.simpleName}.",
                "Nothing was installed.")
        } finally {
            Files.deleteIfExists(download)
        }
    }

    private fun tilde(path: Path): String {
        val home = System.getProperty("user.home")?.let(Path::of) ?: return path.toString()
        return if (path.startsWith(home) && path != home) "~/" + home.relativize(path) else path.toString()
    }

    private inline fun reporting(operation: () -> Unit): Int = try {
        operation()
        out.flush()
        0
    } catch (failure: UpdateFailure) {
        out.flush()
        failure.lines.forEach(err::println)
        err.flush()
        1
    }
}

/** The README's install section, for installs `lighten update` cannot do. */
private const val README_INSTALL = "https://github.com/big-sw-little-sw/lighten#install"

/** A refusal or failure the user can act on, as the lines to print. */
private class UpdateFailure(val lines: List<String>) : Exception(lines.first())

private fun fail(vararg lines: String): Nothing = throw UpdateFailure(lines.toList())

private fun realPath(binary: Path): Path = try {
    binary.toRealPath()
} catch (e: IOException) {
    fail("Could not find the running lighten binary: ${e.message ?: e.javaClass.simpleName}.")
}

// The update owns its client: shutdownNow ends any exchange still open, such as one abandoned at a deadline.
private inline fun <T> withClient(operation: (HttpClient) -> T): T {
    val http = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
    try {
        return operation(http)
    } finally {
        http.shutdownNow()
    }
}

/**
 * A GET of [url] whose response must start within [RESPONSE_TIMEOUT] and finish within [deadline]. A network failure
 * is an [UpdateFailure]; an HTTP error status is left to the caller.
 */
private fun <T> HttpClient.get(url: String, handler: BodyHandler<T>, deadline: Duration): HttpResponse<T> {
    val request = HttpRequest.newBuilder(URI.create(url))
        .timeout(RESPONSE_TIMEOUT)
        .header("User-Agent", "lighten/${resolveVersion()}")
        .GET()
        .build()
    val future = sendAsync(request, handler)
    try {
        return future.get(deadline.toMillis(), TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
        future.cancel(true)
        networkFailure(url, "it did not finish within ${deadline.toSeconds()} seconds")
    } catch (e: ExecutionException) {
        val cause = e.cause as? IOException ?: throw e.cause ?: e
        networkFailure(url, reason(cause))
    }
}

private fun networkFailure(url: String, reason: String): Nothing = fail(
    "Could not download $url: $reason.",
    "Check your network connection, or see $RELEASES_URL",
)

private fun reason(failure: IOException): String = when (failure) {
    is HttpConnectTimeoutException -> "the connection timed out"
    is HttpTimeoutException -> "the server did not answer in time"
    // HttpClient reports an unknown host and a refused connection alike, usually without a message.
    is ConnectException -> "could not connect"
    is SSLException -> "the secure connection failed (${failure.message})"
    else -> failure.message ?: failure.javaClass.simpleName
}

private fun sha256(file: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    DigestInputStream(Files.newInputStream(file), digest).use { it.transferTo(OutputStream.nullOutputStream()) }
    return HexFormat.of().formatHex(digest.digest())
}

/** The first line `<file> --version` prints. A file that does not run, or runs too long, fails the update. */
private fun versionOutput(file: Path): String {
    val process = try {
        ProcessBuilder(file.toString(), "--version").redirectErrorStream(true).start()
    } catch (e: IOException) {
        fail("The downloaded lighten does not run on this system. Nothing was installed.", e.message.orEmpty())
    }
    process.outputStream.close()
    // `--version` prints one short line, well within a pipe's buffer, so waiting before reading cannot deadlock.
    if (!process.waitFor(VERSION_RUN_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
        process.destroyForcibly()
        fail("The downloaded lighten did not finish 'lighten --version'. Nothing was installed.")
    }
    val output = process.inputStream.use { it.readAllBytes().decodeToString() }.trim()
    if (process.exitValue() != 0) {
        fail("The downloaded lighten does not run on this system. Nothing was installed.", output)
    }
    return output.lineSequence().first()
}
