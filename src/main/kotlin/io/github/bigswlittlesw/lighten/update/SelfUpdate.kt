package io.github.bigswlittlesw.lighten.update

import io.github.bigswlittlesw.lighten.application.resolveVersion
import io.github.bigswlittlesw.lighten.fs.displayPath
import java.io.IOException
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Where releases are published. Asset names never change once published, because `install.sh`, `lighten update` and
 * tools such as mise download by name. `install.sh` and `.github/workflows/release.yml` use the same asset names.
 */
internal const val RELEASES_URL = "https://github.com/big-sw-little-sw/lighten/releases"

/**
 * Replaces [RELEASES_URL], for tests only. `install.sh` reads the same variable with the same meaning, and Lighten
 * passes it on. The server must serve `latest/download/<asset>` and `download/v<version>/<asset>` under it, as GitHub
 * does.
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

/** The time limit for one download. Each download is `SHA256SUMS` or `install.sh`, and both are small. */
private val DOWNLOAD_DEADLINE: Duration = Duration.ofSeconds(60)

/**
 * `lighten update`: checks for a newer release, or runs the release's `install.sh` to install one.
 *
 * Lighten never writes its binary: `install.sh` alone downloads, verifies and replaces it, so those rules have one
 * implementation. Lighten decides whether to run it, refusing cases the script would get wrong or cannot know about,
 * and downloads with `curl` or `wget` like the script, so the binary carries no HTTP or TLS code. The network is
 * used only here.
 *
 * Lighten's messages go to [out] and [err]; the script inherits the process's own streams. Each operation returns
 * the exit code: 0, 1 when it failed or refused, or the script's own.
 */
