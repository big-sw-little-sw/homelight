package io.github.bigswlittlesw.homelight.application

import java.io.IOException
import java.util.Properties

// The version and the user guide are resources of this jar, so both the CLI and the TUI read them from here.

private const val RESOURCES = "/io/github/bigswlittlesw/homelight/"

internal fun resolveVersion(): String {
    val version = try {
        HomeLightSession::class.java.getResourceAsStream(RESOURCES + "version.properties")
            ?.use { stream -> Properties().apply { load(stream) }.getProperty("version") }
    } catch (_: IOException) {
        null // fall back when the resource is unreadable
    }
    if (version != null && version.isNotBlank() && !version.startsWith("\${")) return version
    val implementationVersion: String? = HomeLightSession::class.java.`package`.implementationVersion
    if (implementationVersion != null && implementationVersion.isNotBlank()) return implementationVersion
    return "unknown"
}

/** The guide's address on `main`. `docs/user-guide.md` spells it; [userGuide] swaps in [guideUrl]. */
internal const val GUIDE_ON_MAIN = "https://github.com/big-sw-little-sw/homelight/blob/main/docs/user-guide.md"

/** The user guide online for this build's source: `main` for a `-SNAPSHOT`, else the release tag `v<version>`. */
internal fun guideUrl(version: String = resolveVersion()): String =
    if (version.endsWith("-SNAPSHOT")) GUIDE_ON_MAIN else GUIDE_ON_MAIN.replace("/blob/main/", "/blob/v$version/")

/** `docs/user-guide.md` as packaged in this build, with its online link pointing at [guideUrl]. */
internal fun userGuide(version: String = resolveVersion()): String {
    // The build copies the guide into the jar; a build without it is broken, not a user error.
    val text = checkNotNull(HomeLightSession::class.java.getResourceAsStream(RESOURCES + "user-guide.md")) {
        "user-guide.md is missing from the build"
    }.use { stream -> stream.readBytes().decodeToString() }
    return text.replace(GUIDE_ON_MAIN, guideUrl(version))
}
