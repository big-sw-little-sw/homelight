package io.github.bigswlittlesw.homelight.cli

import picocli.CommandLine.IVersionProvider
import java.io.IOException
import java.util.Properties

// picocli creates the provider reflectively through its no-arg constructor, which is public on the JVM.
internal class HomeLightVersionProvider : IVersionProvider {
    override fun getVersion(): Array<String> = arrayOf("homelight " + resolveVersion())
}

internal fun resolveVersion(): String {
    val version = try {
        HomeLightVersionProvider::class.java
            .getResourceAsStream("/io/github/bigswlittlesw/homelight/version.properties")
            ?.use { stream -> Properties().apply { load(stream) }.getProperty("version") }
    } catch (_: IOException) {
        null // fall back when the resource is unreadable
    }
    if (version != null && version.isNotBlank() && !version.startsWith("\${")) return version
    val implementationVersion: String? = HomeLightVersionProvider::class.java.`package`.implementationVersion
    if (implementationVersion != null && implementationVersion.isNotBlank()) return implementationVersion
    return "unknown"
}