internal class SelfUpdate(
    private val installation: Installation,
    private val out: PrintWriter,
    private val err: PrintWriter,
    private val env: (String) -> String? = System::getenv,
) {
    private val baseOverride = env(BASE_URL_VARIABLE)?.takeIf { it.isNotBlank() }?.trimEnd('/')
    private val base = baseOverride ?: RELEASES_URL

    /** Prints the installed and the latest version. A newer release does not change the exit code. */
    fun check(): Int = reporting {
        val latest = latestRelease(requirePlatform(), requireDownloader())
        val installed = ReleaseVersion.parseOrNull(installation.version)
        out.println("Installed: lighten ${installation.version}")
        out.println("Latest:    lighten $latest")
        out.println(when {
            installed == null -> "This is a development build, which lighten update does not replace."
            installed < latest -> "Run lighten update to update."
            installed == latest -> "Lighten is up to date."
            else -> "The installed version is newer than the latest release."
        })
        0
    }

    /**
     * Runs `install.sh` for [requested], or for the latest release when it is null and newer than the installed one.
     */
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
        // The script installs <dir>/lighten; another name would leave this binary as it is.
        if (binary.fileName.toString() != "lighten") fail(
            "The running binary is ${displayPath(binary)}. lighten update runs the install script, which installs a file",
            "named lighten, so it cannot update this one. Rename it to lighten, or update it by hand: see",
            README_INSTALL,
        )
        if (!Files.isWritable(binary.parent)) refuseUnwritable(binary)
        val downloader = requireDownloader()
        val sh = findOnPath("sh") ?: fail("lighten update runs the install script with sh, and there is no sh on PATH.")

        when {
            requested == null -> {
                val latest = latestRelease(platform, downloader)
                if (latest == installed) {
                    out.println("Lighten $installed is up to date.")
                    return@reporting 0
                }
                if (latest < installed) {
                    out.println("The installed lighten $installed is newer than the latest release, $latest. " +
                        "To install that, run: lighten update --version $latest")
                    return@reporting 0
                }
            }
            requested < installed -> out.println("Installing lighten $requested, older than the installed $installed.")
        }
        runInstallScript(sh, downloader, binary.parent, requested)
    }

    private fun requirePlatform(): String = installation.platform ?: fail(
        "There is no Lighten release for ${installation.machine}. Releases have Linux x86_64 and arm64 binaries.",
    )

    private fun requireDownloader(): Downloader =
        (findOnPath("curl")?.let(::Curl) ?: findOnPath("wget")?.let(::Wget)) ?: fail(
            "lighten update needs curl or wget to download releases. Install one of them and run it again.",
        )

    private fun findOnPath(name: String): Path? = env("PATH").orEmpty().split(':')
        .filter { it.isNotEmpty() }
        .map { Path.of(it, name) }
        .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }

    private fun refuseOtherInstallers(binary: Path) {
        val miseDataDir = env("MISE_DATA_DIR")?.takeIf { it.isNotBlank() }
        val underMise = "/mise/installs/" in binary.toString() ||
            miseDataDir?.let { binary.startsWith(Path.of(it).toAbsolutePath()) } == true
        if (underMise) fail(
            "mise installed this lighten (${displayPath(binary)}), so update it with mise:",
            "",
            "  mise upgrade github:big-sw-little-sw/lighten",
        )
    }

    private fun refuseUnwritable(binary: Path): Nothing = fail(
        "You cannot write to ${displayPath(binary.parent)}, so lighten update cannot replace ${displayPath(binary)}.",
        "Lighten does not use sudo. To update it, run the install script for that directory as a user who can",
        "write to it, for example:",
        "",
        "  curl -fsSL $RELEASES_URL/latest/download/install.sh | sudo sh -s -- --dir ${displayPath(binary.parent)}",
        "",
        "Or download the binary by hand: see $README_INSTALL",
    )

    private fun releaseUrl(requested: ReleaseVersion?): String =
        if (requested == null) "$base/latest/download" else "$base/download/v$requested"

    /** The latest release's version for [platform], from its `SHA256SUMS`, as `install.sh` finds it. */
    private fun latestRelease(platform: String, downloader: Downloader): ReleaseVersion {
        val url = "${releaseUrl(null)}/SHA256SUMS"
        val sums = withTempFile { file ->
            download(downloader, url, file, hint = "Check your network connection, or see $RELEASES_URL")
            Files.readString(file)
        }
        return findAsset(sums, platform)?.version ?: fail("The latest release at $url lists no $platform binary.")
    }

    /**
     * Downloads the release's `install.sh` and runs it on [dir], the real directory of the running binary, so a
     * symlink to it keeps pointing at the updated file. It does not offer to change `PATH`: an update leaves the
     * shell's startup files alone.
     */
    private fun runInstallScript(sh: Path, downloader: Downloader, dir: Path, requested: ReleaseVersion?): Int {
        val url = "${releaseUrl(requested)}/install.sh"
        return withTempFile { script ->
            download(downloader, url, script, hint = if (requested == null) {
                "Check your network connection, or see $RELEASES_URL"
            } else {
                "Check that $requested is a published release: $RELEASES_URL"
            })
            val arguments = listOf("--dir", dir.toString()) +
                (requested?.let { listOf("--version", it.text) } ?: listOf()) + "--no-modify-path"
            out.flush()
            val process = ProcessBuilder(listOf(sh.toString(), script.toString()) + arguments)
                .inheritIO()
                .also { builder -> scriptEnvironment(builder.environment()) }
                .start()
            process.waitFor()
        }
    }

    // The script reads the test-only base URL as Lighten does; without an override it must not see a stale one.
    private fun scriptEnvironment(environment: MutableMap<String, String>) {
        if (baseOverride == null) environment.remove(BASE_URL_VARIABLE) else environment[BASE_URL_VARIABLE] = baseOverride
    }

    private inline fun reporting(operation: () -> Int): Int = try {
        operation().also { out.flush() }
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

/** `curl` or `wget`, whichever is installed, with the options `install.sh` uses plus timeouts. */
private sealed interface Downloader {
    fun command(url: String, file: Path): List<String>
}

private data class Curl(val tool: Path) : Downloader {
    override fun command(url: String, file: Path) = listOf(
        tool.toString(), "-fsSL", "--retry", "2", "--connect-timeout", "10",
        "--max-time", DOWNLOAD_DEADLINE.toSeconds().toString(), "-o", file.toString(), url,
    )
}

// -T is the connect and read timeout, in busybox wget too.
private data class Wget(val tool: Path) : Downloader {
    override fun command(url: String, file: Path) =
        listOf(tool.toString(), "-q", "-T", "10", "-O", file.toString(), url)
}

/** Downloads [url] into [file]; a failure is an [UpdateFailure] with the tool's own message and [hint]. */
private fun download(downloader: Downloader, url: String, file: Path, hint: String) {
    val process = ProcessBuilder(downloader.command(url, file))
        .redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()))
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .start()
    // stderr is a line or two at most, well within a pipe's buffer, so waiting before reading cannot deadlock.
    // The tools' own timeouts end a stalled download first; this is the backstop.
    if (!process.waitFor(DOWNLOAD_DEADLINE.toSeconds() + 30, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        fail("Could not download $url: it took too long.", hint)
    }
    val message = process.errorStream.use { it.readAllBytes().decodeToString() }.trim()
    if (process.exitValue() != 0) {
        throw UpdateFailure(listOf("Could not download $url.") + message.lines().filter(String::isNotBlank) + hint)
    }
}

private inline fun <T> withTempFile(operation: (Path) -> T): T {
    val file = Files.createTempFile("lighten-update-", "")
    try {
        return operation(file)
    } finally {
        Files.deleteIfExists(file)
    }
}
